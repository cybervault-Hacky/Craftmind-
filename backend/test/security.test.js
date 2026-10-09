/**
 * Security properties of the account service.
 *
 * These are the properties the Android side and the privacy claims depend on: passwords are hashed and never stored or
 * logged, session tokens exist in the database only as keyed digests, error payloads carry no credentials, the server
 * never trusts client-supplied identity, and the endpoints this phase did not implement say so instead of pretending.
 */

import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { call, guestIdentity, loginCall, register, registerVerified, VALID_PASSWORD, startService, TEST_SECRET } from "./helpers.js";

describe("account service security", () => {
  let service;
  before(async () => {
    service = await startService();
  });
  after(async () => {
    await service.close();
  });

  it("stores passwords only as salted scrypt hashes", async () => {
    await register(service.baseUrl, { email: "hash@example.com" });

    const row = service.database.prepare("SELECT password_hash FROM users WHERE email_canonical = ?").get("hash@example.com");

    assert.match(row.password_hash, /^scrypt\$/);
    assert.equal(row.password_hash.includes(VALID_PASSWORD), false);
    assert.equal(row.password_hash.split("$").length, 6);
    const hashOfOtherAccount = service.database
      .prepare("SELECT password_hash FROM users WHERE email_canonical = ?")
      .get("hash@example.com").password_hash;
    await register(service.baseUrl, { email: "hash2@example.com" });
    const second = service.database
      .prepare("SELECT password_hash FROM users WHERE email_canonical = ?")
      .get("hash2@example.com").password_hash;
    assert.notEqual(hashOfOtherAccount, second, "the same password must not produce the same stored hash twice");
  });

  it("stores session tokens only as keyed digests, never as the raw token", async () => {
    const created = await registerVerified(service, { email: "digest@example.com" });
    const { accessToken, refreshToken } = created.body.session;

    const row = service.database
      .prepare("SELECT access_digest, refresh_digest FROM sessions WHERE user_id = ?")
      .get(created.body.account.userId);

    assert.notEqual(row.access_digest, accessToken);
    assert.notEqual(row.refresh_digest, refreshToken);
    assert.equal(JSON.stringify([row.access_digest, row.refresh_digest]).includes(accessToken), false);
    // A digest alone must not be usable as a credential.
    const replay = await call(service.baseUrl, "GET", "/auth/me", { headers: { Authorization: `Bearer ${row.access_digest}` } });
    assert.equal(replay.status, 401);
  });

  it("derives a different digest for the same token under a different service secret", async () => {
    const created = await registerVerified(service, { email: "rotate@example.com" });
    const anotherService = await startService({ AUTH_SECRET: `${TEST_SECRET}-rotated` });
    try {
      // The same token, presented to a service with a different AUTH_SECRET, matches nothing: the digest is keyed.
      const response = await call(anotherService.baseUrl, "GET", "/auth/me", {
        headers: { Authorization: `Bearer ${created.body.session.accessToken}` },
      });
      assert.equal(response.status, 401);
    } finally {
      await anotherService.close();
    }
  });

  it("never echoes a password, a token, or a hash in an error payload", async () => {
    const password = "Echo Me 4Never";
    const wrong = await loginCall(service.baseUrl, { email: "echo@example.com", password });
    await register(service.baseUrl, { email: "echo2@example.com" });
    const created = await registerVerified(service, { email: "echo3@example.com" });
    const badRefresh = await call(service.baseUrl, "POST", "/auth/refresh", {
      body: { refreshToken: created.body.session.refreshToken, password: VALID_PASSWORD, accessToken: "should-not-be-needed" },
    });
    const malformed = await call(service.baseUrl, "POST", "/auth/login", { raw: "{not json" });

    for (const response of [wrong, badRefresh, malformed]) {
      const serialized = JSON.stringify(response.body);
      assert.equal(serialized.includes(password), false);
      assert.equal(serialized.includes(VALID_PASSWORD), false);
      assert.equal(serialized.includes("scrypt"), false);
      assert.equal(serialized.includes("stack"), false);
    }
  });

  it("never writes a credential to the service log", async () => {
    const password = "Log Never 9Seen";
    const created = await registerVerified(service, { email: "log@example.com" });
    await loginCall(service.baseUrl, { email: "log@example.com", password });
    await call(service.baseUrl, "POST", "/auth/refresh", { body: { refreshToken: created.body.session.refreshToken } });
    await call(service.baseUrl, "POST", "/auth/logout", { body: { accessToken: created.body.session.accessToken } });
    const tokenInUnknownPath = "path-secret-".repeat(5);
    await call(service.baseUrl, "GET", `/auth/${tokenInUnknownPath}`);

    const logged = service.logs.join("\n");
    assert.equal(logged.includes(password), false);
    assert.equal(logged.includes(VALID_PASSWORD), false);
    assert.equal(logged.includes(created.body.session.accessToken), false);
    assert.equal(logged.includes(created.body.session.refreshToken), false);
    assert.equal(logged.includes(tokenInUnknownPath), false, "an attacker-controlled path must not be reflected in logs");
    assert.ok(logged.includes('"route":"/auth/login"'), "the structured access log still records the route");
    assert.ok(logged.includes('"route":"/unmatched"'), "unknown paths are represented by a fixed safe label");
  });

  it("does not accept a client-asserted session or status", async () => {
    const response = await call(service.baseUrl, "POST", "/auth/login", {
      body: { authenticated: true, userId: "usr_attacker", status: "ACTIVE" },
    });
    const me = await call(service.baseUrl, "GET", "/auth/me", {
      headers: { Authorization: "Bearer usr_attacker", "X-CraftMind-Authenticated": "true" },
    });

    assert.equal(response.status, 400);
    assert.equal(response.body.error.code, "INVALID_REQUEST");
    assert.equal(me.status, 401);
  });

  it("bounds the request body", async () => {
    const oversized = await call(service.baseUrl, "POST", "/auth/register", {
      raw: JSON.stringify({ email: "big@example.com", password: "x".repeat(64 * 1024) }),
    });

    assert.equal(oversized.status, 413);
    assert.equal(oversized.body.error.code, "REQUEST_TOO_LARGE");
  });

  it("answers unknown endpoints and wrong methods with typed codes", async () => {
    const unknown = await call(service.baseUrl, "GET", "/auth/nothing-here");
    const wrongMethod = await call(service.baseUrl, "GET", "/auth/login");

    assert.equal(unknown.status, 400);
    assert.equal(unknown.body.error.code, "INVALID_REQUEST");
    assert.equal(wrongMethod.status, 405);
    assert.equal(wrongMethod.body.error.code, "METHOD_NOT_ALLOWED");
  });

  it("keeps the legacy password-reset request route enumeration-safe while using the recovery flow", async () => {
    const response = await call(service.baseUrl, "POST", "/auth/password-reset", { body: { email: "anyone@example.com" } });

    assert.equal(response.status, 202);
    assert.equal(response.body.accepted, true);
    assert.equal(JSON.stringify(response.body).includes("anyone@example.com"), false);
    assert.equal(JSON.stringify(response.body).includes("token"), false);
  });

  it("changes nothing for an unauthenticated caller and creates no account it was not asked to create", async () => {
    const before = service.database.prepare("SELECT COUNT(*) AS count FROM users").get().count;
    await call(service.baseUrl, "GET", "/auth/me");
    await call(service.baseUrl, "POST", "/auth/refresh", { body: { refreshToken: "not-a-token-value-at-all" } });
    await loginCall(service.baseUrl, { email: "ghost@example.com" });
    const after = service.database.prepare("SELECT COUNT(*) AS count FROM users").get().count;

    assert.equal(after, before, "a failed authentication must never create an account");
  });

  it("keeps guest records free of device identifiers and personal data", async () => {
    const identity = guestIdentity("guest-privacy");
    await call(service.baseUrl, "POST", "/auth/guest", { body: { guestIdentityId: identity } });

    const columns = service.database
      .prepare("SELECT name FROM pragma_table_info('guest_identities')")
      .all()
      .map((row) => row.name)
      .sort();

    assert.deepEqual(columns, ["created_at", "guest_identity_id", "last_seen_at", "linked_at", "linked_user_id"]);
  });

  it("contains no table for a feature this phase did not implement", async () => {
    const tables = service.database
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table'")
      .all()
      .map((row) => row.name)
      .filter((name) => !name.startsWith("sqlite_"))
      .sort();

    assert.deepEqual(tables, [
      "admin_audit_log",
      "buyer_jobs",
      "buyer_onboarding",
      "creator_profiles",
      "creator_status_history",
      "credit_ledger",
      "credit_operation_keys",
      "developer_access_grants",
      "developer_accounts",
      "developer_action_confirmations",
      "developer_bootstrap_state",
      "developer_sessions",
      "email_verification_tokens",
      "guest_identities",
      "job_proposals",
      "marketplace_listings",
      "membership_accounts",
      "membership_transitions",
      "password_recovery_tokens",
      "schema_migrations",
      "security_actions",
      "security_events",
      "security_incidents",
      "security_notifications",
      "security_rate_limit_state",
      "seller_onboarding",
      "server_members",
      "server_workspaces",
      "sessions",
      "users",
    ]);
    // Phase 22 added an internal membership and build-credit ledger, so `credit` is no longer a forbidden substring —
    // the ban exists to stop a *payment* system appearing without its phase. Phase 25 added `marketplace_listings`
    // by name (listings and discovery only: no checkout, no price, no settlement), so `marketplace` is no longer a
    // forbidden substring either. Those payment words remain forbidden, and the credit tables are internal
    // allocations with no purchase, price, or settlement behind them.
    for (const forbidden of ["subscription", "payment", "gift", "ban", "invoice", "price", "payout"]) {
      assert.equal(
        tables.some((name) => name.includes(forbidden)),
        false,
        `the schema must not contain a ${forbidden} table in this phase`,
      );
    }
  });

  it("requires a valid configuration and refuses to start without a real secret", async () => {
    const { loadConfiguration, ConfigurationError } = await import("../src/config.js");
    assert.throws(
      () => loadConfiguration({ DATABASE_URL: ":memory:", AUTH_SECRET: "too-short" }, { allowInMemoryDatabase: true }),
      ConfigurationError,
    );
    assert.throws(() => loadConfiguration({ AUTH_SECRET: TEST_SECRET }, { allowInMemoryDatabase: true }), ConfigurationError);
    assert.throws(
      () => loadConfiguration({ DATABASE_URL: ":memory:", AUTH_SECRET: TEST_SECRET }),
      ConfigurationError,
      "an in-memory database must be refused outside tests",
    );
  });
});
