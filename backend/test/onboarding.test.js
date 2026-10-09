/**
 * Phase 24 buyer/seller onboarding tests.
 *
 * The suite follows the same rule as the phase it tests: **the account is the session, completion is server-persisted,
 * and verification belongs to real mechanisms or to nobody.** A client cannot name an owner, claim an email or phone
 * verification, self-approve a creator profile, complete a form it did not fill correctly, or leave a refusal
 * unaudited. Where a request is refused, the test asserts both the typed refusal and that nothing changed — and where
 * a refusal *should* leave a trace, it asserts the audit row survived the rollback.
 *
 * Security thresholds are raised on purpose (same as Phase 23): this suite deliberately produces many refused
 * requests, and the Phase 20 protections correctly treat that as abuse. Rate limits get their own small budgets in
 * their own test.
 */

import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { afterEach, describe, it } from "node:test";
import { migrateToVersion, SCHEMA_VERSION } from "../src/db.js";
import { CREATOR_AGREEMENT_VERSION, REFERRAL_SOURCES } from "../src/onboarding.js";
import { canPublishCreatorContent, CAPABILITY_STATE } from "../src/capabilities.js";
import { call, registerVerified, startService } from "./helpers.js";

const services = new Set();
const temporaryDirectories = new Set();
const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x51).toString("base64url");
const DEVELOPER_EMAIL = "owner@example.test";
const DEVELOPER_PASSWORD = "Creator Plane 7Safe";

const RELAXED = Object.freeze({
  RATE_ONBOARDING_WRITE_MAX: "1000",
  RATE_ONBOARDING_READ_MAX: "1000",
  RATE_CREATOR_WRITE_MAX: "1000",
  RATE_CREATOR_READ_MAX: "1000",
  RATE_CREDITS_MAX: "1000",
  RATE_DEV_ADMIN_MAX: "1000",
  RATE_DEV_LOGIN_MAX: "1000",
  RATE_DEV_SESSION_MAX: "1000",
  SECURITY_UNAUTHORIZED_ACCESS_MEDIUM_MAX: "100000",
  SECURITY_UNAUTHORIZED_ACCESS_HIGH_MAX: "100001",
  SECURITY_UNAUTHORIZED_ACCESS_CRITICAL_MAX: "100002",
  SECURITY_MALFORMED_REQUESTS_MEDIUM_MAX: "100000",
  SECURITY_MALFORMED_REQUESTS_HIGH_MAX: "100001",
  SECURITY_MALFORMED_REQUESTS_CRITICAL_MAX: "100002",
  SECURITY_REQUEST_BURST_MEDIUM_MAX: "100000",
  SECURITY_REQUEST_BURST_HIGH_MAX: "100001",
  SECURITY_REQUEST_BURST_CRITICAL_MAX: "100002",
  SECURITY_RATE_LIMIT_MEDIUM_MAX: "100000",
  SECURITY_RATE_LIMIT_ABUSE_MEDIUM_MAX: "100000",
  SECURITY_RATE_LIMIT_ABUSE_HIGH_MAX: "100001",
  SECURITY_RATE_LIMIT_ABUSE_CRITICAL_MAX: "100002",
  SECURITY_CONFIRMATION_ABUSE_MEDIUM_MAX: "100000",
  SECURITY_CONFIRMATION_ABUSE_HIGH_MAX: "100001",
  SECURITY_CONFIRMATION_ABUSE_CRITICAL_MAX: "100002",
  CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
  CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
});

async function newService(overrides = {}) {
  const service = await startService({ ...RELAXED, ...overrides });
  services.add(service);
  const close = service.close.bind(service);
  service.close = async () => {
    services.delete(service);
    await close();
  };
  return service;
}

afterEach(async () => {
  await Promise.all([...services].map((service) => service.close()));
  for (const directory of temporaryDirectories) rmSync(directory, { recursive: true, force: true });
  temporaryDirectories.clear();
});

function temporaryDatabasePath() {
  const directory = mkdtempSync(join(tmpdir(), "craftmind-phase24-"));
  temporaryDirectories.add(directory);
  return join(directory, "identity.sqlite");
}

function bearer(accessToken) {
  return { Authorization: `Bearer ${accessToken}` };
}

async function signedInAccount(service, email) {
  const registration = await registerVerified(service, { email });
  assert.equal(registration.status, 201, JSON.stringify(registration.body));
  return { email, accessToken: registration.body.session.accessToken };
}

async function ownerSession(service) {
  const bootstrap = await call(service.baseUrl, "POST", "/developer/auth/bootstrap", {
    body: { secret: BOOTSTRAP_SECRET, password: DEVELOPER_PASSWORD },
  });
  assert.equal(bootstrap.status, 201, JSON.stringify(bootstrap.body));
  const login = await call(service.baseUrl, "POST", "/developer/auth/login", {
    body: { email: DEVELOPER_EMAIL, password: DEVELOPER_PASSWORD },
  });
  assert.equal(login.status, 200, JSON.stringify(login.body));
  return { accessToken: login.body.session.accessToken };
}

async function invokeTool(service, accessToken, tool, args) {
  return call(service.baseUrl, "POST", "/developer/tools/invoke", {
    headers: bearer(accessToken), body: { tool, arguments: args },
  });
}

async function confirmedTool(service, accessToken, tool, args) {
  const prepared = await invokeTool(service, accessToken, tool, args);
  assert.equal(prepared.status, 200, `prepare ${tool}: ${JSON.stringify(prepared.body)}`);
  assert.equal(prepared.body.result.confirmationRequired, true, `${tool} must stop at the confirmation boundary`);
  const confirmed = await call(service.baseUrl, "POST", "/developer/tools/confirm", {
    headers: bearer(accessToken), body: { confirmationToken: prepared.body.result.confirmationToken },
  });
  assert.equal(confirmed.status, 200, `confirm ${tool}: ${JSON.stringify(confirmed.body)}`);
  return confirmed.body.result;
}

/** Grants a plan through the developer tool — the only way a paid plan is ever applied. */
async function grantPlan(service, owner, email, plan = "CREATOR", days = 30) {
  return confirmedTool(service, owner.accessToken, "grantMembership", {
    email, plan, days, reason: "Phase 24 test grant",
  });
}

async function creatorAccount(service, owner, email) {
  const account = await signedInAccount(service, email);
  await grantPlan(service, owner, email, "CREATOR");
  return account;
}

function accountIdFor(service, email) {
  return service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(email).user_id;
}

function auditRows(service, actionType) {
  return service.database
    .prepare("SELECT * FROM admin_audit_log WHERE action_type = ? ORDER BY occurred_at, audit_id")
    .all(actionType);
}

function tableColumns(service, table) {
  return service.database.prepare(`PRAGMA table_info(${table})`).all().map((row) => row.name);
}

/** A complete, valid seller payload; individual tests override exactly what they are testing. */
function sellerBody(overrides = {}) {
  return {
    handle: "test-studio",
    displayName: "Test Studio",
    referralSource: "YOUTUBE",
    editions: ["java"],
    minecraftVersions: ["1.20.1"],
    loaders: ["Fabric"],
    agreementAccepted: true,
    agreementVersion: CREATOR_AGREEMENT_VERSION,
    ...overrides,
  };
}

function buyerBody(overrides = {}) {
  return { fullName: "Test Person", referralSource: "GOOGLE", ...overrides };
}

// ------------------------------------------------------------------ A. account and ownership (scenarios 1–7)

describe("Phase 24 account and ownership", () => {
  it("1. reads onboarding state for the authenticated account", async () => {
    const service = await newService();
    const account = await signedInAccount(service, "state@example.test");
    const state = await call(service.baseUrl, "GET", "/account/onboarding", { headers: bearer(account.accessToken) });
    assert.equal(state.status, 200, JSON.stringify(state.body));
    assert.equal(state.body.onboarding.buyer.exists, false);
    assert.equal(state.body.onboarding.buyer.completed, false);
    assert.equal(state.body.onboarding.seller.exists, false);
    assert.deepEqual([...state.body.nextSteps], ["COMPLETE_BUYER_ONBOARDING", "COMPLETE_SELLER_ONBOARDING"]);
    assert.deepEqual([...state.body.requirements.buyer.pending], ["FULL_NAME", "REFERRAL"]);
    // Verification posture is honest: email is real state, phone states its own absence.
    assert.equal(state.body.verification.email.status, "VERIFIED");
    assert.equal(state.body.verification.phone.available, false);
    assert.equal(state.body.verification.phone.status, "UNAVAILABLE");
    assert.equal(state.body.verification.creator.scope, "INTERNAL_MARKER_ONLY");
    // Option vocabulary comes from the server, including the exact agreement version a form must echo.
    assert.deepEqual([...state.body.options.referralSources], [...REFERRAL_SOURCES]);
    assert.equal(state.body.options.agreement.version, CREATOR_AGREEMENT_VERSION);
    // Nothing private crosses the wire: no address, no internal identifiers, no credential-shaped keys.
    const text = JSON.stringify(state.body);
    for (const forbidden of ["usr_", "ses_", "accessToken", "refreshToken", "password", "@example"]) {
      assert.equal(text.includes(forbidden), false, `state must not contain ${forbidden}`);
    }
  });

  it("2. refuses onboarding writes without a session", async () => {
    const service = await newService();
    const writes = [
      ["POST", "/onboarding/buyer", buyerBody()],
      ["POST", "/onboarding/seller", sellerBody()],
    ];
    for (const [method, path, body] of writes) {
      const attempt = await call(service.baseUrl, method, path, { body });
      assert.equal(attempt.status, 401, `${method} ${path}`);
      assert.equal(attempt.body.error.code, "AUTHENTICATION_REQUIRED");
    }
    const read = await call(service.baseUrl, "GET", "/onboarding/buyer");
    assert.equal(read.status, 401);
    const state = await call(service.baseUrl, "GET", "/account/onboarding");
    assert.equal(state.status, 401);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM buyer_onboarding").get().count, 0);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM seller_onboarding").get().count, 0);
  });

  it("3. buyer onboarding creates or updates only the current account's data", async () => {
    const service = await newService();
    const alice = await signedInAccount(service, "alice@example.test");
    const mallory = await signedInAccount(service, "mallory@example.test");
    const aliceId = accountIdFor(service, "alice@example.test");

    const first = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(alice.accessToken), body: buyerBody({ fullName: "Alice Honest", referralSource: "REDDIT" }),
    });
    assert.equal(first.status, 200, JSON.stringify(first.body));
    assert.equal(first.body.buyer.fullName, "Alice Honest");

    // An ownership injection is refused outright, and the refusal changes nothing.
    const injected = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(mallory.accessToken),
      body: buyerBody({ fullName: "Spoofed Name", userId: aliceId, ownerId: aliceId, plan: "CREATOR" }),
    });
    assert.equal(injected.status, 400);
    assert.equal(injected.body.error.code, "INVALID_REQUEST");

    const mallorySave = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(mallory.accessToken), body: buyerBody({ fullName: "Mallory Own" }),
    });
    assert.equal(mallorySave.status, 200);
    assert.equal(mallorySave.body.buyer.fullName, "Mallory Own");

    // Two rows, each keyed to its own account; Alice's answer is untouched.
    const rows = service.database.prepare("SELECT user_id, full_name FROM buyer_onboarding").all();
    assert.equal(rows.length, 2);
    const byUser = new Map(rows.map((row) => [row.user_id, row.full_name]));
    assert.equal(byUser.get(aliceId), "Alice Honest");
    assert.equal(byUser.get(accountIdFor(service, "mallory@example.test")), "Mallory Own");
  });

  it("4. seller onboarding creates or updates only the current account's creator profile", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const ada = await creatorAccount(service, owner, "ada@example.test");
    const bob = await creatorAccount(service, owner, "bob@example.test");
    const adaId = accountIdFor(service, "ada@example.test");
    const bobId = accountIdFor(service, "bob@example.test");

    const adaSave = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(ada.accessToken), body: sellerBody({ handle: "ada-studio", displayName: "Ada Builds" }),
    });
    assert.equal(adaSave.status, 200, JSON.stringify(adaSave.body));
    assert.equal(adaSave.body.profileExists, true);

    // Bob cannot claim Ada's handle, and his failed attempt creates no seller record for him.
    const handleClash = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(bob.accessToken), body: sellerBody({ handle: "ada-studio", displayName: "Bob Copy" }),
    });
    assert.equal(handleClash.status, 409);
    assert.equal(handleClash.body.error.code, "CREATOR_HANDLE_UNAVAILABLE");
    const bobState = await call(service.baseUrl, "GET", "/onboarding/seller", { headers: bearer(bob.accessToken) });
    assert.equal(bobState.body.seller.exists, false);

    const bobSave = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(bob.accessToken), body: sellerBody({ handle: "bob-studio", displayName: "Bob Builds" }),
    });
    assert.equal(bobSave.status, 200, JSON.stringify(bobSave.body));

    const profiles = service.database.prepare("SELECT user_id, display_name FROM creator_profiles").all();
    assert.equal(profiles.length, 2);
    const byUser = new Map(profiles.map((row) => [row.user_id, row.display_name]));
    assert.equal(byUser.get(adaId), "Ada Builds");
    assert.equal(byUser.get(bobId), "Bob Builds");
    // Ada's seller record is hers alone.
    assert.equal(
      service.database.prepare("SELECT user_id FROM seller_onboarding WHERE user_id = ?").get(adaId).user_id, adaId,
    );
  });

  it("5. a second account cannot read another account's private onboarding answers", async () => {
    const service = await newService();
    const alice = await signedInAccount(service, "alice-read@example.test");
    const bob = await signedInAccount(service, "bob-read@example.test");
    await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(alice.accessToken),
      body: buyerBody({ fullName: "Alice Hiddenworth", referralSource: "DISCORD", referralDetail: "A private answer" }),
    });
    for (const path of ["/account/onboarding", "/onboarding/buyer", "/onboarding/seller"]) {
      const read = await call(service.baseUrl, "GET", path, { headers: bearer(bob.accessToken) });
      assert.equal(read.status, 200, path);
      const text = JSON.stringify(read.body);
      assert.equal(text.includes("Alice Hiddenworth"), false, `${path} leaked a name`);
      assert.equal(text.includes("A private answer"), false, `${path} leaked referral detail`);
    }
    // Bob's own read shows an honest empty state, not someone else's row.
    const bobOwn = await call(service.baseUrl, "GET", "/onboarding/buyer", { headers: bearer(bob.accessToken) });
    assert.equal(bobOwn.body.buyer.exists, false);
  });

  it("6. buyer onboarding does not prevent later seller onboarding on the same account", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "both@example.test");
    await grantPlan(service, owner, "both@example.test", "CREATOR");

    const buyer = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), body: buyerBody({ fullName: "Both Roles" }),
    });
    assert.equal(buyer.status, 200);
    const seller = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(account.accessToken), body: sellerBody({ handle: "both-studio" }),
    });
    assert.equal(seller.status, 200, JSON.stringify(seller.body));

    const userId = accountIdFor(service, "both@example.test");
    const buyerRow = service.database.prepare("SELECT user_id FROM buyer_onboarding WHERE user_id = ?").get(userId);
    const sellerRow = service.database.prepare("SELECT user_id FROM seller_onboarding WHERE user_id = ?").get(userId);
    assert.ok(buyerRow);
    assert.ok(sellerRow);
    // One account did both — no second credential, no second identity.
    assert.equal(
      service.database.prepare("SELECT COUNT(*) AS count FROM users WHERE email_canonical LIKE '%both%'").get().count, 1,
    );
    const state = await call(service.baseUrl, "GET", "/account/onboarding", { headers: bearer(account.accessToken) });
    assert.equal(state.body.onboarding.buyer.completed, true);
    assert.equal(state.body.onboarding.seller.completed, true);
    assert.deepEqual([...state.body.nextSteps], []);
  });

  it("7. repeated submissions stay idempotent: one buyer row, one seller row, one profile", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "repeat@example.test");

    const first = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), body: buyerBody({ fullName: "Repeat Buyer" }),
    });
    const createdAt = first.body.buyer.createdAt;
    const second = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), body: buyerBody({ fullName: "Repeat Buyer", referralSource: "REDDIT" }),
    });
    assert.equal(second.status, 200);
    assert.equal(second.body.buyer.createdAt, createdAt, "a repeat save must not re-create the record");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM buyer_onboarding").get().count, 1);

    for (const handle of ["repeat-studio", "repeat-studio"]) {
      const save = await call(service.baseUrl, "POST", "/onboarding/seller", {
        headers: bearer(account.accessToken), body: sellerBody({ handle }),
      });
      assert.equal(save.status, 200, JSON.stringify(save.body));
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM seller_onboarding").get().count, 1);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 1);
  });
});

// ------------------------------------------------------------------------- B. validation (scenarios 8–15)

describe("Phase 24 onboarding validation", () => {
  it("8. invalid names and oversized text are rejected", async () => {
    const service = await newService();
    const account = await signedInAccount(service, "names@example.test");
    for (const fullName of ["", "A", "x".repeat(121), "<script>alert(1)</script>", "Line\u2028Break", "   "]) {
      const attempt = await call(service.baseUrl, "POST", "/onboarding/buyer", {
        headers: bearer(account.accessToken), body: buyerBody({ fullName }),
      });
      assert.equal(attempt.status, 400, `fullName ${JSON.stringify(fullName).slice(0, 40)} must be refused`);
      assert.equal(attempt.body.error.code, "INVALID_REQUEST");
    }
    const oversizedDetail = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken),
      body: buyerBody({ referralSource: "OTHER", referralDetail: "d".repeat(161) }),
    });
    assert.equal(oversizedDetail.status, 400);
    const valid = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), body: buyerBody({ fullName: "Li Wei" }),
    });
    assert.equal(valid.status, 200, JSON.stringify(valid.body));
    assert.equal(valid.body.buyer.fullName, "Li Wei");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM buyer_onboarding").get().count, 1);
  });

  it("9. invalid referral values are rejected", async () => {
    const service = await newService();
    const account = await signedInAccount(service, "referral@example.test");
    for (const referralSource of ["TIKTOK", "youtube", "", 42, null, "YOUTUBE "]) {
      const attempt = await call(service.baseUrl, "POST", "/onboarding/buyer", {
        headers: bearer(account.accessToken), body: buyerBody({ referralSource }),
      });
      assert.equal(attempt.status, 400, `referral ${JSON.stringify(referralSource)} must be refused`);
      assert.equal(attempt.body.error.code, "INVALID_REQUEST");
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM buyer_onboarding").get().count, 0);
  });

  it('10. "Other" referral text respects the length limits', async () => {
    const service = await newService();
    const account = await signedInAccount(service, "other-referral@example.test");
    const tooLong = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken),
      body: buyerBody({ referralSource: "OTHER", referralDetail: "x".repeat(161) }),
    });
    assert.equal(tooLong.status, 400);
    const tooShort = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), body: buyerBody({ referralSource: "OTHER", referralDetail: "ab" }),
    });
    assert.equal(tooShort.status, 400);
    const ok = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken),
      body: buyerBody({ referralSource: "OTHER", referralDetail: "Referred by a survival server community".slice(0, 150) }),
    });
    assert.equal(ok.status, 200, JSON.stringify(ok.body));
    assert.equal(ok.body.buyer.referralSource, "OTHER");
    assert.equal(ok.body.buyer.referralDetail.length > 3, true);
    // Detail is optional everywhere, including with Other — but when present it is bounded.
    const empty = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), body: buyerBody({ referralSource: "OTHER" }),
    });
    assert.equal(empty.status, 200);
    assert.equal(empty.body.buyer.referralDetail, "");
  });

  it("11. invalid handles and reserved handles are rejected by Phase 23's rules", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "handles@example.test");
    for (const [handle, code] of [
      ["Admin", "CREATOR_HANDLE_RESERVED"],
      ["market_place", "CREATOR_HANDLE_RESERVED"],
      ["x", "INVALID_REQUEST"],
      ["café-studio", "INVALID_REQUEST"],
      ["x".repeat(33), "INVALID_REQUEST"],
    ]) {
      const attempt = await call(service.baseUrl, "POST", "/onboarding/seller", {
        headers: bearer(account.accessToken), body: sellerBody({ handle }),
      });
      assert.equal(attempt.status, 400, `handle ${handle} must be refused`);
      assert.equal(attempt.body.error.code, code, handle);
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 0);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM seller_onboarding").get().count, 0);
  });

  it("12. invalid image and reference URLs are refused", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "avatar@example.test");
    for (const avatarReference of [
      "javascript:alert(1)",
      "http://insecure.example/avatar.png",
      "https://user:pass@example.test/avatar.png",
      "data:image/svg+xml;base64,AAAA",
      "not a url",
    ]) {
      const attempt = await call(service.baseUrl, "POST", "/onboarding/seller", {
        headers: bearer(account.accessToken), body: sellerBody({ avatarReference }),
      });
      assert.equal(attempt.status, 400, `avatar ${avatarReference} must be refused`);
      assert.equal(attempt.body.error.code, "INVALID_REQUEST");
    }
    const ok = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(account.accessToken),
      body: sellerBody({ avatarReference: "https://cdn.example.test/avatar.png" }),
    });
    assert.equal(ok.status, 200, JSON.stringify(ok.body));
    assert.equal(ok.body.profile.avatarReference, "https://cdn.example.test/avatar.png");
  });

  it("13. unsupported edition, version, and loader values are refused by the compatibility rules", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "compat@example.test");
    const cases = [
      { editions: ["java2000"] },
      { minecraftVersions: ["latest"] },
      { minecraftVersions: ["1.20.1-fabric"] },
      { loaders: ["OptiFine"] },
      // A loader from an edition the creator did not claim: the cross-field rule, not just an alphabet check.
      { loaders: ["Bedrock Native"] },
      { editions: ["bedrock"], loaders: ["Fabric"] },
      { editions: [] },
      { minecraftVersions: [] },
    ];
    for (const override of cases) {
      const attempt = await call(service.baseUrl, "POST", "/onboarding/seller", {
        headers: bearer(account.accessToken), body: sellerBody(override),
      });
      assert.equal(attempt.status, 400, `${JSON.stringify(override)} must be refused`);
      assert.equal(attempt.body.error.code, "INVALID_REQUEST");
    }
    const ok = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(account.accessToken),
      body: sellerBody({ editions: ["java", "bedrock"], minecraftVersions: ["1.20.1", "1.21.0"], loaders: ["Fabric", "Bedrock Native"] }),
    });
    assert.equal(ok.status, 200, JSON.stringify(ok.body));
    assert.deepEqual([...ok.body.seller.editions], ["java", "bedrock"]);
    assert.deepEqual([...ok.body.seller.loaders], ["Fabric", "Bedrock Native"]);
  });

  it("14. unknown privileged fields are rejected, never silently accepted", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "privileged@example.test");
    const victimId = accountIdFor(service, "privileged@example.test");
    const privileged = ["userId", "ownerId", "accountId", "plan", "role", "status", "emailVerified", "verification", "completed", "entitlementGranted"];
    for (const key of privileged) {
      const buyerAttempt = await call(service.baseUrl, "POST", "/onboarding/buyer", {
        headers: bearer(account.accessToken),
        body: buyerBody({ [key]: key === "status" ? "ACTIVE" : victimId }),
      });
      assert.equal(buyerAttempt.status, 400, `buyer field ${key}`);
      assert.equal(buyerAttempt.body.error.code, "INVALID_REQUEST", key);
      const sellerAttempt = await call(service.baseUrl, "POST", "/onboarding/seller", {
        headers: bearer(account.accessToken),
        body: sellerBody({ [key]: key === "verification" ? "VERIFIED" : victimId }),
      });
      assert.equal(sellerAttempt.status, 400, `seller field ${key}`);
      assert.equal(sellerAttempt.body.error.code, "INVALID_REQUEST", key);
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM buyer_onboarding").get().count, 0);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM seller_onboarding").get().count, 0);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 0);
  });

  it("15. malformed JSON and oversized request bodies are handled safely", async () => {
    const service = await newService({ MAX_BODY_BYTES: "2048" });
    const account = await signedInAccount(service, "malformed@example.test");
    const broken = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), raw: '{"fullName": "Broken"',
    });
    assert.equal(broken.status, 400);
    assert.equal(broken.body.error.code, "INVALID_REQUEST");
    const wrongType = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), raw: '["array","is","not","a","body"]',
    });
    assert.equal(wrongType.status, 400);
    assert.equal(wrongType.body.error.code, "INVALID_REQUEST");
    const huge = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken),
      body: buyerBody({ fullName: "Big", referralSource: "OTHER", referralDetail: "b".repeat(8000) }),
    });
    assert.equal(huge.status, 413);
    assert.equal(huge.body.error.code, "REQUEST_TOO_LARGE");
    // No error response ever names an internal detail.
    for (const response of [broken, wrongType, huge]) {
      const text = JSON.stringify(response.body);
      assert.equal(/sql|sqlite|stack|usr_|ses_/.test(text.toLowerCase()), false, text);
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM buyer_onboarding").get().count, 0);
  });
});

// ------------------------------------------------- C. verification and entitlements (scenarios 16–21)

describe("Phase 24 verification and entitlements", () => {
  it("16. an unverified email can never be presented as verified", async () => {
    const service = await newService();
    const account = await signedInAccount(service, "email-check@example.test");
    // The client cannot *send* verification success.
    const forged = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), body: buyerBody({ emailVerified: true }),
    });
    assert.equal(forged.status, 400);
    assert.equal(forged.body.error.code, "INVALID_REQUEST");
    // The table has no column that could store such a claim: verification status is derived, never copied.
    const columns = tableColumns(service, "buyer_onboarding");
    assert.equal(columns.some((name) => /email|verified/.test(name)), false, columns.join(","));
    // And an account that stops being verified cannot even resolve its session, so nothing downstream can present it
    // as verified: the read is refused at the auth boundary, and a fresh sign-in is refused too.
    service.database.prepare("UPDATE users SET email_verified_at = NULL WHERE user_id = ?")
      .run(accountIdFor(service, "email-check@example.test"));
    const read = await call(service.baseUrl, "GET", "/account/onboarding", { headers: bearer(account.accessToken) });
    assert.equal(read.status, 403);
    assert.equal(read.body.error.code, "EMAIL_NOT_VERIFIED");
    const signIn = await call(service.baseUrl, "POST", "/auth/login", {
      body: { email: "email-check@example.test", password: "Correct Horse 7Battery" },
    });
    assert.equal(signIn.status, 403);
    assert.equal(signIn.body.error.code, "EMAIL_NOT_VERIFIED");
  });

  it("17. a submitted phone number cannot manufacture phone-verification success", async () => {
    const service = await newService();
    const account = await signedInAccount(service, "phone-check@example.test");
    for (const key of ["phone", "phoneNumber", "phoneVerified", "phoneVerification", "mobile"]) {
      const attempt = await call(service.baseUrl, "POST", "/onboarding/buyer", {
        headers: bearer(account.accessToken),
        body: buyerBody({ [key]: key.includes("Verified") ? true : "+911234567890" }),
      });
      assert.equal(attempt.status, 400, key);
      assert.equal(attempt.body.error.code, "PHONE_VERIFICATION_UNAVAILABLE", key);
    }
    const state = await call(service.baseUrl, "GET", "/account/onboarding", { headers: bearer(account.accessToken) });
    assert.equal(state.body.verification.phone.available, false);
    assert.equal(state.body.verification.phone.status, "UNAVAILABLE");
    assert.match(state.body.verification.phone.reason, /does not exist/i);
    const columns = [...tableColumns(service, "buyer_onboarding"), ...tableColumns(service, "seller_onboarding")];
    assert.equal(columns.some((name) => /phone|mobile/.test(name)), false, columns.join(","));
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM buyer_onboarding").get().count, 0);
  });

  it("18. users cannot self-approve or self-verify their creator profile", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "selfverify@example.test");
    // A verification field in the body is refused as unknown: the client never gets a vote.
    const forged = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(account.accessToken), body: sellerBody({ handle: "self-studio", verification: "VERIFIED" }),
    });
    assert.equal(forged.status, 400);
    assert.equal(forged.body.error.code, "INVALID_REQUEST");
    const saved = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(account.accessToken), body: sellerBody({ handle: "self-studio" }),
    });
    assert.equal(saved.status, 200, JSON.stringify(saved.body));
    // Completion does not move the marker: it is UNVERIFIED, and its scope is stated honestly.
    assert.equal(saved.body.profile.verification, "UNVERIFIED");
    assert.equal(saved.body.seller.completed, true);
    const state = await call(service.baseUrl, "GET", "/account/onboarding", { headers: bearer(account.accessToken) });
    assert.equal(state.body.verification.creator.status, "UNVERIFIED");
    assert.equal(state.body.verification.creator.scope, "INTERNAL_MARKER_ONLY");
    const row = service.database.prepare("SELECT verification_status FROM creator_profiles WHERE user_id = ?")
      .get(accountIdFor(service, "selfverify@example.test"));
    assert.equal(row.verification_status, "UNVERIFIED");
  });

  it("19. complete prerequisites report publishing available without granting verification", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "publish@example.test");
    const saved = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(account.accessToken), body: sellerBody({ handle: "publish-studio" }),
    });
    assert.equal(saved.status, 200);
    assert.equal(saved.body.seller.completed, true);

    const userId = accountIdFor(service, "publish@example.test");
    // Phase 25: this account now holds every real prerequisite — entitlement, active profile, accepted seller
    // agreement — so publishing is honestly AVAILABLE. The capability is a genuine evaluation, not a placeholder.
    const publish = canPublishCreatorContent(service.database, service.configuration, userId);
    assert.equal(publish.allowed, true);
    assert.equal(publish.state, CAPABILITY_STATE.ALLOWED);
    assert.equal(publish.code, null);
    assert.equal(publish.wouldBeEligible, true);
    assert.equal(publish.agreementRecorded, true);

    // The same truth is what the account's own creator read reports: the capability exists, and it is not available.
    const profile = await call(service.baseUrl, "GET", "/creator/profile", { headers: bearer(account.accessToken) });
    assert.equal(profile.status, 200);
    const entry = profile.body.eligibility.capabilities.find((capability) => capability.key === "CREATOR_PUBLISH");
    assert.ok(entry, "the creator capability list must state where publishing stands");
    assert.equal(entry.available, true);
    assert.equal(entry.state, "AVAILABLE");
    // Publishing availability is not a verification side effect: the profile row is still UNVERIFIED.
    const row = service.database.prepare("SELECT verification_status FROM creator_profiles WHERE user_id = ?").get(userId);
    assert.equal(row.verification_status, "UNVERIFIED");
  });

  it("20. membership status and credits remain unchanged by onboarding", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "unchanged@example.test");
    const read = async () => {
      const membership = await call(service.baseUrl, "GET", "/account/membership", { headers: bearer(account.accessToken) });
      const credits = await call(service.baseUrl, "GET", "/account/credits", { headers: bearer(account.accessToken) });
      assert.equal(membership.status, 200);
      assert.equal(credits.status, 200);
      return { membership: JSON.stringify(membership.body), credits: JSON.stringify(credits.body) };
    };
    const before = await read();
    // Buyer onboarding on a FREE account changes nothing about membership or credits.
    const buyerSave = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), body: buyerBody({ fullName: "Ledger Observer" }),
    });
    assert.equal(buyerSave.status, 200);
    const afterBuyer = await read();
    assert.deepEqual(afterBuyer, before, "buyer onboarding must not touch membership or credits");

    // Same after a creator grant, a credit grant, and seller onboarding.
    await grantPlan(service, owner, "unchanged@example.test", "CREATOR");
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "unchanged@example.test", amount: 250, reason: "Phase 24 test credits",
    });
    const beforeSeller = await read();
    const sellerSave = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(account.accessToken), body: sellerBody({ handle: "ledger-studio" }),
    });
    assert.equal(sellerSave.status, 200, JSON.stringify(sellerSave.body));
    const afterSeller = await read();
    assert.deepEqual(afterSeller, beforeSeller, "seller onboarding must not touch membership or credits");
    // Onboarding itself granted no entitlement beyond what the developer tool granted.
    const entitlements = await call(service.baseUrl, "GET", "/account/entitlements", { headers: bearer(account.accessToken) });
    assert.equal(entitlements.body.entitlements.some((entry) => entry.key === "CREATOR_PUBLISH"), false);
  });

  it("21. protected developer operations still require authorization and confirmation", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "dev-guard@example.test");
    const saved = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(account.accessToken), body: sellerBody({ handle: "guarded-studio" }),
    });
    assert.equal(saved.status, 200);

    // A normal account cannot invoke developer tools at all.
    const asUser = await invokeTool(service, account.accessToken, "verifyCreator", {
      handle: "guarded-studio", reason: "self-service verification",
    });
    assert.equal(asUser.status, 401);
    // A prepared action changes nothing until a developer confirms it.
    const prepared = await invokeTool(service, owner.accessToken, "verifyCreator", {
      handle: "guarded-studio", reason: "Phase 24 review",
    });
    assert.equal(prepared.status, 200);
    assert.equal(prepared.body.result.confirmationRequired, true);
    const profileRow = service.database.prepare("SELECT verification_status FROM creator_profiles WHERE handle = ?")
      .get("guarded-studio");
    assert.equal(profileRow.verification_status, "UNVERIFIED");
    const confirmed = await confirmedTool(service, owner.accessToken, "verifyCreator", {
      handle: "guarded-studio", reason: "Phase 24 review",
    });
    assert.equal(confirmed.verification, "VERIFIED");
  });
});

// --------------------------------------------- D. audit and resilience (scenarios 22–25, 28) + vocabulary drift

describe("Phase 24 audit and resilience", () => {
  it("22. successful onboarding saves are audited in the one log, without personal data", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "audited-onboarding@example.test");
    const userId = accountIdFor(service, "audited-onboarding@example.test");

    const buyer = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken),
      body: buyerBody({ fullName: "Private Person", referralSource: "OTHER", referralDetail: "A very private answer" }),
    });
    assert.equal(buyer.status, 200);
    const seller = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(account.accessToken), body: sellerBody({ handle: "audited-onboarding-studio" }),
    });
    assert.equal(seller.status, 200);

    const buyerAudit = auditRows(service, "BUYER_ONBOARDING_SAVED");
    assert.equal(buyerAudit.length, 1);
    assert.equal(buyerAudit[0].outcome, "SUCCESS");
    assert.equal(buyerAudit[0].actor_kind, "SYSTEM");
    assert.equal(buyerAudit[0].target_user_id, userId);
    const sellerAudit = auditRows(service, "SELLER_ONBOARDING_SAVED");
    assert.equal(sellerAudit.length, 1);
    assert.equal(sellerAudit[0].outcome, "SUCCESS");
    assert.equal(sellerAudit[0].target_user_id, userId);
    // The record of the save never carries the answer: no name, no referral text, no credential-shaped key.
    for (const row of [...buyerAudit, ...sellerAudit]) {
      assert.equal(row.metadata_json.includes("Private Person"), false, row.metadata_json);
      assert.equal(row.metadata_json.includes("A very private answer"), false, row.metadata_json);
      assert.equal(/password|token|secret|digest/i.test(row.metadata_json), false, row.metadata_json);
      assert.equal(row.metadata_json.length <= 4096, true);
    }
    // A repeat save is still one row per accepted save, still attributed.
    await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), body: buyerBody({ fullName: "Private Person", referralSource: "REDDIT" }),
    });
    assert.equal(auditRows(service, "BUYER_ONBOARDING_SAVED").length, 2);
  });

  it("23. denial records survive the transaction rollback that enforces them", async () => {
    const service = await newService();
    const account = await signedInAccount(service, "denied-onboarding@example.test");
    // No creator entitlement: the seller save is refused, and the whole transaction — profile, seller row, and the
    // success-side writes — rolls back, but the denial audit must remain.
    const attempt = await call(service.baseUrl, "POST", "/onboarding/seller", {
      headers: bearer(account.accessToken), body: sellerBody({ handle: "denied-studio" }),
    });
    assert.equal(attempt.status, 403);
    assert.equal(attempt.body.error.code, "CREATOR_ENTITLEMENT_REQUIRED");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 0);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM seller_onboarding").get().count, 0);
    const denials = auditRows(service, "ENTITLEMENT_DENIED");
    assert.equal(denials.length, 1, "a refused seller save must leave its entitlement denial in the audit log");
    assert.equal(denials[0].outcome, "DENIED");
    assert.equal(denials[0].target_user_id, accountIdFor(service, "denied-onboarding@example.test"));
  });

  it("24. rate limits apply to onboarding writes with their own bounded bucket", async () => {
    const service = await newService({ RATE_ONBOARDING_WRITE_MAX: "2", RATE_ONBOARDING_READ_MAX: "100" });
    const account = await signedInAccount(service, "throttled@example.test");
    for (let attempt = 1; attempt <= 2; attempt += 1) {
      const save = await call(service.baseUrl, "POST", "/onboarding/buyer", {
        headers: bearer(account.accessToken), body: buyerBody({ fullName: `Throttle ${attempt}` }),
      });
      assert.equal(save.status, 200, JSON.stringify(save.body));
    }
    const limited = await call(service.baseUrl, "POST", "/onboarding/buyer", {
      headers: bearer(account.accessToken), body: buyerBody({ fullName: "Throttle 3" }),
    });
    assert.equal(limited.status, 429);
    assert.equal(limited.body.error.code, "RATE_LIMITED");
    // The write bucket is its own: reads still answer within their own budget.
    const read = await call(service.baseUrl, "GET", "/account/onboarding", { headers: bearer(account.accessToken) });
    assert.equal(read.status, 200);
    assert.equal(service.database.prepare("SELECT full_name FROM buyer_onboarding").get().full_name, "Throttle 2");
  });

  it("25. the version 8 migration preserves a Phase 23 database and stays additive", () => {
    // A Phase 23 database is built the way it really comes into being: migrations through 6, Phase 23 rows, then 7.
    const database = new DatabaseSync(temporaryDatabasePath());
    database.exec("PRAGMA foreign_keys = ON");
    database.exec("PRAGMA recursive_triggers = ON");
    database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    migrateToVersion(database, 6);
    const timestamp = "2026-01-01T00:00:00.000Z";
    database.exec(`
      INSERT INTO users (user_id, email, email_canonical, password_hash, display_name, status, email_verified_at, created_at, updated_at)
        VALUES ('usr_p23', 'p23@example.com', 'p23@example.com', 'hash', 'Phase23', 'ACTIVE', '${timestamp}', '${timestamp}', '${timestamp}');
      INSERT INTO membership_accounts (membership_id, user_id, plan, status, source, starts_at, ends_at, granted_by, created_at, updated_at)
        VALUES ('mbr_p23', 'usr_p23', 'CREATOR', 'ACTIVE', 'DEVELOPER_GRANT', '${timestamp}', '2026-06-01T00:00:00.000Z', NULL, '${timestamp}', '${timestamp}');
      INSERT INTO creator_profiles (creator_id, user_id, handle, display_name, bio, category, avatar_reference, status, verification_status, status_changed_at, verified_at, created_at, updated_at)
        VALUES ('crt_p23', 'usr_p23', 'phase23-studio', 'Phase23 Studio', '', NULL, NULL, 'ACTIVE', 'UNVERIFIED', '${timestamp}', NULL, '${timestamp}', '${timestamp}');
      INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
        VALUES ('aud_p23', 'SYSTEM', NULL, 'CREATOR_PROFILE_CREATED', 'usr_p23', NULL, '${timestamp}', 'SUCCESS', '{"source":"phase23"}');
    `);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 6);
    // The v6 schema refuses the Phase 24 action types — they genuinely do not exist yet.
    assert.throws(() => database.prepare(
      `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
        VALUES ('aud_too_early', 'SYSTEM', NULL, 'BUYER_ONBOARDING_SAVED', NULL, NULL, '${timestamp}', 'SUCCESS', '{}')`,
    ).run(), /CHECK constraint failed/);

    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(SCHEMA_VERSION, 8);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 8);
    // Existing data preserved, with values.
    assert.equal(database.prepare("SELECT display_name FROM users WHERE user_id = 'usr_p23'").get().display_name, "Phase23");
    const membership = database.prepare("SELECT * FROM membership_accounts WHERE user_id = 'usr_p23'").get();
    assert.equal(membership.plan, "CREATOR");
    assert.equal(membership.ends_at, "2026-06-01T00:00:00.000Z");
    assert.equal(database.prepare("SELECT handle FROM creator_profiles WHERE creator_id = 'crt_p23'").get().handle, "phase23-studio");
    const legacyAudit = database.prepare("SELECT * FROM admin_audit_log WHERE audit_id = 'aud_p23'").get();
    assert.equal(legacyAudit.action_type, "CREATOR_PROFILE_CREATED");
    assert.equal(legacyAudit.metadata_json, '{"source":"phase23"}');
    // New tables exist and are empty.
    for (const table of ["buyer_onboarding", "seller_onboarding"]) {
      assert.equal(database.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?").get(table)?.name, table);
      assert.equal(database.prepare(`SELECT COUNT(*) AS count FROM ${table}`).get().count, 0);
    }
    // The rebuilt audit log accepts the new action types, still refuses unknown ones, and stays append-only.
    database.exec(`
      INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
        VALUES ('aud_p24', 'SYSTEM', NULL, 'BUYER_ONBOARDING_SAVED', 'usr_p23', NULL, '${timestamp}', 'SUCCESS', '{}');
    `);
    assert.throws(() => database.prepare(
      `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
        VALUES ('aud_bad', 'SYSTEM', NULL, 'NOT_A_REAL_ACTION', NULL, NULL, '${timestamp}', 'SUCCESS', '{}')`,
    ).run(), /CHECK constraint failed/);
    assert.throws(() => database.prepare("UPDATE admin_audit_log SET outcome = 'DENIED' WHERE audit_id = 'aud_p23'").run(), /append-only/);
    assert.throws(() => database.prepare("DELETE FROM admin_audit_log WHERE audit_id = 'aud_p23'").run(), /append-only/);
    // No destructive path: re-running is a no-op.
    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 8);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log").get().count, 2);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 1);
    database.close();
  });

  it("28. the server and website compatibility vocabularies cannot drift apart", async () => {
    const backend = await import("../src/compatibility.js");
    const website = await import(new URL("../../website/assets/compatibility.js", import.meta.url).href);
    assert.deepEqual([...backend.EDITION_VALUES].sort(), [...website.EDITIONS.map((entry) => entry.wire)].sort());
    assert.equal(website.EDITION.UNKNOWN, "unknown", "the system's unknown state must never be an onboarding choice");
    assert.equal(backend.EDITION_VALUES.includes(website.EDITION.UNKNOWN), false);
    for (const edition of backend.EDITION_VALUES) {
      assert.deepEqual(
        [...backend.LOADERS_BY_EDITION[edition]],
        [...(website.LOADERS_BY_EDITION[edition] ?? [])],
        `loaders for ${edition}`,
      );
    }
    const websiteLoaders = new Set(Object.values(website.LOADERS_BY_EDITION).flat());
    for (const loader of backend.LOADER_VALUES) {
      assert.equal(websiteLoaders.has(loader), true, loader);
    }
    for (const loader of websiteLoaders) {
      assert.equal(backend.LOADER_VALUES.includes(loader), true, loader);
    }
    // The website's own validator agrees with the server about edition/loader pairs.
    for (const edition of backend.EDITION_VALUES) {
      for (const loader of backend.LOADERS_BY_EDITION[edition]) {
        assert.equal(website.loaderBelongsToEdition(loader, edition), true, `${loader} in ${edition}`);
      }
    }
  });
});
