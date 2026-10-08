/** Phase 19 developer identity, control-plane tool, audit, confirmation, and AI-boundary tests. */

import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, it } from "node:test";
import { hashPassword } from "../src/passwords.js";
import { newDeveloperId } from "../src/ids.js";
import { ConfigurationError, loadConfiguration } from "../src/config.js";
import { call, registerVerified, startService, TEST_SECRET, VALID_PASSWORD } from "./helpers.js";

const services = new Set();
const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x63).toString("base64url");
const DEVELOPER_EMAIL = "owner@example.test";
const DEVELOPER_PASSWORD = "Control Plane 9Safe";

async function newService(overrides = {}, options = {}) {
  const service = await startService(overrides, options);
  services.add(service);
  const close = service.close.bind(service);
  service.close = async () => { services.delete(service); await close(); };
  return service;
}

afterEach(async () => { await Promise.all([...services].map((service) => service.close())); });

async function bootstrapOwner(service, { secret = BOOTSTRAP_SECRET, password = DEVELOPER_PASSWORD } = {}) {
  return call(service.baseUrl, "POST", "/developer/auth/bootstrap", { body: { secret, password } });
}

async function loginDeveloper(service, { email = DEVELOPER_EMAIL, password = DEVELOPER_PASSWORD, extra = {} } = {}) {
  return call(service.baseUrl, "POST", "/developer/auth/login", { body: { email, password, ...extra } });
}

async function createOwnerSession(service) {
  const bootstrap = await bootstrapOwner(service);
  assert.equal(bootstrap.status, 201, JSON.stringify(bootstrap.body));
  const login = await loginDeveloper(service);
  assert.equal(login.status, 200, JSON.stringify(login.body));
  return { bootstrap, login, ...login.body.session };
}

function developerHeaders(accessToken) {
  return { Authorization: `Bearer ${accessToken}` };
}

async function invoke(service, accessToken, tool, args = {}) {
  return call(service.baseUrl, "POST", "/developer/tools/invoke", {
    headers: developerHeaders(accessToken), body: { tool, arguments: args },
  });
}

async function confirm(service, accessToken, confirmationToken) {
  return call(service.baseUrl, "POST", "/developer/tools/confirm", {
    headers: developerHeaders(accessToken), body: { confirmationToken },
  });
}

describe("Phase 19 developer identity and bootstrap", () => {
  it("fails bootstrap closed unless a paired environment email and high-entropy secret are configured", async () => {
    assert.throws(() => loadConfiguration({
      NODE_ENV: "test", DATABASE_URL: ":memory:", AUTH_SECRET: TEST_SECRET,
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
    }, { allowInMemoryDatabase: true }), ConfigurationError);
    assert.throws(() => loadConfiguration({
      NODE_ENV: "test", DATABASE_URL: ":memory:", AUTH_SECRET: TEST_SECRET,
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: "not-an-email", CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    }, { allowInMemoryDatabase: true }), ConfigurationError);
    assert.throws(() => loadConfiguration({
      NODE_ENV: "test", DATABASE_URL: ":memory:", AUTH_SECRET: TEST_SECRET,
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL, CRAFTMIND_DEV_BOOTSTRAP_SECRET: "too-short",
    }, { allowInMemoryDatabase: true }), ConfigurationError);

    const service = await newService();
    const response = await bootstrapOwner(service);
    assert.equal(response.status, 503);
    assert.equal(response.body.error.code, "DEVELOPER_BOOTSTRAP_DISABLED");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_accounts").get().count, 0);
  });

  it("creates only the configured initial owner, hashes its password, and permanently consumes bootstrap", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    const wrongSecret = await bootstrapOwner(service, { secret: `${BOOTSTRAP_SECRET}wrong` });
    assert.equal(wrongSecret.status, 401);
    assert.equal(wrongSecret.body.error.code, "DEVELOPER_BOOTSTRAP_INVALID");
    const badPassword = await bootstrapOwner(service, { password: "short" });
    assert.equal(badPassword.status, 400);
    assert.equal(badPassword.body.error.code, "INVALID_PASSWORD");

    const response = await bootstrapOwner(service);
    assert.equal(response.status, 201, JSON.stringify(response.body));
    assert.deepEqual(response.body.developer, {
      email: DEVELOPER_EMAIL,
      role: "OWNER",
      status: "ACTIVE",
      createdAt: response.body.developer.createdAt,
      lastLoginAt: null,
    });
    assert.equal(response.body.bootstrapConsumed, true);
    assert.equal(JSON.stringify(response.body).includes(BOOTSTRAP_SECRET), false);
    const row = service.database.prepare("SELECT * FROM developer_accounts WHERE email_canonical = ?").get(DEVELOPER_EMAIL);
    assert.match(row.password_hash, /^scrypt\$/);
    assert.equal(row.password_hash.includes(DEVELOPER_PASSWORD), false);
    assert.equal(row.role, "OWNER");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_bootstrap_state").get().count, 1);
    assert.equal(service.logs.join("\n").includes(BOOTSTRAP_SECRET), false);
    assert.equal(service.logs.join("\n").includes(DEVELOPER_PASSWORD), false);

    const replay = await bootstrapOwner(service);
    assert.equal(replay.status, 409);
    assert.equal(replay.body.error.code, "DEVELOPER_BOOTSTRAP_CONSUMED");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_accounts").get().count, 1);
    const invalidExtras = await call(service.baseUrl, "POST", "/developer/auth/bootstrap", {
      body: { secret: BOOTSTRAP_SECRET, password: DEVELOPER_PASSWORD, email: "attacker@example.test", role: "OWNER" },
    });
    assert.equal(invalidExtras.status, 400);
  });

  it("serializes concurrent valid bootstrap attempts to exactly one initial owner", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    const responses = await Promise.all([bootstrapOwner(service), bootstrapOwner(service)]);
    assert.deepEqual(responses.map((response) => response.status).sort(), [201, 409]);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_accounts").get().count, 1);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_bootstrap_state").get().count, 1);
  });

  it("allows normal password login after the bootstrap secret is removed from the environment", async () => {
    const directory = mkdtempSync(join(tmpdir(), "craftmind-phase19-"));
    const databasePath = join(directory, "account.sqlite");
    let first;
    let restarted;
    try {
      first = await newService({
        DATABASE_URL: databasePath,
        CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
        CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
      });
      await bootstrapOwner(first);
      await first.close();
      first = null;

      restarted = await newService({ DATABASE_URL: databasePath });
      assert.equal(restarted.configuration.developerBootstrapEmail, null);
      assert.equal(restarted.configuration.developerBootstrapSecret, null);
      const login = await loginDeveloper(restarted);
      assert.equal(login.status, 200);
      assert.equal(login.body.developer.role, "OWNER");
      assert.equal(typeof login.body.session.accessToken, "string");
      assert.equal(typeof login.body.session.refreshToken, "string");
    } finally {
      if (first) await first.close();
      if (restarted) await restarted.close();
      rmSync(directory, { recursive: true, force: true });
    }
  });

  it("rejects client-selected developer identities, client roles, and public developer registration", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    await createOwnerSession(service);
    const forgedLogin = await loginDeveloper(service, { extra: { role: "OWNER", developerId: "forged" } });
    const registration = await call(service.baseUrl, "POST", "/developer/auth/register", {
      body: { email: "attacker@example.test", password: DEVELOPER_PASSWORD, role: "OWNER" },
    });
    assert.equal(forgedLogin.status, 400);
    assert.equal(registration.status, 400);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_accounts").get().count, 1);
  });

  it("uses a distinct developer login flow and returns the same credential error for unknown email or wrong password", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    await bootstrapOwner(service);
    const unknown = await loginDeveloper(service, { email: "missing@example.test" });
    const wrong = await loginDeveloper(service, { password: "Incorrect Password 8" });
    assert.equal(unknown.status, 401);
    assert.equal(wrong.status, 401);
    assert.equal(unknown.body.error.code, "DEVELOPER_INVALID_CREDENTIALS");
    assert.equal(wrong.body.error.code, "DEVELOPER_INVALID_CREDENTIALS");
    const malformed = await loginDeveloper(service, { extra: { role: "OWNER" } });
    assert.equal(malformed.status, 400);
    assert.equal(malformed.body.error.code, "INVALID_REQUEST");
  });
});

describe("Phase 19 developer sessions and separation from user auth", () => {
  it("stores only keyed token digests, rotates refresh credentials once, lists safe metadata, revokes sessions, and audits logout", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    await bootstrapOwner(service);
    const first = await loginDeveloper(service);
    const second = await loginDeveloper(service);
    const firstAccess = first.body.session.accessToken;
    const firstRefresh = first.body.session.refreshToken;
    const secondAccess = second.body.session.accessToken;
    const listing = await call(service.baseUrl, "GET", "/developer/auth/sessions", { headers: developerHeaders(firstAccess) });
    assert.equal(listing.status, 200);
    assert.equal(listing.body.sessions.length, 2);
    assert.equal(listing.body.sessions.filter((session) => session.isCurrent).length, 1);
    assert.deepEqual(Object.keys(listing.body.sessions[0]).sort(), ["createdAt", "deviceLabel", "expiresAt", "isCurrent", "lastUsedAt", "sessionId"]);

    const firstSessionId = listing.body.sessions.find((session) => session.isCurrent).sessionId;
    const storage = service.database.prepare("SELECT access_digest, refresh_digest FROM developer_sessions WHERE session_id = ?").get(firstSessionId);
    assert.notEqual(storage.access_digest, firstAccess);
    assert.notEqual(storage.refresh_digest, firstRefresh);
    assert.equal(JSON.stringify(storage).includes(firstAccess), false);
    assert.equal(JSON.stringify(storage).includes(firstRefresh), false);

    const rotated = await call(service.baseUrl, "POST", "/developer/auth/refresh", { body: { refreshToken: firstRefresh } });
    assert.equal(rotated.status, 200);
    const oldAccess = await call(service.baseUrl, "GET", "/developer/auth/me", { headers: developerHeaders(firstAccess) });
    const replay = await call(service.baseUrl, "POST", "/developer/auth/refresh", { body: { refreshToken: firstRefresh } });
    assert.equal(oldAccess.status, 401);
    assert.equal(replay.status, 401);
    assert.equal(replay.body.error.code, "DEVELOPER_REFRESH_FAILED");
    const current = await call(service.baseUrl, "GET", "/developer/auth/me", {
      headers: developerHeaders(rotated.body.session.accessToken),
    });
    assert.equal(current.status, 200);
    assert.equal(current.body.developer.email, DEVELOPER_EMAIL);

    const afterRotation = await call(service.baseUrl, "GET", "/developer/auth/sessions", { headers: developerHeaders(secondAccess) });
    const rotatedSessionId = afterRotation.body.sessions.find((session) => !session.isCurrent).sessionId;
    const revoked = await call(service.baseUrl, "POST", "/developer/auth/sessions/revoke", {
      headers: developerHeaders(secondAccess), body: { sessionId: rotatedSessionId },
    });
    assert.equal(revoked.status, 200);
    assert.equal(revoked.body.revoked, true);
    const revokedSession = await call(service.baseUrl, "GET", "/developer/auth/me", {
      headers: developerHeaders(rotated.body.session.accessToken),
    });
    assert.equal(revokedSession.status, 401);

    const logout = await call(service.baseUrl, "POST", "/developer/auth/logout", {
      headers: developerHeaders(secondAccess), body: {},
    });
    assert.equal(logout.status, 200);
    assert.equal(logout.body.signedOut, true);
    const afterLogout = await call(service.baseUrl, "GET", "/developer/auth/me", { headers: developerHeaders(secondAccess) });
    assert.equal(afterLogout.status, 401);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log WHERE action_type = 'developer_session_revoke'").get().count, 2);
    const logText = service.logs.join("\n");
    for (const secret of [BOOTSTRAP_SECRET, DEVELOPER_PASSWORD, firstAccess, firstRefresh, secondAccess]) {
      assert.equal(logText.includes(secret), false);
    }
  });

  it("enforces developer access and refresh expiry independently", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    const owner = await createOwnerSession(service);
    service.database.prepare("UPDATE developer_sessions SET access_expires_at = ?").run(new Date(Date.now() - 1000).toISOString());
    const expiredAccess = await call(service.baseUrl, "GET", "/developer/auth/me", { headers: developerHeaders(owner.accessToken) });
    assert.equal(expiredAccess.status, 401);
    assert.equal(expiredAccess.body.error.code, "DEVELOPER_SESSION_EXPIRED");
    const refreshed = await call(service.baseUrl, "POST", "/developer/auth/refresh", { body: { refreshToken: owner.refreshToken } });
    assert.equal(refreshed.status, 200);
    service.database.prepare("UPDATE developer_sessions SET refresh_expires_at = ?").run(new Date(Date.now() - 1000).toISOString());
    const expiredRefresh = await call(service.baseUrl, "POST", "/developer/auth/refresh", {
      body: { refreshToken: refreshed.body.session.refreshToken },
    });
    assert.equal(expiredRefresh.status, 401);
    assert.equal(expiredRefresh.body.error.code, "DEVELOPER_REFRESH_FAILED");
  });

  it("rejects concurrent reuse of a developer refresh credential", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    await bootstrapOwner(service);
    const login = await loginDeveloper(service);
    const results = await Promise.all([
      call(service.baseUrl, "POST", "/developer/auth/refresh", { body: { refreshToken: login.body.session.refreshToken } }),
      call(service.baseUrl, "POST", "/developer/auth/refresh", { body: { refreshToken: login.body.session.refreshToken } }),
    ]);
    assert.deepEqual(results.map((result) => result.status).sort(), [200, 401]);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_sessions").get().count, 1);
  });

  it("never treats a normal user session or caller-supplied admin header as developer authority", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    await createOwnerSession(service);
    const user = await registerVerified(service, { email: DEVELOPER_EMAIL });
    const me = await call(service.baseUrl, "GET", "/developer/auth/me", {
      headers: { ...developerHeaders(user.body.session.accessToken), "X-Admin": "true", "X-Developer-Role": "OWNER" },
    });
    const tool = await call(service.baseUrl, "POST", "/developer/tools/invoke", {
      headers: { ...developerHeaders(user.body.session.accessToken), "X-Admin": "true" },
      body: { tool: "overview", arguments: {} },
    });
    const crossRefresh = await call(service.baseUrl, "POST", "/developer/auth/refresh", {
      body: { refreshToken: user.body.session.refreshToken },
    });
    const aiStatus = await call(service.baseUrl, "GET", "/developer/ai/status", {
      headers: developerHeaders(user.body.session.accessToken),
    });
    assert.equal(me.status, 401);
    assert.equal(me.body.error.code, "DEVELOPER_AUTHENTICATION_REQUIRED");
    assert.equal(tool.status, 401);
    assert.equal(tool.body.error.code, "DEVELOPER_AUTHENTICATION_REQUIRED");
    assert.equal(crossRefresh.status, 401);
    assert.equal(crossRefresh.body.error.code, "DEVELOPER_REFRESH_FAILED");
    assert.equal(aiStatus.status, 401);
    assert.equal(aiStatus.body.error.code, "DEVELOPER_AUTHENTICATION_REQUIRED");
  });

  it("applies distinct rate limits to developer login attempts", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
      RATE_DEV_LOGIN_MAX: "1",
    });
    await bootstrapOwner(service);
    const first = await loginDeveloper(service, { password: "Wrong Password 2" });
    const second = await loginDeveloper(service, { password: "Wrong Password 2" });
    assert.equal(first.status, 401);
    assert.equal(second.status, 429);
    assert.equal(second.body.error.code, "RATE_LIMITED");
  });
});

describe("Phase 19 explicit audited admin tools", () => {
  it("serves the separate no-store dashboard and safe developer configuration metadata", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
      CRAFTMIND_DEVELOPER_AI_PROVIDER: "test-provider",
      CRAFTMIND_DEVELOPER_AI_MODEL: "test-model",
      CRAFTMIND_DEVELOPER_AI_API_KEY: "provider-key-that-must-never-be-returned",
    });
    const html = await call(service.baseUrl, "GET", "/developer");
    const sameOrigin = await call(service.baseUrl, "GET", "/developer", { headers: { Origin: service.baseUrl } });
    const script = await call(service.baseUrl, "GET", "/developer/developer.js");
    assert.equal(html.status, 200);
    assert.equal(sameOrigin.status, 200);
    assert.equal(sameOrigin.headers.get("access-control-allow-origin"), null);
    assert.match(html.headers.get("content-security-policy"), /script-src 'self'/);
    assert.equal(html.headers.get("cache-control"), "no-store, max-age=0");
    assert.match(html.body.unparsed, /First-owner setup/);
    assert.match(script.body.unparsed, /developer\/auth\/login/);
    assert.equal(script.body.unparsed.includes("provider-key-that-must-never-be-returned"), false);

    const owner = await createOwnerSession(service);
    const unavailableStatus = await call(service.baseUrl, "GET", "/developer/ai/status", { headers: developerHeaders(owner.accessToken) });
    assert.deepEqual(unavailableStatus.body, { available: false });
    const configuration = await invoke(service, owner.accessToken, "configurationStatus");
    assert.equal(configuration.status, 200);
    assert.deepEqual(configuration.body.result.developerAi, {
      environmentConfigured: true, provider: "test-provider", model: "test-model", adapterAvailable: false,
    });
    assert.equal(JSON.stringify(configuration.body).includes("provider-key-that-must-never-be-returned"), false);
    assert.equal(JSON.stringify(configuration.body).includes(BOOTSTRAP_SECRET), false);
  });

  it("restricts lookup/session tools to bounded safe fields and never exposes credentials", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    const owner = await createOwnerSession(service);
    const user = await registerVerified(service, { email: "lookup@example.test" });
    const inspected = await invoke(service, owner.accessToken, "inspectUser", { email: "lookup@example.test" });
    assert.equal(inspected.status, 200);
    assert.deepEqual(Object.keys(inspected.body.result.user).sort(), ["createdAt", "displayName", "email", "emailVerified", "emailVerifiedAt", "status", "updatedAt"]);
    assert.equal(JSON.stringify(inspected.body).includes("password_hash"), false);
    assert.equal(JSON.stringify(inspected.body).includes("scrypt$"), false);
    assert.equal(JSON.stringify(inspected.body).includes(VALID_PASSWORD), false);

    const sessions = await invoke(service, owner.accessToken, "listUserSessions", { email: "lookup@example.test" });
    assert.equal(sessions.status, 200);
    assert.equal(sessions.body.result.sessions.length, 1);
    assert.deepEqual(Object.keys(sessions.body.result.sessions[0]).sort(), ["createdAt", "deviceLabel", "expiresAt", "lastUsedAt"]);
    assert.equal(JSON.stringify(sessions.body).includes(user.body.session.accessToken), false);
    assert.equal(JSON.stringify(sessions.body).includes(user.body.session.refreshToken), false);
    const readBack = await invoke(service, owner.accessToken, "inspectUser", { email: "missing@example.test" });
    assert.equal(readBack.status, 404);
    assert.equal(readBack.body.error.code, "ACCOUNT_NOT_FOUND");
  });

  it("requires server confirmation for suspension, revokes user sessions, records failures, and safely restores", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    const owner = await createOwnerSession(service);
    const user = await registerVerified(service, { email: "suspend@example.test" });
    const prepared = await invoke(service, owner.accessToken, "suspendUser", { email: "suspend@example.test" });
    assert.equal(prepared.status, 200);
    const challenge = prepared.body.result;
    assert.equal(challenge.confirmationRequired, true);
    assert.equal(challenge.action, "suspendUser");
    assert.equal(service.database.prepare("SELECT status FROM users WHERE email_canonical = ?").get("suspend@example.test").status, "ACTIVE");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_action_confirmations WHERE confirmation_digest = ?").get(challenge.confirmationToken).count, 0);
    const tokenDigest = service.database.prepare("SELECT confirmation_digest FROM developer_action_confirmations").get().confirmation_digest;
    assert.notEqual(tokenDigest, challenge.confirmationToken);

    const beforeConfirmation = await call(service.baseUrl, "GET", "/auth/me", { headers: developerHeaders(user.body.session.accessToken) });
    assert.equal(beforeConfirmation.status, 200);
    const applied = await confirm(service, owner.accessToken, challenge.confirmationToken);
    assert.equal(applied.status, 200, JSON.stringify(applied.body));
    assert.equal(applied.body.result.status, "SUSPENDED");
    assert.equal(applied.body.result.revokedSessions, 1);
    assert.equal(service.database.prepare("SELECT status FROM users WHERE email_canonical = ?").get("suspend@example.test").status, "SUSPENDED");
    const revokedUserSession = await call(service.baseUrl, "GET", "/auth/me", { headers: developerHeaders(user.body.session.accessToken) });
    const blockedLogin = await call(service.baseUrl, "POST", "/auth/login", {
      body: { email: "suspend@example.test", password: VALID_PASSWORD },
    });
    assert.equal(revokedUserSession.status, 401);
    assert.equal(blockedLogin.status, 403);
    assert.equal(blockedLogin.body.error.code, "ACCOUNT_SUSPENDED");

    const replay = await confirm(service, owner.accessToken, challenge.confirmationToken);
    assert.equal(replay.status, 400);
    assert.equal(replay.body.error.code, "DEVELOPER_CONFIRMATION_INVALID");
    const restorePrepared = await invoke(service, owner.accessToken, "restoreUser", { email: "suspend@example.test" });
    assert.equal(restorePrepared.body.result.confirmationRequired, true);
    const restored = await confirm(service, owner.accessToken, restorePrepared.body.result.confirmationToken);
    assert.equal(restored.status, 200);
    assert.equal(restored.body.result.status, "ACTIVE");
    const oldSessionAfterRestore = await call(service.baseUrl, "GET", "/auth/me", { headers: developerHeaders(user.body.session.accessToken) });
    const newLogin = await call(service.baseUrl, "POST", "/auth/login", {
      body: { email: "suspend@example.test", password: VALID_PASSWORD },
    });
    assert.equal(oldSessionAfterRestore.status, 401, "restore must never revive revoked sessions");
    assert.equal(newLogin.status, 200);

    const audits = service.database.prepare("SELECT action_type, outcome, metadata_json FROM admin_audit_log ORDER BY occurred_at").all();
    assert.ok(audits.some((row) => row.action_type === "suspend_user" && row.outcome === "PREPARED"));
    assert.ok(audits.some((row) => row.action_type === "suspend_user" && row.outcome === "SUCCESS"));
    assert.ok(audits.some((row) => row.action_type === "action_confirmation" && row.outcome === "FAILURE"));
    assert.equal(JSON.stringify(audits).includes(challenge.confirmationToken), false);
    assert.equal(JSON.stringify(audits).includes(VALID_PASSWORD), false);
  });

  it("records cancellations and expired confirmations, and never executes either action", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    const owner = await createOwnerSession(service);
    const user = await registerVerified(service, { email: "cancel-target@example.test" });
    const cancelPrepared = await invoke(service, owner.accessToken, "revokeUserSessions", { email: "cancel-target@example.test" });
    const cancelled = await call(service.baseUrl, "POST", "/developer/tools/cancel", {
      headers: developerHeaders(owner.accessToken), body: { confirmationToken: cancelPrepared.body.result.confirmationToken },
    });
    assert.equal(cancelled.status, 200);
    assert.equal(cancelled.body.result.cancelled, true);
    const stillActive = await call(service.baseUrl, "GET", "/auth/me", { headers: developerHeaders(user.body.session.accessToken) });
    assert.equal(stillActive.status, 200);
    const cancelledReplay = await confirm(service, owner.accessToken, cancelPrepared.body.result.confirmationToken);
    assert.equal(cancelledReplay.body.error.code, "DEVELOPER_CONFIRMATION_INVALID");

    const expiredPrepared = await invoke(service, owner.accessToken, "suspendUser", { email: "cancel-target@example.test" });
    service.database.prepare(`UPDATE developer_action_confirmations SET expires_at = ? WHERE developer_id = (
      SELECT developer_id FROM developer_accounts WHERE email_canonical = ?
    )`).run(new Date(Date.now() - 1000).toISOString(), DEVELOPER_EMAIL);
    const expired = await confirm(service, owner.accessToken, expiredPrepared.body.result.confirmationToken);
    assert.equal(expired.status, 410);
    assert.equal(expired.body.error.code, "DEVELOPER_CONFIRMATION_EXPIRED");
    assert.equal(service.database.prepare("SELECT status FROM users WHERE email_canonical = ?").get("cancel-target@example.test").status, "ACTIVE");
    const expiredReplay = await confirm(service, owner.accessToken, expiredPrepared.body.result.confirmationToken);
    assert.equal(expiredReplay.body.error.code, "DEVELOPER_CONFIRMATION_INVALID");
    assert.ok(service.database.prepare("SELECT 1 FROM admin_audit_log WHERE action_type = 'revoke_user_sessions' AND outcome = 'CANCELLED'").get());
    assert.ok(service.database.prepare("SELECT 1 FROM admin_audit_log WHERE action_type = 'suspend_user' AND outcome = 'FAILURE'").get());
  });

  it("rechecks the developer role at confirmation time and consumes a challenge on denial", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    const owner = await createOwnerSession(service);
    await registerVerified(service, { email: "role-confirm-target@example.test" });
    const prepared = await invoke(service, owner.accessToken, "suspendUser", { email: "role-confirm-target@example.test" });
    service.database.prepare("UPDATE developer_accounts SET role = 'DEVELOPER' WHERE email_canonical = ?").run(DEVELOPER_EMAIL);
    const denied = await confirm(service, owner.accessToken, prepared.body.result.confirmationToken);
    assert.equal(denied.status, 403);
    assert.equal(denied.body.error.code, "DEVELOPER_ACCESS_DENIED");
    assert.equal(service.database.prepare("SELECT status FROM users WHERE email_canonical = ?").get("role-confirm-target@example.test").status, "ACTIVE");
    const replay = await confirm(service, owner.accessToken, prepared.body.result.confirmationToken);
    assert.equal(replay.body.error.code, "DEVELOPER_CONFIRMATION_INVALID");
    assert.ok(service.database.prepare("SELECT 1 FROM admin_audit_log WHERE action_type = 'suspend_user' AND outcome = 'DENIED'").get());
  });

  it("audits session revocation, enforces role checks, and rejects generic tools or forged schemas", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    const owner = await createOwnerSession(service);
    const user = await registerVerified(service, { email: "role-target@example.test" });
    const unknown = await invoke(service, owner.accessToken, "executeSql", { sql: "select * from users" });
    assert.equal(unknown.status, 400);
    assert.equal(unknown.body.error.code, "DEVELOPER_TOOL_UNKNOWN");
    const forgedInput = await invoke(service, owner.accessToken, "inspectUser", { email: "role-target@example.test", role: "OWNER" });
    assert.equal(forgedInput.status, 400);
    assert.equal(forgedInput.body.error.code, "DEVELOPER_TOOL_INPUT_INVALID");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM users").get().count, 1);

    const developerId = newDeveloperId();
    const timestamp = new Date().toISOString();
    const hash = await hashPassword(DEVELOPER_PASSWORD);
    service.database.prepare(
      `INSERT INTO developer_accounts (developer_id, email, email_canonical, password_hash, role, status, created_at, updated_at, last_login_at)
       VALUES (?, ?, ?, ?, 'DEVELOPER', 'ACTIVE', ?, ?, NULL)`,
    ).run(developerId, "reader@example.test", "reader@example.test", hash, timestamp, timestamp);
    const reader = await loginDeveloper(service, { email: "reader@example.test" });
    assert.equal(reader.status, 200);
    const denied = await invoke(service, reader.body.session.accessToken, "suspendUser", { email: "role-target@example.test" });
    assert.equal(denied.status, 403);
    assert.equal(denied.body.error.code, "DEVELOPER_ACCESS_DENIED");
    assert.equal(service.database.prepare("SELECT status FROM users WHERE email_canonical = ?").get("role-target@example.test").status, "ACTIVE");
    assert.ok(service.database.prepare("SELECT 1 FROM admin_audit_log WHERE actor_developer_id = ? AND action_type = 'suspend_user' AND outcome = 'DENIED'").get(developerId));

    const ownerGrant = await invoke(service, owner.accessToken, "grantEntitlement", {
      email: "role-target@example.test", entitlementKey: "CREDITS", expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
    });
    assert.equal(ownerGrant.status, 400);
    assert.equal(ownerGrant.body.error.code, "DEVELOPER_TOOL_INPUT_INVALID");
    const invalidExpiry = await invoke(service, owner.accessToken, "grantEntitlement", {
      email: "role-target@example.test", entitlementKey: "BETA_ACCESS", expiresAt: "2027-02-30T12:00:00.000Z",
    });
    assert.equal(invalidExpiry.status, 400);
    assert.equal(invalidExpiry.body.error.code, "DEVELOPER_TOOL_INPUT_INVALID");
    const currentAudit = service.database.prepare("SELECT audit_id, outcome FROM admin_audit_log ORDER BY occurred_at DESC, audit_id DESC LIMIT 1").get();
    assert.ok(currentAudit.audit_id);
    assert.throws(() => service.database.prepare("UPDATE admin_audit_log SET outcome = 'SUCCESS' WHERE audit_id = ?").run(currentAudit.audit_id), /append-only/);
    assert.throws(() => service.database.prepare("DELETE FROM admin_audit_log WHERE audit_id = ?").run(currentAudit.audit_id), /append-only/);
    assert.throws(() => service.database.prepare("INSERT OR REPLACE INTO admin_audit_log SELECT * FROM admin_audit_log WHERE audit_id = ?").run(currentAudit.audit_id), /append-only|replaced/);
    assert.equal(user.status, 201);
  });

  it("grants independent, bounded preview metadata only after confirmation and revokes it with an audit trail", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    });
    const owner = await createOwnerSession(service);
    const user = await registerVerified(service, { email: "grant-target@example.test" });
    const expiresAt = new Date(Date.now() + 3 * 24 * 60 * 60 * 1000).toISOString();
    const prepare = await invoke(service, owner.accessToken, "grantEntitlement", {
      email: "grant-target@example.test", entitlementKey: "BETA_ACCESS", expiresAt,
    });
    assert.equal(prepare.status, 200);
    assert.equal(prepare.body.result.confirmationRequired, true);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_access_grants").get().count, 0);
    const grant = await confirm(service, owner.accessToken, prepare.body.result.confirmationToken);
    assert.equal(grant.status, 200);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_access_grants").get().count, 1);
    const read = await invoke(service, owner.accessToken, "listEntitlements", { email: "grant-target@example.test" });
    assert.equal(read.body.result.grants[0].active, true);
    const normalAccount = await call(service.baseUrl, "GET", "/auth/me", { headers: developerHeaders(user.body.session.accessToken) });
    assert.equal(Object.hasOwn(normalAccount.body.account, "grants"), false);
    assert.equal(Object.hasOwn(normalAccount.body.account, "entitlements"), false);

    const revokePrepare = await invoke(service, owner.accessToken, "revokeEntitlement", { grantId: grant.body.result.grantId });
    assert.equal(revokePrepare.body.result.confirmationRequired, true);
    const revoked = await confirm(service, owner.accessToken, revokePrepare.body.result.confirmationToken);
    assert.equal(revoked.status, 200);
    const row = service.database.prepare("SELECT revoked_at, revoked_by FROM developer_access_grants WHERE grant_id = ?").get(grant.body.result.grantId);
    assert.ok(row.revoked_at);
    assert.ok(row.revoked_by);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log WHERE action_type IN ('grant_entitlement','revoke_entitlement') AND outcome IN ('PREPARED','SUCCESS')").get().count, 4);
  });
});

describe("Phase 19 provider-neutral Developer AI boundary", () => {
  it("does not claim provider integration when no adapter exists and exposes no provider key", async () => {
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
      CRAFTMIND_DEVELOPER_AI_PROVIDER: "configured-but-unavailable",
      CRAFTMIND_DEVELOPER_AI_MODEL: "unconnected-model",
      CRAFTMIND_DEVELOPER_AI_API_KEY: "provider-key-that-must-never-be-returned",
    });
    const owner = await createOwnerSession(service);
    const status = await invoke(service, owner.accessToken, "configurationStatus");
    assert.equal(status.body.result.developerAi.environmentConfigured, true);
    assert.equal(status.body.result.developerAi.adapterAvailable, false);
    const response = await call(service.baseUrl, "POST", "/developer/ai/turn", {
      headers: developerHeaders(owner.accessToken), body: { prompt: "inspect the service" },
    });
    assert.equal(response.status, 503);
    assert.equal(response.body.error.code, "DEVELOPER_AI_UNAVAILABLE");
    assert.equal(JSON.stringify(status.body).includes("provider-key-that-must-never-be-returned"), false);
    assert.equal(service.logs.join("\n").includes("provider-key-that-must-never-be-returned"), false);
  });

  it("passes a bounded prompt and registered schemas to a fake provider, then requires separate confirmation", async () => {
    let capturedRequest = null;
    const fakeProvider = {
      async selectToolCall(request) {
        capturedRequest = request;
        return {
          message: "I can prepare that suspension for review.",
          toolCall: { name: "suspendUser", arguments: { email: "ai-target@example.test" } },
        };
      },
    };
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
      CRAFTMIND_DEVELOPER_AI_PROVIDER: "fake-provider",
      CRAFTMIND_DEVELOPER_AI_MODEL: "deterministic-test",
      CRAFTMIND_DEVELOPER_AI_API_KEY: "never-exposed-test-api-key-value",
    }, { developerAiProvider: fakeProvider });
    const owner = await createOwnerSession(service);
    const aiStatus = await call(service.baseUrl, "GET", "/developer/ai/status", { headers: developerHeaders(owner.accessToken) });
    assert.deepEqual(aiStatus.body, { available: true });
    const target = await registerVerified(service, { email: "ai-target@example.test" });
    const ai = await call(service.baseUrl, "POST", "/developer/ai/turn", {
      headers: developerHeaders(owner.accessToken), body: { prompt: "Please suspend ai-target@example.test" },
    });
    assert.equal(ai.status, 200, JSON.stringify(ai.body));
    assert.equal(ai.body.tool, "suspendUser");
    assert.equal(ai.body.result.confirmationRequired, true);
    assert.equal(capturedRequest.prompt, "Please suspend ai-target@example.test");
    assert.equal(capturedRequest.maximumToolCalls, 1);
    assert.ok(capturedRequest.tools.some((tool) => tool.name === "suspendUser" && tool.requiresConfirmation));
    assert.equal(capturedRequest.tools.some((tool) => tool.name === "executeSql"), false);
    assert.equal(Object.hasOwn(capturedRequest, "database"), false);
    assert.equal(JSON.stringify(capturedRequest).includes("never-exposed-test-api-key-value"), false);
    assert.equal(JSON.stringify(capturedRequest).includes(target.body.session.accessToken), false);
    assert.equal(service.database.prepare("SELECT status FROM users WHERE email_canonical = ?").get("ai-target@example.test").status, "ACTIVE");

    const completed = await confirm(service, owner.accessToken, ai.body.result.confirmationToken);
    assert.equal(completed.status, 200);
    assert.equal(service.database.prepare("SELECT status FROM users WHERE email_canonical = ?").get("ai-target@example.test").status, "SUSPENDED");
    const aiAudit = service.database.prepare("SELECT outcome, metadata_json FROM admin_audit_log WHERE action_type = 'developer_ai_turn'").all();
    assert.ok(aiAudit.some((row) => row.outcome === "SUCCESS" && row.metadata_json.includes("suspendUser")));
    assert.equal(JSON.stringify(aiAudit).includes("Please suspend ai-target@example.test"), false);
    assert.equal(JSON.stringify(aiAudit).includes(ai.body.result.confirmationToken), false);
    assert.equal(service.logs.join("\n").includes(ai.body.result.confirmationToken), false);
  });

  it("rechecks the live developer session after a delayed provider response before preparing a mutation", async () => {
    let announceProviderStart;
    let resolveProvider;
    const providerStarted = new Promise((resolve) => { announceProviderStart = resolve; });
    const providerResponse = new Promise((resolve) => { resolveProvider = resolve; });
    const fakeProvider = {
      selectToolCall() {
        announceProviderStart();
        return providerResponse;
      },
    };
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    }, { developerAiProvider: fakeProvider });
    await bootstrapOwner(service);
    const first = await loginDeveloper(service);
    const second = await loginDeveloper(service);
    await registerVerified(service, { email: "revoked-ai-target@example.test" });
    const pending = call(service.baseUrl, "POST", "/developer/ai/turn", {
      headers: developerHeaders(first.body.session.accessToken), body: { prompt: "suspend this account" },
    });
    await providerStarted;
    const sessions = await call(service.baseUrl, "GET", "/developer/auth/sessions", {
      headers: developerHeaders(second.body.session.accessToken),
    });
    const targetSessionId = sessions.body.sessions.find((session) => !session.isCurrent).sessionId;
    const revoke = await call(service.baseUrl, "POST", "/developer/auth/sessions/revoke", {
      headers: developerHeaders(second.body.session.accessToken), body: { sessionId: targetSessionId },
    });
    assert.equal(revoke.status, 200);
    resolveProvider({
      message: "Preparing only if the session remains authorized.",
      toolCall: { name: "suspendUser", arguments: { email: "revoked-ai-target@example.test" } },
    });
    const response = await pending;
    assert.equal(response.status, 401);
    assert.equal(response.body.error.code, "DEVELOPER_AUTHENTICATION_REQUIRED");
    assert.equal(service.database.prepare("SELECT status FROM users WHERE email_canonical = ?").get("revoked-ai-target@example.test").status, "ACTIVE");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_action_confirmations").get().count, 0);
    assert.ok(service.database.prepare("SELECT 1 FROM admin_audit_log WHERE action_type = 'suspend_user' AND outcome = 'FAILURE'").get());
  });

  it("rejects unknown, malformed, and failed provider proposals without granting model-controlled authority", async () => {
    let responseValue = { message: "try generic SQL", toolCall: { name: "executeSql", arguments: { sql: "DELETE FROM users" } } };
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    }, { developerAiProvider: { selectToolCall: async () => responseValue } });
    const owner = await createOwnerSession(service);
    const unknown = await call(service.baseUrl, "POST", "/developer/ai/turn", {
      headers: developerHeaders(owner.accessToken), body: { prompt: "do anything" },
    });
    assert.equal(unknown.status, 400);
    assert.equal(unknown.body.error.code, "DEVELOPER_TOOL_UNKNOWN");
    responseValue = { message: "unexpected", toolCall: { name: "inspectUser", arguments: { email: "x@example.test", role: "OWNER" } } };
    const malformed = await call(service.baseUrl, "POST", "/developer/ai/turn", {
      headers: developerHeaders(owner.accessToken), body: { prompt: "inspect" },
    });
    assert.equal(malformed.status, 400);
    assert.equal(malformed.body.error.code, "DEVELOPER_TOOL_INPUT_INVALID");
    responseValue = { message: { text: "not plain text" } };
    const invalidResponse = await call(service.baseUrl, "POST", "/developer/ai/turn", {
      headers: developerHeaders(owner.accessToken), body: { prompt: "inspect" },
    });
    assert.equal(invalidResponse.status, 502);
    assert.equal(invalidResponse.body.error.code, "DEVELOPER_AI_RESPONSE_INVALID");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM users").get().count, 0);
    assert.ok(service.database.prepare("SELECT 1 FROM admin_audit_log WHERE action_type = 'unknown_tool' AND outcome = 'FAILURE'").get());
    assert.ok(service.database.prepare("SELECT 1 FROM admin_audit_log WHERE action_type = 'developer_ai_turn' AND outcome = 'FAILURE'").get());
  });

  it("keeps provider exception content out of responses and logs", async () => {
    const providerSecret = "provider-exception-secret-do-not-log";
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    }, { developerAiProvider: { async selectToolCall() { throw new Error(providerSecret); } } });
    const owner = await createOwnerSession(service);
    const response = await call(service.baseUrl, "POST", "/developer/ai/turn", {
      headers: developerHeaders(owner.accessToken), body: { prompt: "inspect" },
    });
    assert.equal(response.status, 503);
    assert.equal(JSON.stringify(response.body).includes(providerSecret), false);
    assert.equal(service.logs.join("\n").includes(providerSecret), false);
  });
});
