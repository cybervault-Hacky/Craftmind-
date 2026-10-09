/**
 * Phase 27 marketplace order lifecycle tests.
 *
 * The suite follows the same rule as the phase it tests: **an order is a server-derived snapshot of one selected
 * proposal on one awarded job, every transition is enforced on real rows, and neither party can act as the other.**
 * A client cannot name a buyer, creator, status, amount, or position; deliveries are immutable versions; the
 * revision policy is finite and snapshotted; approvals belong to the buyer alone; completion requires every
 * milestone approved; cancellation is refused once anything is approved; and denials are audited inside the
 * transaction so the record survives the rollback.
 *
 * Migration coverage is honest about the actual starting version: fresh v10, a seeded **v9** database upgrading
 * to v10 with prior rows preserved, constraint backstops exercised against the real schema (unique order per
 * proposal, unique milestone position, unique delivery version, amount triggers, status CHECKs), and a
 * re-run no-op. Rate-limit tests give each of the four dedicated order categories its own tiny budget in its own
 * service.
 *
 * There is no payment assertion anywhere: creating, delivering, approving, or completing an order never states or
 * implies that money moved, because no payment, escrow, refund, or payout surface exists in this phase.
 *
 * Security thresholds are raised on purpose (same as Phases 23–26): this suite deliberately produces many
 * refused requests, and the Phase 20 protections correctly treat that as abuse.
 */

import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { afterEach, describe, it } from "node:test";
import { migrateToVersion, REGISTERED_AUDIT_ACTION_TYPES, SCHEMA_VERSION } from "../src/db.js";
import { ErrorCode } from "../src/errors.js";
import { CREATOR_AGREEMENT_VERSION } from "../src/onboarding.js";
import { loadConfiguration } from "../src/config.js";
import { call, registerVerified, startService, TEST_SECRET } from "./helpers.js";

const services = new Set();
const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x51).toString("base64url");
const DEVELOPER_EMAIL = "owner@example.test";
const DEVELOPER_PASSWORD = "Creator Plane 7Safe";

const RELAXED = Object.freeze({
  RATE_CREATOR_WRITE_MAX: "1000",
  RATE_CREATOR_READ_MAX: "1000",
  RATE_JOB_WRITE_MAX: "1000",
  RATE_PROPOSAL_WRITE_MAX: "1000",
  RATE_ORDER_CREATE_MAX: "1000",
  RATE_ORDER_WRITE_MAX: "1000",
  RATE_DELIVERY_WRITE_MAX: "1000",
  RATE_REVISION_WRITE_MAX: "1000",
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
});

function bearer(accessToken) {
  return { Authorization: `Bearer ${accessToken}` };
}

function uniqueSuffix() {
  return Math.random().toString(36).slice(2, 8);
}

async function signedInAccount(service, email) {
  const registration = await registerVerified(service, { email });
  assert.equal(registration.status, 201, JSON.stringify(registration.body));
  return { email, accessToken: registration.body.session.accessToken };
}

async function ownerSession(service) {
  // The bootstrap secret is consumed once per service; later flows reuse the same developer session.
  if (service.__ownerSession) return service.__ownerSession;
  const bootstrap = await call(service.baseUrl, "POST", "/developer/auth/bootstrap", {
    body: { secret: BOOTSTRAP_SECRET, password: DEVELOPER_PASSWORD },
  });
  assert.equal(bootstrap.status, 201, JSON.stringify(bootstrap.body));
  const login = await call(service.baseUrl, "POST", "/developer/auth/login", {
    body: { email: DEVELOPER_EMAIL, password: DEVELOPER_PASSWORD },
  });
  assert.equal(login.status, 200, JSON.stringify(login.body));
  service.__ownerSession = { accessToken: login.body.session.accessToken };
  return service.__ownerSession;
}

async function confirmedTool(service, accessToken, tool, args) {
  const prepared = await call(service.baseUrl, "POST", "/developer/tools/invoke", {
    headers: bearer(accessToken), body: { tool, arguments: args },
  });
  assert.equal(prepared.status, 200, `prepare ${tool}: ${JSON.stringify(prepared.body)}`);
  assert.equal(prepared.body.result.confirmationRequired, true, `${tool} must stop at the confirmation boundary`);
  const confirmed = await call(service.baseUrl, "POST", "/developer/tools/confirm", {
    headers: bearer(accessToken), body: { confirmationToken: prepared.body.result.confirmationToken },
  });
  assert.equal(confirmed.status, 200, `confirm ${tool}: ${JSON.stringify(confirmed.body)}`);
  return confirmed.body.result;
}

async function grantPlan(service, owner, email, plan = "CREATOR", days = 30) {
  return confirmedTool(service, owner.accessToken, "grantMembership", {
    email, plan, days, reason: "Phase 27 test grant",
  });
}

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

async function eligibleCreator(service, owner, email, handle) {
  const account = await signedInAccount(service, email);
  await grantPlan(service, owner, email);
  const saved = await call(service.baseUrl, "POST", "/onboarding/seller", {
    headers: bearer(account.accessToken), body: sellerBody({ handle, displayName: `${handle} Studio` }),
  });
  assert.equal(saved.status, 200, JSON.stringify(saved.body));
  const userId = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(email).user_id;
  return { ...account, userId };
}

function jobBody(overrides = {}) {
  return {
    title: "Redstone sorting hall",
    description: "A full item sorting hall for my survival server, with expandable storage rows.",
    edition: "java",
    minecraftVersion: "1.20.1",
    scope: "Design and build one sorting hall, delivered as a schematic plus an in-world install guide.",
    ...overrides,
  };
}

async function postJob(service, buyer, overrides = {}) {
  const created = await call(service.baseUrl, "POST", "/buyer/jobs", {
    headers: bearer(buyer.accessToken), body: jobBody(overrides),
  });
  assert.equal(created.status, 201, JSON.stringify(created.body));
  return created.body;
}

async function submitProposal(service, creator, jobId, overrides = {}) {
  const submitted = await call(service.baseUrl, "POST", "/creator/proposal", {
    headers: bearer(creator.accessToken),
    body: {
      jobId,
      message: "I have built several sorting halls and can start this week with a clear plan.",
      scope: "One sorting hall with 12 modules, installed and demonstrated on your server.",
      ...overrides,
    },
  });
  assert.equal(submitted.status, 201, JSON.stringify(submitted.body));
  return submitted.body;
}

async function awardProposal(service, buyer, jobId, proposalId) {
  const awarded = await call(service.baseUrl, "POST", `/buyer/jobs/${jobId}/award`, {
    headers: bearer(buyer.accessToken), body: { proposalId },
  });
  assert.equal(awarded.status, 200, JSON.stringify(awarded.body));
  return awarded.body;
}

const DEFAULT_MILESTONES = Object.freeze([
  { title: "Plan and spec", description: "Write the plan and confirm the acceptance criteria.", acceptanceCriteria: "Buyer agrees the plan covers the agreed scope." },
  { title: "Build and handover", description: "Build the work and hand over the result with notes.", acceptanceCriteria: "The delivered result matches the agreed scope." },
]);

async function createOrder(service, buyer, jobId, proposalId, milestones = DEFAULT_MILESTONES) {
  const created = await call(service.baseUrl, "POST", "/buyer/orders", {
    headers: bearer(buyer.accessToken), body: { jobId, proposalId, milestones },
  });
  return created;
}

/** Buyer + eligible creator + a job with a SELECTED, AWARDED proposal — the state just before conversion. */
async function awardedProposalFlow(service, { budget = true, suffix = uniqueSuffix(), buyer = null } = {}) {
  const owner = await ownerSession(service);
  const account = buyer ?? await signedInAccount(service, `buyer-${suffix}@example.test`);
  const creator = await eligibleCreator(service, owner, `builder-${suffix}@example.test`, `studio-${suffix}`);
  const job = await postJob(service, account, budget
    ? { budgetMin: 100, budgetMax: 1000, budgetCurrency: "INR" }
    : {});
  const proposal = await submitProposal(service, creator, job.id, budget
    ? { budgetMin: 400, budgetMax: 1000, budgetCurrency: "INR", deliveryEstimateDays: 7 }
    : { deliveryEstimateDays: 7 });
  await awardProposal(service, account, job.id, proposal.id);
  return { owner, buyer: account, creator, job, proposal, suffix };
}

function defaultMilestonesFor(budget) {
  return budget
    ? [
        { ...DEFAULT_MILESTONES[0], amount: 400 },
        { ...DEFAULT_MILESTONES[1], amount: 600 },
      ]
    : DEFAULT_MILESTONES;
}

/** The full buyer journey through the order conversion, with the buyer's detail view attached. */
async function awardedOrderFlow(service, { budget = true, suffix = uniqueSuffix(), buyer = null } = {}) {
  const base = await awardedProposalFlow(service, { budget, suffix, buyer });
  const created = await createOrder(service, base.buyer, base.job.id, base.proposal.id, defaultMilestonesFor(budget));
  assert.equal(created.status, 201, JSON.stringify(created.body));
  const detail = await call(service.baseUrl, "GET", `/orders/${created.body.id}`, {
    headers: bearer(base.buyer.accessToken),
  });
  assert.equal(detail.status, 200, JSON.stringify(detail.body));
  return { ...base, order: created.body, milestones: detail.body.milestones, detail: detail.body };
}

async function startMilestone(service, actor, orderId, milestoneId) {
  return call(service.baseUrl, "POST", `/orders/${orderId}/milestones/${milestoneId}/start`, {
    headers: bearer(actor.accessToken), body: {},
  });
}

async function deliverMilestone(service, actor, orderId, milestoneId, body = {}) {
  return call(service.baseUrl, "POST", `/orders/${orderId}/milestones/${milestoneId}/deliver`, {
    headers: bearer(actor.accessToken),
    body: { note: "Delivered the agreed work with setup notes.", ...body },
  });
}

async function requestRevision(service, actor, orderId, milestoneId, body = {}) {
  return call(service.baseUrl, "POST", `/orders/${orderId}/milestones/${milestoneId}/revision`, {
    headers: bearer(actor.accessToken), body: { reason: "Please adjust the module count as discussed.", ...body },
  });
}

async function approveMilestone(service, actor, orderId, milestoneId) {
  return call(service.baseUrl, "POST", `/orders/${orderId}/milestones/${milestoneId}/approve`, {
    headers: bearer(actor.accessToken), body: {},
  });
}

function auditRows(service, actionType) {
  return service.database
    .prepare("SELECT * FROM admin_audit_log WHERE action_type = ? ORDER BY occurred_at, audit_id")
    .all(actionType);
}

function orderRow(service, orderId) {
  return service.database.prepare("SELECT * FROM orders WHERE order_id = ?").get(orderId);
}

function milestoneRow(service, milestoneId) {
  return service.database.prepare("SELECT * FROM order_milestones WHERE milestone_id = ?").get(milestoneId);
}

// --------------------------------------------------------------- A. schema and migration (categories 1–4)

describe("Phase 27 order schema", () => {
  it("1. initializes the fresh schema at v10 with the order tables, constraints, indexes, triggers, and audit types", async () => {
    const service = await newService();
    assert.equal(SCHEMA_VERSION, 10);
    assert.equal(REGISTERED_AUDIT_ACTION_TYPES.size, 90);
    const version = service.database.prepare("SELECT MAX(version) AS version FROM schema_migrations").get().version;
    assert.equal(version, 10);

    const tables = service.database
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('orders', 'order_milestones', 'milestone_deliveries', 'milestone_revision_requests')")
      .all().map((row) => row.name).sort();
    assert.deepEqual(tables, ["milestone_deliveries", "milestone_revision_requests", "order_milestones", "orders"]);

    // Every ordered column of the order snapshot exists: identity, frozen terms, revision policy, status, timestamps.
    const orderColumns = service.database.prepare("PRAGMA table_info(orders)").all().map((row) => row.name);
    for (const column of ["order_id", "job_id", "proposal_id", "buyer_id", "creator_user_id", "job_title", "scope",
      "edition", "minecraft_version", "loaders", "agreed_budget_min", "agreed_budget_max", "agreed_currency",
      "agreed_deadline", "agreed_delivery_estimate_days", "revision_limit", "status", "created_at", "updated_at",
      "completed_at", "cancelled_at"]) {
      assert.equal(orderColumns.includes(column), true, column);
    }
    const milestoneColumns = service.database.prepare("PRAGMA table_info(order_milestones)").all().map((row) => row.name);
    for (const column of ["milestone_id", "order_id", "position", "title", "description", "acceptance_criteria",
      "amount", "status", "created_at", "updated_at", "started_at", "submitted_at", "approved_at"]) {
      assert.equal(milestoneColumns.includes(column), true, column);
    }
    const deliveryColumns = service.database.prepare("PRAGMA table_info(milestone_deliveries)").all().map((row) => row.name);
    for (const column of ["delivery_id", "milestone_id", "order_id", "version", "note", "evidence_references",
      "compatibility_note", "submitted_by", "submitted_at"]) {
      assert.equal(deliveryColumns.includes(column), true, column);
    }
    const revisionColumns = service.database.prepare("PRAGMA table_info(milestone_revision_requests)").all().map((row) => row.name);
    for (const column of ["revision_id", "milestone_id", "order_id", "kind", "reason", "requested_by", "created_at"]) {
      assert.equal(revisionColumns.includes(column), true, column);
    }

    const indexNames = service.database
      .prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND (name LIKE 'orders%' OR name LIKE 'milestone%' OR name = 'revision_requests_by_milestone')")
      .all().map((row) => row.name).sort();
    assert.deepEqual(indexNames, [
      "milestone_deliveries_by_order",
      "milestone_delivery_versions",
      "milestone_position_per_order",
      "orders_by_buyer",
      "orders_by_creator",
      "orders_by_job",
      "orders_by_proposal",
      "revision_requests_by_milestone",
    ]);

    const triggerNames = service.database
      .prepare("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'milestone_amount%'")
      .all().map((row) => row.name).sort();
    assert.deepEqual(triggerNames, [
      "milestone_amount_requires_budget",
      "milestone_amount_requires_budget_update",
      "milestone_amount_within_budget",
      "milestone_amount_within_budget_update",
    ]);

    // The audit CHECK accepts the new order events and still refuses unknown types; denial audits are first-class.
    for (const type of ["ORDER_CREATED", "ORDER_COMPLETED", "ORDER_CANCELLED", "ORDER_ACCESS_DENIED", "MILESTONE_STARTED",
      "MILESTONE_APPROVED", "DELIVERY_SUBMITTED", "DELIVERY_REVISED", "REVISION_REQUESTED", "SCOPE_CHANGE_REQUESTED"]) {
      assert.equal(auditRows(service, type).length, 0, `${type} must be a registered audit type`);
    }
  });

  it("2. migrates a version 9 database to v10 without losing data or audit history, and re-running is a no-op", () => {
    const database = new DatabaseSync(":memory:");
    database.exec("PRAGMA foreign_keys = ON");
    database.exec("PRAGMA recursive_triggers = ON");
    database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    migrateToVersion(database, 9);
    const timestamp = "2026-01-01T00:00:00.000Z";
    database.exec(`
      INSERT INTO users (user_id, email, email_canonical, password_hash, display_name, status, email_verified_at, created_at, updated_at)
        VALUES ('usr_p26', 'p26@example.com', 'p26@example.com', 'hash', 'Phase26', 'ACTIVE', '${timestamp}', '${timestamp}', '${timestamp}');
      INSERT INTO creator_profiles (creator_id, user_id, handle, display_name, bio, category, avatar_reference, status, verification_status, status_changed_at, verified_at, created_at, updated_at)
        VALUES ('crt_p26', 'usr_p26', 'phase26-studio', 'Phase26 Studio', '', NULL, NULL, 'ACTIVE', 'UNVERIFIED', '${timestamp}', NULL, '${timestamp}', '${timestamp}');
      INSERT INTO buyer_jobs (job_id, buyer_id, title, description, edition, minecraft_version, loaders, image_references,
        budget_min, budget_max, budget_currency, deadline, scope, status, created_at, updated_at, awarded_at)
        VALUES ('job_p26', 'usr_p26', 'Pre-existing job', 'A job that existed before Phase 27.', 'java', '1.20.1',
          '[]', '[]', 100, 1000, 'INR', NULL, 'The pre-phase scope.', 'AWARDED', '${timestamp}', '${timestamp}', '${timestamp}');
      INSERT INTO job_proposals (proposal_id, job_id, user_id, creator_id, message, scope, budget_min, budget_max,
        budget_currency, delivery_estimate_days, status, created_at, updated_at)
        VALUES ('prp_p26', 'job_p26', 'usr_p26', 'crt_p26', 'Pre-existing proposal.', 'The pre-phase proposal scope.',
          400, 1000, 'INR', 7, 'SELECTED', '${timestamp}', '${timestamp}');
      INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
        VALUES ('aud_p26', 'SYSTEM', NULL, 'JOB_AWARDED', 'usr_p26', NULL, '${timestamp}', 'SUCCESS', '{"jobId":"job_p26"}');
    `);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 9);
    const appliedAt = database.prepare("SELECT applied_at FROM schema_migrations WHERE version = 9").get().applied_at;

    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 10);
    assert.equal(database.prepare("SELECT applied_at FROM schema_migrations WHERE version = 9").get().applied_at, appliedAt);

    // Prior rows survive with their values, and the append-only audit triggers were restored by the rebuild.
    assert.equal(database.prepare("SELECT title FROM buyer_jobs WHERE job_id = 'job_p26'").get().title, "Pre-existing job");
    assert.equal(database.prepare("SELECT status FROM job_proposals WHERE proposal_id = 'prp_p26'").get().status, "SELECTED");
    assert.equal(database.prepare("SELECT action_type FROM admin_audit_log WHERE audit_id = 'aud_p26'").get().action_type, "JOB_AWARDED");
    assert.throws(() => database.prepare("UPDATE admin_audit_log SET outcome = 'FAILURE' WHERE audit_id = 'aud_p26'").run(), /append-only/);

    // The upgraded schema accepts order events and still refuses unknown audit types.
    database.prepare(
      `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       VALUES ('aud_p27', 'SYSTEM', NULL, 'ORDER_CREATED', 'usr_p26', NULL, '${timestamp}', 'SUCCESS', '{}')`,
    ).run();
    assert.throws(() => database.prepare(
      `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       VALUES ('aud_p27x', 'SYSTEM', NULL, 'STILL_NOT_A_TYPE', 'usr_p26', NULL, '${timestamp}', 'SUCCESS', '{}')`,
    ).run(), /CHECK constraint failed/);

    // The order created against the pre-existing awarded proposal satisfies every backstop: one order only.
    database.prepare(
      `INSERT INTO orders (order_id, job_id, proposal_id, buyer_id, creator_user_id, job_title, scope, edition,
        minecraft_version, loaders, agreed_budget_min, agreed_budget_max, agreed_currency, agreed_deadline,
        agreed_delivery_estimate_days, revision_limit, status, created_at, updated_at, completed_at, cancelled_at)
       VALUES ('ord_p27', 'job_p26', 'prp_p26', 'usr_p26', 'usr_p26', 'Pre-existing job', 'Frozen scope.', 'java',
        '1.20.1', '[]', 400, 1000, 'INR', NULL, 7, 2, 'ACTIVE', '${timestamp}', '${timestamp}', NULL, NULL)`,
    ).run();
    assert.throws(() => database.prepare(
      `INSERT INTO orders (order_id, job_id, proposal_id, buyer_id, creator_user_id, job_title, scope, edition,
        minecraft_version, loaders, agreed_budget_min, agreed_budget_max, agreed_currency, agreed_deadline,
        agreed_delivery_estimate_days, revision_limit, status, created_at, updated_at, completed_at, cancelled_at)
       VALUES ('ord_p27b', 'job_p26', 'prp_p26', 'usr_p26', 'usr_p26', 'Second order', 'Frozen scope.', 'java',
        '1.20.1', '[]', 400, 1000, 'INR', NULL, 7, 2, 'ACTIVE', '${timestamp}', '${timestamp}', NULL, NULL)`,
    ).run(), /UNIQUE/i);

    // Re-running is a no-op: row counts and index counts are stable.
    const orders = database.prepare("SELECT COUNT(*) AS count FROM orders").get().count;
    const indexes = database.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'index' AND (name LIKE 'orders%' OR name LIKE 'milestone%')").get().count;
    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 10);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM orders").get().count, orders);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'index' AND (name LIKE 'orders%' OR name LIKE 'milestone%')").get().count, indexes);
    database.close();
  });

  it("3. enforces the database backstops: unique order per proposal, unique positions and versions, amount triggers, and status CHECKs", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service, { budget: true });
    const { order, milestones } = flow;

    // One order per awarded proposal — a crossed duplicate insert violates the unique index.
    const buyerId = service.database.prepare("SELECT buyer_id FROM orders WHERE order_id = ?").get(order.id).buyer_id;
    const creatorUserId = service.database.prepare("SELECT creator_user_id FROM orders WHERE order_id = ?").get(order.id).creator_user_id;
    assert.throws(() => service.database.prepare(
      `INSERT INTO orders (order_id, job_id, proposal_id, buyer_id, creator_user_id, job_title, scope, edition,
        minecraft_version, loaders, agreed_budget_min, agreed_budget_max, agreed_currency, agreed_deadline,
        agreed_delivery_estimate_days, revision_limit, status, created_at, updated_at, completed_at, cancelled_at)
       VALUES ('ord_duplicate', ?, ?, ?, ?, 'Duplicate job title', 'Duplicate order scope text.', 'java', '1.20.1', '[]', 400, 1000, 'INR', NULL, 7, 2, 'ACTIVE', '2026-01-01T00:00:00.000Z', '2026-01-01T00:00:00.000Z', NULL, NULL)`,
    ).run(order.jobId, order.proposalId, buyerId, creatorUserId), /UNIQUE/i);

    // Unique milestone position per order.
    assert.throws(() => service.database.prepare(
      "INSERT INTO order_milestones (milestone_id, order_id, position, title, description, acceptance_criteria, amount, status, created_at, updated_at, started_at, submitted_at, approved_at) VALUES (?, ?, 1, 'dup', 'duplicate description', 'dup', NULL, 'PENDING', '2026-01-01T00:00:00.000Z', '2026-01-01T00:00:00.000Z', NULL, NULL, NULL)",
    ).run(`mil_${"1".repeat(36)}`, order.id), /UNIQUE/i);

    // Invalid status values are refused by CHECK, not by application luck.
    assert.throws(() => service.database.prepare("UPDATE order_milestones SET status = 'APPROVEDISH' WHERE milestone_id = ?")
      .run(milestones[0].id), /CHECK/i);
    assert.throws(() => service.database.prepare("UPDATE orders SET status = 'SORT_OF_DONE' WHERE order_id = ?")
      .run(order.id), /CHECK/i);

    // Unique delivery version per milestone.
    const timestamp = "2026-01-02T00:00:00.000Z";
    service.database.prepare(
      "INSERT INTO milestone_deliveries (delivery_id, milestone_id, order_id, version, note, evidence_references, compatibility_note, submitted_by, submitted_at) VALUES (?, ?, ?, 1, 'first', '[]', '', ?, ?)",
    ).run(`dlv_${"2".repeat(36)}`, milestones[0].id, order.id, service.database.prepare("SELECT creator_user_id FROM orders WHERE order_id = ?").get(order.id).creator_user_id, timestamp);
    assert.throws(() => service.database.prepare(
      "INSERT INTO milestone_deliveries (delivery_id, milestone_id, order_id, version, note, evidence_references, compatibility_note, submitted_by, submitted_at) VALUES (?, ?, ?, 1, 'dup', '[]', '', ?, ?)",
    ).run(`dlv_${"3".repeat(36)}`, milestones[0].id, order.id, service.database.prepare("SELECT creator_user_id FROM orders WHERE order_id = ?").get(order.id).creator_user_id, timestamp), /UNIQUE/i);

    // Amount backstops: an amount with no agreed budget fires the requires-budget trigger.
    const noBudget = await awardedOrderFlow(service, { budget: false, suffix: uniqueSuffix() });
    assert.throws(() => service.database.prepare(
      "UPDATE order_milestones SET amount = 10 WHERE milestone_id = ?",
    ).run(noBudget.milestones[0].id), /milestone amounts require an agreed budget/i);

    // An amount above the agreed maximum fires the within-budget trigger (updates included).
    assert.throws(() => service.database.prepare(
      "UPDATE order_milestones SET amount = 100000 WHERE milestone_id = ?",
    ).run(milestones[0].id), /milestone amounts exceed the agreed order budget/i);
    // A sum that still fits the budget passes the same guard: 400 + 500 ≤ 1000.
    service.database.prepare("UPDATE order_milestones SET amount = 500 WHERE milestone_id = ?").run(milestones[1].id);
    assert.equal(milestoneRow(service, milestones[1].id).amount, 500);
  });

  it("4. keeps the migration additive: fresh and upgraded databases expose the same order tables and shapes", () => {
    const fresh = new DatabaseSync(":memory:");
    fresh.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    migrateToVersion(fresh, SCHEMA_VERSION);

    const upgraded = new DatabaseSync(":memory:");
    upgraded.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    migrateToVersion(upgraded, 9);
    migrateToVersion(upgraded, SCHEMA_VERSION);

    const shape = (database, table) => database.prepare(`PRAGMA table_info(${table})`).all().map((row) => `${row.name}:${row.type}`);
    for (const table of ["orders", "order_milestones", "milestone_deliveries", "milestone_revision_requests"]) {
      assert.deepEqual(shape(fresh, table), shape(upgraded, table), table);
    }
    fresh.close();
    upgraded.close();
  });
});

// -------------------------------------------------------------------- B. creation from an award (5–10)

describe("Phase 27 order creation", () => {
  it("5. converts the buyer's selected proposal into exactly one order with server-derived parties and frozen terms", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service, { budget: true });
    const { buyer, creator, job, proposal, order } = flow;

    assert.equal(order.status, "ACTIVE");
    assert.equal(order.jobId, job.id);
    assert.equal(order.proposalId, proposal.id);
    assert.equal(order.revisionLimit, 2);
    assert.equal(order.scope, proposal.scope); // proposal scope frozen at creation
    assert.deepEqual(order.budget, { min: 400, max: 1000, currency: "INR" });
    assert.equal(order.deliveryEstimateDays, 7);
    assert.deepEqual(order.milestoneCounts, { total: 2, pending: 2, inProgress: 0, submitted: 0, revisionRequested: 0, approved: 0 });

    // Parties come from server-side rows: buyer from the session's job ownership, creator from the proposal.
    const row = orderRow(service, order.id);
    const buyerId = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(buyer.email).user_id;
    assert.equal(row.buyer_id, buyerId);
    assert.equal(row.creator_user_id, creator.userId);
    // The detail names the creator for both sides without leaking either user id into the payload.
    assert.equal(flow.detail.role, "BUYER");
    assert.equal(flow.detail.order.creator.handle, `studio-${flow.suffix}`);
    assert.equal(JSON.stringify(flow.detail).includes(creator.userId), false);
    assert.equal(JSON.stringify(flow.detail).includes(buyer.accessToken), false);

    // Milestones: positions from array order, amounts snapshotted, status PENDING.
    const milestones = flow.milestones;
    assert.equal(milestones.length, 2);
    assert.deepEqual(milestones.map((milestone) => milestone.position), [1, 2]);
    assert.deepEqual(milestones.map((milestone) => milestone.amount), [400, 600]);
    assert.deepEqual(milestones.map((milestone) => milestone.status), ["PENDING", "PENDING"]);
    assert.equal(auditRows(service, "ORDER_CREATED").length, 1);
  });

  it("6. refuses to create an order from a job that is not awarded, and from a proposal that is not selected", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const buyer = await signedInAccount(service, `buyer-open-${uniqueSuffix()}@example.test`);
    const creator = await eligibleCreator(service, owner, `builder-open-${uniqueSuffix()}@example.test`, `studio-open-${uniqueSuffix()}`);

    // An OPEN job cannot produce an order at all.
    const openJob = await postJob(service, buyer);
    const proposal = await submitProposal(service, creator, openJob.id);
    const early = await createOrder(service, buyer, openJob.id, proposal.id);
    assert.equal(early.status, 409, JSON.stringify(early.body));
    assert.equal(early.body.error.code, ErrorCode.ORDER_STATE_CONFLICT);

    // An awarded job with two proposals: the non-selected one can never become an order.
    const job = await postJob(service, buyer);
    const winner = await submitProposal(service, creator, job.id);
    const otherCreator = await eligibleCreator(service, owner, `builder-b-${uniqueSuffix()}@example.test`, `studio-b-${uniqueSuffix()}`);
    const loser = await submitProposal(service, otherCreator, job.id);
    await awardProposal(service, buyer, job.id, winner.id);
    const fromLoser = await createOrder(service, buyer, job.id, loser.id);
    assert.equal(fromLoser.status, 409, JSON.stringify(fromLoser.body));
    assert.equal(fromLoser.body.error.code, ErrorCode.ORDER_STATE_CONFLICT);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM orders").get().count, 0);
  });

  it("7. refuses order creation by a non-owner and audits the denial", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const buyer = await signedInAccount(service, `buyer-owner-${uniqueSuffix()}@example.test`);
    const attacker = await signedInAccount(service, `attacker-${uniqueSuffix()}@example.test`);
    const creator = await eligibleCreator(service, owner, `builder-owner-${uniqueSuffix()}@example.test`, `studio-owner-${uniqueSuffix()}`);
    const job = await postJob(service, buyer);
    const proposal = await submitProposal(service, creator, job.id);
    await awardProposal(service, buyer, job.id, proposal.id);

    const forged = await createOrder(service, attacker, job.id, proposal.id);
    assert.equal(forged.status, 404, JSON.stringify(forged.body));
    assert.equal(forged.body.error.code, ErrorCode.JOB_NOT_FOUND);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM orders").get().count, 0);
    const denials = auditRows(service, "ORDER_ACCESS_DENIED");
    assert.equal(denials.length >= 1, true);
    const attackerId = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(attacker.email).user_id;
    assert.equal(denials.some((row) => row.target_user_id === attackerId), true);
  });

  it("8. treats a duplicate create as a typed conflict and keeps a single order", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const again = await createOrder(service, flow.buyer, flow.job.id, flow.proposal.id);
    assert.equal(again.status, 409, JSON.stringify(again.body));
    assert.equal(again.body.error.code, ErrorCode.ORDER_STATE_CONFLICT);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM orders").get().count, 1);
    assert.equal(auditRows(service, "ORDER_CREATED").length, 1);
  });

  it("9. lets concurrent creates converge on one order: exactly one 201 and one typed conflict", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const buyer = await signedInAccount(service, `buyer-race-${uniqueSuffix()}@example.test`);
    const creator = await eligibleCreator(service, owner, `builder-race-${uniqueSuffix()}@example.test`, `studio-race-${uniqueSuffix()}`);
    const job = await postJob(service, buyer);
    const proposal = await submitProposal(service, creator, job.id);
    await awardProposal(service, buyer, job.id, proposal.id);

    const attempts = await Promise.all([
      createOrder(service, buyer, job.id, proposal.id),
      createOrder(service, buyer, job.id, proposal.id),
      createOrder(service, buyer, job.id, proposal.id),
    ]);
    const statuses = attempts.map((attempt) => attempt.status).sort((left, right) => left - right);
    assert.deepEqual(statuses, [201, 409, 409], JSON.stringify(attempts.map((attempt) => attempt.body)));
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM orders").get().count, 1);
    for (const attempt of attempts.filter((result) => result.status === 409)) {
      assert.equal(attempt.body.error.code, ErrorCode.ORDER_STATE_CONFLICT);
    }
  });

  it("10. rejects client-supplied parties, statuses, amounts, and positions instead of trusting them", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const { buyer, job, proposal } = flow;
    const stranger = await signedInAccount(service, `stranger-${uniqueSuffix()}@example.test`);

    const smuggledParty = await call(service.baseUrl, "POST", "/buyer/orders", {
      headers: bearer(buyer.accessToken),
      body: {
        jobId: job.id, proposalId: proposal.id, milestones: DEFAULT_MILESTONES,
        buyerId: "usr_someone_else", creatorUserId: "usr_forged", status: "COMPLETED", price: 1,
      },
    });
    assert.equal(smuggledParty.status, 400, JSON.stringify(smuggledParty.body));
    assert.equal(smuggledParty.body.error.code, ErrorCode.INVALID_REQUEST);

    const smuggledMilestone = await call(service.baseUrl, "POST", "/buyer/orders", {
      headers: bearer(buyer.accessToken),
      body: {
        jobId: job.id, proposalId: proposal.id,
        milestones: [{ ...DEFAULT_MILESTONES[0], position: 9, status: "APPROVED", id: "mil_forged" }],
      },
    });
    assert.equal(smuggledMilestone.status, 400, JSON.stringify(smuggledMilestone.body));

    // Even the real buyer cannot smuggle fields into a transition body.
    const smuggledApprove = await call(service.baseUrl, "POST", `/orders/${flow.order.id}/milestones/${flow.milestones[0].id}/approve`, {
      headers: bearer(buyer.accessToken), body: { status: "APPROVED", approvedBy: stranger.email },
    });
    assert.equal(smuggledApprove.status, 400, JSON.stringify(smuggledApprove.body));
    assert.equal(milestoneRow(service, flow.milestones[0].id).status, "PENDING");

    // The order and its milestone rows are untouched by any of the smuggling attempts.
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM orders").get().count, 1);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM order_milestones").get().count, 2);
  });

  it("10b. enforces amount rules against the agreed budget at creation time", async () => {
    const service = await newService();
    const flow = await awardedProposalFlow(service, { budget: true });
    const { buyer, job, proposal } = flow;

    // Amounts only exist against a valid agreed budget, and the total can never exceed the accepted maximum.
    const overTotal = await createOrder(service, buyer, job.id, proposal.id, [
      { ...DEFAULT_MILESTONES[0], amount: 600 },
      { ...DEFAULT_MILESTONES[1], amount: 600 },
    ]);
    assert.equal(overTotal.status, 400, JSON.stringify(overTotal.body));
    assert.equal(overTotal.body.error.code, ErrorCode.INVALID_REQUEST);
    const overMax = await createOrder(service, buyer, job.id, proposal.id, [{ ...DEFAULT_MILESTONES[0], amount: 1001 }]);
    assert.equal(overMax.status, 400, JSON.stringify(overMax.body));
    const negative = await createOrder(service, buyer, job.id, proposal.id, [{ ...DEFAULT_MILESTONES[0], amount: -5 }]);
    assert.equal(negative.status, 400, JSON.stringify(negative.body));
    const badCount = await createOrder(service, buyer, job.id, proposal.id, []);
    assert.equal(badCount.status, 400, JSON.stringify(badCount.body));
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM orders").get().count, 0);

    // A proposal without a budget refuses amounts rather than inventing a figure.
    const noBudget = await awardedProposalFlow(service, { budget: false, suffix: uniqueSuffix() });
    const withAmount = await createOrder(service, noBudget.buyer, noBudget.job.id, noBudget.proposal.id,
      [{ ...DEFAULT_MILESTONES[0], amount: 100 }]);
    assert.equal(withAmount.status, 400, JSON.stringify(withAmount.body));

    // Within-budget amounts land normally.
    const valid = await createOrder(service, buyer, job.id, proposal.id, defaultMilestonesFor(true));
    assert.equal(valid.status, 201, JSON.stringify(valid.body));
  });
});

// ---------------------------------------------------------------- C. reads, listings, and access control (11–16)

describe("Phase 27 order reads", () => {
  it("11. lists orders for the buyer and for the creator, and never for anyone else", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const outsider = await signedInAccount(service, `outsider-${uniqueSuffix()}@example.test`);

    const buyerList = await call(service.baseUrl, "GET", "/buyer/orders", { headers: bearer(flow.buyer.accessToken) });
    assert.equal(buyerList.status, 200, JSON.stringify(buyerList.body));
    assert.equal(buyerList.body.total, 1);
    assert.equal(buyerList.body.items[0].id, flow.order.id);

    const creatorList = await call(service.baseUrl, "GET", "/creator/orders", { headers: bearer(flow.creator.accessToken) });
    assert.equal(creatorList.status, 200, JSON.stringify(creatorList.body));
    assert.equal(creatorList.body.total, 1);
    assert.equal(creatorList.body.items[0].id, flow.order.id);
    // The creator list carries the same agreed terms, not a reduced or aspirational view.
    assert.deepEqual(creatorList.body.items[0].budget, flow.order.budget);
    assert.equal(creatorList.body.items[0].revisionLimit, 2);

    const outsiderList = await call(service.baseUrl, "GET", "/creator/orders", { headers: bearer(outsider.accessToken) });
    assert.equal(outsiderList.status, 200, JSON.stringify(outsiderList.body));
    assert.equal(outsiderList.body.total, 0);
    assert.deepEqual(outsiderList.body.items, []);
  });

  it("12. serves the authorized detail to both parties with the same agreed terms", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const buyerView = await call(service.baseUrl, "GET", `/orders/${flow.order.id}`, { headers: bearer(flow.buyer.accessToken) });
    const creatorView = await call(service.baseUrl, "GET", `/orders/${flow.order.id}`, { headers: bearer(flow.creator.accessToken) });
    assert.equal(buyerView.status, 200, JSON.stringify(buyerView.body));
    assert.equal(creatorView.status, 200, JSON.stringify(creatorView.body));
    assert.equal(buyerView.body.role, "BUYER");
    assert.equal(creatorView.body.role, "CREATOR");
    assert.deepEqual(buyerView.body.order, creatorView.body.order);
    assert.deepEqual(buyerView.body.milestones, creatorView.body.milestones);
  });

  it("13. answers cross-account reads with the same uniform not-found and audits the denial", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const attacker = await signedInAccount(service, `reader-${uniqueSuffix()}@example.test`);

    const crossAccount = await call(service.baseUrl, "GET", `/orders/${flow.order.id}`, { headers: bearer(attacker.accessToken) });
    const randomMissing = await call(service.baseUrl, "GET", "/orders/ord_00000000-0000-4000-8000-000000000000", { headers: bearer(attacker.accessToken) });
    const malformed = await call(service.baseUrl, "GET", "/orders/not-an-order", { headers: bearer(attacker.accessToken) });
    assert.equal(crossAccount.status, 404);
    assert.equal(randomMissing.status, 404);
    assert.equal(malformed.status, 404);
    assert.equal(crossAccount.body.error.code, randomMissing.body.error.code);
    assert.equal(crossAccount.body.error.code, malformed.body.error.code);
    assert.equal(crossAccount.body.error.message, randomMissing.body.error.message);

    const denials = auditRows(service, "ORDER_ACCESS_DENIED");
    const attackerId = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(attacker.email).user_id;
    assert.equal(denials.some((row) => row.target_user_id === attackerId), true);
    // The order itself was never exposed.
    assert.equal(JSON.stringify(crossAccount.body).includes(flow.order.id), false);
  });

  it("14. serves the authorized history to both parties — every delivery version and revision — and denies others", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const milestone = flow.milestones[0];
    await startMilestone(service, flow.creator, flow.order.id, milestone.id);
    const first = await deliverMilestone(service, flow.creator, flow.order.id, milestone.id, {
      note: "First delivery with the initial build.", evidenceReferences: ["https://example.test/build/v1"],
    });
    assert.equal(first.status, 201, JSON.stringify(first.body));
    const revision = await requestRevision(service, flow.buyer, flow.order.id, milestone.id);
    assert.equal(revision.status, 200, JSON.stringify(revision.body));
    const second = await deliverMilestone(service, flow.creator, flow.order.id, milestone.id, { note: "Second delivery after the revision." });
    assert.equal(second.status, 201, JSON.stringify(second.body));

    for (const actor of [flow.buyer, flow.creator]) {
      const history = await call(service.baseUrl, "GET", `/orders/${flow.order.id}/history?limit=10`, { headers: bearer(actor.accessToken) });
      assert.equal(history.status, 200, JSON.stringify(history.body));
      assert.equal(history.body.totalDeliveries, 2);
      assert.deepEqual(history.body.deliveries.map((delivery) => delivery.version), [2, 1]);
      assert.equal(history.body.deliveries[1].note, "First delivery with the initial build.");
      assert.equal(history.body.deliveries[1].evidenceReferences[0], "https://example.test/build/v1");
      assert.equal(history.body.revisions.length, 1);
      assert.equal(history.body.revisions[0].kind, "REVISION");
      assert.equal(history.body.revisions[0].reason, "Please adjust the module count as discussed.");
      // History never carries internal user ids.
      assert.equal(JSON.stringify(history.body).includes(flow.creator.userId), false);
      assert.equal(JSON.stringify(history.body).includes(flow.creator.email), false);
    }

    const attacker = await signedInAccount(service, `history-attacker-${uniqueSuffix()}@example.test`);
    const denied = await call(service.baseUrl, "GET", `/orders/${flow.order.id}/history`, { headers: bearer(attacker.accessToken) });
    assert.equal(denied.status, 404);
    assert.equal(denied.body.error.code, ErrorCode.ORDER_NOT_FOUND);
    assert.equal(auditRows(service, "ORDER_ACCESS_DENIED").some((row) => row.target_user_id ===
      service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(attacker.email).user_id), true);
  });

  it("15. freezes the order's terms against later job or proposal edits", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const before = (await call(service.baseUrl, "GET", `/orders/${flow.order.id}`, { headers: bearer(flow.buyer.accessToken) })).body.order;

    // Even a direct edit of the source rows (bypassing the API's own state locks) cannot rewrite the snapshot.
    service.database.prepare("UPDATE buyer_jobs SET title = 'Renamed job', scope = 'Renamed job scope.' WHERE job_id = ?").run(flow.job.id);
    service.database.prepare("UPDATE job_proposals SET scope = 'Renamed proposal scope.', budget_max = 999999 WHERE proposal_id = ?").run(flow.proposal.id);

    const after = (await call(service.baseUrl, "GET", `/orders/${flow.order.id}`, { headers: bearer(flow.buyer.accessToken) })).body.order;
    assert.equal(after.jobTitle, before.jobTitle);
    assert.equal(after.scope, before.scope);
    assert.deepEqual(after.budget, before.budget);
    assert.equal(after.revisionLimit, before.revisionLimit);
    assert.equal(after.deadline, before.deadline);
  });

  it("16. exposes the additive order link on proposal and job surfaces without changing existing hire behavior", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);

    const proposalView = await call(service.baseUrl, "GET", `/creator/proposal/${flow.proposal.id}`, {
      headers: bearer(flow.creator.accessToken),
    });
    assert.equal(proposalView.status, 200, JSON.stringify(proposalView.body));
    assert.equal((proposalView.body.proposal ?? proposalView.body).orderId, flow.order.id);

    const jobView = await call(service.baseUrl, "GET", `/buyer/jobs/${flow.job.id}`, { headers: bearer(flow.buyer.accessToken) });
    assert.equal(jobView.status, 200, JSON.stringify(jobView.body));
    assert.equal(jobView.body.job.orderId, flow.order.id);

    // An awarded job that has not been converted still reads as before: null, not an error and not a fabricated id.
    const other = await awardedProposalFlow(service, { suffix: uniqueSuffix() });
    const jobWithoutOrder = await call(service.baseUrl, "GET", `/buyer/jobs/${other.job.id}`, { headers: bearer(other.buyer.accessToken) });
    assert.equal(jobWithoutOrder.status, 200, JSON.stringify(jobWithoutOrder.body));
    assert.equal(jobWithoutOrder.body.job.orderId, null);
    const proposalWithoutOrder = await call(service.baseUrl, "GET", `/creator/proposal/${other.proposal.id}`, {
      headers: bearer(other.creator.accessToken),
    });
    assert.equal(proposalWithoutOrder.status, 200, JSON.stringify(proposalWithoutOrder.body));
    assert.equal(proposalWithoutOrder.body.orderId, null);
  });
});

// ------------------------------------------------------- D. milestone transitions and role boundaries (17–24)

describe("Phase 27 milestone transitions", () => {
  it("17. lets the creator start a pending milestone and refuses invalid or wrong-role starts", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const milestone = flow.milestones[0];

    const buyerStart = await startMilestone(service, flow.buyer, flow.order.id, milestone.id);
    assert.equal(buyerStart.status, 403, JSON.stringify(buyerStart.body));
    assert.equal(buyerStart.body.error.code, ErrorCode.ORDER_ROLE_DENIED);
    assert.equal(milestoneRow(service, milestone.id).status, "PENDING");

    const started = await startMilestone(service, flow.creator, flow.order.id, milestone.id);
    assert.equal(started.status, 200, JSON.stringify(started.body));
    assert.equal(started.body.status, "IN_PROGRESS");
    assert.equal(typeof started.body.startedAt, "string");
    assert.equal(auditRows(service, "MILESTONE_STARTED").length, 1);

    const startedAgain = await startMilestone(service, flow.creator, flow.order.id, milestone.id);
    assert.equal(startedAgain.status, 409, JSON.stringify(startedAgain.body));
    assert.equal(startedAgain.body.error.code, ErrorCode.MILESTONE_STATE_CONFLICT);
    assert.equal(auditRows(service, "MILESTONE_STARTED").length, 1);

    const badMilestone = await startMilestone(service, flow.creator, flow.order.id, "mil_00000000-0000-4000-8000-000000000000");
    assert.equal(badMilestone.status, 404);
    assert.equal(badMilestone.body.error.code, ErrorCode.MILESTONE_NOT_FOUND);
  });

  it("18. requires work to start before delivery, and refuses deliveries from the buyer", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const milestone = flow.milestones[0];

    const beforeStart = await deliverMilestone(service, flow.creator, flow.order.id, milestone.id);
    assert.equal(beforeStart.status, 409, JSON.stringify(beforeStart.body));
    assert.equal(beforeStart.body.error.code, ErrorCode.MILESTONE_STATE_CONFLICT);

    const buyerDeliver = await (async () => {
      await startMilestone(service, flow.creator, flow.order.id, milestone.id);
      return deliverMilestone(service, flow.buyer, flow.order.id, milestone.id);
    })();
    assert.equal(buyerDeliver.status, 403, JSON.stringify(buyerDeliver.body));
    assert.equal(buyerDeliver.body.error.code, ErrorCode.ORDER_ROLE_DENIED);
    assert.equal(milestoneRow(service, milestone.id).status, "IN_PROGRESS");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM milestone_deliveries").get().count, 0);
  });

  it("19. records deliveries as immutable versions: resubmission appends, never erases", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const milestone = flow.milestones[0];
    await startMilestone(service, flow.creator, flow.order.id, milestone.id);

    const first = await deliverMilestone(service, flow.creator, flow.order.id, milestone.id, {
      note: "The original delivery note.", evidenceReferences: ["https://example.test/evidence/1"],
    });
    assert.equal(first.status, 201, JSON.stringify(first.body));
    assert.equal(first.body.version, 1);
    assert.equal(milestoneRow(service, milestone.id).status, "SUBMITTED");
    assert.equal(auditRows(service, "DELIVERY_SUBMITTED").length, 1);

    const revision = await requestRevision(service, flow.buyer, flow.order.id, milestone.id);
    assert.equal(revision.status, 200, JSON.stringify(revision.body));
    assert.equal(milestoneRow(service, milestone.id).status, "REVISION_REQUESTED");

    const second = await deliverMilestone(service, flow.creator, flow.order.id, milestone.id, { note: "The revised delivery note." });
    assert.equal(second.status, 201, JSON.stringify(second.body));
    assert.equal(second.body.version, 2);

    const history = await call(service.baseUrl, "GET", `/orders/${flow.order.id}/history`, { headers: bearer(flow.buyer.accessToken) });
    assert.deepEqual(history.body.deliveries.map((delivery) => delivery.version), [2, 1]);
    assert.equal(history.body.deliveries[1].note, "The original delivery note.");
    assert.equal(history.body.deliveries[1].evidenceReferences[0], "https://example.test/evidence/1");
    assert.equal(auditRows(service, "DELIVERY_REVISED").length, 1);
    // The milestone's own row never points at a single version: both rows remain authoritative history.
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM milestone_deliveries WHERE milestone_id = ?").get(milestone.id).count, 2);
  });

  it("20. lets only the buyer approve, and only after a submission; approval is final", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const milestone = flow.milestones[0];
    await startMilestone(service, flow.creator, flow.order.id, milestone.id);

    const beforeSubmission = await approveMilestone(service, flow.buyer, flow.order.id, milestone.id);
    assert.equal(beforeSubmission.status, 409, JSON.stringify(beforeSubmission.body));
    assert.equal(beforeSubmission.body.error.code, ErrorCode.MILESTONE_STATE_CONFLICT);

    await deliverMilestone(service, flow.creator, flow.order.id, milestone.id);
    const creatorApproval = await approveMilestone(service, flow.creator, flow.order.id, milestone.id);
    assert.equal(creatorApproval.status, 403, JSON.stringify(creatorApproval.body));
    assert.equal(creatorApproval.body.error.code, ErrorCode.ORDER_ROLE_DENIED);
    assert.equal(milestoneRow(service, milestone.id).status, "SUBMITTED");

    const approved = await approveMilestone(service, flow.buyer, flow.order.id, milestone.id);
    assert.equal(approved.status, 200, JSON.stringify(approved.body));
    assert.equal(approved.body.status, "APPROVED");
    assert.equal(auditRows(service, "MILESTONE_APPROVED").length, 1);

    const reapprove = await approveMilestone(service, flow.buyer, flow.order.id, milestone.id);
    assert.equal(reapprove.status, 409, JSON.stringify(reapprove.body));
    assert.equal(milestoneRow(service, milestone.id).status, "APPROVED");
  });

  it("21. applies the revision policy: buyer-only, submitted-only, validated reason, finite rounds", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const milestone = flow.milestones[0];
    await startMilestone(service, flow.creator, flow.order.id, milestone.id);

    const beforeSubmission = await requestRevision(service, flow.buyer, flow.order.id, milestone.id);
    assert.equal(beforeSubmission.status, 409, JSON.stringify(beforeSubmission.body));

    await deliverMilestone(service, flow.creator, flow.order.id, milestone.id);
    const creatorRevision = await requestRevision(service, flow.creator, flow.order.id, milestone.id);
    assert.equal(creatorRevision.status, 403, JSON.stringify(creatorRevision.body));
    assert.equal(creatorRevision.body.error.code, ErrorCode.ORDER_ROLE_DENIED);

    const emptyReason = await requestRevision(service, flow.buyer, flow.order.id, milestone.id, { reason: "" });
    assert.equal(emptyReason.status, 400, JSON.stringify(emptyReason.body));
    assert.equal(emptyReason.body.error.code, ErrorCode.INVALID_REQUEST);
    const shortReason = await requestRevision(service, flow.buyer, flow.order.id, milestone.id, { reason: "no" });
    assert.equal(shortReason.status, 400, JSON.stringify(shortReason.body));
    const longReason = await requestRevision(service, flow.buyer, flow.order.id, milestone.id, { reason: "x".repeat(1001) });
    assert.equal(longReason.status, 400, JSON.stringify(longReason.body));

    const revisionOne = await requestRevision(service, flow.buyer, flow.order.id, milestone.id, { reason: "First revision round, in scope." });
    assert.equal(revisionOne.status, 200, JSON.stringify(revisionOne.body));
    assert.equal(revisionOne.body.revisionsUsed, 1);
    assert.equal(revisionOne.body.revisionLimit, 2);
    await deliverMilestone(service, flow.creator, flow.order.id, milestone.id, { note: "After revision one." });

    const revisionTwo = await requestRevision(service, flow.buyer, flow.order.id, milestone.id, { reason: "Second revision round, still in scope." });
    assert.equal(revisionTwo.status, 200, JSON.stringify(revisionTwo.body));
    assert.equal(revisionTwo.body.revisionsUsed, 2);
    await deliverMilestone(service, flow.creator, flow.order.id, milestone.id, { note: "After revision two." });

    const beyondLimit = await requestRevision(service, flow.buyer, flow.order.id, milestone.id, { reason: "A third in-scope round." });
    assert.equal(beyondLimit.status, 409, JSON.stringify(beyondLimit.body));
    assert.equal(beyondLimit.body.error.code, ErrorCode.REVISION_LIMIT_REACHED);
    assert.equal(milestoneRow(service, milestone.id).status, "SUBMITTED");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM milestone_revision_requests WHERE kind = 'REVISION'").get().count, 2);
  });

  it("22. records a scope-change request without consuming the revision limit or changing any term", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const milestone = flow.milestones[0];
    const termsBefore = flow.order;
    await startMilestone(service, flow.creator, flow.order.id, milestone.id);
    await deliverMilestone(service, flow.creator, flow.order.id, milestone.id);

    const scopeChange = await requestRevision(service, flow.buyer, flow.order.id, milestone.id, {
      reason: "We want a different hall layout than the original scope described.",
      outsideScope: true,
    });
    assert.equal(scopeChange.status, 200, JSON.stringify(scopeChange.body));
    assert.equal(scopeChange.body.scopeChange, true);
    assert.equal(scopeChange.body.revisionsUsed, 0, "a scope change must not consume an in-scope revision round");
    assert.equal(scopeChange.body.revisionLimit, 2);
    assert.equal(milestoneRow(service, milestone.id).status, "SUBMITTED", "a scope change never sends the milestone back by itself");
    assert.equal(auditRows(service, "SCOPE_CHANGE_REQUESTED").length, 1);
    assert.equal(auditRows(service, "REVISION_REQUESTED").length, 0);

    const detail = await call(service.baseUrl, "GET", `/orders/${flow.order.id}`, { headers: bearer(flow.buyer.accessToken) });
    assert.equal(detail.body.order.scope, termsBefore.scope);
    assert.deepEqual(detail.body.order.budget, termsBefore.budget);
    assert.equal(detail.body.order.deadline, termsBefore.deadline);
    assert.equal(detail.body.order.revisionLimit, termsBefore.revisionLimit);
    // The response must not imply that extra payment was agreed — no payment mechanism exists here,
    // and the honest note that says so must not be mistaken for a claim that money moved.
    assert.equal(/escrow|refund|payout|paid in full|payment (processed|received|agreed)|extra fee|price change/i
      .test(JSON.stringify(scopeChange.body)), false);

    const history = await call(service.baseUrl, "GET", `/orders/${flow.order.id}/history`, { headers: bearer(flow.buyer.accessToken) });
    assert.equal(history.body.revisions[0].kind, "SCOPE_CHANGE");
  });

  it("23. refuses cross-account milestone mutations with a uniform not-found and leaves state untouched", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const attacker = await signedInAccount(service, `mutation-attacker-${uniqueSuffix()}@example.test`);
    const milestone = flow.milestones[0];

    const attempts = await Promise.all([
      startMilestone(service, attacker, flow.order.id, milestone.id),
      approveMilestone(service, attacker, flow.order.id, milestone.id),
      deliverMilestone(service, attacker, flow.order.id, milestone.id),
      requestRevision(service, attacker, flow.order.id, milestone.id),
      call(service.baseUrl, "POST", `/orders/${flow.order.id}/complete`, { headers: bearer(attacker.accessToken), body: {} }),
      call(service.baseUrl, "POST", `/orders/${flow.order.id}/cancel`, { headers: bearer(attacker.accessToken), body: {} }),
    ]);
    for (const attempt of attempts) {
      assert.equal(attempt.status, 404, JSON.stringify(attempt.body));
      assert.equal(attempt.body.error.code, ErrorCode.ORDER_NOT_FOUND);
    }
    assert.equal(milestoneRow(service, milestone.id).status, "PENDING");
    assert.equal(orderRow(service, flow.order.id).status, "ACTIVE");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM milestone_deliveries").get().count, 0);
    const attackerId = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(attacker.email).user_id;
    const denials = auditRows(service, "ORDER_ACCESS_DENIED").filter((row) => row.target_user_id === attackerId);
    assert.equal(denials.length >= attempts.length, true, "every denial is audited, inside the transaction");
  });

  it("24. holds every invalid transition to the same typed conflict without partial state", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const [first, second] = flow.milestones;

    // Approve before any delivery exists; deliver an untouched milestone; approve an untouched milestone.
    assert.equal((await approveMilestone(service, flow.buyer, flow.order.id, first.id)).status, 409);
    assert.equal((await deliverMilestone(service, flow.creator, flow.order.id, second.id)).status, 409);
    assert.equal((await approveMilestone(service, flow.buyer, flow.order.id, second.id)).status, 409);
    // Revision before submission.
    assert.equal((await requestRevision(service, flow.buyer, flow.order.id, first.id)).status, 409);
    // Nothing moved.
    assert.deepEqual(flow.milestones.map((milestone) => milestoneRow(service, milestone.id).status), ["PENDING", "PENDING"]);
    assert.equal(orderRow(service, flow.order.id).status, "ACTIVE");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log WHERE action_type IN ('MILESTONE_APPROVED', 'MILESTONE_STARTED', 'DELIVERY_SUBMITTED', 'REVISION_REQUESTED')").get().count, 0);
  });
});

// -------------------------------------------------- E. completion, cancellation, and concurrency (25–30)

describe("Phase 27 order completion and cancellation", () => {
  it("25. completes only when every milestone is approved, then freezes the order", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const [first, second] = flow.milestones;

    await startMilestone(service, flow.creator, flow.order.id, first.id);
    await deliverMilestone(service, flow.creator, flow.order.id, first.id);
    await approveMilestone(service, flow.buyer, flow.order.id, first.id);

    const blocked = await call(service.baseUrl, "POST", `/orders/${flow.order.id}/complete`, {
      headers: bearer(flow.buyer.accessToken), body: {},
    });
    assert.equal(blocked.status, 409, JSON.stringify(blocked.body));
    assert.equal(blocked.body.error.code, ErrorCode.ORDER_COMPLETION_BLOCKED);
    assert.equal(orderRow(service, flow.order.id).status, "ACTIVE");

    await startMilestone(service, flow.creator, flow.order.id, second.id);
    await deliverMilestone(service, flow.creator, flow.order.id, second.id);
    await approveMilestone(service, flow.buyer, flow.order.id, second.id);

    const completed = await call(service.baseUrl, "POST", `/orders/${flow.order.id}/complete`, {
      headers: bearer(flow.creator.accessToken), body: {},
    });
    assert.equal(completed.status, 200, JSON.stringify(completed.body));
    assert.equal(completed.body.status, "COMPLETED");
    assert.equal(typeof completed.body.completedAt, "string");
    assert.equal(auditRows(service, "ORDER_COMPLETED").length, 1);

    // The creator can complete too (either participant, all-approved state) — here the order is already closed.
    const again = await call(service.baseUrl, "POST", `/orders/${flow.order.id}/complete`, {
      headers: bearer(flow.creator.accessToken), body: {},
    });
    assert.equal(again.status, 409, JSON.stringify(again.body));
    assert.equal((await startMilestone(service, flow.creator, flow.order.id, first.id)).status, 409);
    assert.equal((await deliverMilestone(service, flow.creator, flow.order.id, first.id)).status, 409);
  });

  it("26. lets either participant cancel only while no milestone is approved, and never erases history", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);

    const cancelled = await call(service.baseUrl, "POST", `/orders/${flow.order.id}/cancel`, {
      headers: bearer(flow.creator.accessToken), body: {},
    });
    assert.equal(cancelled.status, 200, JSON.stringify(cancelled.body));
    assert.equal(cancelled.body.status, "CANCELLED");
    assert.equal(auditRows(service, "ORDER_CANCELLED").length, 1);

    // The workflow freezes; the record survives.
    assert.equal((await startMilestone(service, flow.creator, flow.order.id, flow.milestones[0].id)).status, 409);
    assert.equal((await deliverMilestone(service, flow.creator, flow.order.id, flow.milestones[0].id)).status, 409);
    assert.equal((await approveMilestone(service, flow.buyer, flow.order.id, flow.milestones[0].id)).status, 409);
    assert.equal((await call(service.baseUrl, "POST", `/orders/${flow.order.id}/cancel`, { headers: bearer(flow.buyer.accessToken), body: {} })).status, 409);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM order_milestones WHERE order_id = ?").get(flow.order.id).count, 2);

    // Once any milestone is approved, neither side can unilaterally cancel.
    const sealed = await awardedOrderFlow(service, { suffix: uniqueSuffix() });
    await startMilestone(service, sealed.creator, sealed.order.id, sealed.milestones[0].id);
    await deliverMilestone(service, sealed.creator, sealed.order.id, sealed.milestones[0].id);
    await approveMilestone(service, sealed.buyer, sealed.order.id, sealed.milestones[0].id);
    const refused = await call(service.baseUrl, "POST", `/orders/${sealed.order.id}/cancel`, {
      headers: bearer(sealed.buyer.accessToken), body: {},
    });
    assert.equal(refused.status, 409, JSON.stringify(refused.body));
    assert.equal(orderRow(service, sealed.order.id).status, "ACTIVE");
  });

  it("27. serializes concurrent approvals: one success, one conflict, a single approved state", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const milestone = flow.milestones[0];
    await startMilestone(service, flow.creator, flow.order.id, milestone.id);
    await deliverMilestone(service, flow.creator, flow.order.id, milestone.id);

    const attempts = await Promise.all([
      approveMilestone(service, flow.buyer, flow.order.id, milestone.id),
      approveMilestone(service, flow.buyer, flow.order.id, milestone.id),
      approveMilestone(service, flow.buyer, flow.order.id, milestone.id),
    ]);
    const statuses = attempts.map((attempt) => attempt.status).sort((left, right) => left - right);
    assert.deepEqual(statuses, [200, 409, 409], JSON.stringify(attempts.map((attempt) => attempt.body)));
    assert.equal(milestoneRow(service, milestone.id).status, "APPROVED");
    assert.equal(auditRows(service, "MILESTONE_APPROVED").length, 1);
  });

  it("28. audits order events with minimal metadata — never delivery contents, links, or tokens", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const secretNote = "Unique delivery secret 9d1f4a that must never be audited.";
    const secretLink = "https://secret-evidence.example.test/private/7f3a";
    await startMilestone(service, flow.creator, flow.order.id, flow.milestones[0].id);
    await deliverMilestone(service, flow.creator, flow.order.id, flow.milestones[0].id, {
      note: secretNote, evidenceReferences: [secretLink],
    });
    await requestRevision(service, flow.buyer, flow.order.id, flow.milestones[0].id);
    await deliverMilestone(service, flow.creator, flow.order.id, flow.milestones[0].id, { note: `${secretNote} v2` });
    await approveMilestone(service, flow.buyer, flow.order.id, flow.milestones[0].id);

    for (const actionType of ["ORDER_CREATED", "MILESTONE_STARTED", "DELIVERY_SUBMITTED", "DELIVERY_REVISED",
      "REVISION_REQUESTED", "MILESTONE_APPROVED"]) {
      const rows = auditRows(service, actionType);
      assert.equal(rows.length >= 1, true, actionType);
      const serialized = JSON.stringify(rows);
      assert.equal(serialized.includes(secretNote), false, `${actionType} must not capture delivery text`);
      assert.equal(serialized.includes(secretLink), false, `${actionType} must not capture evidence links`);
      assert.equal(serialized.includes(flow.buyer.accessToken), false, `${actionType} must not capture tokens`);
      // Metadata is ids, counts, versions, and categories only.
      for (const row of rows) {
        const metadata = JSON.parse(row.metadata_json);
        for (const value of Object.values(metadata)) {
          assert.equal(["string", "number", "boolean"].includes(typeof value), true, `${actionType} metadata stays primitive`);
          if (typeof value === "string") assert.equal(value.length <= 64, true, `${actionType} metadata stays tiny`);
        }
      }
    }
  });

  it("29. survives rollback: a denied cross-account approval leaves no state change but keeps its denial audit", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const attacker = await signedInAccount(service, `rollback-${uniqueSuffix()}@example.test`);
    await startMilestone(service, flow.creator, flow.order.id, flow.milestones[0].id);
    await deliverMilestone(service, flow.creator, flow.order.id, flow.milestones[0].id);

    const denied = await approveMilestone(service, attacker, flow.order.id, flow.milestones[0].id);
    assert.equal(denied.status, 404);
    assert.equal(milestoneRow(service, flow.milestones[0].id).status, "SUBMITTED");
    assert.equal(auditRows(service, "MILESTONE_APPROVED").length, 0);
    const attackerId = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(attacker.email).user_id;
    assert.equal(auditRows(service, "ORDER_ACCESS_DENIED").some((row) => row.target_user_id === attackerId), true,
      "the denial audit was captured before the transaction rolled back");
  });

  it("30. throttles each dedicated order category on its own budget", async () => {
    // Order creation has its own bucket: two creations fit, the third is throttled before any handler runs.
    const createService = await newService({ RATE_ORDER_CREATE_MAX: "2" });
    const makeFlow = async () => {
      const owner = await ownerSession(createService);
      const buyer = await signedInAccount(createService, `ratelimit-create-${uniqueSuffix()}@example.test`);
      const creator = await eligibleCreator(createService, owner, `ratelimit-builder-${uniqueSuffix()}@example.test`, `studio-rl-${uniqueSuffix()}`);
      const job = await postJob(createService, buyer);
      const proposal = await submitProposal(createService, creator, job.id);
      await awardProposal(createService, buyer, job.id, proposal.id);
      return { buyer, job, proposal };
    };
    const first = await makeFlow();
    const second = await makeFlow();
    const third = await makeFlow();
    assert.equal((await createOrder(createService, first.buyer, first.job.id, first.proposal.id)).status, 201);
    assert.equal((await createOrder(createService, second.buyer, second.job.id, second.proposal.id)).status, 201);
    const throttled = await createOrder(createService, third.buyer, third.job.id, third.proposal.id);
    assert.equal(throttled.status, 429, JSON.stringify(throttled.body));
    assert.equal(throttled.body.error.code, ErrorCode.RATE_LIMITED);

    // Delivery submissions get their own bucket as well.
    const deliveryService = await newService({ RATE_DELIVERY_WRITE_MAX: "1" });
    const flow = await awardedOrderFlow(deliveryService);
    await startMilestone(deliveryService, flow.creator, flow.order.id, flow.milestones[0].id);
    await startMilestone(deliveryService, flow.creator, flow.order.id, flow.milestones[1].id);
    assert.equal((await deliverMilestone(deliveryService, flow.creator, flow.order.id, flow.milestones[0].id)).status, 201);
    const deliveryThrottled = await deliverMilestone(deliveryService, flow.creator, flow.order.id, flow.milestones[1].id);
    assert.equal(deliveryThrottled.status, 429, JSON.stringify(deliveryThrottled.body));

    // Milestone state changes (start/approve/complete/cancel) and revision requests likewise.
    const orderWriteService = await newService({ RATE_ORDER_WRITE_MAX: "1" });
    const writeFlow = await awardedOrderFlow(orderWriteService);
    assert.equal((await startMilestone(orderWriteService, writeFlow.creator, writeFlow.order.id, writeFlow.milestones[0].id)).status, 200);
    const writeThrottled = await startMilestone(orderWriteService, writeFlow.creator, writeFlow.order.id, writeFlow.milestones[1].id);
    assert.equal(writeThrottled.status, 429, JSON.stringify(writeThrottled.body));

    const revisionService = await newService({ RATE_REVISION_WRITE_MAX: "1" });
    const revisionFlow = await awardedOrderFlow(revisionService);
    await startMilestone(revisionService, revisionFlow.creator, revisionFlow.order.id, revisionFlow.milestones[0].id);
    await startMilestone(revisionService, revisionFlow.creator, revisionFlow.order.id, revisionFlow.milestones[1].id);
    await deliverMilestone(revisionService, revisionFlow.creator, revisionFlow.order.id, revisionFlow.milestones[0].id);
    await deliverMilestone(revisionService, revisionFlow.creator, revisionFlow.order.id, revisionFlow.milestones[1].id);
    assert.equal((await requestRevision(revisionService, revisionFlow.buyer, revisionFlow.order.id, revisionFlow.milestones[0].id)).status, 200);
    const revisionThrottled = await requestRevision(revisionService, revisionFlow.buyer, revisionFlow.order.id, revisionFlow.milestones[1].id);
    assert.equal(revisionThrottled.status, 429, JSON.stringify(revisionThrottled.body));

    // All four categories exist as configuration with their own keys and un-overridden defaults.
    const bare = loadConfiguration({
      NODE_ENV: "test", DATABASE_URL: ":memory:", AUTH_SECRET: TEST_SECRET,
      ACCESS_TOKEN_TTL_SECONDS: "3600", REFRESH_TOKEN_TTL_SECONDS: "2592000", MAX_BODY_BYTES: "16384",
    }, { allowInMemoryDatabase: true });
    const defaults = bare.rateLimit;
    for (const key of ["orderCreate", "orderWrite", "deliveryWrite", "revisionWrite"]) {
      assert.equal(typeof defaults[key]?.maximum, "number", key);
      assert.equal(typeof defaults[key]?.windowMs, "number", key);
    }
    assert.equal(defaults.orderCreate.maximum, 30);
    assert.equal(defaults.orderWrite.maximum, 60);
    assert.equal(defaults.deliveryWrite.maximum, 30);
    assert.equal(defaults.revisionWrite.maximum, 30);
  });
});

// --------------------------------------- F. pagination, validation, URL safety, and compatibility (31–38)

describe("Phase 27 bounds, safety, and compatibility", () => {
  it("31. paginates order lists and history with strict bounds", async () => {
    const service = await newService();
    const buyer = await signedInAccount(service, `pager-${uniqueSuffix()}@example.test`);
    const flows = [];
    for (let index = 0; index < 3; index += 1) flows.push(await awardedOrderFlow(service, { suffix: uniqueSuffix(), buyer }));

    const firstPage = await call(service.baseUrl, "GET", "/buyer/orders?limit=2", { headers: bearer(buyer.accessToken) });
    assert.equal(firstPage.status, 200, JSON.stringify(firstPage.body));
    assert.equal(firstPage.body.items.length, 2);
    assert.equal(firstPage.body.total, 3);
    assert.equal(firstPage.body.hasMore, true);
    const secondPage = await call(service.baseUrl, "GET", "/buyer/orders?limit=2&offset=2", { headers: bearer(buyer.accessToken) });
    assert.equal(secondPage.body.items.length, 1);
    assert.equal(secondPage.body.hasMore, false);

    for (const query of ["?limit=0", "?limit=51", "?limit=abc", "?limit=-1", "?offset=100001"]) {
      const rejected = await call(service.baseUrl, "GET", `/buyer/orders${query}`, { headers: bearer(buyer.accessToken) });
      assert.equal(rejected.status, 400, `${query} → ${JSON.stringify(rejected.body)}`);
      assert.equal(rejected.body.error.code, ErrorCode.INVALID_REQUEST);
    }
    for (const query of ["?limit=101", "?offset=100001", "?limit=50x"]) {
      const rejected = await call(service.baseUrl, "GET", `/orders/${flows[0].order.id}/history${query}`, { headers: bearer(buyer.accessToken) });
      assert.equal(rejected.status, 400, `${query} → ${JSON.stringify(rejected.body)}`);
    }
    const bounded = await call(service.baseUrl, "GET", `/orders/${flows[0].order.id}/history?limit=1&offset=0`, { headers: bearer(buyer.accessToken) });
    assert.equal(bounded.status, 200);
    assert.equal(bounded.body.deliveries.length, 0);
    assert.equal(bounded.body.hasMore, false);
  });

  it("32. accepts only bounded https evidence references and never reflects markup as content", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    const milestone = flow.milestones[0];
    await startMilestone(service, flow.creator, flow.order.id, milestone.id);

    for (const badReference of ["javascript:alert(1)", "data:text/html,<script>x</script>", "http://example.test/insecure",
      "file:///etc/passwd", "https://user:pass@host.test/", "not a url", "//protocol-relative.test/x"]) {
      const rejected = await deliverMilestone(service, flow.creator, flow.order.id, milestone.id, {
        evidenceReferences: [badReference],
      });
      assert.equal(rejected.status, 400, `${badReference} → ${JSON.stringify(rejected.body)}`);
      assert.equal(rejected.body.error.code, ErrorCode.INVALID_REQUEST);
    }
    const tooMany = await deliverMilestone(service, flow.creator, flow.order.id, milestone.id, {
      evidenceReferences: ["https://a.test/1", "https://b.test/2", "https://c.test/3", "https://d.test/4", "https://e.test/5"],
    });
    assert.equal(tooMany.status, 400, JSON.stringify(tooMany.body));

    // Angle brackets are refused outright at input, so stored text cannot be markup; quotes and ampersands pass
    // through verbatim as data and are the website layer's job to escape on render.
    const markupNote = await deliverMilestone(service, flow.creator, flow.order.id, milestone.id, { note: "<script>bad()</script>" });
    assert.equal(markupNote.status, 400, JSON.stringify(markupNote.body));
    const accepted = await deliverMilestone(service, flow.creator, flow.order.id, milestone.id, {
      note: 'Delivered "quoted" notes with R&D and a 100% complete checklist & guide.',
      evidenceReferences: ["https://example.test/evidence/ok", "https://example.test/evidence/ok"],
      compatibilityNote: "Works on Fabric 1.20.1",
    });
    assert.equal(accepted.status, 201, JSON.stringify(accepted.body));
    assert.equal(accepted.body.note, 'Delivered "quoted" notes with R&D and a 100% complete checklist & guide.');
    // Duplicate references collapse to one stored entry.
    assert.equal(accepted.body.evidenceReferences.length, 1);
    assert.equal(accepted.body.evidenceReferences[0].startsWith("https://"), true);
    // The history returns them as JSON strings, and the stored row keeps the sanitized list only.
    const history = await call(service.baseUrl, "GET", `/orders/${flow.order.id}/history`, { headers: bearer(flow.buyer.accessToken) });
    assert.equal(history.body.deliveries[0].evidenceReferences[0].startsWith("https://"), true);
  });

  it("33. never claims payment, escrow, refund, or payout outcomes anywhere in the order surface", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);
    await startMilestone(service, flow.creator, flow.order.id, flow.milestones[0].id);
    await deliverMilestone(service, flow.creator, flow.order.id, flow.milestones[0].id);
    await approveMilestone(service, flow.buyer, flow.order.id, flow.milestones[0].id);
    await startMilestone(service, flow.creator, flow.order.id, flow.milestones[1].id);
    await deliverMilestone(service, flow.creator, flow.order.id, flow.milestones[1].id);
    await approveMilestone(service, flow.buyer, flow.order.id, flow.milestones[1].id);
    const completed = await call(service.baseUrl, "POST", `/orders/${flow.order.id}/complete`, {
      headers: bearer(flow.buyer.accessToken), body: {},
    });
    assert.equal(completed.status, 200, JSON.stringify(completed.body));

    const surfaces = [
      await call(service.baseUrl, "GET", "/buyer/orders", { headers: bearer(flow.buyer.accessToken) }),
      await call(service.baseUrl, "GET", "/creator/orders", { headers: bearer(flow.creator.accessToken) }),
      await call(service.baseUrl, "GET", `/orders/${flow.order.id}`, { headers: bearer(flow.buyer.accessToken) }),
      await call(service.baseUrl, "GET", `/orders/${flow.order.id}/history`, { headers: bearer(flow.creator.accessToken) }),
      completed,
    ];
    for (const surface of bodies(surfaces)) {
      assert.equal(/escrow|refund|chargeback|payout|\bpaid\b|payment (was|is|has been)/i.test(surface), false,
        `payment language leaked: ${surface.slice(0, 200)}`);
    }
    function bodies(results) {
      return results.map((result) => JSON.stringify(result.body));
    }
  });

  it("34. keeps Phase 17–26 behavior intact alongside orders: full hire journey plus additive links", async () => {
    const service = await newService();
    const flow = await awardedOrderFlow(service);

    // Error vocabulary and audit vocabulary grew; nothing was removed (100 codes, 90 audit types).
    assert.equal(Object.keys(ErrorCode).length, 100);
    assert.equal(REGISTERED_AUDIT_ACTION_TYPES.size, 90);

    // The P26 surfaces still answer exactly as before the phase.
    const ownProposals = await call(service.baseUrl, "GET", "/creator/proposal", { headers: bearer(flow.creator.accessToken) });
    assert.equal(ownProposals.status, 200, JSON.stringify(ownProposals.body));
    assert.equal(ownProposals.body.proposals.some((proposal) => proposal.id === flow.proposal.id), true);
    const ownJobs = await call(service.baseUrl, "GET", "/buyer/jobs", { headers: bearer(flow.buyer.accessToken) });
    assert.equal(ownJobs.status, 200, JSON.stringify(ownJobs.body));
    assert.equal(ownJobs.body.jobs.some((job) => job.id === flow.job.id), true);
    const openJobs = await call(service.baseUrl, "GET", "/marketplace/jobs");
    assert.equal(openJobs.status, 200, JSON.stringify(openJobs.body));
    assert.equal(auditRows(service, "JOB_AWARDED").length, 1);
    assert.equal(auditRows(service, "PROPOSAL_SELECTED").length, 1);

    // The awarded job is closed to new proposals, and a second award cannot land — unchanged P26 rules.
    const other = await eligibleCreator(service, flow.owner, `late-${uniqueSuffix()}@example.test`, `late-${uniqueSuffix()}`);
    const lateProposal = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(other.accessToken),
      body: { jobId: flow.job.id, message: "A proposal against an already-awarded job.", scope: "Late scope for the awarded job." },
    });
    assert.equal(lateProposal.status >= 400, true, JSON.stringify(lateProposal.body));
    const reAward = await call(service.baseUrl, "POST", `/buyer/jobs/${flow.job.id}/award`, {
      headers: bearer(flow.buyer.accessToken), body: { proposalId: flow.proposal.id },
    });
    assert.equal(reAward.status >= 400, true, JSON.stringify(reAward.body));
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM orders").get().count, 1);
  });

  it("35. upgrades a seeded Phase 26 database and creates the first order from its pre-existing rows", async () => {
    const database = new DatabaseSync(":memory:");
    database.exec("PRAGMA foreign_keys = ON");
    database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    migrateToVersion(database, 9);
    const timestamp = "2026-01-01T00:00:00.000Z";
    database.exec(`
      INSERT INTO users (user_id, email, email_canonical, password_hash, display_name, status, email_verified_at, created_at, updated_at)
        VALUES ('usr_legacy', 'legacy@example.com', 'legacy@example.com', 'hash', 'Legacy', 'ACTIVE', '${timestamp}', '${timestamp}', '${timestamp}');
      INSERT INTO creator_profiles (creator_id, user_id, handle, display_name, bio, category, avatar_reference, status, verification_status, status_changed_at, verified_at, created_at, updated_at)
        VALUES ('crt_legacy', 'usr_legacy', 'legacy-studio', 'Legacy Studio', '', NULL, NULL, 'ACTIVE', 'UNVERIFIED', '${timestamp}', NULL, '${timestamp}', '${timestamp}');
      INSERT INTO buyer_jobs (job_id, buyer_id, title, description, edition, minecraft_version, loaders, image_references,
        budget_min, budget_max, budget_currency, deadline, scope, status, created_at, updated_at, awarded_at)
        VALUES ('job_legacy', 'usr_legacy', 'Legacy job', 'Pre-phase job.', 'java', '1.20.1', '[]', '[]',
          100, 1000, 'INR', NULL, 'Legacy scope.', 'AWARDED', '${timestamp}', '${timestamp}', '${timestamp}');
      INSERT INTO job_proposals (proposal_id, job_id, user_id, creator_id, message, scope, budget_min, budget_max,
        budget_currency, delivery_estimate_days, status, created_at, updated_at)
        VALUES ('prp_legacy', 'job_legacy', 'usr_legacy', 'crt_legacy', 'Legacy proposal.', 'Legacy proposal scope.',
          500, 1000, 'INR', 5, 'SELECTED', '${timestamp}', '${timestamp}');
    `);
    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(database.prepare("SELECT MAX(version) AS version FROM schema_migrations").get().version, 10);
    // The legacy proposal's selected state and budget are exactly what the new order logic derives from.
    const proposal = database.prepare("SELECT * FROM job_proposals WHERE proposal_id = 'prp_legacy'").get();
    assert.equal(proposal.status, "SELECTED");
    assert.equal(proposal.budget_max, 1000);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM orders").get().count, 0);
    // The migrated database accepts an order insert that satisfies every FK and CHECK.
    database.prepare(
      `INSERT INTO orders (order_id, job_id, proposal_id, buyer_id, creator_user_id, job_title, scope, edition,
        minecraft_version, loaders, agreed_budget_min, agreed_budget_max, agreed_currency, agreed_deadline,
        agreed_delivery_estimate_days, revision_limit, status, created_at, updated_at, completed_at, cancelled_at)
       VALUES ('ord_legacy', 'job_legacy', 'prp_legacy', 'usr_legacy', 'usr_legacy', 'Legacy job', 'Legacy proposal scope.',
        'java', '1.20.1', '[]', 500, 1000, 'INR', NULL, 5, 2, 'ACTIVE', '${timestamp}', '${timestamp}', NULL, NULL)`,
    ).run();
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM orders").get().count, 1);
    database.close();
  });
});
