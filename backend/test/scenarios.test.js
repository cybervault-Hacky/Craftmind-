/**
 * The four data-clear and reinstall scenarios, stated as scenarios (Phase 17 §8).
 *
 * These overlap deliberately with the contract tests in `accounts.test.js`: that file proves each endpoint behaves,
 * this file proves the four journeys a user actually takes — including the two that involve losing device state — come
 * out the way the app promises they will. No test here is a copy of another; each one asserts the outcome of a journey,
 * not the shape of a response.
 */

import assert from "node:assert/strict";
import test from "node:test";

import { call, guestIdentity, loginCall, register, startService, VALID_PASSWORD } from "./helpers.js";

test("scenario A — register, sign out, sign in again: the same account", async () => {
  const service = await startService();
  try {
    const created = await register(service.baseUrl, { email: "scenario-a@example.com" });
    assert.equal(created.status, 201);
    const accountId = created.body.account.userId;

    const signedOut = await call(service.baseUrl, "POST", "/auth/logout", {
      body: { refreshToken: created.body.session.refreshToken },
    });
    assert.equal(signedOut.status, 200);
    assert.equal(signedOut.body.revoked, true);

    const signedIn = await loginCall(service.baseUrl, { email: "scenario-a@example.com" });
    assert.equal(signedIn.status, 200);
    assert.equal(signedIn.body.account.userId, accountId, "signing back in must reach the account that was created");
    assert.notEqual(
      signedIn.body.session.refreshToken,
      created.body.session.refreshToken,
      "a new session is a new session, not a replay of the revoked one",
    );
  } finally {
    await service.close();
  }
});

test("scenario B — sign in, close the app, reopen: the session is restored while it is valid", async () => {
  const service = await startService();
  try {
    const created = await register(service.baseUrl, { email: "scenario-b@example.com" });
    const { accessToken, refreshToken } = created.body.session;

    // Reopening the app is the device asking the service who it is with the credential it stored — nothing else.
    const restored = await call(service.baseUrl, "GET", "/auth/me", {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    assert.equal(restored.status, 200);
    assert.equal(restored.body.account.userId, created.body.account.userId);

    // And after a long absence, the same journey still works because the refresh token can mint a fresh pair.
    const refreshed = await call(service.baseUrl, "POST", "/auth/refresh", { body: { refreshToken } });
    assert.equal(refreshed.status, 200);
    const reopened = await call(service.baseUrl, "GET", "/auth/me", {
      headers: { Authorization: `Bearer ${refreshed.body.session.accessToken}` },
    });
    assert.equal(reopened.status, 200);
    assert.equal(reopened.body.account.userId, created.body.account.userId);
  } finally {
    await service.close();
  }
});

test("scenario C — sign in, clear app data, reinstall, sign in again: the same server account", async () => {
  const service = await startService();
  try {
    const first = await register(service.baseUrl, { email: "scenario-c@example.com", guestIdentityId: guestIdentity("c1") });
    assert.equal(first.status, 201);
    const accountId = first.body.account.userId;

    // Clearing app data takes the encrypted session and the guest identity with it. Nothing on the service changes:
    // the account rows are untouched, and the session the device abandoned is simply never used again.
    const afterReinstall = await loginCall(service.baseUrl, {
      email: "scenario-c@example.com",
      guestIdentityId: guestIdentity("c2"),
    });

    assert.equal(afterReinstall.status, 200);
    assert.equal(
      afterReinstall.body.account.userId,
      accountId,
      "an account must not depend on the device it was created from",
    );
    assert.equal(
      afterReinstall.body.guestLinked,
      undefined,
      "signing in to an existing account from a new device must not claim a link it did not make",
    );
  } finally {
    await service.close();
  }
});

test("scenario D — use it as a guest, then create an account: the guest identity is linked once", async () => {
  const service = await startService();
  try {
    const identity = guestIdentity("d");

    const recorded = await call(service.baseUrl, "POST", "/auth/guest", { body: { guestIdentityId: identity } });
    assert.equal(recorded.status, 201);
    assert.equal(recorded.body.guest.linked, false);

    const created = await register(service.baseUrl, {
      email: "scenario-d@example.com",
      guestIdentityId: identity,
    });

    assert.equal(created.status, 201);
    assert.equal(created.body.guestLinked, true, "the guest identity must be linked to the new account");
    assert.equal(created.body.account.status, "ACTIVE");

    // The link is one account's, and a second account cannot take it — which is what "exactly once" means.
    const second = await register(service.baseUrl, {
      email: "scenario-d-second@example.com",
      guestIdentityId: identity,
    });
    assert.equal(second.status, 409);
    assert.equal(second.body.error.code, "GUEST_IDENTITY_ALREADY_LINKED");
    assert.equal(
      (await loginCall(service.baseUrl, { email: "scenario-d-second@example.com" })).status,
      401,
      "a refused registration must not have created an account",
    );

    // And the first account still signs in, with its password, after all of that.
    const signedIn = await loginCall(service.baseUrl, { email: "scenario-d@example.com", password: VALID_PASSWORD });
    assert.equal(signedIn.status, 200);
    assert.equal(signedIn.body.account.userId, created.body.account.userId);
  } finally {
    await service.close();
  }
});
