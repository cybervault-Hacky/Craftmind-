/** Phase 18 regression tests over the actual HTTP listener and SQLite persistence. */

import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, it } from "node:test";
import { DatabaseSync } from "node:sqlite";
import { loadConfiguration, ConfigurationError } from "../src/config.js";
import { openDatabase, SCHEMA_VERSION } from "../src/db.js";
import { createAccountService } from "../src/server.js";
import { oneTimeTokenDigest, tokenDigest } from "../src/ids.js";
import { cleanupExpiredAccountRecords, refreshSession } from "../src/accounts.js";
import { InMemoryRateLimiter } from "../src/rate-limiter.js";
import { call, guestIdentity, loginCall, register, registerVerified, startService, TEST_SECRET, VALID_PASSWORD } from "./helpers.js";

const services = new Set();
async function newService(overrides = {}, options = {}) {
  const service = await startService(overrides, options);
  services.add(service);
  const close = service.close.bind(service);
  service.close = async () => { services.delete(service); await close(); };
  return service;
}
afterEach(async () => { await Promise.all([...services].map((service) => service.close())); });

async function requestRecovery(service, email) {
  return call(service.baseUrl, "POST", "/auth/password-reset/request", { body: { email } });
}

async function requestVerification(service, email) {
  return call(service.baseUrl, "POST", "/auth/resend-verification", { body: { email } });
}

describe("Phase 18 account security and recovery", () => {
  it("rotates resend tokens, invalidates the previous token, and keeps resend responses non-enumerating", async () => {
    const service = await newService();
    const first = await register(service.baseUrl, { email: "verify@example.com" });
    const firstMessage = service.emailDelivery.takeMessage("verification", "verify@example.com");
    assert.equal(first.status, 201);

    const resend = await requestVerification(service, "verify@example.com");
    const unknown = await requestVerification(service, "missing@example.com");
    assert.equal(resend.status, 202);
    assert.equal(unknown.status, 202);
    assert.deepEqual(resend.body, unknown.body, "resend does not reveal whether the address has an account");
    assert.equal(resend.body.deliveryMode, "DEVELOPMENT_SINK");
    assert.equal(JSON.stringify(resend.body).includes(firstMessage.token), false);

    const secondMessage = service.emailDelivery.takeMessage("verification", "verify@example.com");
    assert.ok(secondMessage);
    assert.notEqual(secondMessage.token, firstMessage.token);
    const old = await call(service.baseUrl, "POST", "/auth/verify-email", { body: { token: firstMessage.token } });
    const verified = await call(service.baseUrl, "POST", "/auth/verify-email", { body: { token: secondMessage.token } });
    assert.equal(old.body.error.code, "EMAIL_VERIFICATION_TOKEN_USED");
    assert.equal(verified.status, 200);
    assert.equal(verified.body.account.emailVerified, true);
  });

  it("rejects invalid and expired verification tokens and stores only a purpose-bound digest", async () => {
    const service = await newService();
    await register(service.baseUrl, { email: "expiry@example.com" });
    const message = service.emailDelivery.takeMessage("verification", "expiry@example.com");
    const digest = oneTimeTokenDigest(TEST_SECRET, "email-verification-v1", message.token);
    const row = service.database.prepare(`SELECT token_digest FROM email_verification_tokens WHERE user_id = (
      SELECT user_id FROM users WHERE email_canonical = ?
    )`).get("expiry@example.com");
    assert.equal(row.token_digest, digest);
    assert.notEqual(row.token_digest, message.token);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM email_verification_tokens WHERE token_digest = ?").get(message.token).count, 0);

    const invalid = await call(service.baseUrl, "POST", "/auth/verify-email", { body: { token: "x".repeat(43) } });
    assert.equal(invalid.body.error.code, "EMAIL_VERIFICATION_TOKEN_INVALID");
    service.database.prepare("UPDATE email_verification_tokens SET expires_at = ? WHERE token_digest = ?")
      .run(new Date(Date.now() - 1000).toISOString(), digest);
    const expired = await call(service.baseUrl, "POST", "/auth/verify-email", { body: { token: message.token } });
    assert.equal(expired.status, 410);
    assert.equal(expired.body.error.code, "EMAIL_VERIFICATION_TOKEN_EXPIRED");
  });

  it("does not deliver or claim email in development, and exposes that local sink state honestly", async () => {
    const service = await newService();
    const created = await register(service.baseUrl, { email: "dev-sink@example.com" });
    assert.equal(created.body.deliveryStatus, "DEVELOPMENT_SINK");
    assert.equal(created.body.verificationRequired, true);
    assert.equal(service.emailDelivery.pendingCount, 1);
    assert.equal(service.logs.some((line) => line.includes("dev-sink@example.com")), false);
  });

  it("reports provider acceptance without claiming delivery and keeps provider failures enumeration-neutral", async () => {
    const provider = {
      mode: "PROVIDER_CONFIGURED",
      messages: [],
      async sendVerification(message) { this.messages.push(message); },
      async sendPasswordRecovery(message) { throw new Error(`private provider failure ${message.email} ${message.token}`); },
    };
    const service = await newService({
      EMAIL_PROVIDER: "webhook",
      EMAIL_WEBHOOK_URL: "https://mailer.example.test/send",
      EMAIL_WEBHOOK_TOKEN: "provider-token-".repeat(3),
      EMAIL_FROM: "accounts@example.test",
    }, { emailDelivery: provider });

    const created = await register(service.baseUrl, { email: "provider@example.test" });
    assert.equal(created.body.deliveryStatus, "PROVIDER_ACCEPTED");
    assert.equal(JSON.stringify(created.body).toLowerCase().includes("delivered"), false);
    const challenge = provider.messages[0];
    assert.ok(challenge);
    const verified = await call(service.baseUrl, "POST", "/auth/verify-email", { body: { token: challenge.token } });
    assert.equal(verified.status, 200);

    const known = await requestRecovery(service, "provider@example.test");
    const unknown = await requestRecovery(service, "missing-provider@example.test");
    assert.equal(known.status, 202);
    assert.deepEqual(known.body, unknown.body);
    assert.equal(known.body.deliveryMode, "PROVIDER_CONFIGURED");
    assert.equal(known.body.message.includes("neither confirms"), true);
    const logs = service.logs.join("\\n");
    assert.equal(logs.includes("provider@example.test"), false);
    assert.equal(logs.includes(challenge.token), false);
    assert.equal(logs.includes("private provider failure"), false);
  });

  it("uses neutral password-recovery responses, single-use tokens, and revokes every session after reset", async () => {
    const service = await newService();
    const account = await registerVerified(service, { email: "recovery@example.com" });
    const existingToken = account.body.session.accessToken;

    const known = await requestRecovery(service, "recovery@example.com");
    const unknown = await requestRecovery(service, "not-registered@example.com");
    assert.equal(known.status, 202);
    assert.equal(unknown.status, 202);
    assert.deepEqual(known.body, unknown.body);
    assert.equal(known.body.accepted, true);
    assert.equal(JSON.stringify(known.body).includes("recovery@example.com"), false);
    const mail = service.emailDelivery.takeMessage("password-recovery", "recovery@example.com");
    assert.ok(mail);

    const digest = oneTimeTokenDigest(TEST_SECRET, "password-recovery-v1", mail.token);
    const row = service.database.prepare("SELECT token_digest FROM password_recovery_tokens WHERE token_digest = ?").get(digest);
    assert.ok(row);
    assert.notEqual(row.token_digest, mail.token);
    const reset = await call(service.baseUrl, "POST", "/auth/password-reset/confirm", {
      body: { token: mail.token, newPassword: "New Correct 8Password" },
    });
    assert.equal(reset.status, 200);
    assert.equal(reset.body.reset, true);
    assert.equal(reset.body.revokedSessions, 1);
    const replay = await call(service.baseUrl, "POST", "/auth/password-reset/confirm", {
      body: { token: mail.token, newPassword: "New Correct 8Password" },
    });
    assert.equal(replay.body.error.code, "PASSWORD_RESET_TOKEN_USED");
    const oldSession = await call(service.baseUrl, "GET", "/auth/me", { headers: { Authorization: `Bearer ${existingToken}` } });
    assert.equal(oldSession.body.error.code, "SESSION_INVALID");
    assert.equal((await loginCall(service.baseUrl, { email: "recovery@example.com", password: VALID_PASSWORD })).body.error.code, "INVALID_CREDENTIALS");
    assert.equal((await loginCall(service.baseUrl, { email: "recovery@example.com", password: "New Correct 8Password" })).status, 200);
  });

  it("rejects invalid and expired password-recovery tokens without changing the password", async () => {
    const service = await newService();
    await registerVerified(service, { email: "recovery-expiry@example.com" });
    const invalid = await call(service.baseUrl, "POST", "/auth/password-reset/confirm", {
      body: { token: "z".repeat(43), newPassword: "New Correct 8Password" },
    });
    assert.equal(invalid.body.error.code, "PASSWORD_RESET_TOKEN_INVALID");
    await requestRecovery(service, "recovery-expiry@example.com");
    const mail = service.emailDelivery.takeMessage("password-recovery", "recovery-expiry@example.com");
    const digest = oneTimeTokenDigest(TEST_SECRET, "password-recovery-v1", mail.token);
    service.database.prepare("UPDATE password_recovery_tokens SET expires_at = ? WHERE token_digest = ?")
      .run(new Date(Date.now() - 1000).toISOString(), digest);
    const expired = await call(service.baseUrl, "POST", "/auth/password-reset/confirm", {
      body: { token: mail.token, newPassword: "New Correct 8Password" },
    });
    assert.equal(expired.status, 410);
    assert.equal(expired.body.error.code, "PASSWORD_RESET_TOKEN_EXPIRED");
    assert.equal((await loginCall(service.baseUrl, { email: "recovery-expiry@example.com" })).status, 200, "an expired recovery code must not alter the current password");
  });

  it("changes a password only after current-password proof and retains only the current session", async () => {
    const service = await newService();
    const first = await registerVerified(service, { email: "change@example.com", deviceLabel: "CraftMind Android" });
    const second = await loginCall(service.baseUrl, { email: "change@example.com", deviceLabel: "Tablet" });
    const url = "/auth/password/change";
    const wrong = await call(service.baseUrl, "POST", url, {
      headers: { Authorization: `Bearer ${first.body.session.accessToken}` },
      body: { currentPassword: "Wrong Horse 7Battery", newPassword: "Changed Pass 8Secure" },
    });
    assert.equal(wrong.body.error.code, "CURRENT_PASSWORD_INVALID");

    const changed = await call(service.baseUrl, "POST", url, {
      headers: { Authorization: `Bearer ${first.body.session.accessToken}` },
      body: { currentPassword: VALID_PASSWORD, newPassword: "Changed Pass 8Secure" },
    });
    assert.equal(changed.status, 200);
    assert.equal(changed.body.currentSessionRetained, true);
    assert.equal(changed.body.revokedOtherSessions, 1);
    assert.equal((await call(service.baseUrl, "GET", "/auth/me", {
      headers: { Authorization: `Bearer ${first.body.session.accessToken}` },
    })).status, 200);
    const otherSession = await call(service.baseUrl, "GET", "/auth/me", {
      headers: { Authorization: `Bearer ${second.body.session.accessToken}` },
    });
    assert.equal(otherSession.body.error.code, "SESSION_INVALID");
    assert.equal((await loginCall(service.baseUrl, { email: "change@example.com" })).body.error.code, "INVALID_CREDENTIALS");
    assert.equal((await loginCall(service.baseUrl, { email: "change@example.com", password: "Changed Pass 8Secure" })).status, 200);
  });

  it("rejects a refresh token if another service instance rotates it after lookup", async () => {
    const service = await newService();
    const created = await registerVerified(service, { email: "refresh-race@example.com" });
    const refreshToken = created.body.session.refreshToken;
    let interleaved = false;
    const databaseWithCompetingRotation = {
      prepare(sql) {
        const statement = service.database.prepare(sql);
        if (sql === "SELECT * FROM sessions WHERE refresh_digest = ?") {
          return {
            get(...parameters) {
              const row = statement.get(...parameters);
              if (row && !interleaved) {
                interleaved = true;
                service.database.prepare("UPDATE sessions SET refresh_digest = ? WHERE session_id = ?")
                  .run(tokenDigest(TEST_SECRET, "competing-rotation-refresh-secret"), row.session_id);
              }
              return row;
            },
          };
        }
        return statement;
      },
    };

    assert.throws(
      () => refreshSession(databaseWithCompetingRotation, service.configuration, { refreshToken }),
      (error) => error.code === "REFRESH_FAILED",
      "the compare-and-rotate predicate must reject a token another instance already consumed",
    );
    const replay = await call(service.baseUrl, "POST", "/auth/refresh", { body: { refreshToken } });
    assert.equal(replay.body.error.code, "REFRESH_FAILED");
  });

  it("lists only safe session metadata and supports revoke-one/revoke-all-other without exposing the current secret", async () => {
    const service = await newService();
    const current = await registerVerified(service, { email: "sessions@example.com", deviceLabel: "CraftMind Android" });
    const other = await loginCall(service.baseUrl, { email: "sessions@example.com", deviceLabel: "Home tablet" });
    const auth = { Authorization: `Bearer ${current.body.session.accessToken}` };
    const listed = await call(service.baseUrl, "GET", "/auth/sessions", { headers: auth });
    assert.equal(listed.status, 200);
    assert.equal(listed.body.sessions.length, 2);
    const own = listed.body.sessions.find((session) => session.isCurrent);
    const remote = listed.body.sessions.find((session) => !session.isCurrent);
    assert.equal(own.deviceLabel, "CraftMind Android");
    assert.equal(remote.deviceLabel, "Home tablet");
    const safeJson = JSON.stringify(listed.body);
    for (const secret of [current.body.session.accessToken, current.body.session.refreshToken, other.body.session.accessToken, other.body.session.refreshToken]) {
      assert.equal(safeJson.includes(secret), false);
    }
    assert.equal(safeJson.includes("access_digest"), false);
    assert.equal(safeJson.includes("refresh_digest"), false);
    assert.equal(safeJson.includes(VALID_PASSWORD), false);
    for (const session of listed.body.sessions) {
      assert.ok(session.sessionId);
      assert.ok(session.createdAt);
      assert.ok(session.lastUsedAt);
      assert.ok(session.expiresAt);
      assert.equal(Object.hasOwn(session, "token"), false);
    }

    const revokeCurrent = await call(service.baseUrl, "POST", "/auth/sessions/revoke", { headers: auth, body: { sessionId: own.sessionId } });
    assert.equal(revokeCurrent.body.error.code, "CURRENT_SESSION_REVOKE_NOT_ALLOWED");
    const revoked = await call(service.baseUrl, "POST", "/auth/sessions/revoke", { headers: auth, body: { sessionId: remote.sessionId } });
    assert.equal(revoked.body.revoked, true);
    assert.equal((await call(service.baseUrl, "GET", "/auth/me", { headers: { Authorization: `Bearer ${other.body.session.accessToken}` } })).body.error.code, "SESSION_INVALID");
    const third = await loginCall(service.baseUrl, { email: "sessions@example.com", deviceLabel: "Spare tablet" });
    const revokeAll = await call(service.baseUrl, "POST", "/auth/sessions/revoke-all", { headers: auth });
    assert.equal(revokeAll.body.currentSessionRetained, true);
    assert.equal(revokeAll.body.revokedSessions, 1);
    assert.equal((await call(service.baseUrl, "GET", "/auth/me", { headers: auth })).status, 200);
    assert.equal((await call(service.baseUrl, "GET", "/auth/me", { headers: { Authorization: `Bearer ${third.body.session.accessToken}` } })).body.error.code, "SESSION_INVALID");
  });

  it("cleans expired session and one-time-token records while retaining recent rows for typed expiry responses", async () => {
    const service = await newService();
    await register(service.baseUrl, { email: "cleanup-unverified@example.com" });
    const verified = await registerVerified(service, { email: "cleanup-session@example.com" });
    const now = Date.now();
    const olderThanRetention = new Date(now - 31 * 24 * 60 * 60 * 1000).toISOString();
    const justExpired = new Date(now - 1000).toISOString();
    service.database.prepare(`UPDATE email_verification_tokens SET expires_at = ? WHERE user_id = (
      SELECT user_id FROM users WHERE email_canonical = ?
    )`).run(olderThanRetention, "cleanup-unverified@example.com");
    service.database.prepare("UPDATE sessions SET refresh_expires_at = ? WHERE session_id = ?")
      .run(justExpired, verified.body.session.sessionId);

    const cleaned = cleanupExpiredAccountRecords(service.database, now);
    assert.equal(cleaned.sessions, 1);
    assert.equal(cleaned.verificationTokens, 1);
    assert.equal(cleaned.recoveryTokens, 0);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM sessions WHERE session_id = ?")
      .get(verified.body.session.sessionId).count, 0);
  });

  it("rate-limits login attempts and responds with a typed retry delay", async () => {
    const service = await newService({ RATE_LOGIN_MAX: "2", RATE_LOGIN_WINDOW_MS: "60000" });
    await registerVerified(service, { email: "rate@example.com" }); // first login attempt in this fixed window
    const allowed = await loginCall(service.baseUrl, { email: "rate@example.com" });
    const limited = await loginCall(service.baseUrl, { email: "rate@example.com" });
    assert.equal(allowed.status, 200);
    assert.equal(limited.status, 429);
    assert.equal(limited.body.error.code, "RATE_LIMITED");
    assert.equal(Number(limited.headers.get("retry-after")) > 0, true);
    assert.equal(JSON.stringify(limited.body).includes("rate@example.com"), false);
  });

  it("keeps active rate-limit buckets when the bounded in-memory capacity is full", () => {
    let now = 0;
    const limiter = new InMemoryRateLimiter({ authSecret: TEST_SECRET, now: () => now, maximumBuckets: 1 });
    const limits = { maximum: 1, windowMs: 1000 };
    limiter.consume("login", ["203.0.113.1", "person@example.test"], limits);
    assert.throws(
      () => limiter.consume("login", ["203.0.113.1", "another@example.test"], limits),
      (error) => error.code === "RATE_LIMITED" && error.retryAfterSeconds === 1,
    );
    assert.throws(
      () => limiter.consume("login", ["203.0.113.1", "person@example.test"], limits),
      (error) => error.code === "RATE_LIMITED",
      "pressure from new subjects must not evict a still-active subject bucket",
    );
    now = 1000;
    limiter.consume("login", ["203.0.113.1", "another@example.test"], limits);
  });

  it("sets no-store/security headers, rejects non-JSON bodies, and applies exact-origin CORS only", async () => {
    const service = await newService({ CORS_ALLOWED_ORIGINS: "https://craftmind.example" });
    const health = await call(service.baseUrl, "GET", "/health");
    assert.equal(health.headers.get("cache-control"), "no-store, max-age=0");
    assert.equal(health.headers.get("x-content-type-options"), "nosniff");
    assert.equal(health.headers.get("content-security-policy").includes("default-src 'none'"), true);
    const blocked = await call(service.baseUrl, "GET", "/health", { headers: { Origin: "https://evil.example" } });
    assert.equal(blocked.body.error.code, "CORS_ORIGIN_NOT_ALLOWED");
    const allowed = await call(service.baseUrl, "GET", "/health", { headers: { Origin: "https://craftmind.example" } });
    assert.equal(allowed.headers.get("access-control-allow-origin"), "https://craftmind.example");
    const wrongType = await call(service.baseUrl, "POST", "/auth/login", {
      raw: "{}",
      headers: { "Content-Type": "text/plain" },
    });
    assert.equal(wrongType.status, 415);
    assert.equal(wrongType.body.error.code, "INVALID_CONTENT_TYPE");
  });

  it("requires production webhook mail, TLS termination, persistent storage, and non-wildcard HTTPS origins", () => {
    assert.throws(() => loadConfiguration({
      NODE_ENV: "production",
      DATABASE_URL: "/var/lib/craftmind/accounts.sqlite",
      AUTH_SECRET: TEST_SECRET,
      TRUST_PROXY_TLS: "true",
      PUBLIC_ORIGIN: "https://accounts.craftmind.example",
      EMAIL_PROVIDER: "memory",
    }), ConfigurationError);
    assert.throws(() => loadConfiguration({
      NODE_ENV: "production",
      DATABASE_URL: ":memory:",
      AUTH_SECRET: TEST_SECRET,
      TRUST_PROXY_TLS: "true",
      PUBLIC_ORIGIN: "https://accounts.craftmind.example",
      EMAIL_PROVIDER: "webhook",
      EMAIL_WEBHOOK_URL: "http://mailer.example/send",
      EMAIL_WEBHOOK_TOKEN: "x".repeat(32),
      EMAIL_FROM: "accounts@craftmind.example",
    }, { allowInMemoryDatabase: true }), ConfigurationError);
    const configuration = loadConfiguration({
      NODE_ENV: "production",
      DATABASE_URL: "/var/lib/craftmind/accounts.sqlite",
      AUTH_SECRET: TEST_SECRET,
      TRUST_PROXY_TLS: "true",
      PUBLIC_ORIGIN: "https://accounts.craftmind.example",
      EMAIL_PROVIDER: "webhook",
      EMAIL_WEBHOOK_URL: "https://mailer.example/send",
      EMAIL_WEBHOOK_TOKEN: "x".repeat(32),
      EMAIL_FROM: "accounts@craftmind.example",
      CORS_ALLOWED_ORIGINS: "https://app.craftmind.example",
    });
    assert.equal(configuration.production, true);
    assert.equal(configuration.emailProvider, "webhook");
    assert.equal(configuration.corsAllowedOrigins[0], "https://app.craftmind.example");
  });

  it("migrates a Phase 17 database in place without dropping account/session/guest rows and is idempotent", () => {
    const directory = mkdtempSync(join(tmpdir(), "craftmind-auth-migration-"));
    const filename = join(directory, "old.sqlite");
    const old = new DatabaseSync(filename);
    old.exec(`
      CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL);
      INSERT INTO schema_migrations VALUES (1, '2026-01-01T00:00:00.000Z');
      CREATE TABLE users (user_id TEXT PRIMARY KEY, email TEXT NOT NULL, email_canonical TEXT NOT NULL UNIQUE,
        display_name TEXT NOT NULL, password_hash TEXT NOT NULL, status TEXT NOT NULL,
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
      CREATE TABLE guest_identities (guest_identity_id TEXT PRIMARY KEY, created_at TEXT NOT NULL,
        last_seen_at TEXT NOT NULL, linked_user_id TEXT, linked_at TEXT);
      CREATE TABLE sessions (session_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, access_digest TEXT NOT NULL,
        refresh_digest TEXT NOT NULL, issued_at TEXT NOT NULL, access_expires_at TEXT NOT NULL,
        refresh_expires_at TEXT NOT NULL, revoked_at TEXT, guest_identity_id TEXT);
      INSERT INTO users VALUES ('usr_existing', 'legacy@example.com', 'legacy@example.com', 'Legacy', 'scrypt$hash', 'ACTIVE', '2026-01-01T00:00:00.000Z', '2026-01-01T00:00:00.000Z');
      INSERT INTO guest_identities VALUES ('guest_existing_0123456789012', '2026-01-01T00:00:00.000Z', '2026-01-01T00:00:00.000Z', 'usr_existing', '2026-01-01T00:00:00.000Z');
      INSERT INTO sessions VALUES ('ses_existing', 'usr_existing', 'access-digest', 'refresh-digest', '2026-01-01T00:00:00.000Z', '2027-01-01T00:00:00.000Z', '2027-02-01T00:00:00.000Z', NULL, 'guest_existing_0123456789012');
    `);
    old.close();
    try {
      const migrated = openDatabase(filename);
      // Phase 22 added migration v5 (membership, entitlements, credit ledger), Phase 23 added v6 (creator identity
      // and server workspaces), and Phase 25 added v8 (marketplace listings) on top of this Phase 17 database.
      // Every one is additive.
      assert.equal(SCHEMA_VERSION, 8);
      assert.equal(migrated.prepare("SELECT email FROM users WHERE user_id = ?").get("usr_existing").email, "legacy@example.com");
      assert.equal(migrated.prepare("SELECT last_used_at, device_label FROM sessions WHERE session_id = ?").get("ses_existing").last_used_at, "2026-01-01T00:00:00.000Z");
      assert.equal(migrated.prepare("SELECT device_label FROM sessions WHERE session_id = ?").get("ses_existing").device_label, "Unknown device");
      assert.equal(migrated.prepare("SELECT email_verified_at FROM users WHERE user_id = ?").get("usr_existing").email_verified_at, null);
      migrated.close();
      const reopened = openDatabase(filename);
      assert.equal(reopened.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 8);
      assert.equal(reopened.prepare("SELECT COUNT(*) AS count FROM sessions").get().count, 1);
      reopened.close();
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
});

// Keep the deterministic test environment's guest identifier shape aligned with the app contract.
assert.match(guestIdentity("phase18"), /^[A-Za-z0-9_-]{22,64}$/);
