/**
 * Account, session, and guest-identity behaviour of the CraftMind account service.
 *
 * These tests drive the real HTTP surface against a real SQLite database, so they cover the contract the Android client
 * depends on: typed codes, real sessions, real rotation, and the deterministic guest → account link.
 */

import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { call, guestIdentity, loginCall, register, registrationBody, startService, VALID_PASSWORD } from "./helpers.js";

describe("account service", () => {
  let service;
  before(async () => {
    service = await startService();
  });
  after(async () => {
    await service.close();
  });

  it("registers an account, returns a real session, and never returns a password hash", async () => {
    const response = await register(service.baseUrl, { email: "first@example.com", displayName: "First Builder" });

    assert.equal(response.status, 201);
    const { account, session } = response.body;
    assert.match(account.userId, /^usr_[0-9a-f-]{36}$/);
    assert.equal(account.email, "first@example.com");
    assert.equal(account.displayName, "First Builder");
    assert.equal(account.status, "ACTIVE");
    assert.ok(Date.parse(account.createdAt) > 0);
    assert.equal(account.updatedAt, account.createdAt);
    assert.equal(typeof session.accessToken, "string");
    assert.equal(typeof session.refreshToken, "string");
    assert.notEqual(session.accessToken, session.refreshToken);
    assert.ok(Date.parse(session.accessExpiresAt) > Date.now());
    assert.ok(Date.parse(session.refreshExpiresAt) > Date.parse(session.accessExpiresAt));
    assert.equal(JSON.stringify(response.body).includes("scrypt"), false);
    assert.equal(JSON.stringify(response.body).includes("password"), false);
  });

  it("refuses a duplicate email address", async () => {
    await register(service.baseUrl, { email: "duplicate@example.com" });

    const second = await register(service.baseUrl, { email: "DUPLICATE@example.com " });

    assert.equal(second.status, 409);
    assert.equal(second.body.error.code, "ACCOUNT_ALREADY_EXISTS");
  });

  it("rejects an unusable email address and an unusable password with distinct codes", async () => {
    const badEmail = await register(service.baseUrl, { email: "not-an-address" });
    const shortPassword = await register(service.baseUrl, { email: "short@example.com", password: "abc" });
    const oneClass = await register(service.baseUrl, { email: "oneclass@example.com", password: "aaaaaaaaaaaa" });

    assert.equal(badEmail.body.error.code, "INVALID_EMAIL");
    assert.equal(shortPassword.body.error.code, "INVALID_PASSWORD");
    assert.equal(oneClass.body.error.code, "INVALID_PASSWORD");
  });

  it("requires a real display name and does not derive one from the email address", async () => {
    const response = await register(service.baseUrl, { email: "nameless@example.com", displayName: undefined });

    assert.equal(response.status, 400);
    assert.equal(response.body.error.code, "INVALID_DISPLAY_NAME");
    assert.equal(
      service.database.prepare("SELECT count(*) AS count FROM users WHERE email_canonical = ?").get("nameless@example.com").count,
      0,
    );
  });

  it("serializes concurrent duplicate registrations into one success and one stable duplicate error", async () => {
    const [first, second] = await Promise.all([
      register(service.baseUrl, { email: "race@example.com", displayName: "First" }),
      register(service.baseUrl, { email: " RACE@example.com ", displayName: "Second" }),
    ]);
    const responses = [first, second];

    assert.equal(responses.filter((response) => response.status === 201).length, 1);
    assert.equal(responses.filter((response) => response.status === 409).length, 1);
    assert.equal(responses.find((response) => response.status === 409).body.error.code, "ACCOUNT_ALREADY_EXISTS");
    assert.equal(
      service.database.prepare("SELECT count(*) AS count FROM users WHERE email_canonical = ?").get("race@example.com").count,
      1,
    );
  });

  it("signs in with correct credentials and refuses incorrect ones without revealing whether the address exists", async () => {
    await register(service.baseUrl, { email: "signin@example.com" });

    const good = await loginCall(service.baseUrl, { email: "signin@example.com" });
    const wrongPassword = await loginCall(service.baseUrl, { email: "signin@example.com", password: "Wrong Horse 7Battery" });
    const unknownEmail = await loginCall(service.baseUrl, { email: "nobody@example.com" });

    assert.equal(good.status, 200);
    assert.equal(good.body.account.email, "signin@example.com");
    assert.equal(wrongPassword.status, 401);
    assert.equal(wrongPassword.body.error.code, "INVALID_CREDENTIALS");
    assert.equal(unknownEmail.status, 401);
    assert.equal(unknownEmail.body.error.code, "INVALID_CREDENTIALS");
    assert.equal(wrongPassword.body.error.message, unknownEmail.body.error.message);
  });

  it("rejects a suspended account at sign-in and ends its existing sessions", async () => {
    const created = await register(service.baseUrl, { email: "suspended@example.com" });
    const accessToken = created.body.session.accessToken;
    service.database.prepare("UPDATE users SET status = 'SUSPENDED' WHERE email_canonical = ?").run("suspended@example.com");

    const signInAttempt = await loginCall(service.baseUrl, { email: "suspended@example.com" });
    const meAttempt = await call(service.baseUrl, "GET", "/auth/me", {
      headers: { Authorization: `Bearer ${accessToken}` },
    });

    assert.equal(signInAttempt.status, 403);
    assert.equal(signInAttempt.body.error.code, "ACCOUNT_SUSPENDED");
    assert.equal(meAttempt.status, 403);
    assert.equal(meAttempt.body.error.code, "ACCOUNT_SUSPENDED");
    const session = service.database
      .prepare("SELECT revoked_at FROM sessions WHERE access_digest IS NOT NULL ORDER BY issued_at DESC LIMIT 1")
      .get();
    assert.ok(session.revoked_at !== null, "a suspended account's session must be revoked");
  });

  it("never authenticates a deleted account and does not reveal deletion during password login", async () => {
    const created = await register(service.baseUrl, { email: "deleted@example.com" });
    const accessToken = created.body.session.accessToken;
    const refreshToken = created.body.session.refreshToken;
    service.database.prepare("UPDATE users SET status = 'DELETED' WHERE email_canonical = ?").run("deleted@example.com");

    const loginAttempt = await loginCall(service.baseUrl, { email: "deleted@example.com" });
    const currentAttempt = await call(service.baseUrl, "GET", "/auth/me", {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const refreshAttempt = await call(service.baseUrl, "POST", "/auth/refresh", { body: { refreshToken } });

    assert.equal(loginAttempt.status, 401);
    assert.equal(loginAttempt.body.error.code, "INVALID_CREDENTIALS");
    assert.equal(currentAttempt.status, 403);
    assert.equal(currentAttempt.body.error.code, "ACCOUNT_DELETED");
    assert.equal(refreshAttempt.status, 403);
    assert.equal(refreshAttempt.body.error.code, "ACCOUNT_DELETED");
  });

  it("returns the current account for a valid access token only", async () => {
    const created = await register(service.baseUrl, { email: "me@example.com" });
    const accessToken = created.body.session.accessToken;

    const valid = await call(service.baseUrl, "GET", "/auth/me", { headers: { Authorization: `Bearer ${accessToken}` } });
    const missing = await call(service.baseUrl, "GET", "/auth/me");
    const forged = await call(service.baseUrl, "GET", "/auth/me", { headers: { Authorization: "Bearer not-a-real-token-value" } });

    assert.equal(valid.status, 200);
    assert.equal(valid.body.account.userId, created.body.account.userId);
    assert.equal(missing.status, 401);
    assert.equal(missing.body.error.code, "AUTHENTICATION_REQUIRED");
    assert.equal(forged.status, 401);
    assert.equal(forged.body.error.code, "SESSION_INVALID");
  });

  it("expires an access token but lets the refresh token mint a new pair, rotating both", async () => {
    const created = await register(service.baseUrl, { email: "refresh@example.com" });
    const { accessToken, refreshToken } = created.body.session;
    service.database.prepare("UPDATE sessions SET access_expires_at = ?").run(new Date(Date.now() - 1000).toISOString());

    const expiredUse = await call(service.baseUrl, "GET", "/auth/me", { headers: { Authorization: `Bearer ${accessToken}` } });
    const refreshed = await call(service.baseUrl, "POST", "/auth/refresh", { body: { refreshToken } });
    const reuseOfOldRefresh = await call(service.baseUrl, "POST", "/auth/refresh", { body: { refreshToken } });
    const meWithNewToken = await call(service.baseUrl, "GET", "/auth/me", {
      headers: { Authorization: `Bearer ${refreshed.body.session.accessToken}` },
    });

    assert.equal(expiredUse.status, 401);
    assert.equal(expiredUse.body.error.code, "SESSION_EXPIRED");
    assert.equal(refreshed.status, 200);
    assert.notEqual(refreshed.body.session.refreshToken, refreshToken);
    assert.notEqual(refreshed.body.session.accessToken, accessToken);
    assert.equal(reuseOfOldRefresh.status, 401, "a rotated refresh token must not work twice");
    assert.equal(reuseOfOldRefresh.body.error.code, "REFRESH_FAILED");
    assert.equal(meWithNewToken.status, 200);
  });

  it("refuses an expired refresh token", async () => {
    const created = await register(service.baseUrl, { email: "stagerefresh@example.com" });
    const { refreshToken } = created.body.session;
    service.database.prepare("UPDATE sessions SET refresh_expires_at = ?").run(new Date(Date.now() - 1000).toISOString());

    const response = await call(service.baseUrl, "POST", "/auth/refresh", { body: { refreshToken } });

    assert.equal(response.status, 401);
    assert.equal(response.body.error.code, "SESSION_EXPIRED");
  });

  it("revokes the session on logout and answers a repeated logout idempotently", async () => {
    const created = await register(service.baseUrl, { email: "logout@example.com" });
    const { accessToken, refreshToken } = created.body.session;

    const first = await call(service.baseUrl, "POST", "/auth/logout", { body: { accessToken, refreshToken } });
    const second = await call(service.baseUrl, "POST", "/auth/logout", { body: { accessToken } });
    const afterLogout = await call(service.baseUrl, "GET", "/auth/me", { headers: { Authorization: `Bearer ${accessToken}` } });
    const refreshAfterLogout = await call(service.baseUrl, "POST", "/auth/refresh", { body: { refreshToken } });

    assert.equal(first.status, 200);
    assert.equal(first.body.revoked, true);
    assert.equal(first.body.revokedSessions, 1);
    assert.equal(second.status, 200);
    assert.equal(second.body.revoked, true, "the session is revoked, so the answer is still 'revoked'");
    assert.equal(second.body.alreadyRevoked, true);
    assert.equal(second.body.revokedSessions, 0, "a repeated sign-out revokes nothing new");
    assert.equal(afterLogout.status, 401);
    assert.equal(afterLogout.body.error.code, "SESSION_INVALID");
    assert.equal(refreshAfterLogout.status, 401);
    assert.equal(refreshAfterLogout.body.error.code, "REFRESH_FAILED");
  });

  it("keeps the same account across logout and sign-in again", async () => {
    const created = await register(service.baseUrl, { email: "roundtrip@example.com" });
    const originalUserId = created.body.account.userId;

    await call(service.baseUrl, "POST", "/auth/logout", { body: { accessToken: created.body.session.accessToken } });
    const signedInAgain = await loginCall(service.baseUrl, { email: "roundtrip@example.com" });

    assert.equal(signedInAgain.status, 200);
    assert.equal(signedInAgain.body.account.userId, originalUserId);
  });

  it("records a guest identity without treating it as a credential", async () => {
    const identity = guestIdentity("guest-record");
    const created = await call(service.baseUrl, "POST", "/auth/guest", { body: { guestIdentityId: identity } });
    const again = await call(service.baseUrl, "POST", "/auth/guest", { body: { guestIdentityId: identity } });
    const malformed = await call(service.baseUrl, "POST", "/auth/guest", { body: { guestIdentityId: "short" } });

    assert.equal(created.status, 201);
    assert.equal(created.body.guest.guestIdentityId, identity);
    assert.equal(created.body.guest.linked, false);
    assert.equal(again.status, 201);
    assert.equal(again.body.guest.createdAt, created.body.guest.createdAt);
    assert.equal(malformed.status, 400);
    assert.equal(malformed.body.error.code, "INVALID_GUEST_IDENTITY");
    assert.equal(JSON.stringify(created.body).includes("session"), false, "a guest record is not a session");
  });

  it("links a guest identity to the account created from it, exactly once", async () => {
    const identity = guestIdentity("guest-convert");
    await call(service.baseUrl, "POST", "/auth/guest", { body: { guestIdentityId: identity } });

    const created = await register(service.baseUrl, { email: "convert@example.com", guestIdentityId: identity });
    const link = service.database
      .prepare("SELECT linked_user_id FROM guest_identities WHERE guest_identity_id = ?")
      .get(identity);
    const secondAttempt = await register(service.baseUrl, {
      email: "second@example.com",
      guestIdentityId: identity,
    });

    assert.equal(created.status, 201);
    assert.equal(created.body.guestLinked, true);
    assert.equal(link.linked_user_id, created.body.account.userId);
    assert.equal(secondAttempt.status, 409);
    assert.equal(secondAttempt.body.error.code, "GUEST_IDENTITY_ALREADY_LINKED");
    assert.equal(
      service.database.prepare("SELECT COUNT(*) AS count FROM users WHERE email_canonical = ?").get("second@example.com").count,
      0,
      "a refused conversion must not create a second account",
    );
  });

  it("does not consume a guest identity when the registration itself is rejected", async () => {
    const identity = guestIdentity("guest-rejected");
    await register(service.baseUrl, { email: "taken@example.com" });

    const duplicate = await register(service.baseUrl, { email: "taken@example.com", guestIdentityId: identity });
    const later = await register(service.baseUrl, { email: "fresh@example.com", guestIdentityId: identity });

    assert.equal(duplicate.status, 409);
    assert.equal(duplicate.body.error.code, "ACCOUNT_ALREADY_EXISTS");
    assert.equal(later.status, 201);
    assert.equal(later.body.guestLinked, true);
  });

  it("links a guest identity when a guest device signs in to an existing account", async () => {
    const identity = guestIdentity("guest-signin");
    await register(service.baseUrl, { email: "existing@example.com" });

    const signedIn = await loginCall(service.baseUrl, { email: "existing@example.com", guestIdentityId: identity });
    const link = service.database
      .prepare("SELECT linked_user_id FROM guest_identities WHERE guest_identity_id = ?")
      .get(identity);

    assert.equal(signedIn.status, 200);
    assert.equal(link.linked_user_id, signedIn.body.account.userId);
  });

  it("ignores client-supplied identity claims and issues server-owned identifiers", async () => {
    const response = await call(service.baseUrl, "POST", "/auth/register", {
      body: registrationBody({
        email: "claim@example.com",
        userId: "usr_attacker",
        status: "ACTIVE",
        authenticated: true,
        createdAt: "1999-01-01T00:00:00.000Z",
      }),
    });

    assert.equal(response.status, 201);
    assert.notEqual(response.body.account.userId, "usr_attacker");
    assert.notEqual(response.body.account.createdAt, "1999-01-01T00:00:00.000Z");
  });
});
