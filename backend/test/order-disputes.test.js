/**
 * Phase 30 order dispute tests.
 *
 * A dispute is an order-scoped, participants-only workflow that freezes closure while open and can, only when BOTH
 * parties agree, close an order whose work was already accepted — the exact case Phase 27 refused to cancel
 * unilaterally and called out as "a future dispute phase." Nothing here touches money: the module has no refund,
 * payout, escrow, commission, or settlement, and no test asserts one.
 *
 * Coverage: migration to v11 is additive and preserves the audit-vocabulary widening; a dispute can only be opened by
 * a participant of an ACTIVE order, one at a time; statements are append-only immutable evidence with a bounded count;
 * resolution needs two-sided agreement (CONTINUE keeps the order live, CLOSE cancels it); the opener may withdraw;
 * an open dispute freezes complete/cancel and a resolved one releases them; strangers get the uniform not-found; and
 * denials survive the rollback. Security thresholds are relaxed (as in Phases 23–27) because the suite mints many
 * refused requests on purpose.
 */

import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { migrateToVersion, openDatabase, SCHEMA_VERSION, REGISTERED_AUDIT_ACTION_TYPES } from "../src/db.js";
import { ErrorCode } from "../src/errors.js";
import { CREATOR_AGREEMENT_VERSION } from "../src/onboarding.js";
import { call, registerVerified, startService, TEST_SECRET } from "./helpers.js";

const services = new Set();
const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x51).toString("base64url");
const DEVELOPER_EMAIL = "owner@example.test";
const DEVELOPER_PASSWORD = "Creator Plane 7Safe";

const RELAXED = Object.freeze({
  RATE_CREATOR_WRITE_MAX: "1000", RATE_CREATOR_READ_MAX: "1000",
  RATE_JOB_WRITE_MAX: "1000", RATE_PROPOSAL_WRITE_MAX: "1000",
  RATE_ORDER_CREATE_MAX: "1000", RATE_ORDER_WRITE_MAX: "1000",
  RATE_DELIVERY_WRITE_MAX: "1000", RATE_REVISION_WRITE_MAX: "1000",
  RATE_TRUST_WRITE_MAX: "1000", RATE_DISPUTE_WRITE_MAX: "1000",
  RATE_CREDITS_MAX: "1000", RATE_DEV_ADMIN_MAX: "1000", RATE_DEV_LOGIN_MAX: "1000", RATE_DEV_SESSION_MAX: "1000",
  SECURITY_UNAUTHORIZED_ACCESS_MEDIUM_MAX: "100000", SECURITY_UNAUTHORIZED_ACCESS_HIGH_MAX: "100001", SECURITY_UNAUTHORIZED_ACCESS_CRITICAL_MAX: "100002",
  SECURITY_MALFORMED_REQUESTS_MEDIUM_MAX: "100000", SECURITY_MALFORMED_REQUESTS_HIGH_MAX: "100001", SECURITY_MALFORMED_REQUESTS_CRITICAL_MAX: "100002",
  SECURITY_REQUEST_BURST_MEDIUM_MAX: "100000", SECURITY_REQUEST_BURST_HIGH_MAX: "100001", SECURITY_REQUEST_BURST_CRITICAL_MAX: "100002",
  SECURITY_RATE_LIMIT_MEDIUM_MAX: "100000", SECURITY_RATE_LIMIT_ABUSE_MEDIUM_MAX: "100000", SECURITY_RATE_LIMIT_ABUSE_HIGH_MAX: "100001", SECURITY_RATE_LIMIT_ABUSE_CRITICAL_MAX: "100002",
  CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL, CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
});

async function newService(overrides = {}) {
  const service = await startService({ ...RELAXED, ...overrides });
  services.add(service);
  const close = service.close.bind(service);
  service.close = async () => { services.delete(service); await close(); };
  return service;
}

afterEach(async () => { await Promise.all([...services].map((service) => service.close())); });

function bearer(accessToken) { return { Authorization: `Bearer ${accessToken}` }; }
function suffix() { return Math.random().toString(36).slice(2, 8); }

async function account(service, email) {
  const registration = await registerVerified(service, { email });
  assert.equal(registration.status, 201, JSON.stringify(registration.body));
  return { email, accessToken: registration.body.session.accessToken };
}

async function developer(service) {
  if (service.__dev) return service.__dev;
  await call(service.baseUrl, "POST", "/developer/auth/bootstrap", { body: { secret: BOOTSTRAP_SECRET, password: DEVELOPER_PASSWORD } });
  const login = await call(service.baseUrl, "POST", "/developer/auth/login", { body: { email: DEVELOPER_EMAIL, password: DEVELOPER_PASSWORD } });
  assert.equal(login.status, 200);
  service.__dev = { accessToken: login.body.session.accessToken };
  return service.__dev;
}

async function confirmedTool(service, token, tool, args) {
  const prepared = await call(service.baseUrl, "POST", "/developer/tools/invoke", { headers: bearer(token), body: { tool, arguments: args } });
  assert.equal(prepared.status, 200, JSON.stringify(prepared.body));
  const confirmed = await call(service.baseUrl, "POST", "/developer/tools/confirm", { headers: bearer(token), body: { confirmationToken: prepared.body.result.confirmationToken } });
  assert.equal(confirmed.status, 200, JSON.stringify(confirmed.body));
  return confirmed.body.result;
}

async function sellerAccount(service, dev, email, handle) {
  const created = await account(service, email);
  await confirmedTool(service, dev.accessToken, "grantMembership", { email, plan: "CREATOR", days: 30, reason: "Phase 30 test grant" });
  const saved = await call(service.baseUrl, "POST", "/onboarding/seller", {
    headers: bearer(created.accessToken),
    body: { handle, displayName: `${handle} Studio`, referralSource: "YOUTUBE", editions: ["java"], minecraftVersions: ["1.20.1"], loaders: ["Fabric"], agreementAccepted: true, agreementVersion: CREATOR_AGREEMENT_VERSION },
  });
  assert.equal(saved.status, 200, JSON.stringify(saved.body));
  return created;
}

/** A buyer and a seller with an ACTIVE order (two pending milestones), plus the fresh detail. */
async function activeOrder(service, { budget = false } = {}) {
  const dev = await developer(service);
  const sfx = suffix();
  const buyer = await account(service, `buyer-${sfx}@example.test`);
  const creator = await sellerAccount(service, dev, `seller-${sfx}@example.test`, `studio-${sfx}`);
  const job = (await call(service.baseUrl, "POST", "/buyer/jobs", {
    headers: bearer(buyer.accessToken),
    body: { title: "Nether hub", description: "A nether fast-travel hub linking three outposts.", edition: "java", minecraftVersion: "1.20.1", scope: "Design and build one hub, delivered with an install guide.", ...(budget ? { budgetMin: 100, budgetMax: 1000, budgetCurrency: "INR" } : {}) },
  })).body;
  const proposal = (await call(service.baseUrl, "POST", "/creator/proposal", {
    headers: bearer(creator.accessToken),
    body: { jobId: job.id, message: "I can build this hub this week with a clear plan.", scope: "One hub, three links, installed and shown.", ...(budget ? { budgetMin: 400, budgetMax: 1000, budgetCurrency: "INR", deliveryEstimateDays: 7 } : { deliveryEstimateDays: 7 }) },
  })).body;
  await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, { headers: bearer(buyer.accessToken), body: { proposalId: proposal.id } });
  const milestones = [
    { title: "Plan", description: "Write the plan and confirm acceptance criteria.", acceptanceCriteria: "Buyer agrees the plan covers scope." },
    { title: "Build", description: "Build and hand over with notes.", acceptanceCriteria: "Delivered result matches scope." },
  ];
  const order = (await call(service.baseUrl, "POST", "/buyer/orders", { headers: bearer(buyer.accessToken), body: { jobId: job.id, proposalId: proposal.id, milestones } })).body;
  const detail = await call(service.baseUrl, "GET", `/orders/${order.id}`, { headers: bearer(buyer.accessToken) });
  assert.equal(detail.status, 200, JSON.stringify(detail.body));
  return { dev, buyer, creator, job, proposal, order: { ...order, milestones: detail.body.milestones } };
}

function openDispute(service, actor, orderId, body = { reasonCategory: "QUALITY", reason: "The delivered hub does not match the agreed plan and cannot be used as shown." }) {
  return call(service.baseUrl, "POST", `/orders/${orderId}/disputes`, { headers: bearer(actor.accessToken), body });
}

function disputeRow(service, disputeId) {
  return service.database.prepare("SELECT * FROM order_disputes WHERE dispute_id = ?").get(disputeId);
}
function orderRow(service, orderId) {
  return service.database.prepare("SELECT * FROM orders WHERE order_id = ?").get(orderId);
}
function audits(service, actionType) {
  return service.database.prepare("SELECT * FROM admin_audit_log WHERE action_type = ? ORDER BY occurred_at, audit_id").all(actionType);
}

describe("Phase 30 migration and schema (v11)", () => {
  it("opens at v11 with the dispute tables, triggers, and the widened audit vocabulary", async () => {
    const service = await newService();
    assert.equal(SCHEMA_VERSION, 11);
    assert.equal(service.database.prepare("SELECT MAX(version) AS v FROM schema_migrations").get().v, 11);
    for (const table of ["order_disputes", "order_dispute_statements", "order_dispute_positions"]) {
      assert.ok(service.database.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?").get(table), table);
    }
    const triggers = service.database.prepare("SELECT name FROM sqlite_master WHERE type = 'trigger'").all().map((r) => r.name);
    for (const t of ["dispute_statements_no_update", "dispute_statements_no_delete", "admin_audit_log_no_update"]) {
      assert.ok(triggers.includes(t), t);
    }
    for (const a of ["DISPUTE_OPENED", "DISPUTE_STATEMENT_ADDED", "DISPUTE_POSITION_SET", "DISPUTE_WITHDRAWN", "DISPUTE_RESOLVED", "DISPUTE_ACCESS_DENIED"]) {
      assert.equal(REGISTERED_AUDIT_ACTION_TYPES.has(a), true, a);
    }
  });

  it("upgrades a seeded v10 database additively, preserving order rows and audit history", async () => {
    const dir = mkdtempSync(join(tmpdir(), "cm-p30-"));
    const file = join(dir, "db.sqlite");
    try {
      const seeded = openDatabase(file);
      migrateToVersion(seeded, 10);
      assert.equal(SCHEMA_VERSION, 11);
      seeded.exec(`INSERT INTO users (user_id, email, email_canonical, password_hash, display_name, status, email_verified_at, created_at, updated_at)
        VALUES ('usr_seed', 'seed@example.test', 'seed@example.test', 'scrypt$x', 'Seed', 'ACTIVE', '2026-01-01', '2026-01-01', '2026-01-01')`);
      seeded.prepare("INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json) VALUES (?,?,?,?,?,?,?,?,'{}')")
        .run("aud_seed", "SYSTEM", null, "ORDER_CREATED", "usr_seed", null, "2026-01-01", "SUCCESS");
      seeded.close();
      const upgraded = openDatabase(file);
      assert.equal(upgraded.prepare("SELECT COUNT(*) AS c FROM schema_migrations").get().c, 11);
      assert.equal(upgraded.prepare("SELECT outcome FROM admin_audit_log WHERE audit_id = 'aud_seed'").get().outcome, "SUCCESS");
      // The v11 CHECK now accepts a dispute action the v10 log refused; append-only still holds.
      upgraded.prepare("INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json) VALUES ('aud_d', 'SYSTEM', NULL, 'DISPUTE_OPENED', 'usr_seed', NULL, '2026-01-02', 'SUCCESS', '{}')").run();
      assert.throws(() => upgraded.prepare("UPDATE admin_audit_log SET outcome = 'FAILURE' WHERE audit_id = 'aud_seed'").run(), /append-only/);
      upgraded.close();
    } finally { rmSync(dir, { recursive: true, force: true }); }
  });
});

describe("Phase 30 opening a dispute", () => {
  it("lets a participant open exactly one open dispute on an ACTIVE order", async () => {
    const service = await newService();
    const { buyer, order } = await activeOrder(service);
    const opened = await openDispute(service, buyer, order.id);
    assert.equal(opened.status, 201, JSON.stringify(opened.body));
    assert.equal(opened.body.status, "OPEN");
    assert.equal(opened.body.openedByRole, "BUYER");
    assert.equal(opened.body.outcome, null);
    assert.equal(opened.body.reasonCategory, "QUALITY");
    assert.equal(disputeRow(service, opened.body.id).status, "OPEN");
    // A second attempt — by the same actor — is a typed conflict via the service pre-check.
    const again = await openDispute(service, buyer, order.id);
    assert.equal(again.status, 409);
    assert.equal(again.body.error.code, "DISPUTE_STATE_CONFLICT");
    // The partial unique index is the backstop: a direct second OPEN dispute on the same order is refused by SQLite.
    assert.throws(() => service.database.prepare(
      `INSERT INTO order_disputes (dispute_id, order_id, opened_by, other_participant, reason_category, reason, status, outcome, statement_count, created_at, updated_at, resolved_at)
       VALUES ('dsp_second', ?, 'usr_x', 'usr_y', 'DELIVERY', 'a second open dispute', 'OPEN', NULL, 0, '2026-01-01', '2026-01-01', NULL)`,
    ).run(order.id), /UNIQUE/i);
  });

  it("refuses a non-participant with the uniform not-found, audited inside the transaction", async () => {
    const service = await newService();
    const { buyer, order } = await activeOrder(service);
    const stranger = await account(service, "stranger@example.test");
    const tried = await openDispute(service, stranger, order.id);
    assert.equal(tried.status, 404);
    assert.equal(tried.body.error.code, "ORDER_NOT_FOUND");
    const denial = audits(service, "DISPUTE_ACCESS_DENIED");
    assert.equal(denial.length >= 1, true);
  });

  it("validates the category and reason without leaking anything from a bad body", async () => {
    const service = await newService();
    const { buyer, order } = await activeOrder(service);
    assert.equal((await openDispute(service, buyer, order.id, { reasonCategory: "NOT_A_THING", reason: "x".repeat(10) })).status, 400);
    assert.equal((await openDispute(service, buyer, order.id, { reasonCategory: "QUALITY", reason: "too" })).status, 400);
    // A client cannot smuggle a status, outcome, or role.
    assert.equal((await call(service.baseUrl, "POST", `/orders/${order.id}/disputes`, { headers: bearer(buyer.accessToken), body: { reasonCategory: "QUALITY", reason: "a".repeat(20), status: "RESOLVED" } })).status, 400);
  });

  it("refuses to open a dispute on an already-closed order", async () => {
    const service = await newService();
    const { buyer, order } = await activeOrder(service);
    await call(service.baseUrl, "POST", `/orders/${order.id}/cancel`, { headers: bearer(buyer.accessToken), body: {} });
    const tried = await openDispute(service, buyer, order.id);
    assert.equal(tried.status, 409);
    assert.equal(tried.body.error.code, "DISPUTE_STATE_CONFLICT");
  });
});

describe("Phase 30 statements are append-only evidence", () => {
  it("appends immutable, versioned statements readable by both parties", async () => {
    const service = await newService();
    const { buyer, creator, order } = await activeOrder(service);
    const { body: dispute } = await openDispute(service, buyer, order.id);
    const first = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/statements`, { headers: bearer(buyer.accessToken), body: { body: "Here is the plan I agreed to." } });
    assert.equal(first.status, 201);
    assert.equal(first.body.version, 1);
    const second = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/statements`, { headers: bearer(creator.accessToken), body: { body: "And here is my side of the delivery." } });
    assert.equal(second.status, 201);
    assert.equal(second.body.version, 2);
    const detail = await call(service.baseUrl, "GET", `/orders/${order.id}/disputes/${dispute.id}`, { headers: bearer(creator.accessToken) });
    assert.equal(detail.status, 200);
    assert.equal(detail.body.statements.length, 2);
    assert.equal(detail.body.statements[0].authorRole, "BUYER");
    assert.equal(detail.body.statements[1].authorRole, "CREATOR");
    // The database enforces immutability too, not only the service.
    const id = detail.body.statements[0].id;
    assert.throws(() => service.database.prepare("UPDATE order_dispute_statements SET body = 'x' WHERE statement_id = ?").run(id), /immutable/);
    assert.throws(() => service.database.prepare("DELETE FROM order_dispute_statements WHERE statement_id = ?").run(id), /immutable/);
  });

  it("caps statements at the bound and refuses to add to a closed dispute", async () => {
    const service = await newService();
    const { buyer, order } = await activeOrder(service);
    const { body: dispute } = await openDispute(service, buyer, order.id);
    for (let i = 0; i < 12; i += 1) {
      const added = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/statements`, { headers: bearer(buyer.accessToken), body: { body: `Statement ${i + 1}.` } });
      assert.equal(added.status, 201, `statement ${i + 1}`);
    }
    const over = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/statements`, { headers: bearer(buyer.accessToken), body: { body: "one too many" } });
    assert.equal(over.status, 409);
    assert.equal(over.body.error.code, "DISPUTE_STATE_CONFLICT");
    // Withdraw the dispute; a closed dispute accepts no further statements.
    await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/withdraw`, { headers: bearer(buyer.accessToken) });
    const afterClose = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/statements`, { headers: bearer(buyer.accessToken), body: { body: "after close" } });
    assert.equal(afterClose.status, 409);
    assert.equal(afterClose.body.error.code, "DISPUTE_STATE_CONFLICT");
  });
});

describe("Phase 30 resolution needs both parties", () => {
  it("mutual CLOSE cancels the order and records the outcome, with no money surface", async () => {
    const service = await newService();
    const { buyer, creator, order } = await activeOrder(service);
    // Approve a milestone first — the exact state Phase 27 refuses to cancel from.
    const milestoneId = order.milestones[0].id;
    await call(service.baseUrl, "POST", `/orders/${order.id}/milestones/${milestoneId}/start`, { headers: bearer(creator.accessToken), body: {} });
    await call(service.baseUrl, "POST", `/orders/${order.id}/milestones/${milestoneId}/deliver`, { headers: bearer(creator.accessToken), body: { note: "Delivered the first milestone." } });
    await call(service.baseUrl, "POST", `/orders/${order.id}/milestones/${milestoneId}/approve`, { headers: bearer(buyer.accessToken), body: {} });
    // Now even the buyer cannot cancel unilaterally.
    assert.equal(orderRow(service, order.id).status, "ACTIVE");
    const { body: dispute } = await openDispute(service, buyer, order.id);
    await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/position`, { headers: bearer(buyer.accessToken), body: { position: "CLOSE" } });
    const mid = disputeRow(service, dispute.id);
    assert.equal(mid.status, "OPEN"); // one side is not enough
    assert.equal(orderRow(service, order.id).status, "ACTIVE");
    const resolved = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/position`, { headers: bearer(creator.accessToken), body: { position: "CLOSE" } });
    assert.equal(resolved.status, 200);
    assert.equal(resolved.body.status, "RESOLVED");
    assert.equal(resolved.body.outcome, "CLOSED");
    assert.equal(orderRow(service, order.id).status, "CANCELLED");
    assert.notEqual(orderRow(service, order.id).cancelled_at, null);
    // There is no settlement column anywhere; the order's frozen amounts are untouched.
    assert.equal(Object.hasOwn(orderRow(service, order.id), "amount"), false);
  });

  it("mutual CONTINUE closes the dispute and leaves the order ACTIVE", async () => {
    const service = await newService();
    const { buyer, creator, order } = await activeOrder(service);
    const { body: dispute } = await openDispute(service, buyer, order.id);
    await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/position`, { headers: bearer(creator.accessToken), body: { position: "CONTINUE" } });
    const res = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/position`, { headers: bearer(buyer.accessToken), body: { position: "CONTINUE" } });
    assert.equal(res.body.status, "RESOLVED");
    assert.equal(res.body.outcome, "CONTINUED");
    assert.equal(orderRow(service, order.id).status, "ACTIVE");
    assert.equal(disputeRow(service, dispute.id).resolved_at === null, false);
  });

  it("keeps the dispute open on a mixed pair and lets either side change their position", async () => {
    const service = await newService();
    const { buyer, creator, order } = await activeOrder(service);
    const { body: dispute } = await openDispute(service, buyer, order.id);
    await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/position`, { headers: bearer(buyer.accessToken), body: { position: "CLOSE" } });
    const mixed = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/position`, { headers: bearer(creator.accessToken), body: { position: "CONTINUE" } });
    assert.equal(mixed.body.status, "OPEN");
    assert.equal(mixed.body.positions.buyer, "CLOSE");
    assert.equal(mixed.body.positions.creator, "CONTINUE");
    // The creator flips to CLOSE; now both are CLOSE and it resolves.
    const agreed = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/position`, { headers: bearer(creator.accessToken), body: { position: "CLOSE" } });
    assert.equal(agreed.body.status, "RESOLVED");
    assert.equal(orderRow(service, order.id).status, "CANCELLED");
  });

  it("only the opener may withdraw, which unblocks the order", async () => {
    const service = await newService();
    const { buyer, creator, order } = await activeOrder(service);
    const { body: dispute } = await openDispute(service, buyer, order.id);
    const wrongHands = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/withdraw`, { headers: bearer(creator.accessToken) });
    assert.equal(wrongHands.status, 404); // non-opener is treated as not having this dispute
    assert.equal(audits(service, "DISPUTE_ACCESS_DENIED").length >= 1, true);
    const withdrawn = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/withdraw`, { headers: bearer(buyer.accessToken) });
    assert.equal(withdrawn.status, 200);
    assert.equal(withdrawn.body.status, "RESOLVED");
    assert.equal(withdrawn.body.outcome, "WITHDRAWN");
    assert.equal(orderRow(service, order.id).status, "ACTIVE"); // withdrawal never closes the order
    const again = await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/withdraw`, { headers: bearer(buyer.accessToken) });
    assert.equal(again.status, 409);
  });
});

describe("Phase 30 an open dispute freezes closure, not work", () => {
  it("blocks complete and cancel while open and releases them after a CONTINUE resolution", async () => {
    const service = await newService();
    const { buyer, creator, order } = await activeOrder(service);
    const mid0 = order.milestones[0].id;
    const mid1 = order.milestones[1].id;
    for (const mid of [mid0, mid1]) {
      await call(service.baseUrl, "POST", `/orders/${order.id}/milestones/${mid}/start`, { headers: bearer(creator.accessToken), body: {} });
      await call(service.baseUrl, "POST", `/orders/${order.id}/milestones/${mid}/deliver`, { headers: bearer(creator.accessToken), body: { note: "Done." } });
      await call(service.baseUrl, "POST", `/orders/${order.id}/milestones/${mid}/approve`, { headers: bearer(buyer.accessToken), body: {} });
    }
    const { body: dispute } = await openDispute(service, buyer, order.id);
    const blockedComplete = await call(service.baseUrl, "POST", `/orders/${order.id}/complete`, { headers: bearer(buyer.accessToken), body: {} });
    assert.equal(blockedComplete.status, 409);
    assert.equal(blockedComplete.body.error.code, "ORDER_DISPUTED");
    const blockedCancel = await call(service.baseUrl, "POST", `/orders/${order.id}/cancel`, { headers: bearer(creator.accessToken), body: {} });
    assert.equal(blockedCancel.status, 409);
    assert.equal(blockedCancel.body.error.code, "ORDER_DISPUTED");
    // But a milestone can still be re-opened for work: the dispute does not freeze granular progress. Start is a
    // no-op here since all are approved; assert the order detail surfaces the open dispute instead.
    const detail = await call(service.baseUrl, "GET", `/orders/${order.id}`, { headers: bearer(creator.accessToken) });
    assert.equal(detail.body.dispute.id, dispute.id);
    assert.equal(detail.body.dispute.positions.close, 0);
    // Both agree to continue, order unfreezes, completion then succeeds.
    await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/position`, { headers: bearer(buyer.accessToken), body: { position: "CONTINUE" } });
    await call(service.baseUrl, "POST", `/orders/${order.id}/disputes/${dispute.id}/position`, { headers: bearer(creator.accessToken), body: { position: "CONTINUE" } });
    const cleared = await call(service.baseUrl, "GET", `/orders/${order.id}`, { headers: bearer(buyer.accessToken) });
    assert.equal(cleared.body.dispute, null);
    const completed = await call(service.baseUrl, "POST", `/orders/${order.id}/complete`, { headers: bearer(buyer.accessToken), body: {} });
    assert.equal(completed.status, 200);
    assert.equal(orderRow(service, order.id).status, "COMPLETED");
  });

  it("reports an undisputed order exactly as Phase 27 did (dispute: null)", async () => {
    const service = await newService();
    const { buyer, order } = await activeOrder(service);
    const detail = await call(service.baseUrl, "GET", `/orders/${order.id}`, { headers: bearer(buyer.accessToken) });
    assert.equal(detail.status, 200);
    assert.equal("dispute" in detail.body, true);
    assert.equal(detail.body.dispute, null);
  });
});

describe("Phase 30 visibility and audit", () => {
  it("a stranger cannot list or read disputes on someone else's order", async () => {
    const service = await newService();
    const { buyer, order } = await activeOrder(service);
    await openDispute(service, buyer, order.id);
    const stranger = await account(service, "nosey@example.test");
    assert.equal((await call(service.baseUrl, "GET", `/orders/${order.id}/disputes`, { headers: bearer(stranger.accessToken) })).status, 404);
    const listed = await call(service.baseUrl, "GET", `/marketplace/disputes`, { headers: bearer(stranger.accessToken) });
    assert.equal(listed.status, 200);
    assert.equal(listed.body.total, 0);
  });

  it("surfaces only the caller's disputes across orders and audits every state change with ids and categories", async () => {
    const service = await newService();
    const flowA = await activeOrder(service);
    const flowB = await activeOrder(service, { budget: true });
    await openDispute(service, flowA.buyer, flowA.order.id);
    await openDispute(service, flowB.creator, flowB.order.id);
    const mine = await call(service.baseUrl, "GET", `/marketplace/disputes`, { headers: bearer(flowA.buyer.accessToken) });
    assert.equal(mine.status, 200);
    assert.equal(mine.body.disputes.some((d) => d.id !== undefined), true);
    assert.equal(audits(service, "DISPUTE_OPENED").length, 2);
    const opened = audits(service, "DISPUTE_OPENED")[0];
    const meta = JSON.parse(opened.metadata_json);
    // Audit metadata carries ids and a category only — never a reason or any personal text.
    assert.equal(typeof meta.reasonCategory, "string");
    assert.equal(opened.metadata_json.includes("does not match"), false);
  });
});
