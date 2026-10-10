/**
 * Phase 33 — production infrastructure and deployment hardening.
 *
 * These tests cover the runtime around the API rather than the API's products: what the process refuses to start
 * with, how it bounds a socket, what it writes to a log, how it stops, whether a failed migration can lie about the
 * schema, and whether a backup can actually be restored. Phases 30-32 are re-checked as *consumers* of that runtime
 * (a referral claim, an analytics read, a trust read) so a hardening change that broke an existing contract fails
 * here as well as in its own suite.
 *
 * Conventions worth knowing before reading:
 *   - Services are booted with relaxed security thresholds so a rate-limit or abort assertion measures exactly one
 *     mechanism. `ownService()` builds the same service by hand when a test needs to close the database handle
 *     itself (the readiness test) or needs production mode with a real file (helpers only boot `:memory:`).
 *   - Startup and shutdown behaviour can only be proven in a child process, because that is where the signal
 *     handlers and the exit code live.
 *   - Databases are throwaway files under `tmpdir()`. Nothing here touches a real deployment's data, and the restore
 *     tests restore into paths they created.
 */

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import net from "node:net";
import { join, resolve } from "node:path";
import { after, describe, it } from "node:test";
import { DatabaseSync } from "node:sqlite";

import { assertSupportedRuntime, ConfigurationError, loadConfiguration, MINIMUM_NODE_VERSION } from "../src/config.js";
import { createBackup, defaultBackupFileName, inspectBackup, restoreBackup } from "../src/db-maintenance.js";
import { migrateToVersion, openDatabase, SCHEMA_VERSION } from "../src/db.js";
import { DevelopmentEmailSink } from "../src/email-delivery.js";
import { ErrorCode } from "../src/errors.js";
import { createAccountService } from "../src/server.js";
import { call, registerVerified, startService } from "./helpers.js";

const BACKEND_ROOT = resolve(import.meta.dirname, "..");
const SECRET = "phase33-hardening-secret-that-is-long-enough-48";
// A configuration test that names a repo-relative database file would leave a real SQLite file in the working tree
// (and share it between runs). Every path a test can hand to the opener lives under the temporary directory instead.
const CONFIG_ROOT = mkdtempSync(join(tmpdir(), "craftmind-phase33-config-"));
const CONFIG_DATABASE_URL = join(CONFIG_ROOT, "configured-by-environment.db");

/** Everything `loadConfiguration` requires in development, so each test can vary exactly one thing. */
const BASE_ENV = Object.freeze({
  NODE_ENV: "development",
  DATABASE_URL: CONFIG_DATABASE_URL,
  AUTH_SECRET: SECRET,
  HOST: "127.0.0.1",
  PORT: "8787",
});

const RELAXED = Object.freeze({
  RATE_CREATOR_WRITE_MAX: "1000", RATE_CREATOR_READ_MAX: "1000",
  RATE_JOB_WRITE_MAX: "1000", RATE_PROPOSAL_WRITE_MAX: "1000",
  RATE_ORDER_CREATE_MAX: "1000", RATE_ORDER_WRITE_MAX: "1000", RATE_DELIVERY_WRITE_MAX: "1000", RATE_REVISION_WRITE_MAX: "1000",
  RATE_TRUST_WRITE_MAX: "1000", RATE_DISPUTE_WRITE_MAX: "1000",
  RATE_ONBOARDING_WRITE_MAX: "1000", RATE_ONBOARDING_READ_MAX: "1000",
  RATE_CREDITS_MAX: "1000", RATE_DEV_ADMIN_MAX: "1000", RATE_DEV_LOGIN_MAX: "1000", RATE_DEV_SESSION_MAX: "1000",
  SECURITY_UNAUTHORIZED_ACCESS_MEDIUM_MAX: "100000", SECURITY_UNAUTHORIZED_ACCESS_HIGH_MAX: "100001", SECURITY_UNAUTHORIZED_ACCESS_CRITICAL_MAX: "100002",
  SECURITY_MALFORMED_REQUESTS_MEDIUM_MAX: "100000", SECURITY_MALFORMED_REQUESTS_HIGH_MAX: "100001", SECURITY_MALFORMED_REQUESTS_CRITICAL_MAX: "100002",
  SECURITY_REQUEST_BURST_MEDIUM_MAX: "100000", SECURITY_REQUEST_BURST_HIGH_MAX: "100001", SECURITY_REQUEST_BURST_CRITICAL_MAX: "100002",
  SECURITY_RATE_LIMIT_MEDIUM_MAX: "100000", SECURITY_RATE_LIMIT_ABUSE_MEDIUM_MAX: "100000", SECURITY_RATE_LIMIT_ABUSE_HIGH_MAX: "100001", SECURITY_RATE_LIMIT_ABUSE_CRITICAL_MAX: "100002",
});

const temporaryDirectories = new Set();
const spawned = new Set();
// A test that fails halfway must not leave a live server holding this process's stdio pipes open, which is how a
// single bad assertion turns into a hung suite.
after(() => { for (const child of spawned) { try { child.kill("SIGKILL"); } catch { /* already gone */ } } });
// In-process listeners are just as fatal as a leaked child: an open handle keeps the file from finishing.
const localServers = new Set();
after(() => { for (const server of localServers) { try { server.close(); } catch { /* already closed */ } } });
function temporaryDatabasePath(prefix = "craftmind-phase33-") {
  const directory = mkdtempSync(join(tmpdir(), prefix));
  temporaryDirectories.add(directory);
  return join(directory, "identity.sqlite");
}
after(() => { for (const directory of temporaryDirectories) rmSync(directory, { recursive: true, force: true }); rmSync(CONFIG_ROOT, { recursive: true, force: true }); });

const services = new Set();
async function newService(overrides = {}) {
  const service = await startService({ ...RELAXED, ...overrides });
  services.add(service);
  return service;
}
after(async () => {
  for (const service of services) {
    try { await service.close(); } catch { /* a test that closed the handle deliberately is not a failure */ }
  }
});

/**
 * The same service `startService` builds, assembled here so a test may close the database handle itself (readiness)
 * or boot in production mode with a real file (the helpers only ever boot `:memory:`, which production forbids).
 */
async function ownService({ overrides = {}, production = false } = {}) {
  const databaseUrl = production ? temporaryDatabasePath("craftmind-phase33-prod-") : ":memory:";
  const environment = production
    ? {
      NODE_ENV: "production", DATABASE_URL: databaseUrl, AUTH_SECRET: SECRET,
      HOST: "127.0.0.1",
      EMAIL_PROVIDER: "webhook", EMAIL_WEBHOOK_URL: "https://mail.example/send",
      EMAIL_WEBHOOK_TOKEN: "a-webhook-token-long-enough-24", EMAIL_FROM: "accounts@craftmind.example",
      PUBLIC_ORIGIN: "https://craftmind.example", TRUST_PROXY_TLS: "true",
      ...RELAXED, ...overrides,
    }
    : { ...BASE_ENV, ...RELAXED, ...overrides };
  const configuration = loadConfiguration(environment, { allowInMemoryDatabase: !production });
  const database = openDatabase(configuration.databaseUrl);
  const logs = [];
  const logger = {
    info: (line) => logs.push(`info ${line}`),
    warn: (line) => logs.push(`warn ${line}`),
    error: (line) => logs.push(`error ${line}`),
  };
  const server = createAccountService({ database, configuration, logger, emailDelivery: new DevelopmentEmailSink() });
  await new Promise((done) => server.listen(0, "127.0.0.1", done));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  const handle = {
    baseUrl, database, logs, server, configuration, emailDelivery: new DevelopmentEmailSink(), emailDelivery: new DevelopmentEmailSink(),
    async close() {
      await new Promise((done) => server.close(done));
      try { database.close(); } catch { /* already closed on purpose by the test */ }
      services.delete(handle);
    },
  };
  services.add(handle);
  return handle;
}

/** One request over a raw socket, for the cases a `fetch` client cannot express (partial bodies, stalls, framing). */
function rawRequest(port, { bytes, keepOpen = false, expectResponse = false, timeoutMs = 1500 }) {
  return new Promise((resolveResult) => {
    const socket = net.connect(port, "127.0.0.1");
    let received = "";
    let closed = false;
    const finish = (outcome) => {
      if (closed) return;
      closed = true;
      clearTimeout(timer);
      if (!keepOpen) socket.destroy();
      resolveResult(outcome);
    };
    const timer = setTimeout(() => finish({ response: received, closedBy: "test-timeout" }), timeoutMs);
    socket.on("connect", () => socket.write(bytes));
    socket.on("data", (chunk) => {
      received += chunk.toString("latin1");
      if (expectResponse && /\r\n\r\n/.test(received)) finish({ response: received, closedBy: "server" });
    });
    socket.on("close", () => finish({ response: received, closedBy: "socket-closed" }));
    socket.on("error", (error) => finish({ response: received, error: error.code ?? error.message, closedBy: "error" }));
  });
}

function head(method, path, { host = "127.0.0.1", contentLength, extra = {} } = {}) {
  const lines = [`${method} ${path} HTTP/1.1`, `Host: ${host}`];
  if (contentLength !== undefined) lines.push(`Content-Length: ${contentLength}`);
  for (const [key, value] of Object.entries(extra)) lines.push(`${key}: ${value}`);
  return `${lines.join("\r\n")}\r\n\r\n`;
}

async function freePort() {
  const probe = net.createServer();
  await new Promise((done) => probe.listen(0, "127.0.0.1", done));
  const port = probe.address().port;
  await new Promise((done) => probe.close(done));
  return port;
}

/** Runs the real entry point in a child process, which is the only place signal handling and exit codes exist. */
function runService({ env = {}, waitFor = "account_service_started", waitMs = 6000 } = {}) {
  const child = spawn(process.execPath, ["--no-warnings=ExperimentalWarning", "src/index.js"], {
    cwd: BACKEND_ROOT,
    env: { ...process.env, ...env },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (chunk) => { stdout += chunk.toString(); });
  child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
  spawned.add(child);
  const exited = new Promise((done) => child.on("exit", (code, signal) => { spawned.delete(child); done({ code, signal }); }));
  const ready = waitFor === null
    ? Promise.resolve()
    : new Promise((done, reject) => {
      const timer = setTimeout(() => reject(new Error(`the service never reported "${waitFor}". stdout=${stdout} stderr=${stderr}`)), waitMs);
      const poll = setInterval(() => {
        const both = `${stdout}${stderr}`;
        if (both.includes(waitFor) || both.includes("cannot start")) { clearInterval(poll); clearTimeout(timer); done(); }
      }, 25);
    });
  return {
    child,
    get stdout() { return stdout; },
    get stderr() { return stderr; },
    ready,
    exited,
    signal(name) { child.kill(name); },
    kill() { try { child.kill("SIGKILL"); } catch { /* already exited */ } },
    async waitForExit() { return exited; },
  };
}

/* ------------------------------------------------------------------------------- 1: configuration validation */

describe("Phase 33 configuration validation", () => {
  const load = (env) => loadConfiguration({ ...BASE_ENV, ...env }, { allowInMemoryDatabase: true });
  const refuses = (env, pattern, label) => assert.throws(() => load(env), pattern, label);

  it("accepts the host forms a real deployment uses, and rejects the ones that cannot bind", () => {
    for (const host of ["127.0.0.1", "0.0.0.0", "::1", "[::1]", "localhost", "api.internal.example"]) {
      assert.equal(load({ HOST: host }).host, host.replace(/^\[|\]$/g, ""), host);
    }
    refuses({ HOST: "" }, /IPv4 address, an IPv6 address, or a hostname/, "empty");
    refuses({ HOST: "0.0.0.0:8787" }, /must not include a port/, "the classic port-in-host typo");
    refuses({ HOST: "http://127.0.0.1" }, /must not include a port or a URL/, "a URL is not a bind address");
    refuses({ HOST: "0.0.0.0; reboot" }, /IPv4 address, an IPv6 address, or a hostname/, "shell-shaped input is not a host");
    refuses({ HOST: "a".repeat(300) }, /IPv4 address, an IPv6 address, or a hostname/, "an over-long label");
  });

  it("validates that timeouts and token lifetimes are mutually possible", () => {
    refuses({ ACCESS_TOKEN_TTL_SECONDS: "7200", REFRESH_TOKEN_TTL_SECONDS: "3600" },
      /ACCESS_TOKEN_TTL_SECONDS must be shorter than REFRESH_TOKEN_TTL_SECONDS/);
    refuses({ DEVELOPER_ACCESS_TOKEN_TTL_SECONDS: "3600", DEVELOPER_REFRESH_TOKEN_TTL_SECONDS: "1800" },
      /DEVELOPER_ACCESS_TOKEN_TTL_SECONDS must be shorter/);
    refuses({ HEADERS_TIMEOUT_MS: "60000", REQUEST_TIMEOUT_MS: "30000" },
      /HEADERS_TIMEOUT_MS cannot exceed REQUEST_TIMEOUT_MS/);
    refuses({ HEADERS_TIMEOUT_MS: "1000", KEEP_ALIVE_TIMEOUT_MS: "5000" },
      /HEADERS_TIMEOUT_MS must not be shorter than KEEP_ALIVE_TIMEOUT_MS/);
    refuses({ KEEP_ALIVE_TIMEOUT_MS: "30000", REQUEST_TIMEOUT_MS: "30000" },
      /KEEP_ALIVE_TIMEOUT_MS must be shorter than REQUEST_TIMEOUT_MS/);
    refuses({ SECURITY_DEFAULT_PROTECTION_SECONDS: "7200", SECURITY_MAX_PROTECTION_SECONDS: "600" },
      /SECURITY_DEFAULT_PROTECTION_SECONDS cannot exceed SECURITY_MAX_PROTECTION_SECONDS/);
    refuses({ MAX_BODY_BYTES: "16" }, /MAX_BODY_BYTES must be at least 1024 bytes/, "a cap that cannot carry a listing");
    // And the documented defaults are internally consistent, so a fresh deployment never trips its own rules.
    const defaults = load({});
    assert.equal(defaults.server.requestTimeoutMs, 30000);
    assert.equal(defaults.server.headersTimeoutMs, 10000);
    assert.equal(defaults.server.keepAliveTimeoutMs, 5000);
    assert.equal(defaults.server.shutdownGraceMs, 10000);
    assert.equal(defaults.maxBodyBytes, 16384);
  });

  it("reports why it refused without ever repeating a configured value", () => {
    const sentinel = "super-secret-sentinel-value-that-is-long";
    for (const env of [
      { AUTH_SECRET: sentinel.slice(0, 20) },
      { RATE_LOGIN_MAX: `${sentinel}` },
      { PORT: sentinel },
      { EMAIL_WEBHOOK_TOKEN: "tiny" , EMAIL_PROVIDER: "webhook", EMAIL_WEBHOOK_URL: "https://mail.example/x", EMAIL_FROM: "a@b.example" },
    ]) {
      let message = "";
      try { load(env); } catch (error) { message = error.message; }
      assert.notEqual(message, "", "the configuration must have been refused");
      assert.equal(message.includes(sentinel), false, `the refusal leaked a value: ${message}`);
      assert.equal(message.includes(CONFIG_DATABASE_URL) || message.includes("configured-by-environment"), false,
        "a refusal must not echo the database path");
    }
  });

  it("separates development from production instead of softening production", () => {
    // The same settings boot in development and are refused in production, one rule at a time.
    const productionShaped = {
      NODE_ENV: "production", DATABASE_URL: CONFIG_DATABASE_URL,
      EMAIL_PROVIDER: "webhook", EMAIL_WEBHOOK_URL: "https://mail.example/send",
      EMAIL_WEBHOOK_TOKEN: "a-webhook-token-long-enough-24", EMAIL_FROM: "accounts@craftmind.example",
      PUBLIC_ORIGIN: "https://craftmind.example", TRUST_PROXY_TLS: "true",
    };
    const productionLoad = (extra) => () => loadConfiguration({ ...BASE_ENV, ...productionShaped, ...extra });
    assert.doesNotThrow(productionLoad({}), "a complete production configuration must load");
    assert.throws(productionLoad({ PUBLIC_ORIGIN: "" }), /PUBLIC_ORIGIN must be an absolute HTTPS URL/);
    assert.throws(productionLoad({ TRUST_PROXY_TLS: "false" }), /TRUST_PROXY_TLS=true/);
    // In production the provider gate is the first check, so a development sink is refused there rather than
    // reaching the "sink is test-only" branch further down the function.
    assert.throws(productionLoad({ EMAIL_PROVIDER: "memory" }), /production requires EMAIL_PROVIDER=webhook/);
    assert.throws(productionLoad({ CORS_ALLOWED_ORIGINS: "*" }), /does not allow wildcard origins/);
    assert.throws(productionLoad({ DATABASE_URL: ":memory:" }), /in-memory databases are for tests only/);
    // Development keeps working with the in-memory sink and no public origin: the gates are production-only on purpose.
    const development = load({});
    assert.equal(development.production, false);
    assert.equal(development.publicOrigin, null);
    assert.equal(development.emailProvider, "memory");
  });

  it("refuses to start on a runtime that cannot provide the database driver", async () => {
    await assert.rejects(() => assertSupportedRuntime({ nodeVersion: "20.11.1" }),
      /Node\.js 22\.5\.0 or newer is required for the built-in SQLite driver/);
    await assert.rejects(() => assertSupportedRuntime({ nodeVersion: "22.4.9" }), /or newer is required/);
    await assert.rejects(() => assertSupportedRuntime({ nodeVersion: "not a version" }), /not a version this service can validate/);
    await assert.rejects(() => assertSupportedRuntime({ nodeVersion: "22.22.3", sqliteSpecifier: "node:definitely-not-a-real-module" }),
      /does not expose node:sqlite/);
    await assert.doesNotReject(() => assertSupportedRuntime());
    assert.deepEqual(MINIMUM_NODE_VERSION, { major: 22, minor: 5, patch: 0 });
  });

  it("keeps one runtime floor across code, manifest and documentation", () => {
    const manifest = JSON.parse(readFileSync(join(BACKEND_ROOT, "package.json"), "utf8"));
    assert.equal(manifest.engines.node, `>=${MINIMUM_NODE_VERSION.major}.${MINIMUM_NODE_VERSION.minor}.${MINIMUM_NODE_VERSION.patch}`);
    assert.deepEqual(manifest.dependencies ?? {}, {}, "the zero-dependency posture is the point, not an accident");
    const docs = readFileSync(join(BACKEND_ROOT, "..", "docs", "account-authentication.md"), "utf8");
    assert.match(docs, new RegExp(`Node\\.js ${MINIMUM_NODE_VERSION.major}\\.${MINIMUM_NODE_VERSION.minor} or newer`));
  });

  it("documents every configuration key it reads, so the environment cannot drift from the docs", () => {
    const configSource = readFileSync(join(BACKEND_ROOT, "src", "config.js"), "utf8");
    const documented = [
      readFileSync(join(BACKEND_ROOT, ".env.example"), "utf8"),
      readFileSync(join(BACKEND_ROOT, "..", "docs", "account-authentication.md"), "utf8"),
      readFileSync(join(BACKEND_ROOT, "..", "docs", "production-operations.md"), "utf8"),
    ].join("\n");
    const referenced = new Set();
    for (const pattern of [/environment\.([A-Z][A-Z0-9_]{2,})/g, /positiveInteger\(environment, "([A-Z][A-Z0-9_]+)"/g, /httpsUrl\(environment, "([A-Z][A-Z0-9_]+)"/g]) {
      for (const match of configSource.matchAll(pattern)) referenced.add(match[1]);
    }
    referenced.delete("NODE_ENV");
    const undocumented = [...referenced].filter((key) => !documented.includes(key)).sort();
    assert.deepEqual(undocumented, [], `${undocumented.length} configuration key(s) exist in code but in no document`);
    assert.ok(referenced.size > 80, `the sweep should have found the whole configuration surface, found ${referenced.size}`);
  });

  it("boots from .env.example with only the secret left to fill in", () => {
    const example = readFileSync(join(BACKEND_ROOT, ".env.example"), "utf8");
    const environment = {};
    for (const line of example.split("\n")) {
      const match = /^([A-Z][A-Z0-9_]+)=(.*)$/.exec(line.trim());
      if (match) environment[match[1]] = match[2];
    }
    // The example file must be *usable*: the only thing standing between a copy of it and a running service is the
    // secret an operator generates. A missing key or a malformed default would show up here instead of at 03:00.
    assert.throws(() => loadConfiguration({ ...environment, AUTH_SECRET: "" }),
      (error) => error instanceof ConfigurationError && /AUTH_SECRET/.test(error.message));
    const filled = loadConfiguration({ ...environment, AUTH_SECRET: SECRET });
    assert.equal(filled.host, "127.0.0.1");
    assert.equal(filled.port, 8787);
    assert.equal(filled.production, false);
    assert.equal(filled.emailProvider, "memory");
  });
});

/* ------------------------------------------------------------------------------- 2: HTTP runtime bounds */

describe("Phase 33 HTTP runtime bounds", () => {
  it("drops a client that stalls mid-request, using the configured bound", async () => {
    // Deliberately tiny numbers: the point is that the configured value reaches the socket. A client that sends a
    // head, declares a body, and then stops must be closed by the server, not held until Node's 300 s default ends.
    const service = await newService({ REQUEST_TIMEOUT_MS: "300", HEADERS_TIMEOUT_MS: "250", KEEP_ALIVE_TIMEOUT_MS: "100" });
    const port = Number(new URL(service.baseUrl).port);
    const outcome = await rawRequest(port, {
      bytes: head("POST", "/auth/login", { contentLength: 5000, extra: { "Content-Type": "application/json" } }) + "{}",
      timeoutMs: 3000,
    });
    assert.doesNotMatch(outcome.response, /^HTTP\/1\.1/, "a stalled client is answered by closing the socket, not with a status code");
    assert.notEqual(outcome.closedBy, "test-timeout", `the server should have hung up first; got ${outcome.closedBy}`);
    // A normal request on the same service is unaffected: the bound is for stalls, not for legitimate traffic.
    assert.equal((await call(service.baseUrl, "GET", "/health")).status, 200);
  });

  it("rejects an oversized body while it is still arriving, with the typed error shape", async () => {
    const service = await newService({ MAX_BODY_BYTES: "1024" });
    const port = Number(new URL(service.baseUrl).port);
    // The declared length is far above the cap and only a fraction of it is ever sent: an answer must still arrive,
    // which is what proves the limit is enforced as bytes stream in rather than after a full upload.
    const bytes = head("POST", "/auth/register", { contentLength: 900_000, extra: { "Content-Type": "application/json" } })
      + JSON.stringify({ email: `big-${Math.random().toString(36).slice(2)}@example.test`, password: "Correct Horse 7Battery", displayName: "x".repeat(2000) });
    const outcome = await rawRequest(port, { bytes, expectResponse: true, timeoutMs: 2000 });
    assert.match(outcome.response, /HTTP\/1\.1 413/, `expected a prompt 413, got: ${outcome.response.slice(0, 120)}`);
    assert.match(outcome.response, /REQUEST_TOO_LARGE/);
    assert.match(outcome.response, /x-content-type-options: nosniff/i);
    // And a body the same service can legitimately answer still works, so the cap is a bound and not a block.
    const small = await call(service.baseUrl, "POST", "/auth/register", {
      raw: JSON.stringify({ email: `ok-${Math.random().toString(36).slice(2)}@example.test`, password: "Correct Horse 7Battery", displayName: "Within Cap" }),
    });
    assert.equal(small.status, 201, JSON.stringify(small.body));
  });

  it("answers malformed JSON, a wrong content type, an unknown path and a wrong method with the same safe shape", async () => {
    const service = await newService();
    const malformed = await call(service.baseUrl, "POST", "/auth/login", { raw: "{\"email\":", headers: { "Content-Type": "application/json" } });
    assert.equal(malformed.status, 400);
    assert.equal(malformed.body.error.code, ErrorCode.INVALID_REQUEST);
    const wrongType = await call(service.baseUrl, "POST", "/auth/login", { raw: "{}", headers: { "Content-Type": "text/plain" } });
    assert.equal(wrongType.status, 415);
    assert.equal(wrongType.body.error.code, ErrorCode.INVALID_CONTENT_TYPE);
    const missing = await call(service.baseUrl, "GET", "/no-such-endpoint");
    assert.equal(missing.status, 400, "an unknown path is not distinguished from other client mistakes");
    const method = await call(service.baseUrl, "DELETE", "/auth/login");
    assert.equal(method.status, 405);
    for (const response of [malformed, wrongType, missing, method]) {
      const serialized = JSON.stringify(response.body);
      assert.equal(typeof response.body.error.requestId, "string", serialized);
      for (const leak of ["node:", "ERR_", "SQLITE", "sqlite", "DatabaseSync", "at Object.", "at async", ".js:", "prepare("]) {
        assert.equal(serialized.toLowerCase().includes(leak.toLowerCase()), false, `an error body must not contain "${leak}": ${serialized}`);
      }
    }
  });

  it("keeps the security headers identical on success, client error and unreadiness", async () => {
    const service = await newService();
    const ok = await call(service.baseUrl, "GET", "/health");
    const notFound = await call(service.baseUrl, "GET", "/definitely-not-a-route");
    const headersOf = (headers) => ["cache-control", "x-content-type-options", "referrer-policy", "x-frame-options", "content-security-policy", "permissions-policy"]
      .map((name) => `${name}=${headers.get(name)}`)
      .join("|");
    assert.match(headersOf(ok.headers), /cache-control=no-store, max-age=0/);
    assert.equal(headersOf(notFound.headers), headersOf(ok.headers), "an error response is not an opportunity to drop a header");
    assert.equal(ok.headers.get("x-frame-options"), "DENY");
    assert.equal(ok.headers.get("permissions-policy"), "camera=(), microphone=(), geolocation=()");
    assert.equal(notFound.headers.get("content-security-policy").includes("default-src 'none'"), true);
  });

  it("keys rate limits and abuse detection on the socket, so a forged X-Forwarded-For buys nothing", async () => {
    const service = await newService({ RATE_LOGIN_MAX: "3", RATE_LOGIN_WINDOW_MS: "600000" });
    const statuses = [];
    for (let attempt = 0; attempt < 4; attempt += 1) {
      const response = await call(service.baseUrl, "POST", "/auth/login", {
        body: { email: `spoof${attempt}@example.test`, password: "wrong-password-here" },
        headers: { "X-Forwarded-For": `203.0.113.${attempt}`, "X-Forwarded-Proto": "https", "X-Forwarded-Host": "victim.example" },
      });
      statuses.push(response.status);
    }
    assert.deepEqual(statuses.slice(0, 3), [401, 401, 401], `three ordinary failures: ${JSON.stringify(statuses)}`);
    assert.equal(statuses[3], 429, "four rotating forwarded IPs must not reset the same socket's budget");
    const limited = await call(service.baseUrl, "POST", "/auth/login", { body: { email: "spoof9@example.test", password: "wrong-password-here" } });
    assert.equal(limited.body.error.code, ErrorCode.RATE_LIMITED);
    assert.equal(typeof limited.body.error.requestId, "string");
  });

  it("classifies a client that hangs up as an abort, not as a server failure or an attack", async () => {
    const service = await newService();
    const port = Number(new URL(service.baseUrl).port);
    const before = service.database.prepare("SELECT COUNT(*) AS c FROM security_events").get().c;
    // Headers claim a large body, two bytes arrive, then the socket goes away: the handler never gets a request.
    await rawRequest(port, {
      bytes: head("POST", "/auth/login", { contentLength: 4000, extra: { "Content-Type": "application/json" } }) + "{}",
      timeoutMs: 1500,
    });
    await new Promise((done) => setTimeout(done, 150));
    const logged = service.logs.join("\n");
    assert.match(logged, /account_http_client_aborted/);
    assert.equal(logged.includes("account_request_internal_failure"), false, "a disconnect is not an internal failure");
    assert.equal(logged.includes('"status":499'), true, "the access log line carries the conventional client-abort code");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS c FROM security_events").get().c, before,
      "an aborted request is not evidence about the client and must not spend anyone's abuse budget");
  });

  it("never writes a response to a socket the client already closed", async () => {
    const service = await newService();
    const port = Number(new URL(service.baseUrl).port);
    // A complete request followed by an immediate shutdown of the write half: the handler will answer into nothing.
    const outcome = await new Promise((done) => {
      const socket = net.connect(port, "127.0.0.1", () => {
        socket.write(head("GET", "/live"));
        socket.end();
      });
      let received = "";
      socket.on("data", (chunk) => { received += chunk.toString(); });
      socket.on("close", () => done(received));
      socket.on("error", () => done(received));
      setTimeout(() => done(received), 1200);
    });
    assert.doesNotMatch(outcome, /HTTP\/1\.1 500/, "a half-closed connection must not be answered with a server error");
    const logged = service.logs.join("\n");
    assert.equal(logged.includes("ERR_HTTP_HEADERS_SENT"), false);
    assert.equal(logged.includes("account_request_internal_failure"), false, logged.slice(0, 200));
  });
});

/* ------------------------------------------------------------------------------- 3: observability */

describe("Phase 33 health, liveness and log hygiene", () => {
  it("splits liveness from readiness, and only readiness consults the database", async () => {
    const service = await ownService();
    const live = await call(service.baseUrl, "GET", "/live");
    assert.equal(live.status, 200);
    assert.deepEqual(live.body, { status: "alive", service: "craftmind-auth" }, "liveness carries nothing else");

    const ready = await call(service.baseUrl, "GET", "/health");
    assert.equal(ready.status, 200);
    assert.equal(ready.body.status, "ok");
    assert.equal(ready.body.database, "reachable");
    assert.equal(ready.body.schema, "current");
    assert.equal(ready.body.schemaVersion, SCHEMA_VERSION, "the existing field stays: operators already read it");

    // Close the handle underneath a running process: that is exactly a disk or database fault.
    service.database.close();
    const degraded = await call(service.baseUrl, "GET", "/health");
    assert.equal(degraded.status, 503, "not ready must not be reported as ready");
    assert.equal(degraded.body.database, "unreachable");
    assert.equal(typeof degraded.body.failure, "string");
    const serialized = JSON.stringify(degraded.body).toLowerCase();
    assert.ok(String(degraded.body.failure).length <= 12, `failure must stay a category, got "${degraded.body.failure}"`);
    for (const leak of ["errno", "node:", "databaseurl", ".db", "src/", SECRET.toLowerCase()]) {
      assert.equal(serialized.includes(leak), false, `an unready probe must not explain itself with "${leak}": ${serialized}`);
    }
    // And liveness still answers, which is the whole reason the two exist separately: a probe that restarts a
    // process for a storage blip turns one fault into an outage.
    assert.equal((await call(service.baseUrl, "GET", "/live")).status, 200);
  });

  it("reveals no secret, path, or account data through the probe endpoints", async () => {
    const service = await ownService();
    await registerVerified(service, { email: `probe-${Math.random().toString(36).slice(2)}@example.test` }).catch(() => {});
    for (const path of ["/live", "/health"]) {
      const response = await call(service.baseUrl, "GET", path);
      const serialized = JSON.stringify(response.body);
      const forbiddenStrings = [SECRET, "usr_", "@example.test", "configured-by-environment", CONFIG_ROOT, "tmp", ":memory:", "AUTH_SECRET", "DATABASE_URL", ".db"];
      for (const forbidden of forbiddenStrings) {
        assert.equal(serialized.includes(forbidden), false, `${path} reveals "${forbidden}"`);
      }
    }
  });

  it("logs one structured line per request, with the route pattern rather than the path, and no credentials", async () => {
    const service = await newService();
    const token = "Bearer super-private-session-token-value-123456";
    const email = `logsentinel-${Math.random().toString(36).slice(2)}@example.test`;
    const before = service.logs.length;
    await call(service.baseUrl, "GET", "/auth/me", { headers: { Authorization: token } });
    await call(service.baseUrl, "POST", "/auth/register", { body: { email, password: "Correct Horse 7Battery", displayName: "Log Sentinel" } });
    const lines = service.logs.slice(before);
    assert.equal(lines.length, 2, `exactly one line per request: ${JSON.stringify(lines)}`);
    const logged = lines.join("\n");
    for (const forbidden of [token, "super-private-session-token", email, "Correct Horse 7Battery", "Authorization"]) {
      assert.equal(logged.includes(forbidden), false, `the request log must not carry "${forbidden}"`);
    }
    for (const line of lines) {
      const entry = JSON.parse(line.replace(/^(info|warn|error) /, ""));
      assert.equal(entry.event, "account_http_request");
      assert.equal(typeof entry.requestId, "string", "every line is correlatable with the client-facing error id");
      assert.equal(typeof entry.durationMs, "number");
       assert.equal(typeof entry.route, "string");
      assert.equal(entry.route.includes(email), false, "a concrete path segment never reaches the log");
      assert.equal(Number.isInteger(entry.status), true);
    }
  });

  it("keeps an attacker-controlled newline out of the log structure", async () => {
    const service = await newService();
    await call(service.baseUrl, "GET", `/%%0a%%{"event":"forged"}%%2Fx`);
    for (const line of service.logs) {
      assert.equal(line.includes("\n{\"event\":\"forged\"}"), false, "a request must not be able to inject a second log entry");
    }
  });
});

/* ------------------------------------------------------------------------------- 4: migrations, startup, shutdown */

describe("Phase 33 migration and process lifecycle", () => {
  it("leaves no advanced schema version when a migration fails halfway", () => {
    const file = temporaryDatabasePath();
    const database = new DatabaseSync(file);
    database.exec("PRAGMA foreign_keys = ON");
    database.exec("PRAGMA recursive_triggers = ON");
    database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    migrateToVersion(database, 11);
    const stamp = new Date().toISOString();
    database.prepare("INSERT INTO users (user_id,email,email_canonical,display_name,password_hash,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)")
      .run("usr_half", "half@example.test", "half@example.test", "Half", "h", "ACTIVE", stamp, stamp);

    // A table in the way of v12 simulates the realistic failure: a partially applied or hand-edited schema.
    database.exec("CREATE TABLE referral_codes (code TEXT PRIMARY KEY)");
    assert.throws(() => migrateToVersion(database, SCHEMA_VERSION), /already exists/i);
    assert.equal(database.prepare("SELECT COUNT(*) AS c FROM schema_migrations WHERE version = ?").get(SCHEMA_VERSION).c, 0,
      "the watermark must not claim a version that does not exist in the file");
    assert.equal(database.prepare("SELECT name FROM sqlite_master WHERE name = 'referral_attributions'").get(), undefined);
    assert.equal(database.prepare("SELECT display_name FROM users WHERE user_id = 'usr_half'").get().display_name, "Half", "a failed migration loses nothing");

    // Once the conflict is cleared, the same connection can finish the job: the rollback left it usable, not wedged.
    database.exec("DROP TABLE referral_codes");
    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(database.prepare("SELECT COUNT(*) AS c FROM schema_migrations WHERE version = ?").get(SCHEMA_VERSION).c, 1);
    assert.notEqual(database.prepare("SELECT name FROM sqlite_master WHERE name = 'referral_attributions'").get(), undefined);
    assert.equal(database.prepare("SELECT display_name FROM users WHERE user_id = 'usr_half'").get().display_name, "Half");
    database.close();
  });

  it("repeated startup on a persistent file neither re-applies nor re-writes anything", () => {
    const file = temporaryDatabasePath();
    const first = openDatabase(file);
    const stamp = new Date().toISOString();
    first.prepare("INSERT INTO users (user_id,email,email_canonical,display_name,password_hash,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)")
      .run("usr_cycle", "cycle@example.test", "cycle@example.test", "Cycle", "h", "ACTIVE", stamp, stamp);
    const applied = first.prepare("SELECT version, applied_at FROM schema_migrations ORDER BY version").all();
    assert.equal(applied.length, SCHEMA_VERSION, "one row per version, in order, from the first start");
    assert.deepEqual(applied.map((row) => row.version), applied.map((row) => Number(row.version)).sort((a, b) => a - b));
    first.close();

    for (const _round of [0, 1, 2]) {
      const again = openDatabase(file);
      assert.deepEqual(again.prepare("SELECT version, applied_at FROM schema_migrations ORDER BY version").all(), applied,
        "a later startup must be a no-op: same versions, same timestamps, no duplicates");
      assert.equal(again.prepare("SELECT display_name FROM users WHERE user_id = 'usr_cycle'").get().display_name, "Cycle");
      again.close();
    }
  });

  it("refuses to start on an unusable database path, without echoing the path", async () => {
    const run = runService({
      env: {
        NODE_ENV: "development", DATABASE_URL: tmpdir(),
        AUTH_SECRET: SECRET, HOST: "127.0.0.1", PORT: String(await freePort()),
      },
      waitFor: "cannot start",
    });
    await run.ready;
    const outcome = await run.waitForExit();
    assert.equal(outcome.code, 2, `exit code must distinguish a refused start: ${run.stderr}`);
    assert.match(run.stderr, /cannot start/);
    assert.match(run.stderr, /database stage failed/);
    assert.equal(run.stderr.includes(tmpdir()), false, "a startup failure line must not repeat the configured path");
    assert.equal(run.stderr.includes(SECRET), false);
  });

  it("starts, serves both probes, and shuts down idempotently on a signal", async () => {
    const directory = mkdtempSync(join(tmpdir(), "craftmind-phase33-run-"));
    temporaryDirectories.add(directory);
    const port = await freePort();
    const run = runService({
      env: { NODE_ENV: "development", DATABASE_URL: join(directory, "service.db"), AUTH_SECRET: SECRET, HOST: "127.0.0.1", PORT: String(port) },
    });
    await run.ready;
    const baseUrl = `http://127.0.0.1:${port}`;
    const live = await call(baseUrl, "GET", "/live");
    assert.equal(live.status, 200);
    const health = await call(baseUrl, "GET", "/health");
    assert.equal(health.status, 200);
    assert.equal(health.body.schemaVersion, SCHEMA_VERSION);

    run.signal("SIGTERM");
    run.signal("SIGINT");
    run.signal("SIGTERM");
    const outcome = await run.waitForExit();
    assert.equal(outcome.code, 0, `a clean stop exits 0; stderr was: ${run.stderr}`);
    assert.equal((run.stdout.match(/account_service_started/g) ?? []).length, 1);
    assert.equal((run.stdout.match(/account_service_stopping/g) ?? []).length, 1, "three signals, one shutdown");
    assert.equal((run.stdout.match(/account_service_stopped/g) ?? []).length, 1);
    assert.equal(run.stdout.includes(SECRET), false, "the startup line must not carry a secret");
    assert.equal(run.stdout.includes(directory), false, "nor the data directory");
    // The port must be free again: the process really released it, so a supervisor can restart into the same slot.
    const rebind = net.createServer();
    await new Promise((done) => rebind.listen(port, "127.0.0.1", done));
    await new Promise((done) => rebind.close(done));
    // And the database it wrote survives the shutdown intact, ready for the next start.
    const reopened = openDatabase(join(directory, "service.db"));
    assert.equal(reopened.prepare("SELECT MAX(version) AS v FROM schema_migrations").get().v, SCHEMA_VERSION);
    reopened.close();
  });

  it("fails with a clear line when the port is already taken", async () => {
    const directory = mkdtempSync(join(tmpdir(), "craftmind-phase33-bind-"));
    temporaryDirectories.add(directory);
    const squatter = net.createServer();
    await new Promise((done) => squatter.listen(0, "127.0.0.1", done));
    localServers.add(squatter);
    const port = squatter.address().port;
    const run = runService({
      env: { NODE_ENV: "development", DATABASE_URL: join(directory, "service.db"), AUTH_SECRET: SECRET, HOST: "127.0.0.1", PORT: String(port) },
      waitFor: "listen_failed",
    });
    await run.ready;
    const outcome = await run.waitForExit();
    assert.equal(outcome.code, 2, `a bind failure is not a healthy start; stdout: ${run.stdout}`);
    assert.match(run.stderr, /account_service_listen_failed/);
    assert.match(run.stderr, /EADDRINUSE/);
    assert.equal(run.stderr.includes("ReferenceError"), false, "a bind failure must be reported, not crash the process");
    assert.equal(run.stderr.includes(SECRET), false, "the refusal line carries the code, not the environment");
    squatter.close();
  });

  it("carries the configured bounds onto the server it builds", async () => {
    const service = await ownService({ overrides: { REQUEST_TIMEOUT_MS: "23456", HEADERS_TIMEOUT_MS: "12345", KEEP_ALIVE_TIMEOUT_MS: "4321" } });
    assert.equal(service.server.requestTimeout, 23456);
    assert.equal(service.server.headersTimeout, 12345);
    assert.equal(service.server.keepAliveTimeout, 4321);
    assert.equal(typeof service.server.closeIdleConnections, "function", "shutdown depends on this existing");
    assert.equal(typeof service.server.closeAllConnections, "function");
  });
});

/* ------------------------------------------------------------------------------- 5: production mode over a real socket */

describe("Phase 33 production gates on a real service", () => {
  it("serves only over the configured HTTPS gate", async () => {
    const service = await ownService({ production: true });
    const plain = await call(service.baseUrl, "GET", "/health");
    assert.equal(plain.status, 400, "production refuses cleartext without a trusted terminator's header");
    assert.equal(plain.body.error.code, ErrorCode.HTTPS_REQUIRED);

    const forwarded = await call(service.baseUrl, "GET", "/health", { headers: { "X-Forwarded-Proto": "https" } });
    assert.equal(forwarded.status, 200, "the configured terminator's header is what production trusts");
    assert.equal(forwarded.body.status, "ok");
    assert.equal(forwarded.headers.get("strict-transport-security"), "max-age=31536000; includeSubDomains");
  });

  it("measures origin against the configured public origin, never the caller's Host header", async () => {
    const service = await ownService({ production: true });
    const spoofed = await call(service.baseUrl, "GET", "/health", {
      headers: { Origin: "https://victim.example", Host: "victim.example", "X-Forwarded-Proto": "https" },
    });
    assert.equal(spoofed.status, 403, "a matching Host and Origin must not be treated as same-origin in production");
    assert.equal(spoofed.body.error.code, ErrorCode.CORS_ORIGIN_NOT_ALLOWED);
    const sameOrigin = await call(service.baseUrl, "GET", "/health", {
      headers: { Origin: "https://craftmind.example", "X-Forwarded-Proto": "https" },
    });
    assert.equal(sameOrigin.status, 200);
    assert.equal(sameOrigin.headers.get("access-control-allow-origin"), null, "same-origin needs no CORS header");
  });

  it("keeps every sensitive route requiring a session under the hardened runtime", async () => {
    const service = await newService();
    for (const path of ["/auth/me", "/account/credits", "/marketplace/analytics/creator", "/marketplace/trust", "/marketing/referrals/me"]) {
      const anonymous = await call(service.baseUrl, "GET", path);
      assert.equal(anonymous.status, 401, `${path} must still demand a session`);
      const garbage = await call(service.baseUrl, "GET", path, { headers: { Authorization: "Bearer not-a-real-token-value-000000" } });
      assert.equal(garbage.status, 401, `${path} must not distinguish a forged token`);
      assert.equal(JSON.stringify(garbage.body).includes("HMAC"), false);
    }
    for (const path of ["/marketing/referrals/claim", "/marketplace/reports", "/account/credits/consume", "/marketing/referrals/verify"]) {
      assert.equal((await call(service.baseUrl, "POST", path, { body: {} })).status, 401, path);
    }
  });
});

/* ------------------------------------------------------------------------------- 6: backup and restore */

describe("Phase 33 backup and restore", () => {
  function fileServiceWithAccount() {
    const file = temporaryDatabasePath("craftmind-phase33-db-");
    const database = openDatabase(file);
    const stamp = new Date().toISOString();
    database.prepare("INSERT INTO users (user_id,email,email_canonical,display_name,password_hash,status,created_at,updated_at,email_verified_at) VALUES (?,?,?,?,?,?,?,?,?)")
      .run("usr_backup", "restore-proof@example.test", "restore-proof@example.test", "Restore Proof", "hash", "ACTIVE", stamp, stamp, stamp);
    database.prepare("INSERT INTO referral_codes (code, user_id, created_at, updated_at) VALUES (?, ?, ?, ?)")
      .run("CM-ABCDEF012345", "usr_backup", stamp, stamp);
    return { file, directory: resolve(file, ".."), database };
  }

  it("snapshots a live WAL database including what has not been checkpointed yet", () => {
    const { file, directory, database } = fileServiceWithAccount();
    const journal = join(directory, `${file.split("/").pop()}-wal`);
    // A WAL sidecar is the proof that the newest commits are not in the main file yet — precisely the state a
    // copied file gets wrong and `VACUUM INTO` gets right.
    assert.ok(existsSync(journal), "WAL mode is expected for a file-backed database");

    const snapshot = join(directory, "backups", defaultBackupFileName());
    const result = createBackup(database, snapshot);
    assert.equal(result.integrity, "ok");
    assert.equal(result.schemaVersion, SCHEMA_VERSION);
    assert.ok(result.bytes > 0);
    assert.equal(statSync(snapshot).mode & 0o777, 0o600, "a backup is the whole database and must be owner-only");
    assert.equal(statSync(resolve(snapshot, "..")).mode & 0o777, 0o700);

    // Uncheckpointed writes made *after* the snapshot must not affect it, and writes before it must be inside it.
    const restored = join(directory, "restored.db");
    const restoreResult = restoreBackup(snapshot, restored);
    assert.equal(restoreResult.replaced, false, "a restore into a path that did not exist replaced nothing, and must not claim otherwise");
    const opened = new DatabaseSync(restored, { readOnly: true });
    assert.equal(opened.prepare("PRAGMA integrity_check").get().integrity_check, "ok");
    assert.equal(opened.prepare("SELECT email FROM users WHERE user_id = 'usr_backup'").get().email, "restore-proof@example.test");
    assert.equal(opened.prepare("SELECT code FROM referral_codes WHERE user_id = 'usr_backup'").get().code, "CM-ABCDEF012345");
    assert.equal(opened.prepare("SELECT MAX(version) AS v FROM schema_migrations").get().v, SCHEMA_VERSION);
    opened.close();
    database.close();
  });

  it("refuses the destinations and the files that would make a restore a liability", () => {
    const { file, directory, database } = fileServiceWithAccount();
    const snapshot = join(directory, "backups", "one.db");
    createBackup(database, snapshot);

    assert.throws(() => createBackup(database, snapshot), /already exists; refusing to overwrite/, "a snapshot never clobbers another");
    assert.throws(() => createBackup(database, join(directory, "public", "leak.db"), { publicDirectories: [join(directory, "public")] }),
      /must not be inside a served website directory/);
    assert.equal(existsSync(join(directory, "public", "leak.db")), false, "the refusal happens before anything is written");

    const notADatabase = join(directory, "garbage.db");
    writeFileSync(notADatabase, "this is not a sqlite file at all, it is just text");
    assert.throws(() => inspectBackup(notADatabase), /.*/, "a foreign file must not be describable as a backup");
    assert.throws(() => restoreBackup(notADatabase, join(directory, "victim.db")), /.*/);
    assert.equal(existsSync(join(directory, "victim.db")), false);

    // A database with no migration watermark verifies as a file and still fails as a CraftMind snapshot — and the
    // artifact is deleted rather than left behind to be mistaken for a good one.
    const stray = new DatabaseSync(join(directory, "stray.db"));
    stray.exec("CREATE TABLE something (x)");
    assert.throws(() => createBackup(stray, join(directory, "backups", "stray-snapshot.db")), /did not verify/);
    assert.equal(existsSync(join(directory, "backups", "stray-snapshot.db")), false);
    stray.close();
    database.close();
  });

  it("restores only into a state an operator chose, and dry-run changes nothing", () => {
    const { directory, database } = fileServiceWithAccount();
    const snapshot = join(directory, "backups", "one.db");
    createBackup(database, snapshot);
    const target = join(directory, "target.db");

    const planned = restoreBackup(snapshot, target, { dryRun: true });
    assert.equal(planned.dryRun, true);
    assert.equal(existsSync(target), false, "--dry-run must not create the target");

    const first = restoreBackup(snapshot, target);
    assert.equal(first.replaced, false);
    assert.throws(() => restoreBackup(snapshot, target), /already exists; pass --force/, "an existing live database needs an explicit acknowledgement");
    writeFileSync(join(directory, "target.db-wal"), "stale sidecar from the database being replaced");
    const forced = restoreBackup(snapshot, target, { force: true });
    assert.equal(forced.integrity, "ok");
    assert.equal(forced.replaced, true, "only an overwrite of a database that was already there is reported as a replacement");
    assert.equal(existsSync(join(directory, "target.db-wal")), false, "a stale journal must not be replayed into the restored file");
    database.close();
  });

  it("backs up through a read-only handle, so the backup tool can never migrate the live file", async () => {
    const { file, directory } = fileServiceWithAccount();
    // A backup that opened the database the way the service does would run migrations on it. Prove it cannot.
    const readOnly = new DatabaseSync(file, { readOnly: true });
    try {
      assert.throws(() => readOnly.exec("CREATE TABLE sneaked (x)"), /readonly|attempt to write|ERR_SQLITE_ERROR/i);
      const snapshot = join(directory, "backups", "read-only-handle.db");
      createBackup(readOnly, snapshot);
      assert.equal(existsSync(snapshot), true);
    } finally {
      readOnly.close();
    }
    // And the live file is byte-for-byte untouched by the operation.
    const before = statSync(file).size;
    assert.ok(before > 0);
    const live = openDatabase(file);
    assert.equal(live.prepare("SELECT MAX(version) AS v FROM schema_migrations").get().v, SCHEMA_VERSION);
    live.close();
  });

  it("runs the operator commands end to end, reporting only what is safe to log", async () => {
    const { file, directory } = fileServiceWithAccount();
    const backup = await runScript("scripts/backup-database.mjs", ["--to", join(directory, "cron")], { DATABASE_URL: file });
    assert.equal(backup.code, 0, backup.stderr);
    const reported = JSON.parse(backup.stdout.trim().split("\n").at(-1));
    assert.equal(reported.event, "database_backup_completed");
    assert.equal(reported.integrity, "ok");
    assert.equal(reported.schemaVersion, SCHEMA_VERSION);
    assert.equal(backup.stdout.includes(directory), false, "a scheduled job's log should not carry the paths");
    const produced = join(directory, "cron", reported.file);
    assert.ok(existsSync(produced));

    const dry = await runScript("scripts/restore-database.mjs", ["--from", produced, "--database", join(directory, "restored-by-cli.db"), "--dry-run"], {});
    assert.equal(dry.code, 0, dry.stderr);
    assert.equal(JSON.parse(dry.stdout.trim()).event, "database_restore_planned");
    assert.equal(existsSync(join(directory, "restored-by-cli.db")), false);

    const applied = await runScript("scripts/restore-database.mjs", ["--from", produced, "--database", join(directory, "restored-by-cli.db")], {});
    assert.equal(applied.code, 0, applied.stderr);
    assert.equal(JSON.parse(applied.stdout.trim()).event, "database_restore_completed");
    const check = new DatabaseSync(join(directory, "restored-by-cli.db"), { readOnly: true });
    assert.equal(check.prepare("SELECT COUNT(*) AS c FROM users").get().c, 1, "the record the test planted is the record that came back");
    check.close();

    // Misuse fails with a sentence about the *variable*, not about the filesystem.
    const unconfigured = await runScript("scripts/backup-database.mjs", [], { DATABASE_URL: "" });
    assert.equal(unconfigured.code, 1);
    assert.match(unconfigured.stderr, /DATABASE_URL \(or --database\) must name/);
    const memory = await runScript("scripts/backup-database.mjs", [], { DATABASE_URL: ":memory:" });
    assert.equal(memory.code, 1);
    assert.match(memory.stderr, /in-memory database has no file to snapshot/);
    const help = await runScript("scripts/backup-database.mjs", ["--help"], {});
    assert.equal(help.code, 0);
    assert.match(help.stdout, /usage: node scripts\/backup-database\.mjs/);
  });
});

async function runScript(relative, args, env) {
  return await new Promise((done) => {
    const child = spawn(process.execPath, ["--no-warnings=ExperimentalWarning", relative, ...args], {
      cwd: BACKEND_ROOT, env: { ...process.env, ...env }, stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("exit", (code) => done({ code, stdout, stderr }));
  });
}

/* ------------------------------------------------------------------------------- 7: nothing above broke a phase */

describe("Phase 33 leaves Phases 30-32 and the checks intact", () => {
  it("referral claim, trust read and analytics read all still work through the hardened runtime", async () => {
    const service = await newService();
    const suffix = Math.random().toString(36).slice(2, 8);
    const referrer = await registerVerified(service, { email: `ref-${suffix}@example.test` });
    const referee = await registerVerified(service, { email: `new-${suffix}@example.test` });
    const issued = await call(service.baseUrl, "POST", "/marketing/referrals/code", { body: {}, headers: { Authorization: `Bearer ${referrer.body.session.accessToken}` } });
    assert.equal(issued.status, 200, JSON.stringify(issued.body));
    const claimed = await call(service.baseUrl, "POST", "/marketing/referrals/claim", {
      body: { referralCode: issued.body.code, source: "YOUTUBE", medium: " You  Tube " },
      headers: { Authorization: `Bearer ${referee.body.session.accessToken}` },
    });
    assert.equal(claimed.status, 201, JSON.stringify(claimed.body));
    assert.equal(claimed.body.status, "VERIFIED");
    assert.equal(claimed.body.medium, "you-tube");

    const analytics = await call(service.baseUrl, "GET", "/marketplace/analytics/overview?window=30d", { headers: { Authorization: `Bearer ${referrer.body.session.accessToken}` } });
    assert.equal(analytics.status, 200);
    assert.equal(analytics.body.operations.referrals.attributedInWindow, 1);
    assert.deepEqual(analytics.body.operations.referrals.bySource, { YOUTUBE: 1 });
    const trust = await call(service.baseUrl, "GET", "/marketplace/trust", { headers: { Authorization: `Bearer ${referrer.body.session.accessToken}` } });
    assert.equal(trust.status, 200, JSON.stringify(trust.body));
    const summary = await call(service.baseUrl, "GET", "/marketing/referrals/me", { headers: { Authorization: `Bearer ${referee.body.session.accessToken}` } });
    assert.equal(summary.status, 200);
    assert.equal(JSON.stringify(summary.body).includes("usr_"), false, "privacy invariants survive the runtime changes");
  });

  it("keeps the audit vocabulary, the error contract and the schema watermark where the earlier phases left them", async () => {
    const service = await newService();
    const { REGISTERED_AUDIT_ACTION_TYPES } = await import("../src/db.js");
    assert.equal(REGISTERED_AUDIT_ACTION_TYPES.size, 102, "no audit vocabulary was added or removed by hardening");
    assert.equal(SCHEMA_VERSION, 12);
    assert.equal((await call(service.baseUrl, "GET", "/health")).body.schemaVersion, 12);
    const unknown = await call(service.baseUrl, "GET", "/nope");
    assert.deepEqual(Object.keys(unknown.body), ["error"], "the top-level error shape is still exactly one key");
    assert.deepEqual(Object.keys(unknown.body.error).sort(), ["code", "message", "requestId"]);
  });

  it("keeps the runtime's own files parseable and the documented commands real", () => {
    const manifest = JSON.parse(readFileSync(join(BACKEND_ROOT, "package.json"), "utf8"));
    assert.match(manifest.scripts.test, /--test "test\/\*\.test\.js"/);
    assert.match(manifest.scripts.start, /src\/index\.js/);
    for (const file of ["src/index.js", "src/config.js", "src/server.js", "src/health.js", "src/db-maintenance.js",
      "scripts/backup-database.mjs", "scripts/restore-database.mjs"]) {
      assert.ok(existsSync(join(BACKEND_ROOT, file)), `${file} is referenced by the docs but missing`);
    }
    const operations = readFileSync(join(BACKEND_ROOT, "..", "docs", "production-operations.md"), "utf8");
    // The runtime's own vocabulary: every event name the entry point can emit must be described in the operations
    // doc, so a new lifecycle signal cannot ship undocumented.
    const emitted = [...readFileSync(join(BACKEND_ROOT, "src", "index.js"), "utf8").matchAll(/event: "([a-z_]+)"/g)].map((m) => m[1]);
    assert.ok(emitted.length >= 4, `expected the entry point to log lifecycle events, found ${emitted.length}`);
    for (const event of emitted) {
      assert.ok(operations.includes(event), `the operations doc never mentions the "${event}" log event`);
    }
    for (const claim of ["VACUUM INTO", "GET /live", "SHUTDOWN_GRACE_MS", "closeIdleConnections", "no automated backup scheduler"]) {
      assert.ok(operations.includes(claim), `the operations doc no longer states "${claim}"`);
    }
  });
});
