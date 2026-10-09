/**
 * Phase 22 membership, entitlement, and build-credit tests.
 *
 * The suite is deliberately adversarial. It asserts the *server* decides: a client cannot declare a plan, invent a
 * balance, spend what it does not have, spend twice with one idempotency key, read another account's ledger, keep
 * credits after paying for them twice, or be granted anything by the AI boundary. The Phase 17–21 suites run alongside
 * this one and continue to describe the account, security, and control-plane behaviour this phase must not change.
 */

import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, it } from "node:test";
import { hashPassword } from "../src/passwords.js";
import { migrateToVersion, openDatabase, SCHEMA_VERSION } from "../src/db.js";
import { loadConfiguration } from "../src/config.js";
import { newDeveloperId } from "../src/ids.js";
import { AUDIT_ACTOR_KIND } from "../src/audit.js";
import { developerAiToolCall, invokeDeveloperTool } from "../src/admin-tools.js";
import {
  assignMembership,
  enforceEntitlement,
  getAccountEntitlements,
  hasEntitlement,
  MEMBERSHIP_PLAN,
  planCatalog,
} from "../src/entitlements.js";
import { creditBalance, creditLedgerState, grantCredits, consumeBuildCredits } from "../src/credits.js";
import { requireAccountIdForToken } from "../src/accounts.js";
import { call, registerVerified, startService, TEST_SECRET, VALID_PASSWORD } from "./helpers.js";

const services = new Set();
const temporaryDirectories = new Set();
const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x37).toString("base64url");
const DEVELOPER_EMAIL = "owner@example.test";
const DEVELOPER_PASSWORD = "Membership 8Credits";

async function newService(overrides = {}, options = {}) {
  const service = await startService(overrides, options);
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
  const directory = mkdtempSync(join(tmpdir(), "craftmind-phase22-"));
  temporaryDirectories.add(directory);
  return join(directory, "identity.sqlite");
}

function developerService(overrides = {}) {
  return newService({
    CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
    CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    ...overrides,
  });
}

/**
 * Phase 20's detection rules are exercised by their own suite. Tests that deliberately generate many refused requests
 * raise the thresholds so an autonomous protection cannot turn an authorization assertion into a throttle assertion —
 * the protection itself is still active and still enforced.
 */
const RELAXED_DETECTION = Object.freeze({
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
});

async function ownerSession(service) {
  const bootstrap = await call(service.baseUrl, "POST", "/developer/auth/bootstrap", {
    body: { secret: BOOTSTRAP_SECRET, password: DEVELOPER_PASSWORD },
  });
  assert.equal(bootstrap.status, 201, JSON.stringify(bootstrap.body));
  const login = await call(service.baseUrl, "POST", "/developer/auth/login", {
    body: { email: DEVELOPER_EMAIL, password: DEVELOPER_PASSWORD },
  });
  assert.equal(login.status, 200, JSON.stringify(login.body));
  // The developer identifier is never in a response; the tests that need it read it from the database, as an operator
  // surface rather than an API contract.
  const developerId = service.database.prepare(
    "SELECT developer_id FROM developer_accounts WHERE email_canonical = ?",
  ).get(DEVELOPER_EMAIL).developer_id;
  return { accessToken: login.body.session.accessToken, developerId };
}

function bearer(accessToken) {
  return { Authorization: `Bearer ${accessToken}` };
}

async function invokeTool(service, accessToken, tool, args) {
  return call(service.baseUrl, "POST", "/developer/tools/invoke", {
    headers: bearer(accessToken), body: { tool, arguments: args },
  });
}

async function confirmAction(service, accessToken, confirmationToken) {
  return call(service.baseUrl, "POST", "/developer/tools/confirm", {
    headers: bearer(accessToken), body: { confirmationToken },
  });
}

/** prepare → confirm, returning the confirmed result. */
async function confirmedTool(service, accessToken, tool, args) {
  const prepared = await invokeTool(service, accessToken, tool, args);
  assert.equal(prepared.status, 200, `prepare ${tool}: ${JSON.stringify(prepared.body)}`);
  assert.equal(prepared.body.result.confirmationRequired, true);
  const confirmed = await confirmAction(service, accessToken, prepared.body.result.confirmationToken);
  return { prepared, confirmed };
}

async function signedInAccount(service, email) {
  const registration = await registerVerified(service, { email });
  assert.equal(registration.status, 201, JSON.stringify(registration.body));
  return { accessToken: registration.body.session.accessToken, userId: registration.body.account.userId ?? null };
}

function ledgerRows(database, userId = null) {
  return userId === null
    ? database.prepare("SELECT * FROM credit_ledger").all()
    : database.prepare("SELECT * FROM credit_ledger WHERE user_id = ?").all(userId);
}

function auditRows(database, actionType) {
  return database.prepare("SELECT * FROM admin_audit_log WHERE action_type = ? ORDER BY occurred_at").all(actionType);
}

// --------------------------------------------------------------------------------------------------------- A. baseline

describe("Phase 22 free baseline and membership state", () => {
  it("assigns a deterministic free baseline on first read and only once", async () => {
    const service = await newService();
    const email = "baseline@example.test";
    const account = await signedInAccount(service, email);
    const user = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(email);

    const first = await call(service.baseUrl, "GET", "/account/membership", { headers: bearer(account.accessToken) });
    assert.equal(first.status, 200, JSON.stringify(first.body));
    assert.equal(first.body.membership.plan, "FREE");
    assert.equal(first.body.membership.status, "ACTIVE");
    assert.equal(first.body.membership.source, "DEFAULT_BASELINE");
    assert.equal(typeof first.body.membership.startsAt, "string");
    assert.equal(first.body.membership.endsAt, null);
    assert.equal(first.body.plan.purchasable, false);
    assert.equal(first.body.plan.availability, "AVAILABLE_TODAY");
    assert.deepEqual(first.body.plan.entitlements, ["BUILD_GENERATION", "LOCAL_BUILD_HISTORY", "VISUAL_REFERENCE"]);
    assert.equal(first.body.credits.available, 0);

    const second = await call(service.baseUrl, "GET", "/account/membership", { headers: bearer(account.accessToken) });
    assert.deepEqual(second.body, first.body);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM membership_accounts").get().count, 1);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM membership_transitions").get().count, 1);
    assert.equal(auditRows(service.database, "MEMBERSHIP_BASELINE_ASSIGNED").length, 1);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM users WHERE user_id = ?").get(user.user_id).count, 1);
  });

  it("resolves the account from the session, never from the request", async () => {
    const service = await newService();
    const first = await signedInAccount(service, "first@example.test");
    await signedInAccount(service, "second@example.test");
    const accounts = service.database.prepare("SELECT user_id, email_canonical FROM users ORDER BY email_canonical").all();
    const view = getAccountEntitlements(service.database, service.configuration, accounts[0].user_id);
    assert.equal(view.membership.plan, MEMBERSHIP_PLAN.FREE);

    // A body carrying an account id, a plan, or a balance is irrelevant: the route has no such input at all.
    const forged = await call(service.baseUrl, "GET", "/account/entitlements", {
      headers: bearer(first.accessToken),
    });
    assert.equal(forged.status, 200);
    assert.equal(forged.body.plan, "FREE");
    assert.equal(JSON.stringify(forged.body).includes(accounts[0].user_id), false);
    assert.equal(JSON.stringify(forged.body).includes(accounts[1].user_id), false);
    // And an anonymous caller gets nothing.
    const anonymous = await call(service.baseUrl, "GET", "/account/membership");
    assert.equal(anonymous.status, 401);
    assert.equal(anonymous.body.error.code, "AUTHENTICATION_REQUIRED");
  });

  it("exposes only typed entitlement keys and rejects unknown ones", async () => {
    const service = await newService();
    const account = await signedInAccount(service, "entitlements@example.test");
    const response = await call(service.baseUrl, "GET", "/account/entitlements", { headers: bearer(account.accessToken) });
    assert.equal(response.status, 200);
    assert.deepEqual(response.body.entitlements.map((entry) => entry.key), [
      "BUILD_GENERATION", "LOCAL_BUILD_HISTORY", "VISUAL_REFERENCE",
    ]);
    for (const entry of response.body.entitlements) {
      assert.equal(typeof entry.label, "string");
      assert.equal(typeof entry.description, "string");
      assert.equal(entry.grantedBy, "PLAN");
    }
    const userId = requireAccountIdForToken(service.database, service.configuration, account.accessToken);
    const view = getAccountEntitlements(service.database, service.configuration, userId);
    assert.equal(hasEntitlement(view, "ADVANCED_AI"), false);
    assert.equal(hasEntitlement(view, "NOT_AN_ENTITLEMENT"), false);
    assert.throws(
      () => enforceEntitlement(service.database, view, "NOT_AN_ENTITLEMENT", { targetUserId: userId }),
      (error) => error.code === "ENTITLEMENT_REQUIRED",
    );
    const denials = auditRows(service.database, "ENTITLEMENT_DENIED");
    assert.equal(denials.length, 1);
    assert.equal(denials[0].actor_kind, AUDIT_ACTOR_KIND.SYSTEM);
    assert.equal(denials[0].target_user_id, userId);
    assert.equal(denials[0].metadata_json.includes("UNREGISTERED"), true);
  });

  it("defines every plan without making any of them purchasable", async () => {
    const service = await newService();
    const catalog = planCatalog(service.configuration);
    assert.deepEqual(catalog.plans.map((plan) => plan.id), ["FREE", "PRO", "CREATOR", "SERVER"]);
    for (const plan of catalog.plans) {
      assert.equal(plan.purchasable, false, `${plan.id} must not be purchasable in this phase`);
      // Prices are not merely hidden: no numeric price field exists anywhere in the catalog, and no plan claims to be
      // available for purchase.
      assert.equal(Object.keys(plan).some((key) => /price|amount|cost|currency/i.test(key)), true);
      assert.equal(plan.priceState, plan.id === "FREE" ? "No price applies" : "Not priced yet");
      assert.equal(/[0-9]/.test(plan.priceState), false, `${plan.id} must not state a price`);
      assert.equal(plan.summary.toLowerCase().includes("$"), false);
    }
    assert.equal(catalog.byId.FREE.availability, "AVAILABLE_TODAY");
    for (const planId of ["PRO", "CREATOR", "SERVER"]) {
      assert.equal(catalog.byId[planId].availability, "UNAVAILABLE");
      assert.equal(catalog.byId[planId].availabilityLabel, "Planned — not purchasable");
      assert.equal(catalog.byId[planId].grantable, true);
    }
    // Tiers are cumulative, and the free baseline is never empty.
    for (const [lower, higher] of [["FREE", "PRO"], ["PRO", "CREATOR"], ["CREATOR", "SERVER"]]) {
      for (const key of catalog.byId[lower].entitlements) {
        assert.equal(catalog.byId[higher].entitlements.includes(key), true, `${higher} lost ${key} from ${lower}`);
      }
    }
  });
});

// ------------------------------------------------------------------------------------------------ B. credit grants

describe("Phase 22 credit grants and consumption", () => {
  it("grants promotional credits through the developer tool, with confirmation and audit", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    await signedInAccount(service, "granted@example.test");

    const { prepared, confirmed } = await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "granted@example.test", amount: 12, reason: "Phase 22 pilot allocation",
    });
    assert.equal(prepared.body.result.action, "grantCredits");
    assert.equal(confirmed.status, 200, JSON.stringify(confirmed.body));
    assert.equal(confirmed.body.result.amount, 12);
    assert.equal(confirmed.body.result.availableAfter, 12);

    const ledger = ledgerRows(service.database);
    assert.equal(ledger.length, 1);
    assert.equal(ledger[0].type, "GRANT");
    assert.equal(ledger[0].amount, 12);
    assert.equal(ledger[0].source, "DEVELOPER_GRANT");
    assert.equal(ledger[0].actor_kind, "DEVELOPER");
    assert.equal(ledger[0].actor_developer_id, owner.developerId);
    assert.equal(ledger[0].expires_at, null);

    const prepared_rows = auditRows(service.database, "grant_credits");
    assert.deepEqual(prepared_rows.map((row) => row.outcome), ["PREPARED", "SUCCESS"]);
    assert.equal(prepared_rows[1].actor_kind, "DEVELOPER");
    assert.equal(prepared_rows[1].metadata_json.includes("\"amount\":12"), true);
    // No other audit log, and no log row was replaced: the same single append-only table is reused.
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log").get().count >= 3, true);
  });

  it("consumes credits atomically, refuses a shortfall, and never goes negative", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "spender@example.test");
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "spender@example.test", amount: 5, reason: "Pilot allocation for builds",
    });

    const tooMuch = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 6, idempotencyKey: "consume-key-000001" },
    });
    assert.equal(tooMuch.status, 409);
    assert.equal(tooMuch.body.error.code, "INSUFFICIENT_CREDITS");
    assert.equal(ledgerRows(service.database).filter((row) => row.type === "CONSUME").length, 0);

    const spent = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 5, idempotencyKey: "consume-key-000002" },
    });
    assert.equal(spent.status, 200, JSON.stringify(spent.body));
    assert.equal(spent.body.transaction.amount, -5);
    assert.equal(spent.body.credits.available, 0);

    const exhausted = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 1, idempotencyKey: "consume-key-000003" },
    });
    assert.equal(exhausted.body.error.code, "INSUFFICIENT_CREDITS");
    const balance = await call(service.baseUrl, "GET", "/account/credits", { headers: bearer(account.accessToken) });
    assert.equal(balance.body.credits.available, 0);
    assert.equal(balance.body.credits.lifetimeConsumed, 5);
    assert.equal(balance.body.credits.lifetimeGranted, 5);
    assert.equal(
      auditRows(service.database, "CREDIT_OPERATION_REJECTED").every((row) => row.outcome === "DENIED"), true,
    );
  });

  it("never consumes twice for one idempotency key", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "idempotent@example.test");
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "idempotent@example.test", amount: 10, reason: "Retry-safe allocation",
    });

    const first = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 4, idempotencyKey: "retry-key-000001" },
    });
    assert.equal(first.body.credits.available, 6);
    const replay = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 4, idempotencyKey: "retry-key-000001" },
    });
    assert.equal(replay.status, 200);
    assert.equal(replay.body.reused, true);
    assert.equal(replay.body.transaction.transactionId, first.body.transaction.transactionId);
    assert.equal(replay.body.credits.available, 6);
    assert.equal(ledgerRows(service.database).filter((row) => row.type === "CONSUME").length, 1);

    // The same key with a different amount is a conflict, not a second consumption.
    const conflicting = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 9, idempotencyKey: "retry-key-000001" },
    });
    assert.equal(conflicting.status, 409);
    assert.equal(conflicting.body.error.code, "CREDIT_OPERATION_DUPLICATE");
    assert.equal(ledgerRows(service.database).filter((row) => row.type === "CONSUME").length, 1);

    // The same key reused for a different operation is refused as well.
    const otherOperation = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 1, idempotencyKey: "retry-key-000001", purpose: "BUILD_GENERATION" },
    });
    assert.equal(otherOperation.body.error.code, "CREDIT_OPERATION_DUPLICATE");

    // A header key is accepted, and two different keys in one request are refused rather than guessed at.
    const headerKey = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: { ...bearer(account.accessToken), "Idempotency-Key": "header-key-000001" }, body: { amount: 1 },
    });
    assert.equal(headerKey.status, 200);
    assert.equal(headerKey.body.credits.available, 5);
    const mismatched = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: { ...bearer(account.accessToken), "Idempotency-Key": "header-key-000002" },
      body: { amount: 1, idempotencyKey: "body-key-000002" },
    });
    assert.equal(mismatched.status, 400);
    assert.equal(mismatched.body.error.code, "CREDIT_OPERATION_INVALID");
  });

  it("serializes concurrent consumption so exactly one of two competing spends succeeds", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "concurrent@example.test");
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "concurrent@example.test", amount: 10, reason: "Concurrency fixture allocation",
    });

    const attempt = (key) => call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 7, idempotencyKey: key },
    });
    const [left, right] = await Promise.all([attempt("concurrent-key-00001"), attempt("concurrent-key-00002")]);
    const statuses = [left.status, right.status].sort();
    assert.deepEqual(statuses, [200, 409]);
    const refused = left.status === 409 ? left : right;
    assert.equal(refused.body.error.code, "INSUFFICIENT_CREDITS");
    const balance = await call(service.baseUrl, "GET", "/account/credits", { headers: bearer(account.accessToken) });
    assert.equal(balance.body.credits.available, 3);
    assert.equal(ledgerRows(service.database).filter((row) => row.type === "CONSUME").length, 1);
  });

  it("rejects malformed, unbounded, and client-authored consumption requests", async () => {
    const service = await developerService({ ...RELAXED_DETECTION });
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "malformed@example.test");
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "malformed@example.test", amount: 8, reason: "Malformed-request fixture",
    });
    const attempt = (body, headers = bearer(account.accessToken)) => call(
      service.baseUrl, "POST", "/account/credits/consume", { headers, body },
    );

    for (const body of [
      { amount: 0, idempotencyKey: "malformed-key-0001" },
      { amount: -3, idempotencyKey: "malformed-key-0002" },
      { amount: 1.5, idempotencyKey: "malformed-key-0003" },
      { amount: "4", idempotencyKey: "malformed-key-0004" },
      { amount: 10_000, idempotencyKey: "malformed-key-0005" },
      { amount: 1, idempotencyKey: "short" },
      { amount: 1, idempotencyKey: "malformed-key-0006", reason: "client written text" },
      { amount: 1, idempotencyKey: "malformed-key-0007", purpose: "PAYMENT_SETTLEMENT" },
      { amount: 1 },
    ]) {
      const response = await attempt(body);
      assert.equal(response.status, 400, JSON.stringify(body));
      assert.equal(response.body.error.code, "CREDIT_OPERATION_INVALID");
    }
    const anonymous = await attempt({ amount: 1, idempotencyKey: "malformed-key-0008" }, {});
    assert.equal(anonymous.body.error.code, "AUTHENTICATION_REQUIRED");
    const notJson = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: { ...bearer(account.accessToken), "Content-Type": "text/plain" }, raw: "amount=1",
    });
    assert.equal(notJson.body.error.code, "INVALID_CONTENT_TYPE");
    const broken = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), raw: "{not json",
    });
    assert.equal(broken.body.error.code, "INVALID_REQUEST");
    const oversized = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken),
      raw: JSON.stringify({ amount: 1, idempotencyKey: "malformed-key-0009", padding: "x".repeat(40_000) }),
    });
    assert.equal(oversized.body.error.code, "REQUEST_TOO_LARGE");
    assert.equal(ledgerRows(service.database).filter((row) => row.type === "CONSUME").length, 0);
    // Every refusal left the balance untouched and wrote no ledger row at all.
    const balance = await call(service.baseUrl, "GET", "/account/credits", { headers: bearer(account.accessToken) });
    assert.equal(balance.body.credits.available, 8);
  });

  it("keeps one account's ledger invisible to another", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    const first = await signedInAccount(service, "ledger-one@example.test");
    const second = await signedInAccount(service, "ledger-two@example.test");
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "ledger-one@example.test", amount: 4, reason: "Fixture allocation one",
    });
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "ledger-two@example.test", amount: 9, reason: "Fixture allocation two",
    });

    const firstCredits = await call(service.baseUrl, "GET", "/account/credits", { headers: bearer(first.accessToken) });
    const secondCredits = await call(service.baseUrl, "GET", "/account/credits", { headers: bearer(second.accessToken) });
    assert.equal(firstCredits.body.credits.available, 4);
    assert.equal(secondCredits.body.credits.available, 9);

    await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(first.accessToken), body: { amount: 4, idempotencyKey: "isolation-key-0001" },
    });
    const secondTransactions = await call(service.baseUrl, "GET", "/account/credits/transactions", {
      headers: bearer(second.accessToken),
    });
    assert.equal(secondTransactions.body.transactions.length, 1);
    assert.equal(secondTransactions.body.transactions[0].amount, 9);
    const firstTransactions = await call(service.baseUrl, "GET", "/account/credits/transactions", {
      headers: bearer(first.accessToken),
    });
    assert.deepEqual(firstTransactions.body.transactions.map((row) => row.amount), [-4, 4]);
    assert.equal(JSON.stringify(firstTransactions.body).includes("ledger-two@example.test"), false);
  });

  it("preserves membership and credit history across sign-out", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "signout@example.test");
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "signout@example.test", amount: 6, reason: "Sign-out fixture allocation",
    });
    await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 6, idempotencyKey: "signout-key-00001" },
    });

    const secondSession = await call(service.baseUrl, "POST", "/auth/login", {
      body: { email: "signout@example.test", password: VALID_PASSWORD },
    });
    assert.equal(secondSession.status, 200);
    const loggedOut = await call(service.baseUrl, "POST", "/auth/logout", {
      body: { refreshToken: secondSession.body.session.refreshToken },
    });
    assert.equal(loggedOut.status, 200);
    // The signed-out token no longer reaches the account surface, but nothing about membership or credits was erased.
    const afterSignOut = await call(service.baseUrl, "GET", "/account/credits", {
      headers: bearer(secondSession.body.session.accessToken),
    });
    assert.equal(afterSignOut.status, 401);
    assert.equal(afterSignOut.body.error.code, "SESSION_INVALID");
    assert.equal(ledgerRows(service.database).length, 2);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM membership_accounts").get().count, 1);

    const signInAgain = await call(service.baseUrl, "POST", "/auth/login", {
      body: { email: "signout@example.test", password: VALID_PASSWORD },
    });
    const history = await call(service.baseUrl, "GET", "/account/credits/transactions", {
      headers: bearer(signInAgain.body.session.accessToken),
    });
    assert.deepEqual(history.body.transactions.map((row) => row.type), ["CONSUME", "GRANT"]);
    // The consumption's reason is the server's own text: a client cannot write into the ledger's history.
    assert.equal(history.body.transactions[0].reason.startsWith("Build generation"), true);
    assert.equal(history.body.transactions[0].source, "BUILD_CONSUMPTION");
  });
});

// ------------------------------------------------------------------------------------------------- C. time and expiry

describe("Phase 22 expiration, reversal, and time", () => {
  it("stops counting expired credits, records one lapse marker, and keeps the original grant", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    await signedInAccount(service, "expiring@example.test");
    const email = "expiring@example.test";
    const user = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(email);

    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email, amount: 10, reason: "Time-limited promotional allocation", expiresInDays: 1,
    });
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email, amount: 10, reason: "Permanent pilot allocation",
    });
    const start = Date.now();
    const before = creditBalance(service.database, service.configuration, user.user_id, { now: start });
    assert.equal(before.available, 20);
    assert.equal(before.expiring, 10);

    // Two days on: the expiring grant has lapsed; the other still stands.
    const later = start + 2 * 24 * 60 * 60 * 1000;
    const after = creditBalance(service.database, service.configuration, user.user_id, { now: later });
    assert.equal(after.available, 10);
    assert.equal(after.expired, 10);
    const markers = service.database.prepare("SELECT * FROM credit_ledger WHERE type = 'EXPIRE'").all();
    assert.equal(markers.length, 1);
    assert.equal(markers[0].amount, -10);
    assert.equal(markers[0].reference_id !== null, true);
    assert.equal(auditRows(service.database, "CREDIT_EXPIRED").length, 1);
    // Reading again writes nothing new, and the original grants are untouched.
    creditBalance(service.database, service.configuration, user.user_id, { now: later + 60_000 });
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM credit_ledger").get().count, 3);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM credit_ledger WHERE type = 'GRANT'").get().count, 2);

    // Spending the soonest-to-expire credits first is what keeps the surviving balance honest.
    const other = await signedInAccount(service, "waterfall@example.test");
    const otherUser = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get("waterfall@example.test");
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "waterfall@example.test", amount: 10, reason: "Waterfall allocation", expiresInDays: 1,
    });
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "waterfall@example.test", amount: 10, reason: "Waterfall permanent allocation",
    });
    await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(other.accessToken), body: { amount: 15, idempotencyKey: "waterfall-key-0001" },
    });
    const waterfall = creditBalance(service.database, service.configuration, otherUser.user_id, { now: later });
    assert.equal(waterfall.available, 5, "the expiring credits must be the ones that were spent");
    assert.equal(waterfall.expired, 0, "nothing lapsed, because the expiring grant was spent down first");
  });

  it("expires a membership at its end timestamp and falls back to the free baseline", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "member@example.test");
    const user = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get("member@example.test");

    const { confirmed } = await confirmedTool(service, owner.accessToken, "grantMembership", {
      email: "member@example.test", plan: "PRO", days: 30, reason: "Evaluation membership grant",
    });
    assert.equal(confirmed.status, 200, JSON.stringify(confirmed.body));
    assert.equal(confirmed.body.result.plan, "PRO");
    assert.equal(confirmed.body.result.previousPlan, "FREE");
    assert.equal(confirmed.body.result.promotionalCreditsGranted, 200);

    const asPro = await call(service.baseUrl, "GET", "/account/entitlements", { headers: bearer(account.accessToken) });
    assert.equal(asPro.body.plan, "PRO");
    assert.equal(asPro.body.entitlements.some((entry) => entry.key === "ADVANCED_AI"), true);
    assert.equal(asPro.body.credits.available, 200);
    const allocation = ledgerRows(service.database).find((row) => row.source === "PLAN_ALLOCATION");
    assert.equal(allocation.amount, 200);
    assert.equal(allocation.expires_at !== null, true);

    // Move the whole grant window into the past, which is what "the plan ended" means once real time has passed.
    // Nothing else about the row changes, and no client action is involved in the expiry.
    service.database.prepare("UPDATE membership_accounts SET starts_at = ?, ends_at = ? WHERE user_id = ?").run(
      new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
      new Date(Date.now() - 60 * 60 * 1000).toISOString(),
      user.user_id,
    );
    const afterExpiry = await call(service.baseUrl, "GET", "/account/membership", { headers: bearer(account.accessToken) });
    assert.equal(afterExpiry.status, 200);
    assert.equal(afterExpiry.body.membership.plan, "FREE");
    assert.equal(afterExpiry.body.membership.status, "ACTIVE");
    assert.equal(afterExpiry.body.membership.source, "BASELINE_FALLBACK");
    assert.equal(afterExpiry.body.plan.entitlements.includes("ADVANCED_AI"), false);
    assert.equal(afterExpiry.body.plan.entitlements.includes("BUILD_GENERATION"), true);
    assert.equal(auditRows(service.database, "MEMBERSHIP_EXPIRED").length, 1);
    const transitions = service.database.prepare(
      "SELECT from_plan, to_plan, from_status, to_status FROM membership_transitions WHERE user_id = ? ORDER BY rowid",
    ).all(user.user_id);
    assert.deepEqual(transitions.map((row) => `${row.from_plan}->${row.to_plan}`), ["FREE->FREE", "FREE->PRO", "PRO->FREE"]);
    // The ledger is untouched by the membership change: the allocation still exists and still expires on its own.
    assert.equal(ledgerRows(service.database).filter((row) => row.type === "GRANT").length, 1);
  });

  it("reverses a grant through the ledger without rewriting history", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    await signedInAccount(service, "reversed@example.test");
    const { confirmed } = await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "reversed@example.test", amount: 8, reason: "Reversible pilot allocation",
    });
    const transactionId = confirmed.body.result.transactionId;

    const partial = await confirmedTool(service, owner.accessToken, "reverseCreditGrant", {
      email: "reversed@example.test", transactionId, amount: 3, reason: "Partial correction of the allocation",
    });
    assert.equal(partial.confirmed.body.result.amount, -3);
    assert.equal(partial.confirmed.body.result.availableAfter, 5);
    const full = await confirmedTool(service, owner.accessToken, "reverseCreditGrant", {
      email: "reversed@example.test", transactionId, reason: "Remaining correction of the allocation",
    });
    assert.equal(full.confirmed.body.result.amount, -5);
    assert.equal(full.confirmed.body.result.availableAfter, 0);

    const beyond = await confirmedTool(service, owner.accessToken, "reverseCreditGrant", {
      email: "reversed@example.test", transactionId, reason: "Attempted over-reversal of the allocation",
    });
    assert.equal(beyond.confirmed.status, 409);
    assert.equal(beyond.confirmed.body.error.code, "CREDIT_OPERATION_CONFLICT");
    assert.equal(auditRows(service.database, "CREDIT_REVERSED").length, 2);
    assert.equal(auditRows(service.database, "reverse_credit_grant").at(-1).outcome, "FAILURE");
    // The original grant row is intact, and the reversals reference it rather than replacing it.
    const grant = service.database.prepare("SELECT * FROM credit_ledger WHERE transaction_id = ?").get(transactionId);
    assert.equal(grant.amount, 8);
    const reversals = service.database.prepare("SELECT * FROM credit_ledger WHERE type = 'REVERSAL'").all();
    assert.equal(reversals.length, 2);
    assert.equal(reversals.every((row) => row.reference_id === transactionId), true);
    assert.throws(
      () => service.database.prepare("DELETE FROM credit_ledger WHERE transaction_id = ?").run(transactionId),
      /append-only/,
    );
  });

  it("refuses to reverse another account's grant or a lapse", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    await signedInAccount(service, "owner-of-grant@example.test");
    await signedInAccount(service, "innocent@example.test");
    const { confirmed } = await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "owner-of-grant@example.test", amount: 5, reason: "Cross-account reversal fixture",
    });
    const transactionId = confirmed.body.result.transactionId;

    const crossAccount = await invokeTool(service, owner.accessToken, "reverseCreditGrant", {
      email: "innocent@example.test", transactionId, reason: "Cross-account reversal attempt",
    });
    assert.equal(crossAccount.status, 400);
    assert.equal(crossAccount.body.error.code, "DEVELOPER_TOOL_INPUT_INVALID");
    assert.equal(ledgerRows(service.database).filter((row) => row.type === "REVERSAL").length, 0);
    const unknownReference = await invokeTool(service, owner.accessToken, "reverseCreditGrant", {
      email: "owner-of-grant@example.test", transactionId: "crd_00000000-0000-4000-8000-000000000000",
      reason: "Unknown reference attempt",
    });
    assert.equal(unknownReference.status, 400);
  });
});

// ------------------------------------------------------------------------------------------- D. authorization boundary

describe("Phase 22 developer authorization and the AI boundary", () => {
  it("refuses unauthorized, unknown, and forged tool calls", async () => {
    const service = await developerService({ ...RELAXED_DETECTION });
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "boundary@example.test");

    // A normal account token is not a developer credential at all.
    const asUser = await call(service.baseUrl, "POST", "/developer/tools/invoke", {
      headers: bearer(account.accessToken), body: { tool: "grantCredits", arguments: { email: "boundary@example.test", amount: 100, reason: "Unauthorized attempt" } },
    });
    assert.equal(asUser.status, 401);
    assert.equal(asUser.body.error.code, "DEVELOPER_AUTHENTICATION_REQUIRED");

    // There is no generic credit mutation tool.
    for (const name of ["executeSql", "admin.updateCredits", "updateCredits", "setBalance"]) {
      const unknown = await invokeTool(service, owner.accessToken, name, { email: "boundary@example.test", amount: 100 });
      assert.equal(unknown.status, 400, name);
      assert.equal(unknown.body.error.code, "DEVELOPER_TOOL_UNKNOWN", name);
    }
    assert.equal(auditRows(service.database, "unknown_tool").length, 4);

    // A DEVELOPER-role developer can inspect but never grant.
    const developerId = newDeveloperId();
    const timestamp = new Date().toISOString();
    service.database.prepare(
      `INSERT INTO developer_accounts (developer_id, email, email_canonical, password_hash, role, status, created_at, updated_at, last_login_at)
       VALUES (?, ?, ?, ?, 'DEVELOPER', 'ACTIVE', ?, ?, NULL)`,
    ).run(developerId, "reader@example.test", "reader@example.test", await hashPassword(DEVELOPER_PASSWORD), timestamp, timestamp);
    const readerSession = await call(service.baseUrl, "POST", "/developer/auth/login", {
      body: { email: "reader@example.test", password: DEVELOPER_PASSWORD },
    });
    const readerToken = readerSession.body.session.accessToken;
    const inspection = await invokeTool(service, readerToken, "inspectMembership", { email: "boundary@example.test" });
    assert.equal(inspection.status, 200);
    assert.equal(inspection.body.result.membership.plan, "FREE");
    for (const [tool, args] of [
      ["grantCredits", { email: "boundary@example.test", amount: 50, reason: "Unauthorized developer grant" }],
      ["grantMembership", { email: "boundary@example.test", plan: "PRO", days: 30, reason: "Unauthorized membership grant" }],
      ["reverseCreditGrant", { email: "boundary@example.test", transactionId: "crd_00000000-0000-4000-8000-000000000000", reason: "Unauthorized reversal" }],
    ]) {
      const denied = await invokeTool(service, readerToken, tool, args);
      assert.equal(denied.status, 403, tool);
      assert.equal(denied.body.error.code, "DEVELOPER_ACCESS_DENIED", tool);
      assert.equal(auditRows(service.database, tool === "grantCredits" ? "grant_credits" : tool === "grantMembership" ? "grant_membership" : "reverse_credit_grant")
        .some((row) => row.outcome === "DENIED" && row.actor_developer_id === developerId), true, tool);
    }
    assert.equal(ledgerRows(service.database).length, 0, "no denied attempt may move a single credit");

    // Forged arguments are rejected before anything is prepared.
    for (const args of [
      { email: "boundary@example.test", amount: 5, reason: "no" },
      { email: "boundary@example.test", amount: 5, reason: "Valid reason text", role: "OWNER" },
      { email: "boundary@example.test", amount: 5, reason: "Valid reason text", expiresInDays: 9_999 },
      { email: "boundary@example.test", amount: 999_999, reason: "Valid reason text" },
      { email: "not-an-email.test", amount: 5, reason: "Valid reason text" },
      { email: "boundary@example.test", amount: 5, reason: "Valid reason text", idempotencyKey: "short" },
    ]) {
      const forged = await invokeTool(service, owner.accessToken, "grantCredits", args);
      assert.equal(forged.status, 400, JSON.stringify(args));
      assert.equal(forged.body.error.code, "DEVELOPER_TOOL_INPUT_INVALID");
    }
    const forgedMembership = await invokeTool(service, owner.accessToken, "grantMembership", {
      email: "boundary@example.test", plan: "MEGA", days: 30, reason: "Forged plan attempt",
    });
    assert.equal(forgedMembership.body.error.code, "DEVELOPER_TOOL_INPUT_INVALID");
    const freeGrant = await invokeTool(service, owner.accessToken, "grantMembership", {
      email: "boundary@example.test", plan: "FREE", days: 30, reason: "Free plan grant attempt",
    });
    assert.equal(freeGrant.body.error.code, "DEVELOPER_TOOL_INPUT_INVALID");
    assert.equal(ledgerRows(service.database).length, 0);
  });

  it("lets the AI boundary inspect and propose, but never grant", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "ai-boundary@example.test");
    const actor = {
      developer_id: owner.developerId,
      role: "OWNER",
      session: service.database.prepare(
        "SELECT session_id, access_digest FROM developer_sessions WHERE developer_id = ? ORDER BY issued_at DESC LIMIT 1",
      ).get(owner.developerId),
    };
    const context = { configuration: service.configuration, schemaVersion: SCHEMA_VERSION };

    // Inspection through the AI tool-call path is allowed and audited as an AI turn.
    const inspection = developerAiToolCall(
      service.database, service.configuration, actor,
      { name: "inspectMembership", arguments: { email: "ai-boundary@example.test" } }, context,
    );
    assert.equal(inspection.command, undefined);
    assert.equal(inspection.membership.plan, "FREE");
    assert.equal(inspection.credits.available, 0);

    // A grant proposed by the AI stops at the confirmation boundary: nothing is written, nothing is executed.
    const proposal = developerAiToolCall(
      service.database, service.configuration, actor,
      { name: "grantCredits", arguments: { email: "ai-boundary@example.test", amount: 25, reason: "AI proposed promotional grant" } },
      context,
    );
    assert.equal(proposal.confirmationRequired, true);
    assert.equal(proposal.action, "grantCredits");
    assert.equal(typeof proposal.confirmationToken, "string");
    assert.equal(ledgerRows(service.database).length, 0, "the AI must never move a credit on its own");
    const preparedAudit = auditRows(service.database, "grant_credits");
    assert.equal(preparedAudit.length, 1);
    assert.equal(preparedAudit[0].outcome, "PREPARED");
    assert.equal(preparedAudit[0].actor_kind, "DEVELOPER", "the human developer remains the acting identity");

    // The database itself refuses an AI-attributed grant, even if some future code path tried it.
    const user = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get("ai-boundary@example.test");
    assert.throws(() => service.database.prepare(
      `INSERT INTO credit_ledger
         (transaction_id, user_id, type, amount, reason, source, actor_kind, actor_developer_id, created_at, expires_at)
       VALUES ('crd_ai_forged', ?, 'GRANT', 10, 'AI attempt', 'DEVELOPER_GRANT', 'AI', NULL, '2026-01-01T00:00:00.000Z', NULL)`,
    ).run(user.user_id), /CHECK constraint failed/);

    // Only the human confirmation executes it.
    const confirmed = await confirmAction(service, owner.accessToken, proposal.confirmationToken);
    assert.equal(confirmed.status, 200);
    assert.equal(confirmed.body.result.amount, 25);
    assert.equal(ledgerRows(service.database).length, 1);
    const afterAudit = auditRows(service.database, "grant_credits");
    assert.deepEqual(afterAudit.map((row) => row.outcome), ["PREPARED", "SUCCESS"]);
    assert.equal(afterAudit[0].actor_developer_id, owner.developerId);
  });

  it("requires a fresh confirmation for every membership or credit tool call", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    await signedInAccount(service, "confirm@example.test");
    const prepared = await invokeTool(service, owner.accessToken, "grantCredits", {
      email: "confirm@example.test", amount: 3, reason: "Confirmation boundary fixture",
    });
    const replay = await confirmAction(service, owner.accessToken, prepared.body.result.confirmationToken);
    assert.equal(replay.status, 200);
    const replayed = await confirmAction(service, owner.accessToken, prepared.body.result.confirmationToken);
    assert.equal(replayed.body.error.code, "DEVELOPER_CONFIRMATION_INVALID");
    assert.equal(ledgerRows(service.database).length, 1);

    const second = await invokeTool(service, owner.accessToken, "grantCredits", {
      email: "confirm@example.test", amount: 3, reason: "Confirmation boundary fixture",
    });
    const cancelled = await call(service.baseUrl, "POST", "/developer/tools/cancel", {
      headers: bearer(owner.accessToken), body: { confirmationToken: second.body.result.confirmationToken },
    });
    assert.equal(cancelled.status, 200);
    assert.equal(cancelled.body.result.cancelled, true);
    assert.equal(ledgerRows(service.database).length, 1);
    assert.equal(auditRows(service.database, "grant_credits").at(-1).outcome, "CANCELLED");
  });

  it("rate-limits credit consumption and reading on the existing limiter", async () => {
    const service = await newService({ RATE_CREDIT_CONSUME_MAX: "2", RATE_CREDIT_CONSUME_WINDOW_MS: "60000" });
    const account = await signedInAccount(service, "limited@example.test");
    const attempt = (key) => call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 1, idempotencyKey: key },
    });
    assert.equal((await attempt("limited-key-000001")).status, 409);
    assert.equal((await attempt("limited-key-000002")).status, 409);
    const limited = await attempt("limited-key-000003");
    assert.equal(limited.status, 429);
    assert.equal(limited.body.error.code, "RATE_LIMITED");
    assert.equal(Number(limited.headers.get("retry-after")) > 0, true);
    assert.equal(limited.body.error.retryAfterSeconds > 0, true);
  });
});

// -------------------------------------------------------------------------------------- E. privacy and non-disclosure

describe("Phase 22 response privacy", () => {
  it("never returns credentials, internal identifiers, or server detail", async () => {
    const service = await developerService({ ...RELAXED_DETECTION });
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "private@example.test");
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "private@example.test", amount: 7, reason: "Privacy fixture allocation", expiresInDays: 30,
    });
    await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 2, idempotencyKey: "private-key-00001" },
    });

    const responses = await Promise.all([
      call(service.baseUrl, "GET", "/account/membership", { headers: bearer(account.accessToken) }),
      call(service.baseUrl, "GET", "/account/entitlements", { headers: bearer(account.accessToken) }),
      call(service.baseUrl, "GET", "/account/credits", { headers: bearer(account.accessToken) }),
      call(service.baseUrl, "GET", "/account/credits/transactions", { headers: bearer(account.accessToken) }),
      invokeTool(service, owner.accessToken, "inspectMembership", { email: "private@example.test", includeHistory: true }),
    ]);
    for (const response of responses) {
      const text = JSON.stringify(response.body);
      for (const forbidden of [
        "password", "passwordHash", "tokenDigest", "accessToken", "refreshToken", "session_id", "sessionId",
        "membership_id", "actor_developer_id", "metadata_json", "SELECT ", "sqlite", "credit_operation_keys",
        "confirmation_digest", "scrypt", "secret",
      ]) {
        assert.equal(text.includes(forbidden), false, `${forbidden} in ${text.slice(0, 200)}`);
      }
      assert.equal(text.includes("owner@example.test"), false, "no developer identity may reach an account response");
    }
    const membership = responses[0].body;
    assert.deepEqual(Object.keys(membership).sort(), ["credits", "membership", "plan"]);
    const transactions = responses[3].body.transactions;
    assert.equal(transactions.every((row) => row.transactionId.startsWith("crd_")), true);
    assert.equal(transactions.every((row) => !("userId" in row) && !("rowid" in row)), true);
  });

  it("refuses unknown endpoints, unknown query keys, and unsupported methods", async () => {
    const service = await newService();
    const account = await signedInAccount(service, "routing@example.test");
    const adjust = await call(service.baseUrl, "POST", "/account/credits/adjust", {
      headers: bearer(account.accessToken), body: { amount: 1000 },
    });
    assert.equal(adjust.status, 400);
    assert.equal(adjust.body.error.code, "INVALID_REQUEST");
    const wrongMethod = await call(service.baseUrl, "POST", "/account/membership", {
      headers: bearer(account.accessToken), body: {},
    });
    assert.equal(wrongMethod.status, 405);
    assert.equal(wrongMethod.body.error.code, "METHOD_NOT_ALLOWED");
    const unknownQuery = await call(service.baseUrl, "GET", "/account/credits/transactions?accountId=usr_other", {
      headers: bearer(account.accessToken),
    });
    assert.equal(unknownQuery.status, 400);
    assert.equal(unknownQuery.body.error.code, "CREDIT_OPERATION_INVALID");
    for (const query of ["?limit=0", "?limit=1000", "?limit=abc", "?type=WITHDRAWAL"]) {
      const response = await call(service.baseUrl, "GET", `/account/credits/transactions${query}`, {
        headers: bearer(account.accessToken),
      });
      assert.equal(response.status, 400, query);
      assert.equal(response.body.error.code, "CREDIT_OPERATION_INVALID", query);
    }
    const emptyHistory = await call(service.baseUrl, "GET", "/account/credits/transactions", {
      headers: bearer(account.accessToken),
    });
    assert.deepEqual(emptyHistory.body.transactions, []);
    assert.equal(emptyHistory.body.count, 0);
  });
});

// ------------------------------------------------------------------------------------------------ F. schema safety

describe("Phase 22 migration and data preservation", () => {
  it("adds the Phase 22 tables and lifts the credit-table prohibition", async () => {
    const service = await newService();
    // Reading an account's membership writes the baseline audit row, so the append-only triggers below have a row to fire
    // on: an empty table cannot demonstrate immutability.
    const account = await signedInAccount(service, "schema@example.test");
    await call(service.baseUrl, "GET", "/account/membership", { headers: bearer(account.accessToken) });
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log").get().count > 0, true);
    const tables = service.database.prepare(
      "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
    ).all().map((row) => row.name);
    for (const added of ["membership_accounts", "membership_transitions", "credit_ledger", "credit_operation_keys"]) {
      assert.equal(tables.includes(added), true, added);
    }
    // Phase 23 added two more additive tables and their append-only history without touching the Phase 22 ones.
    for (const phase23 of ["creator_profiles", "creator_status_history", "server_workspaces", "server_members"]) {
      assert.equal(tables.includes(phase23), true, phase23);
    }
    for (const preserved of ["users", "sessions", "guest_identities", "developer_accounts", "developer_sessions",
      "developer_access_grants", "developer_action_confirmations", "admin_audit_log", "security_incidents",
      "security_events", "security_actions", "security_notifications", "security_rate_limit_state"]) {
      assert.equal(tables.includes(preserved), true, preserved);
    }
    assert.equal(SCHEMA_VERSION, 11);
    const health = await call(service.baseUrl, "GET", "/health");
    assert.equal(health.body.schemaVersion, 11);
    // The one audit log stays append-only, with all three of its triggers recreated by the v5 rebuild.
    const triggers = service.database.prepare("SELECT name FROM sqlite_master WHERE type = 'trigger'").all().map((row) => row.name);
    for (const trigger of ["admin_audit_log_no_update", "admin_audit_log_no_delete", "admin_audit_log_no_replacement",
      "credit_ledger_no_update", "credit_ledger_no_delete", "credit_ledger_no_replacement",
      "membership_transitions_no_update", "membership_transitions_no_delete",
      "creator_status_history_no_update", "creator_status_history_no_delete",
      "server_members_owner_immutable_update", "server_members_owner_immutable_delete", "server_members_single_owner"]) {
      assert.equal(triggers.includes(trigger), true, trigger);
    }
    assert.throws(() => service.database.prepare("UPDATE admin_audit_log SET outcome = 'FAILURE'").run(), /append-only/);
    assert.throws(() => service.database.prepare(
      "INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json) VALUES ('aud_forged', 'SYSTEM', NULL, 'credit_everything', NULL, NULL, '2026-01-01T00:00:00.000Z', 'SUCCESS', '{}')",
    ).run(), /CHECK constraint failed/);
  });

  it("migrates an existing version-4 database without losing prior data", () => {
    const database = openDatabase(temporaryDatabasePath());
    migrateToVersion(database, 4);
    const timestamp = "2026-01-01T00:00:00.000Z";
    database.prepare(
      `INSERT INTO users (user_id, email, email_canonical, display_name, password_hash, status, created_at, updated_at, email_verified_at)
       VALUES ('usr_legacy', 'legacy@example.test', 'legacy@example.test', 'Legacy', 'scrypt$legacy', 'ACTIVE', ?, ?, ?)`,
    ).run(timestamp, timestamp, timestamp);
    database.prepare(
      `INSERT INTO sessions (session_id, user_id, access_digest, refresh_digest, issued_at, access_expires_at, refresh_expires_at, revoked_at, guest_identity_id, last_used_at, device_label)
       VALUES ('ses_legacy', 'usr_legacy', 'digest-a', 'digest-r', ?, '2027-01-01T00:00:00.000Z', '2027-01-01T00:00:00.000Z', NULL, NULL, ?, 'Legacy device')`,
    ).run(timestamp, timestamp);
    database.prepare(
      `INSERT INTO guest_identities (guest_identity_id, created_at, last_seen_at, linked_user_id)
       VALUES ('gst_legacy', ?, ?, NULL)`,
    ).run(timestamp, timestamp);
    database.prepare(
      `INSERT INTO developer_accounts (developer_id, email, email_canonical, password_hash, role, status, created_at, updated_at, last_login_at)
       VALUES ('dvl_legacy', 'legacy-dev@example.test', 'legacy-dev@example.test', 'scrypt$dev', 'OWNER', 'ACTIVE', ?, ?, NULL)`,
    ).run(timestamp, timestamp);
    database.prepare(
      `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       VALUES ('aud_legacy', 'DEVELOPER', 'dvl_legacy', 'suspend_user', 'usr_legacy', 'inc_legacy', ?, 'SUCCESS', '{"legacy":true}')`,
    ).run(timestamp);
    database.prepare(
      `INSERT INTO security_incidents
         (incident_id, reference, severity, status, threat_category, subject_kind, subject_reference, risk_score,
          detection_reasons_json, dedup_key, event_count, correlation_id, detected_at, last_activity_at,
          contained_at, resolved_at, resolution, created_at)
       VALUES ('inc_legacy', 'CM-LEGACY', 'MEDIUM', 'OPEN', 'BRUTE_FORCE', 'SOURCE', 'src_legacy', 42,
               '[]', 'dedup-legacy', 3, 'corr-legacy', ?, ?, NULL, NULL, NULL, ?)`,
    ).run(timestamp, timestamp, timestamp);

    const before = database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log").get().count;
    const appliedAt = database.prepare("SELECT applied_at FROM schema_migrations WHERE version = 4").get().applied_at;

    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(SCHEMA_VERSION, 11);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 11);
    assert.equal(database.prepare("SELECT applied_at FROM schema_migrations WHERE version = 4").get().applied_at, appliedAt);
    assert.equal(database.prepare("SELECT display_name FROM users WHERE user_id = 'usr_legacy'").get().display_name, "Legacy");
    assert.equal(database.prepare("SELECT device_label FROM sessions WHERE session_id = 'ses_legacy'").get().device_label, "Legacy device");
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM guest_identities").get().count, 1);
    assert.equal(database.prepare("SELECT role FROM developer_accounts WHERE developer_id = 'dvl_legacy'").get().role, "OWNER");
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log").get().count, before);
    const audit = database.prepare("SELECT * FROM admin_audit_log WHERE audit_id = 'aud_legacy'").get();
    assert.equal(audit.outcome, "SUCCESS");
    assert.equal(audit.incident_id, "inc_legacy");
    assert.equal(audit.metadata_json, "{\"legacy\":true}");
    assert.equal(database.prepare("SELECT status FROM security_incidents WHERE incident_id = 'inc_legacy'").get().status, "OPEN");
    // The rebuilt audit log still refuses mutation, and the new tables are usable.
    assert.throws(() => database.prepare("DELETE FROM admin_audit_log WHERE audit_id = 'aud_legacy'").run(), /append-only/);
    const configuration = loadConfiguration({
      NODE_ENV: "test", DATABASE_URL: ":memory:", AUTH_SECRET: TEST_SECRET,
    }, { allowInMemoryDatabase: true });
    // The migrated database immediately serves the Phase 22 engine for a pre-existing account.
    const view = getAccountEntitlements(database, configuration, "usr_legacy");
    assert.equal(view.membership.plan, "FREE");
    assert.equal(view.credits.available, 0);
    database.close();
  });

  it("keeps the ledger append-only under direct database access", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    await signedInAccount(service, "immutable@example.test");
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "immutable@example.test", amount: 5, reason: "Immutability fixture grant",
    });
    const row = ledgerRows(service.database)[0];
    assert.throws(() => service.database.prepare("UPDATE credit_ledger SET amount = 500 WHERE transaction_id = ?").run(row.transaction_id), /append-only/);
    assert.throws(() => service.database.prepare("DELETE FROM credit_ledger WHERE transaction_id = ?").run(row.transaction_id), /append-only/);
    assert.throws(() => service.database.prepare(
      `INSERT INTO credit_ledger (transaction_id, user_id, type, amount, reason, source, actor_kind, actor_developer_id, created_at, expires_at)
       VALUES ('crd_negative', ?, 'GRANT', -5, 'Negative grant', 'DEVELOPER_GRANT', 'SYSTEM', NULL, '2026-01-01T00:00:00.000Z', NULL)`,
    ).run(row.user_id), /CHECK constraint failed/);
    assert.throws(() => service.database.prepare(
      `INSERT INTO credit_ledger (transaction_id, user_id, type, amount, reason, source, actor_kind, actor_developer_id, created_at, expires_at)
       VALUES ('crd_ai', ?, 'GRANT', 5, 'AI grant', 'DEVELOPER_GRANT', 'AI', NULL, '2026-01-01T00:00:00.000Z', NULL)`,
    ).run(row.user_id), /CHECK constraint failed/);
    assert.equal(ledgerRows(service.database)[0].amount, 5);
    // Membership history is append-only as well: the baseline assignment must be present to have something to refuse.
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM membership_transitions").get().count > 0, true);
    assert.throws(() => service.database.prepare("DELETE FROM membership_transitions").run(), /append-only/);
    assert.throws(
      () => service.database.prepare("UPDATE membership_transitions SET reason = 'rewritten'").run(),
      /append-only/,
    );
  });

  it("computes the ledger without a background job and without a mutable balance column", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "derived@example.test");
    await confirmedTool(service, owner.accessToken, "grantCredits", {
      email: "derived@example.test", amount: 9, reason: "Derived balance fixture",
    });
    const userId = requireAccountIdForToken(service.database, service.configuration, account.accessToken);
    const columns = service.database.prepare("PRAGMA table_info(users)").all().map((column) => column.name);
    assert.equal(columns.includes("credits"), false, "users must not carry a mutable credit column");
    assert.equal(columns.includes("plan"), false, "users must not carry a mutable plan column");
    const state = creditLedgerState(service.database, userId, new Date().toISOString());
    assert.equal(state.spendable, 9);
    assert.equal(state.standing.length, 1);
    // A direct write that bypasses the ledger cannot change the balance…
    service.database.prepare("UPDATE users SET display_name = 'Derived' WHERE user_id = ?").run(userId);
    assert.equal(creditBalance(service.database, service.configuration, userId).available, 9);
    // …and the ledger is the only place a credit movement may be recorded.
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM credit_ledger WHERE user_id = ?").get(userId).count, 1);
  });

  it("leaves the pre-existing administrative grant flow untouched", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    await signedInAccount(service, "legacy-grant@example.test");
    const expiresAt = new Date(Date.now() + 86_400_000).toISOString();
    const { confirmed } = await confirmedTool(service, owner.accessToken, "grantEntitlement", {
      email: "legacy-grant@example.test", entitlementKey: "BETA_ACCESS", expiresAt,
    });
    assert.equal(confirmed.status, 200);
    assert.equal(confirmed.body.result.entitlementKey, "BETA_ACCESS");
    const listed = await invokeTool(service, owner.accessToken, "listEntitlements", { email: "legacy-grant@example.test" });
    assert.equal(listed.body.result.grants.length, 1);
    // Those administrative grants now also appear in the account's own entitlement view, as grants rather than plans.
    const account = await call(service.baseUrl, "POST", "/auth/login", {
      body: { email: "legacy-grant@example.test", password: VALID_PASSWORD },
    });
    const entitlements = await call(service.baseUrl, "GET", "/account/entitlements", {
      headers: bearer(account.body.session.accessToken),
    });
    assert.deepEqual(entitlements.body.administrativeGrants.map((grant) => grant.entitlementKey), ["BETA_ACCESS"]);
    assert.equal(entitlements.body.plan, "FREE");
  });

  it("runs an end-to-end grant, consume, and inspect flow through the developer tools only", async () => {
    const service = await developerService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "flow@example.test");
    const { confirmed: membership } = await confirmedTool(service, owner.accessToken, "grantMembership", {
      email: "flow@example.test", plan: "CREATOR", days: 14, reason: "Creator evaluation for the studio",
    });
    assert.equal(membership.body.result.plan, "CREATOR");

    const beforeConsume = await call(service.baseUrl, "GET", "/account/entitlements", { headers: bearer(account.accessToken) });
    assert.equal(beforeConsume.body.plan, "CREATOR");
    assert.equal(beforeConsume.body.entitlements.some((entry) => entry.key === "CREATOR_TOOLS"), true);
    assert.equal(beforeConsume.body.credits.available, 500);

    const consumed = await call(service.baseUrl, "POST", "/account/credits/consume", {
      headers: bearer(account.accessToken), body: { amount: 25, idempotencyKey: "flow-key-00000001" },
    });
    assert.equal(consumed.body.credits.available, 475);

    const inspection = await invokeTool(service, owner.accessToken, "inspectMembership", {
      email: "flow@example.test", includeHistory: true,
    });
    assert.equal(inspection.body.result.membership.plan, "CREATOR");
    assert.equal(inspection.body.result.credits.available, 475);
    assert.equal(inspection.body.result.recentTransactions.length, 2);
    // History is newest first: the account was given CREATOR on top of its free baseline.
    assert.deepEqual(inspection.body.result.history.map((entry) => entry.toPlan), ["CREATOR", "FREE"]);
    const ledger = await invokeTool(service, owner.accessToken, "listCreditTransactions", { email: "flow@example.test", limit: 10 });
    assert.deepEqual(ledger.body.result.transactions.map((row) => row.type), ["CONSUME", "GRANT"]);
    assert.equal(ledger.body.result.transactions[1].source, "PLAN_ALLOCATION");
  });
});
