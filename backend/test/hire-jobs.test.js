/**
 * Phase 26 Hire a Builder tests.
 *
 * The suite follows the same rule as the phase it tests: **the session is the owner, the lifecycle is real state
 * on real rows, one award always lands, and proposal content is invisible to everyone except its creator and the
 * buyer who owns the job.** A client cannot name an owner, cannot set a status or an award, cannot propose for
 * someone else, and cannot read another account's job or proposal through any parameter combination. Where a
 * request is refused, the test asserts both the typed refusal and that nothing changed — and where a refusal
 * *should* leave a trace, it asserts the denial audit survived the rollback.
 *
 * Discovery tests cover open-only listing, every declared filter, bounded pagination with a stable order, and
 * literal handling of wildcard/injection text. There is no payment, escrow, milestone, or rating claim anywhere:
 * an award records which builder a buyer selected and nothing more.
 *
 * Security thresholds are raised on purpose (same as Phases 23–25): this suite deliberately produces many refused
 * requests, and the Phase 20 protections correctly treat that as abuse. The two dedicated rate-limit tests get
 * their own small budgets in their own services.
 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { afterEach, describe, it } from "node:test";
import { migrateToVersion, SCHEMA_VERSION } from "../src/db.js";
import { CREATOR_AGREEMENT_VERSION } from "../src/onboarding.js";
import { call, loginCall, register, registerVerified, startService } from "./helpers.js";

const services = new Set();
const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x51).toString("base64url");
const DEVELOPER_EMAIL = "owner@example.test";
const DEVELOPER_PASSWORD = "Creator Plane 7Safe";

const REPOSITORY_ROOT = dirname(dirname(dirname(fileURLToPath(import.meta.url))));

const RELAXED = Object.freeze({
  RATE_CREATOR_WRITE_MAX: "1000",
  RATE_CREATOR_READ_MAX: "1000",
  RATE_JOB_WRITE_MAX: "1000",
  RATE_PROPOSAL_WRITE_MAX: "1000",
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

/** Grants a plan through the developer tool — the only way a paid plan is ever applied. */
async function grantPlan(service, owner, email, plan = "CREATOR", days = 30) {
  return confirmedTool(service, owner.accessToken, "grantMembership", {
    email, plan, days, reason: "Phase 26 test grant",
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

/** A fully-eligible creator: verified account, granted plan, completed seller onboarding (profile + agreement). */
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

/** Entitled creator with an ACTIVE profile but no seller onboarding — the agreement prerequisite is genuinely absent. */
async function creatorWithoutAgreement(service, owner, email, handle) {
  const account = await signedInAccount(service, email);
  await grantPlan(service, owner, email);
  const created = await call(service.baseUrl, "POST", "/creator/profile", {
    headers: bearer(account.accessToken), body: { handle, displayName: `${handle} Studio` },
  });
  assert.equal(created.status, 201, JSON.stringify(created.body));
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

function proposalBody(jobId, overrides = {}) {
  return {
    jobId,
    message: "I have built several sorting halls and can start this week with a clear plan.",
    scope: "One sorting hall with 12 modules, installed and demonstrated on your server.",
    ...overrides,
  };
}

async function submitProposal(service, creator, jobId, overrides = {}) {
  const submitted = await call(service.baseUrl, "POST", "/creator/proposal", {
    headers: bearer(creator.accessToken), body: proposalBody(jobId, overrides),
  });
  assert.equal(submitted.status, 201, JSON.stringify(submitted.body));
  return submitted.body;
}

async function openJobWithProposals(service, { proposals = 2 } = {}) {
  const owner = await ownerSession(service);
  const buyer = await signedInAccount(service, `buyer-${Math.random().toString(36).slice(2, 8)}@example.test`);
  const job = await postJob(service, buyer);
  const creators = [];
  for (let index = 0; index < proposals; index += 1) {
    creators.push(await eligibleCreator(service, owner, `builder-${Math.random().toString(36).slice(2, 8)}-${index}@example.test`, `build-studio-${Math.random().toString(36).slice(2, 8)}-${index}`));
  }
  const submitted = [];
  for (const creator of creators) submitted.push(await submitProposal(service, creator, job.id));
  return { owner, buyer, job, creators, proposals: submitted };
}

function auditRows(service, actionType) {
  return service.database
    .prepare("SELECT * FROM admin_audit_log WHERE action_type = ? ORDER BY occurred_at, audit_id")
    .all(actionType);
}

function jobRow(service, jobId) {
  return service.database.prepare("SELECT * FROM buyer_jobs WHERE job_id = ?").get(jobId);
}

function proposalRow(service, proposalId) {
  return service.database.prepare("SELECT * FROM job_proposals WHERE proposal_id = ?").get(proposalId);
}

/** Inserts a job row directly, for search/pagination tests with deterministic created_at values. */
function seedJob(service, { id, buyerId, title, description = "Seeded job description for discovery tests.",
  edition = "java", version = "1.20.1", status = "OPEN", createdAt = "2026-01-01T00:00:00.000Z", scope = "Seeded scope for the discovery test job." }) {
  service.database.prepare(
    `INSERT INTO buyer_jobs
       (job_id, buyer_id, title, description, edition, minecraft_version, loaders, image_references,
        budget_min, budget_max, budget_currency, deadline, scope, status, created_at, updated_at, awarded_at)
     VALUES (?, ?, ?, ?, ?, ?, '[]', '[]', NULL, NULL, NULL, NULL, ?, ?, ?, ?, NULL)`,
  ).run(id, buyerId, title, description, edition, version, scope, status, createdAt, createdAt);
  return id;
}

function seededBuyer(service, handle) {
  const timestamp = "2026-01-01T00:00:00.000Z";
  const userId = `usr_${handle.replaceAll("-", "")}`.slice(0, 40).padEnd(40, "0").slice(0, 40);
  service.database.prepare(
    `INSERT INTO users (user_id, email, email_canonical, password_hash, display_name, status, email_verified_at, created_at, updated_at)
     VALUES (?, ?, ?, 'hash', ?, 'ACTIVE', ?, ?, ?)`,
  ).run(userId, `${handle}@example.test`, `${handle}@example.test`, handle, timestamp, timestamp, timestamp);
  return userId;
}

// ------------------------------------------------------------------ A. schema and migration (categories 1–2)

describe("Phase 26 hire schema", () => {
  it("1. initializes the fresh schema at v9 with the job and proposal tables, constraints, indexes, and audit types", async () => {
    const service = await newService();
    assert.equal(SCHEMA_VERSION, 9);
    const version = service.database.prepare("SELECT MAX(version) AS version FROM schema_migrations").get().version;
    assert.equal(version, 9);

    const jobColumns = service.database.prepare("PRAGMA table_info(buyer_jobs)").all().map((row) => row.name);
    for (const column of ["job_id", "buyer_id", "title", "description", "edition", "minecraft_version", "loaders",
      "image_references", "budget_min", "budget_max", "budget_currency", "deadline", "scope", "status",
      "created_at", "updated_at", "awarded_at"]) {
      assert.equal(jobColumns.includes(column), true, column);
    }
    const proposalColumns = service.database.prepare("PRAGMA table_info(job_proposals)").all().map((row) => row.name);
    for (const column of ["proposal_id", "job_id", "user_id", "creator_id", "message", "scope", "budget_min",
      "budget_max", "budget_currency", "delivery_estimate_days", "status", "created_at", "updated_at"]) {
      assert.equal(proposalColumns.includes(column), true, column);
    }

    const jobIndexes = service.database.prepare("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND name LIKE 'job%' OR type = 'index' AND name LIKE 'buyer%'")
      .all().map((row) => row.name).sort();
    assert.deepEqual(jobIndexes, [
      "buyer_jobs_by_buyer",
      "buyer_jobs_by_edition",
      "buyer_jobs_by_open",
      "job_proposals_active_per_creator",
      "job_proposals_by_creator",
      "job_proposals_by_job",
    ]);
    const uniqueIndex = service.database.prepare("SELECT sql FROM sqlite_master WHERE name = 'job_proposals_active_per_creator'").get().sql;
    assert.match(uniqueIndex, /UNIQUE/i);
    assert.match(uniqueIndex, /status = 'SUBMITTED'/);

    // CHECK constraints refuse invalid lifecycle and budget shapes at the database level.
    assert.throws(() => service.database.prepare(
      `INSERT INTO buyer_jobs (job_id, buyer_id, title, description, edition, minecraft_version, loaders,
         image_references, budget_min, budget_max, budget_currency, deadline, scope, status, created_at, updated_at, awarded_at)
       VALUES ('job_ffffffff-ffff-4fff-8fff-fffffffffff0', 'usr_missing', 'Bad job title here', 'A description that is long enough.', 'java', '1.20.1', '[]', '[]', 10, 5, 'INR', NULL, 'A scope that is long enough.', 'OPEN', '2026-01-01T00:00:00.000Z', '2026-01-01T00:00:00.000Z', NULL)`,
    ).run(), /CHECK constraint failed/);
    assert.throws(() => service.database.prepare(
      `INSERT INTO buyer_jobs (job_id, buyer_id, title, description, edition, minecraft_version, loaders,
         image_references, budget_min, budget_max, budget_currency, deadline, scope, status, created_at, updated_at, awarded_at)
       VALUES ('job_ffffffff-ffff-4fff-8fff-fffffffffff1', 'usr_missing', 'Bad job title here', 'A description that is long enough.', 'java', '1.20.1', '[]', '[]', NULL, NULL, NULL, NULL, 'A scope that is long enough.', 'AWARDED', '2026-01-01T00:00:00.000Z', '2026-01-01T00:00:00.000Z', NULL)`,
    ).run(), /CHECK constraint failed/);

    // The ten hire action types are declared; unknown actions are still refused by the database.
    for (const actionType of ["JOB_CREATED", "JOB_UPDATED", "JOB_CANCELLED", "JOB_AWARDED", "JOB_ACCESS_DENIED",
      "PROPOSAL_SUBMITTED", "PROPOSAL_UPDATED", "PROPOSAL_WITHDRAWN", "PROPOSAL_SELECTED", "PROPOSAL_ACCESS_DENIED"]) {
      service.database.prepare(
        `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
         VALUES (?, 'SYSTEM', NULL, ?, NULL, NULL, '2026-01-01T00:00:00.000Z', 'SUCCESS', '{}')`,
      ).run(`aud_${actionType.toLowerCase()}`, actionType);
    }
    assert.throws(() => service.database.prepare(
      `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       VALUES ('aud_bogus', 'SYSTEM', NULL, 'NOT_A_HIRE_ACTION', NULL, NULL, '2026-01-01T00:00:00.000Z', 'SUCCESS', '{}')`,
    ).run(), /CHECK constraint failed/);
  });

  it("2. migrates a version 8 database to v9 without losing data or audit history, and re-running is a no-op", () => {
    const database = new DatabaseSync(":memory:");
    database.exec("PRAGMA foreign_keys = ON");
    database.exec("PRAGMA recursive_triggers = ON");
    database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    migrateToVersion(database, 8);
    const timestamp = "2026-01-01T00:00:00.000Z";
    database.exec(`
      INSERT INTO users (user_id, email, email_canonical, password_hash, display_name, status, email_verified_at, created_at, updated_at)
        VALUES ('usr_p25', 'p25@example.com', 'p25@example.com', 'hash', 'Phase25', 'ACTIVE', '${timestamp}', '${timestamp}', '${timestamp}');
      INSERT INTO creator_profiles (creator_id, user_id, handle, display_name, bio, category, avatar_reference, status, verification_status, status_changed_at, verified_at, created_at, updated_at)
        VALUES ('crt_p25', 'usr_p25', 'phase25-studio', 'Phase25 Studio', '', NULL, NULL, 'ACTIVE', 'UNVERIFIED', '${timestamp}', NULL, '${timestamp}', '${timestamp}');
      INSERT INTO marketplace_listings (listing_id, creator_id, title, description, category, subcategory, edition,
        minecraft_versions, loaders, tags, image_references, status, published_at, created_at, updated_at)
        VALUES ('lst_p25', 'crt_p25', 'Pre-existing listing', 'A listing that existed before Phase 26.', 'Structures', '', 'java',
          '["1.20.1"]', '["Fabric"]', '[]', '[]', 'DRAFT', NULL, '${timestamp}', '${timestamp}');
      INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
        VALUES ('aud_p25', 'SYSTEM', NULL, 'LISTING_CREATED', 'usr_p25', NULL, '${timestamp}', 'SUCCESS', '{"listingId":"lst_p25"}');
    `);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 8);
    const appliedAt = database.prepare("SELECT applied_at FROM schema_migrations WHERE version = 8").get().applied_at;

    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 9);
    assert.equal(database.prepare("SELECT applied_at FROM schema_migrations WHERE version = 8").get().applied_at, appliedAt);

    // Prior rows survive with their values; the audit row is intact and append-only triggers were restored.
    assert.equal(database.prepare("SELECT title FROM marketplace_listings WHERE listing_id = 'lst_p25'").get().title, "Pre-existing listing");
    assert.equal(database.prepare("SELECT action_type FROM admin_audit_log WHERE audit_id = 'aud_p25'").get().action_type, "LISTING_CREATED");
    assert.throws(() => database.prepare("UPDATE admin_audit_log SET outcome = 'FAILURE' WHERE audit_id = 'aud_p25'").run(), /append-only/);

    // The rebuilt audit CHECK accepts the new hire types and still refuses unknown ones.
    database.prepare(
      `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       VALUES ('aud_p26', 'SYSTEM', NULL, 'JOB_CREATED', 'usr_p25', NULL, '${timestamp}', 'SUCCESS', '{}')`,
    ).run();
    assert.throws(() => database.prepare(
      `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       VALUES ('aud_p26x', 'SYSTEM', NULL, 'STILL_NOT_A_TYPE', 'usr_p25', NULL, '${timestamp}', 'SUCCESS', '{}')`,
    ).run(), /CHECK constraint failed/);

    // Re-running is a no-op: row counts and index counts are stable.
    const indexes = database.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'index' AND name LIKE 'marketplace_listings%' OR type = 'index' AND (name LIKE 'job%' OR name LIKE 'buyer%')").get().count;
    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 9);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'index' AND name LIKE 'marketplace_listings%' OR type = 'index' AND (name LIKE 'job%' OR name LIKE 'buyer%')").get().count, indexes);
    database.close();
  });
});

// ------------------------------------------------------------------- B. job lifecycle and authentication (3–8)

describe("Phase 26 job requests", () => {
  it("3. posts a job from the session buyer and lists it under their own account", async () => {
    const service = await newService();
    const buyer = await signedInAccount(service, "buyer-a@example.test");
    const job = await postJob(service, buyer, { budgetMin: 500, budgetMax: 1500, budgetCurrency: "INR", deadline: "2027-01-01" });
    assert.match(job.id, /^job_[0-9a-f-]{36}$/);
    assert.equal(job.status, "OPEN");
    assert.equal(job.title, "Redstone sorting hall");
    assert.deepEqual(job.budget, { min: 500, max: 1500, currency: "INR" });
    assert.equal(job.deadline, "2027-01-01");
    assert.equal(job.proposalCount, 0);

    const mine = await call(service.baseUrl, "GET", "/buyer/jobs", { headers: bearer(buyer.accessToken) });
    assert.equal(mine.status, 200);
    assert.equal(mine.body.jobs.length, 1);
    assert.equal(mine.body.counts.open, 1);
    assert.equal("buyerId" in mine.body.jobs[0], false, "the owner's account id must never appear in a view");
    assert.equal(jobRow(service, job.id).buyer_id.includes("usr_"), true, "ownership persists as the session account");
  });

  it("4. refuses invalid and oversized job content without writing anything", async () => {
    const service = await newService();
    const buyer = await signedInAccount(service, "buyer-b@example.test");
    const before = service.database.prepare("SELECT COUNT(*) AS count FROM buyer_jobs").get().count;
    const invalid = [
      { title: "x" },
      { title: "a".repeat(121) },
      { description: "too short" },
      { edition: "console" },
      { minecraftVersion: "not a version" },
      { scope: "no" },
      { loaders: ["ForgeX"] },
      { imageReferences: ["http://insecure.example/x.png"] },
      { imageReferences: ["https://a.example/" + "x".repeat(400)] },
      { budgetMin: 10, budgetMax: 5, budgetCurrency: "INR" },
      { budgetMin: 10, budgetMax: 20, budgetCurrency: "BTC" },
      { budgetMin: 10 },
      { budgetCurrency: "INR" },
      { deadline: "2020-01-01" },
      { deadline: "not-a-date" },
    ];
    for (const patch of invalid) {
      const refused = await call(service.baseUrl, "POST", "/buyer/jobs", {
        headers: bearer(buyer.accessToken), body: jobBody(patch),
      });
      assert.equal(refused.status, 400, JSON.stringify(patch));
      assert.equal(refused.body.error.code, "INVALID_REQUEST", JSON.stringify(patch));
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM buyer_jobs").get().count, before);
  });

  it("5. rejects server-controlled ownership, status, and award fields on every write", async () => {
    const service = await newService();
    const buyer = await signedInAccount(service, "buyer-c@example.test");
    const smuggled = [
      { buyerId: "usr_someone_else" },
      { ownerId: "usr_someone_else" },
      { status: "AWARDED" },
      { awardedAt: "2026-01-01T00:00:00.000Z" },
      { jobId: "job_ffffffff-ffff-4fff-8fff-ffffffffffff" },
      { createdAt: "2020-01-01T00:00:00.000Z" },
    ];
    for (const extra of smuggled) {
      const refused = await call(service.baseUrl, "POST", "/buyer/jobs", {
        headers: bearer(buyer.accessToken), body: { ...jobBody(), ...extra },
      });
      assert.equal(refused.status, 400, JSON.stringify(extra));
      assert.equal(refused.body.error.code, "INVALID_REQUEST");
    }
    const job = await postJob(service, buyer);
    const statusPatch = await call(service.baseUrl, "PATCH", `/buyer/jobs/${job.id}`, {
      headers: bearer(buyer.accessToken), body: { status: "AWARDED" },
    });
    assert.equal(statusPatch.status, 400);
    const awardSmuggle = await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, {
      headers: bearer(buyer.accessToken), body: { proposalId: "prp_ffffffff-ffff-4fff-8fff-ffffffffffff", status: "AWARDED", awardedTo: "usr_x" },
    });
    assert.equal(awardSmuggle.status, 400);
    const proposalSmuggle = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(buyer.accessToken), body: { ...proposalBody(job.id), creatorId: "crt_x", status: "SELECTED" },
    });
    assert.equal(proposalSmuggle.status, 400);
    assert.equal(service.database.prepare("SELECT status FROM buyer_jobs WHERE job_id = ?").get(job.id).status, "OPEN");
  });

  it("6. requires a session and a verified email for job and proposal writes", async () => {
    const service = await newService();
    const anonymousJob = await call(service.baseUrl, "POST", "/buyer/jobs", { body: jobBody() });
    assert.equal(anonymousJob.status, 401);
    assert.equal(anonymousJob.body.error.code, "AUTHENTICATION_REQUIRED");
    const anonymousList = await call(service.baseUrl, "GET", "/buyer/jobs");
    assert.equal(anonymousList.status, 401);
    const anonymousProposal = await call(service.baseUrl, "POST", "/creator/proposal", { body: proposalBody("job_ffffffff-ffff-4fff-8fff-ffffffffffff") });
    assert.equal(anonymousProposal.status, 401);

    // An unverified account cannot hold a session at all: registration issues no token and login refuses until
    // verification, so there is no way to reach a job or proposal write without a verified email.
    const unverified = await register(service.baseUrl, { email: "unverified@example.test" });
    assert.equal(unverified.status, 201, JSON.stringify(unverified.body));
    assert.equal(Object.hasOwn(unverified.body, "session"), false, "registration must not issue a session");
    const signIn = await loginCall(service.baseUrl, { email: "unverified@example.test" });
    assert.equal(signIn.status, 403);
    assert.equal(signIn.body.error.code, "EMAIL_NOT_VERIFIED");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM buyer_jobs").get().count, 0);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM job_proposals").get().count, 0);
  });

  it("7. applies the existing creator prerequisites to proposals and audits each denial", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const buyer = await signedInAccount(service, "buyer-d@example.test");
    const job = await postJob(service, buyer);

    // No entitlement, no profile: the listing pipeline's refusals, unchanged.
    const plain = await signedInAccount(service, "plain-creator@example.test");
    const noEntitlement = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(plain.accessToken), body: proposalBody(job.id),
    });
    assert.equal(noEntitlement.status, 403);
    assert.equal(noEntitlement.body.error.code, "CREATOR_ENTITLEMENT_REQUIRED");

    await grantPlan(service, owner, "plain-creator@example.test");
    const noProfile = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(plain.accessToken), body: proposalBody(job.id),
    });
    assert.equal(noProfile.status, 404, "CREATOR_PROFILE_NOT_FOUND keeps its established status");
    assert.equal(noProfile.body.error.code, "CREATOR_PROFILE_NOT_FOUND");

    // Entitled with an ACTIVE profile but no recorded agreement: the publish-class prerequisite still applies.
    const partial = await creatorWithoutAgreement(service, owner, "partial-creator@example.test", "partial-studio");
    const blocked = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(partial.accessToken), body: proposalBody(job.id),
    });
    assert.equal(blocked.status, 403);
    assert.equal(blocked.body.error.code, "PROPOSAL_BLOCKED");
    const agreementDenials = auditRows(service, "CREATOR_ACCESS_DENIED")
      .filter((row) => row.metadata_json.includes("agreement_missing"));
    assert.equal(agreementDenials.length, 1);

    // A suspended profile is refused with an audited denial that survives the rolled-back transaction.
    const eligible = await eligibleCreator(service, owner, "suspended-creator@example.test", "suspended-studio");
    service.database.prepare("UPDATE creator_profiles SET status = 'SUSPENDED' WHERE user_id = ?").run(eligible.userId);
    const suspended = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(eligible.accessToken), body: proposalBody(job.id),
    });
    assert.equal(suspended.status, 403);
    assert.equal(suspended.body.error.code, "CREATOR_PROFILE_SUSPENDED");
    const suspendedDenials = auditRows(service, "CREATOR_ACCESS_DENIED")
      .filter((row) => row.metadata_json.includes('"profileStatus":"SUSPENDED"'));
    assert.equal(suspendedDenials.length, 1, "the denial audit must survive the rollback");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM job_proposals").get().count, 0);
  });

  it("8. denies every cross-account job and proposal route with uniform not-found plus a denial audit", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const alice = await signedInAccount(service, "alice@example.test");
    const mallory = await signedInAccount(service, "mallory@example.test");
    const job = await postJob(service, alice);

    for (const attempt of [
      ["GET", `/buyer/jobs/${job.id}`, undefined],
      ["PATCH", `/buyer/jobs/${job.id}`, { title: "Stolen job title" }],
      ["POST", `/buyer/jobs/${job.id}/cancel`, {}],
      ["POST", `/buyer/jobs/${job.id}/award`, { proposalId: "prp_ffffffff-ffff-4fff-8fff-ffffffffffff" }],
    ]) {
      const refused = await call(service.baseUrl, attempt[0], attempt[1], {
        headers: bearer(mallory.accessToken), ...(attempt[2] === undefined ? {} : { body: attempt[2] }),
      });
      assert.equal(refused.status, 404, `${attempt[0]} ${attempt[1]}`);
      assert.equal(refused.body.error.code, "JOB_NOT_FOUND");
      assert.equal(JSON.stringify(refused.body).includes("alice"), false, "the response must not leak the owner");
    }
    const jobDenials = auditRows(service, "JOB_ACCESS_DENIED");
    assert.equal(jobDenials.length, 4, "each refused cross-account attempt leaves a denial record");

    const creatorA = await eligibleCreator(service, owner, "creator-a@example.test", "creator-a-studio");
    const creatorB = await eligibleCreator(service, owner, "creator-b@example.test", "creator-b-studio");
    const proposal = await submitProposal(service, creatorA, job.id);
    for (const attempt of [
      ["GET", `/creator/proposal/${proposal.id}`, undefined],
      ["PATCH", `/creator/proposal/${proposal.id}`, { message: "Let me take this proposal over instead, please." }],
      ["POST", `/creator/proposal/${proposal.id}/withdraw`, {}],
    ]) {
      const refused = await call(service.baseUrl, attempt[0], attempt[1], {
        headers: bearer(creatorB.accessToken), ...(attempt[2] === undefined ? {} : { body: attempt[2] }),
      });
      assert.equal(refused.status, 404, `${attempt[0]} ${attempt[1]}`);
      assert.equal(refused.body.error.code, "PROPOSAL_NOT_FOUND");
    }
    assert.equal(auditRows(service, "PROPOSAL_ACCESS_DENIED").length, 3);
    assert.equal(proposalRow(service, proposal.id).status, "SUBMITTED", "the victim's proposal is untouched");
  });
});

// ------------------------------------------------------------------------- C. proposals (categories 9–15)

describe("Phase 26 proposals", () => {
  it("9. submits a proposal and shows it to its creator and to the job's buyer only", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const buyer = await signedInAccount(service, "buyer-e@example.test");
    const job = await postJob(service, buyer);
    const creator = await eligibleCreator(service, owner, "builder-e@example.test", "builder-e-studio");
    const proposal = await submitProposal(service, creator, job.id, { budgetMin: 400, budgetMax: 900, budgetCurrency: "USD", deliveryEstimateDays: 21 });
    assert.match(proposal.id, /^prp_[0-9a-f-]{36}$/);
    assert.equal(proposal.status, "SUBMITTED");
    assert.equal(proposal.job.id, job.id);
    assert.deepEqual(proposal.budget, { min: 400, max: 900, currency: "USD" });
    assert.equal(proposal.deliveryEstimateDays, 21);

    const mine = await call(service.baseUrl, "GET", "/creator/proposal", { headers: bearer(creator.accessToken) });
    assert.equal(mine.status, 200);
    assert.equal(mine.body.proposals.length, 1);
    assert.equal(mine.body.counts.submitted, 1);

    const review = await call(service.baseUrl, "GET", `/buyer/jobs/${job.id}`, { headers: bearer(buyer.accessToken) });
    assert.equal(review.status, 200);
    assert.equal(review.body.proposals.length, 1);
    assert.equal(review.body.proposals[0].id, proposal.id);
    assert.equal(review.body.proposals[0].creator.handle, "builder-e-studio");
    assert.equal(review.body.counts.submitted, 1);
  });

  it("10. bounds proposal message, scope, budget, and delivery estimate without inserting anything", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const buyer = await signedInAccount(service, "buyer-f@example.test");
    const job = await postJob(service, buyer);
    const creator = await eligibleCreator(service, owner, "builder-f@example.test", "builder-f-studio");
    const before = service.database.prepare("SELECT COUNT(*) AS count FROM job_proposals").get().count;
    const invalid = [
      { message: "short" },
      { message: "x".repeat(2001) },
      { scope: "no" },
      { deliveryEstimateDays: 0 },
      { deliveryEstimateDays: 366 },
      { deliveryEstimateDays: 3.5 },
      { budgetMin: 50, budgetMax: 10, budgetCurrency: "INR" },
      { budgetMin: 0, budgetMax: 10 },
      { budgetMin: -1, budgetMax: 10, budgetCurrency: "USD" },
      { budgetCurrency: "USD" },
      { message: "A perfectly reasonable message that is long enough.", scope: "A scope that is long enough.", jobId: "job_ffffffff-ffff-4fff-8fff-ffffffffffff" },
    ];
    for (const patch of invalid) {
      const refused = await call(service.baseUrl, "POST", "/creator/proposal", {
        headers: bearer(creator.accessToken), body: proposalBody(job.id, patch),
      });
      assert.equal(refused.status === 400 || refused.status === 404, true, JSON.stringify(patch));
      assert.equal(["INVALID_REQUEST", "JOB_NOT_FOUND"].includes(refused.body.error.code), true, refused.body.error.code);
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM job_proposals").get().count, before);
  });

  it("11. refuses a duplicate active proposal per creator per job — at the API and in the database", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const buyer = await signedInAccount(service, "buyer-g@example.test");
    const job = await postJob(service, buyer);
    const creator = await eligibleCreator(service, owner, "builder-g@example.test", "builder-g-studio");
    const first = await submitProposal(service, creator, job.id);
    const duplicate = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(creator.accessToken), body: proposalBody(job.id),
    });
    assert.equal(duplicate.status, 409);
    assert.equal(duplicate.body.error.code, "PROPOSAL_STATE_CONFLICT");

    // The partial unique index refuses a second live row even if the application check were bypassed.
    const userId = creator.userId;
    const creatorId = service.database.prepare("SELECT creator_id FROM creator_profiles WHERE user_id = ?").get(userId).creator_id;
    assert.throws(() => service.database.prepare(
      `INSERT INTO job_proposals (proposal_id, job_id, user_id, creator_id, message, scope, budget_min, budget_max,
         budget_currency, delivery_estimate_days, status, created_at, updated_at)
       VALUES ('prp_ffffffff-ffff-4fff-8fff-fffffffffff9', ?, ?, ?, 'A direct message that is long enough.', 'A direct scope that is long enough.',
         NULL, NULL, NULL, NULL, 'SUBMITTED', '2026-01-01T00:00:00.000Z', '2026-01-01T00:00:00.000Z')`,
    ).run(job.id, userId, creatorId), /UNIQUE/i);
    assert.equal(proposalRow(service, first.id).status, "SUBMITTED");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM job_proposals WHERE job_id = ?").get(job.id).count, 1);
  });

  it("12. refuses proposing on your own job, on a malformed job id, and on a closed job", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const buyer = await eligibleCreator(service, owner, "self-builder@example.test", "self-builder-studio");
    const job = await postJob(service, buyer);
    const own = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(buyer.accessToken), body: proposalBody(job.id),
    });
    assert.equal(own.status, 400);
    assert.equal(own.body.error.code, "INVALID_REQUEST");
    assert.match(own.body.error.message, /cannot propose on it/i);

    const missing = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(buyer.accessToken), body: proposalBody("job_ffffffff-ffff-4fff-8fff-ffffffffffff"),
    });
    assert.equal(missing.status, 404);
    assert.equal(missing.body.error.code, "JOB_NOT_FOUND");

    const other = await eligibleCreator(service, owner, "other-builder@example.test", "other-builder-studio");
    const stranger = await signedInAccount(service, "stranger-buyer@example.test");
    const theirJob = await postJob(service, stranger);
    await call(service.baseUrl, "POST", `/buyer/jobs/${theirJob.id}/cancel`, { headers: bearer(stranger.accessToken) });
    const onClosed = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(other.accessToken), body: proposalBody(theirJob.id),
    });
    assert.equal(onClosed.status, 409);
    assert.equal(onClosed.body.error.code, "JOB_STATE_CONFLICT");
  });

  it("13. hides proposal content and buyer identity from unrelated accounts and from public reads", async () => {
    const service = await newService();
    const { owner, buyer, job, creators, proposals } = await openJobWithProposals(service, { proposals: 1 });

    const detail = await call(service.baseUrl, "GET", `/marketplace/jobs/${job.id}`);
    assert.equal(detail.status, 200);
    const detailText = JSON.stringify(detail.body);
    assert.equal("proposals" in detail.body, false, "public detail carries no proposal list");
    assert.equal(detailText.includes(proposals[0].id), false, "no proposal id leaks");
    assert.equal(detailText.includes("builder-"), false, "no creator identity leaks before award");
    assert.equal(detailText.includes("usr_"), false, "no account id leaks");
    assert.equal(detailText.includes(proposals[0].message.slice(0, 24)), false, "proposal message never leaks");
    assert.equal(detail.body.proposalCount, 1, "an aggregate count is the only public signal");

    const stranger = await eligibleCreator(service, owner, "stranger-creator@example.test", "stranger-creator-studio");
    const peek = await call(service.baseUrl, "GET", `/creator/proposal/${proposals[0].id}`, {
      headers: bearer(stranger.accessToken),
    });
    assert.equal(peek.status, 404);
    assert.equal(peek.body.error.code, "PROPOSAL_NOT_FOUND");

    const strangerBuyer = await signedInAccount(service, "stranger-buyer-2@example.test");
    const review = await call(service.baseUrl, "GET", `/buyer/jobs/${job.id}`, { headers: bearer(strangerBuyer.accessToken) });
    assert.equal(review.status, 404);
    assert.equal(review.body.error.code, "JOB_NOT_FOUND");
    assert.equal(buyer.email.includes("buyer"), true);
  });

  it("14. withdraws a proposal exactly once and allows a fresh proposal afterwards", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const buyer = await signedInAccount(service, "buyer-h@example.test");
    const job = await postJob(service, buyer);
    const creator = await eligibleCreator(service, owner, "builder-h@example.test", "builder-h-studio");
    const proposal = await submitProposal(service, creator, job.id);

    const withdrawn = await call(service.baseUrl, "POST", `/creator/proposal/${proposal.id}/withdraw`, {
      headers: bearer(creator.accessToken),
    });
    assert.equal(withdrawn.status, 200);
    assert.equal(withdrawn.body.status, "WITHDRAWN");

    const again = await call(service.baseUrl, "POST", `/creator/proposal/${proposal.id}/withdraw`, {
      headers: bearer(creator.accessToken),
    });
    assert.equal(again.status, 409);
    assert.equal(again.body.error.code, "PROPOSAL_STATE_CONFLICT");

    const updateAfterWithdraw = await call(service.baseUrl, "PATCH", `/creator/proposal/${proposal.id}`, {
      headers: bearer(creator.accessToken), body: { message: "Changed my mind about this wording entirely." },
    });
    assert.equal(updateAfterWithdraw.status, 409);

    // The active-slot is free again: a fresh proposal on the same job succeeds and only one is live.
    const fresh = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(creator.accessToken), body: proposalBody(job.id),
    });
    assert.equal(fresh.status, 201);
    assert.equal(fresh.body.id !== proposal.id, true);
    const live = service.database.prepare("SELECT COUNT(*) AS count FROM job_proposals WHERE job_id = ? AND status = 'SUBMITTED'").get(job.id).count;
    assert.equal(live, 1);
  });

  it("15. updates a live proposal while the job is open, and refuses updates once it closes", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const buyer = await signedInAccount(service, "buyer-i@example.test");
    const job = await postJob(service, buyer);
    const creator = await eligibleCreator(service, owner, "builder-i@example.test", "builder-i-studio");
    const proposal = await submitProposal(service, creator, job.id);

    const updated = await call(service.baseUrl, "PATCH", `/creator/proposal/${proposal.id}`, {
      headers: bearer(creator.accessToken),
      body: { message: "Updated message with a clearer timeline and a revised approach.", deliveryEstimateDays: 14 },
    });
    assert.equal(updated.status, 200);
    assert.equal(updated.body.deliveryEstimateDays, 14);
    assert.equal(auditRows(service, "PROPOSAL_UPDATED").length, 1);

    const other = await eligibleCreator(service, owner, "builder-i2@example.test", "builder-i2-studio");
    const rival = await submitProposal(service, other, job.id);
    const awarded = await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, {
      headers: bearer(buyer.accessToken), body: { proposalId: proposal.id },
    });
    assert.equal(awarded.status, 200);

    const updateAfterAward = await call(service.baseUrl, "PATCH", `/creator/proposal/${rival.id}`, {
      headers: bearer(other.accessToken), body: { message: "Still updating after the decision was made, which must fail." },
    });
    assert.equal(updateAfterAward.status, 409);
    assert.equal(updateAfterAward.body.error.code, "PROPOSAL_STATE_CONFLICT");
    assert.equal(proposalRow(service, rival.id).status, "NOT_SELECTED");
    assert.equal(proposalRow(service, proposal.id).status, "SELECTED");
  });
});

// ------------------------------------------------------ D. buyer review, selection, and job management (16–22)

describe("Phase 26 buyer review and award", () => {
  it("16. lets only the job's owner review its proposals", async () => {
    const service = await newService();
    const { buyer, job, proposals } = await openJobWithProposals(service, { proposals: 2 });
    const review = await call(service.baseUrl, "GET", `/buyer/jobs/${job.id}`, { headers: bearer(buyer.accessToken) });
    assert.equal(review.status, 200);
    assert.equal(review.body.proposals.length, 2);
    assert.equal(review.body.counts.submitted, 2);
    assert.equal(review.body.proposals.every((entry) => entry.creator.handle && entry.message), true);

    const strangerBuyer = await signedInAccount(service, "nosy-buyer@example.test");
    const denied = await call(service.baseUrl, "GET", `/buyer/jobs/${job.id}`, { headers: bearer(strangerBuyer.accessToken) });
    assert.equal(denied.status, 404);
    assert.equal(denied.body.error.code, "JOB_NOT_FOUND");
    assert.equal(JSON.stringify(denied.body).includes(proposals[0].id), false);
  });

  it("17. awards exactly one builder: the choice is recorded and every other proposal closes", async () => {
    const service = await newService();
    const { buyer, job, proposals } = await openJobWithProposals(service, { proposals: 3 });
    const awarded = await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, {
      headers: bearer(buyer.accessToken), body: { proposalId: proposals[1].id },
    });
    assert.equal(awarded.status, 200);
    assert.equal(awarded.body.job.status, "AWARDED");
    assert.equal(awarded.body.job.awardedAt !== null, true);
    assert.equal(awarded.body.selectedProposalId, proposals[1].id);
    assert.equal(awarded.body.closedProposals, 2);
    assert.equal(proposalRow(service, proposals[1].id).status, "SELECTED");
    const others = proposals.filter((_, index) => index !== 1);
    for (const other of others) assert.equal(proposalRow(service, other.id).status, "NOT_SELECTED");

    const detail = await call(service.baseUrl, "GET", `/marketplace/jobs/${job.id}`);
    assert.equal(detail.status, 404, "an awarded job leaves the public directory");
    assert.equal(detail.body.error.code, "JOB_NOT_FOUND");
  });

  it("18. refuses a second award on the same job or a re-award of a decided proposal", async () => {
    const service = await newService();
    const { buyer, job, proposals } = await openJobWithProposals(service, { proposals: 2 });
    const first = await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, {
      headers: bearer(buyer.accessToken), body: { proposalId: proposals[0].id },
    });
    assert.equal(first.status, 200);
    const repeat = await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, {
      headers: bearer(buyer.accessToken), body: { proposalId: proposals[1].id },
    });
    assert.equal(repeat.status, 409);
    assert.equal(repeat.body.error.code, "JOB_STATE_CONFLICT");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM job_proposals WHERE job_id = ? AND status = 'SELECTED'").get(job.id).count, 1);
    assert.equal(jobRow(service, job.id).status, "AWARDED");
  });

  it("19. keeps the award atomic under two concurrent award requests", async () => {
    const service = await newService();
    const { buyer, job, proposals } = await openJobWithProposals(service, { proposals: 2 });
    const [a, b] = await Promise.all([
      call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, { headers: bearer(buyer.accessToken), body: { proposalId: proposals[0].id } }),
      call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, { headers: bearer(buyer.accessToken), body: { proposalId: proposals[1].id } }),
    ]);
    const statuses = [a.status, b.status].sort((left, right) => left - right);
    assert.equal(statuses.filter((status) => status === 200).length, 1, JSON.stringify([a.status, b.status]));
    assert.equal(statuses.filter((status) => status === 409).length, 1, JSON.stringify([a.status, b.status]));
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM job_proposals WHERE job_id = ? AND status = 'SELECTED'").get(job.id).count, 1);
    assert.equal(jobRow(service, job.id).status, "AWARDED");
    const awardAudits = auditRows(service, "JOB_AWARDED").filter((row) => row.metadata_json.includes(job.id));
    assert.equal(awardAudits.length, 1, "exactly one award was ever recorded");
  });

  it("20. refuses awards from non-owners, for unknown proposals, and for decided proposals", async () => {
    const service = await newService();
    const { buyer, job, creators, proposals } = await openJobWithProposals(service, { proposals: 2 });
    const mallory = await signedInAccount(service, "mallory-2@example.test");
    const foreign = await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, {
      headers: bearer(mallory.accessToken), body: { proposalId: proposals[0].id },
    });
    assert.equal(foreign.status, 404);
    assert.equal(foreign.body.error.code, "JOB_NOT_FOUND");
    assert.equal(auditRows(service, "JOB_ACCESS_DENIED").length >= 1, true);

    const unknown = await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, {
      headers: bearer(buyer.accessToken), body: { proposalId: "prp_ffffffff-ffff-4fff-8fff-ffffffffffff" },
    });
    assert.equal(unknown.status, 404);
    assert.equal(unknown.body.error.code, "PROPOSAL_NOT_FOUND");

    // Withdraw by its real creator, then the buyer's award of that decided proposal must conflict.
    const withdrawnProposal = proposals[1];
    const withdraw = await call(service.baseUrl, "POST", `/creator/proposal/${withdrawnProposal.id}/withdraw`, {
      headers: bearer(creators[1].accessToken),
    });
    assert.equal(withdraw.status, 200, JSON.stringify(withdraw.body));
    const decided = await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, {
      headers: bearer(buyer.accessToken), body: { proposalId: withdrawnProposal.id },
    });
    assert.equal(decided.status, 409);
    assert.equal(decided.body.error.code, "PROPOSAL_STATE_CONFLICT");
  });

  it("21. cancels an open job exactly once, closes its proposals, and blocks new ones", async () => {
    const service = await newService();
    const { buyer, job, creators } = await openJobWithProposals(service, { proposals: 1 });
    const cancelled = await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/cancel`, { headers: bearer(buyer.accessToken) });
    assert.equal(cancelled.status, 200);
    assert.equal(cancelled.body.status, "CANCELLED");
    assert.equal(service.database.prepare("SELECT status FROM job_proposals WHERE job_id = ?").get(job.id).status, "NOT_SELECTED");

    const again = await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/cancel`, { headers: bearer(buyer.accessToken) });
    assert.equal(again.status, 409);
    assert.equal(again.body.error.code, "JOB_STATE_CONFLICT");

    const newProposal = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(creators[0].accessToken), body: proposalBody(job.id),
    });
    assert.equal(newProposal.status, 409);
    assert.equal(newProposal.body.error.code, "JOB_STATE_CONFLICT");

    const detail = await call(service.baseUrl, "GET", `/marketplace/jobs/${job.id}`);
    assert.equal(detail.status, 404);
    const list = await call(service.baseUrl, "GET", "/marketplace/jobs");
    assert.equal(list.body.items.some((entry) => entry.id === job.id), false);
    assert.equal(auditRows(service, "JOB_CANCELLED").length, 1);
  });

  it("22. edits an open job, refuses edits once decided, and audits each change", async () => {
    const service = await newService();
    const buyer = await signedInAccount(service, "buyer-j@example.test");
    const job = await postJob(service, buyer);
    const edited = await call(service.baseUrl, "PATCH", `/buyer/jobs/${job.id}`, {
      headers: bearer(buyer.accessToken), body: { title: "Expanded sorting hall", budgetMin: 100, budgetMax: 400, budgetCurrency: "INR" },
    });
    assert.equal(edited.status, 200);
    assert.equal(edited.body.title, "Expanded sorting hall");
    assert.deepEqual(edited.body.budget, { min: 100, max: 400, currency: "INR" });
    assert.equal(auditRows(service, "JOB_UPDATED").length, 1);

    await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/cancel`, { headers: bearer(buyer.accessToken) });
    const afterCancel = await call(service.baseUrl, "PATCH", `/buyer/jobs/${job.id}`, {
      headers: bearer(buyer.accessToken), body: { title: "Too late to rename this" },
    });
    assert.equal(afterCancel.status, 409);
    assert.equal(afterCancel.body.error.code, "JOB_STATE_CONFLICT");
    assert.equal(jobRow(service, job.id).title, "Expanded sorting hall");
  });
});

// --------------------------------------------------------------------- E. discovery (categories 23–26)

describe("Phase 26 open-jobs discovery", () => {
  it("23. serves only OPEN jobs on the public routes", async () => {
    const service = await newService();
    const { buyer, job } = await openJobWithProposals(service, { proposals: 1 });
    const second = await postJob(service, buyer, { title: "Second open job for discovery" });
    await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, {
      headers: bearer(buyer.accessToken), body: { proposalId: (await call(service.baseUrl, "GET", `/buyer/jobs/${job.id}`, { headers: bearer(buyer.accessToken) })).body.proposals[0].id },
    });
    const third = await postJob(service, buyer, { title: "Third open job for cancellation" });
    await call(service.baseUrl, "POST", `/buyer/jobs/${third.id}/cancel`, { headers: bearer(buyer.accessToken) });

    const list = await call(service.baseUrl, "GET", "/marketplace/jobs");
    assert.equal(list.status, 200);
    const ids = list.body.items.map((entry) => entry.id);
    assert.equal(ids.includes(second.id), true);
    assert.equal(ids.includes(job.id), false, "awarded jobs are excluded by the query itself");
    assert.equal(ids.includes(third.id), false, "cancelled jobs are excluded by the query itself");
    assert.equal(list.body.total, 1);
    assert.equal(await call(service.baseUrl, "GET", `/marketplace/jobs/${job.id}`).then((r) => r.status), 404);
    assert.equal(await call(service.baseUrl, "GET", `/marketplace/jobs/${third.id}`).then((r) => r.status), 404);
  });

  it("24. filters the directory by search text, edition, and version", async () => {
    const service = await newService();
    const buyerId = seededBuyer(service, "search-buyer");
    seedJob(service, { id: "job_00000000-0000-4000-8000-000000000001", buyerId, title: "Nether hub blueprint", description: "A nether highway hub.", edition: "java", version: "1.20.1" });
    seedJob(service, { id: "job_00000000-0000-4000-8000-000000000002", buyerId, title: "Bedrock tower", description: "A spawn tower for realms.", edition: "bedrock", version: "1.20.0" });
    seedJob(service, { id: "job_00000000-0000-4000-8000-000000000003", buyerId, title: "Mega base", description: "Scope includes a nether portal room.", edition: "java", version: "1.19.4" });

    const byText = await call(service.baseUrl, "GET", "/marketplace/jobs?q=nether");
    assert.equal(byText.body.total, 2, "q searches title, description, and scope");
    assert.deepEqual(byText.body.items.map((entry) => entry.id).sort(), [
      "job_00000000-0000-4000-8000-000000000001",
      "job_00000000-0000-4000-8000-000000000003",
    ]);
    const byEdition = await call(service.baseUrl, "GET", "/marketplace/jobs?edition=bedrock");
    assert.equal(byEdition.body.total, 1);
    assert.equal(byEdition.body.items[0].edition, "bedrock");
    const byVersion = await call(service.baseUrl, "GET", "/marketplace/jobs?edition=java&version=1.19.4");
    assert.equal(byVersion.body.total, 1);
    assert.equal(byVersion.body.items[0].id, "job_00000000-0000-4000-8000-000000000003");
    const combined = await call(service.baseUrl, "GET", "/marketplace/jobs?q=hub&edition=java&version=1.20.1");
    assert.equal(combined.body.total, 1);
    const badEdition = await call(service.baseUrl, "GET", "/marketplace/jobs?edition=console");
    assert.equal(badEdition.status, 400);
    assert.equal(badEdition.body.error.code, "INVALID_REQUEST");
  });

  it("25. paginates with a stable order and bounded limit/offset", async () => {
    const service = await newService();
    const buyerId = seededBuyer(service, "page-buyer");
    for (let index = 0; index < 30; index += 1) {
      const stamp = `2026-02-${String(index + 1).padStart(2, "0")}T00:00:00.000Z`;
      seedJob(service, {
        id: `job_00000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
        buyerId,
        title: `Seeded job number ${index}`,
        createdAt: stamp,
      });
    }
    const firstPage = await call(service.baseUrl, "GET", "/marketplace/jobs?limit=12");
    assert.equal(firstPage.body.items.length, 12);
    assert.equal(firstPage.body.total, 30);
    assert.equal(firstPage.body.hasMore, true);
    assert.equal(firstPage.body.items[0].title, "Seeded job number 29", "newest first");
    const secondPage = await call(service.baseUrl, "GET", "/marketplace/jobs?limit=12&offset=12");
    assert.equal(secondPage.body.items[0].title, "Seeded job number 17");
    const overlap = firstPage.body.items[11].id === secondPage.body.items[0].id;
    assert.equal(overlap, false);
    const lastPage = await call(service.baseUrl, "GET", "/marketplace/jobs?limit=12&offset=24");
    assert.equal(lastPage.body.items.length, 6);
    assert.equal(lastPage.body.hasMore, false);

    const hugeLimit = await call(service.baseUrl, "GET", "/marketplace/jobs?limit=4000");
    assert.equal(hugeLimit.status, 400);
    const hugeOffset = await call(service.baseUrl, "GET", "/marketplace/jobs?offset=100001");
    assert.equal(hugeOffset.status, 400);
    const negative = await call(service.baseUrl, "GET", "/marketplace/jobs?limit=-1");
    assert.equal(negative.status, 400);
  });

  it("26. treats wildcard and injection text literally and never errors on it", async () => {
    const service = await newService();
    const buyerId = seededBuyer(service, "sqli-buyer");
    seedJob(service, { id: "job_00000000-0000-4000-8000-000000000010", buyerId, title: "50% redstone special", description: "Literal percent in the title." });
    seedJob(service, { id: "job_00000000-0000-4000-8000-000000000011", buyerId, title: "snake_case build request", description: "Literal underscore in the title." });
    seedJob(service, { id: "job_00000000-0000-4000-8000-000000000012", buyerId, title: "Ordinary job", description: "Unrelated row." });

    const percent = await call(service.baseUrl, "GET", `/marketplace/jobs?q=${encodeURIComponent("50%")}`);
    assert.equal(percent.status, 200);
    assert.equal(percent.body.total, 1, "a bare % matches literally, not as a wildcard");
    assert.equal(percent.body.items[0].id, "job_00000000-0000-4000-8000-000000000010");

    const underscore = await call(service.baseUrl, "GET", `/marketplace/jobs?q=${encodeURIComponent("snake_case")}`);
    assert.equal(underscore.body.total, 1, "a _ matches literally, not as any-character");

    const injection = await call(service.baseUrl, "GET", `/marketplace/jobs?q=${encodeURIComponent("' OR '1'='1")}`);
    assert.equal(injection.status, 200);
    assert.equal(injection.body.total, 0, "injection text matches nothing and changes no query");

    const union = await call(service.baseUrl, "GET", `/marketplace/jobs?q=${encodeURIComponent("'; DROP TABLE buyer_jobs; --")}`);
    assert.equal(union.status, 200);
    assert.equal(union.body.total, 0);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM buyer_jobs").get().count >= 3, true, "the table survives");
  });
});

// ------------------------------------------------- F. audits, rate limits, and sanitized errors (categories 27–30)

describe("Phase 26 security behaviour", () => {
  it("27. audits every state change with ids and vocabularies only — never contents or personal details", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const buyer = await signedInAccount(service, "buyer-audit@example.test");
    const creator = await eligibleCreator(service, owner, "audit-creator@example.test", "audit-creator-studio");
    const job = await postJob(service, buyer, { title: "Audit-visible job title" });
    const secretMessage = "This proposal message must never appear in an audit record anywhere.";
    const proposals = [await submitProposal(service, creator, job.id, { message: secretMessage })];
    await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, {
      headers: bearer(buyer.accessToken), body: { proposalId: proposals[0].id },
    });

    const metadatas = ["JOB_CREATED", "PROPOSAL_SUBMITTED", "JOB_AWARDED", "PROPOSAL_SELECTED"]
      .flatMap((actionType) => auditRows(service, actionType).map((row) => row.metadata_json));
    assert.equal(metadatas.length >= 4, true, JSON.stringify(metadatas));
    const haystack = metadatas.join("\n");
    assert.equal(haystack.includes(secretMessage.slice(0, 20)), false, "proposal contents never reach the audit log");
    assert.equal(haystack.includes("buyer-i@example.test") || haystack.includes("@example.test"), false, "no email addresses");
    assert.equal(haystack.includes("Redstone sorting hall"), false, "no job titles");
    assert.equal(haystack.includes(job.id), true, "ids are the correlation handle");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log").get().count >= 4, true);
  });

  it("28. keeps rollback-surviving denial audits for refused cross-account and prerequisite attempts", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const alice = await signedInAccount(service, "alice-rb@example.test");
    const mallory = await signedInAccount(service, "mallory-rb@example.test");
    const job = await postJob(service, alice);
    // Both attempts throw after their denial record is captured; the capture flushes after the rollback.
    await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/cancel`, { headers: bearer(mallory.accessToken) });
    await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, {
      headers: bearer(mallory.accessToken), body: { proposalId: "prp_ffffffff-ffff-4fff-8fff-ffffffffffff" },
    });
    const jobDenials = auditRows(service, "JOB_ACCESS_DENIED");
    assert.equal(jobDenials.length, 2, "the refusal evidence survives its rolled-back transaction");
    assert.equal(jobDenials.every((row) => row.outcome === "DENIED"), true);
    assert.equal(jobRow(service, job.id).status, "OPEN", "the job itself never changed");
  });

  it("29. enforces the dedicated job-creation and proposal-submission rate limits", async () => {
    const service = await newService({ RATE_JOB_WRITE_MAX: "1", RATE_PROPOSAL_WRITE_MAX: "1" });
    const buyer = await signedInAccount(service, "buyer-rate@example.test");
    const first = await postJob(service, buyer);
    const second = await call(service.baseUrl, "POST", "/buyer/jobs", {
      headers: bearer(buyer.accessToken), body: jobBody({ title: "Second job request" }),
    });
    assert.equal(second.status, 429);
    assert.equal(second.body.error.code, "RATE_LIMITED");
    assert.equal(typeof second.body.error.retryAfterSeconds, "number");

    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "rate-creator@example.test", "rate-creator-studio");
    const firstProposal = await submitProposal(service, creator, first.id);
    assert.equal(firstProposal.status, "SUBMITTED");
    const secondProposal = await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(creator.accessToken), body: proposalBody(first.id),
    });
    assert.equal(secondProposal.status, 429);
    assert.equal(secondProposal.body.error.code, "RATE_LIMITED");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM job_proposals").get().count, 1);
  });

  it("30. returns typed, sanitized errors without stacks, paths, or SQL", async () => {
    const service = await newService();
    const buyer = await signedInAccount(service, "buyer-sanitize@example.test");
    const probes = [
      await call(service.baseUrl, "POST", "/buyer/jobs", { headers: bearer(buyer.accessToken), body: { title: "x" } }),
      await call(service.baseUrl, "GET", "/buyer/jobs/job_ffffffff-ffff-4fff-8fff-ffffffffffff", { headers: bearer(buyer.accessToken) }),
      await call(service.baseUrl, "GET", "/marketplace/jobs?q=" + "a".repeat(500)),
      await call(service.baseUrl, "PATCH", "/buyer/jobs/not-an-id", { headers: bearer(buyer.accessToken), body: { title: "Valid enough title" } }),
      await call(service.baseUrl, "POST", "/creator/proposal", { headers: bearer(buyer.accessToken), body: { jobId: "x" } }),
    ];
    for (const probe of probes) {
      assert.equal(probe.status >= 400, true, JSON.stringify(probe.body));
      const text = JSON.stringify(probe.body);
      assert.equal(text.includes("stack"), false, text);
      assert.equal(/\.js:\d+/.test(text), false, text);
      assert.equal(/SQLITE|sqlite_|SELECT \*|CREATE TABLE/i.test(text), false, text);
      assert.equal(typeof probe.body.error.code, "string");
      assert.equal(typeof probe.body.error.message, "string");
      assert.equal("stack" in probe.body.error, false);
    }
  });
});

// -------------------------------------------- G. website adapter and page contracts (category 31)

describe("Phase 26 website contracts", () => {
  it("31. registers the hire pages and adapter routes the site checker requires", () => {
    const checker = readFileSync(join(REPOSITORY_ROOT, "scripts", "check_website.py"), "utf8");
    for (const page of ["marketplace/hire.html", "marketplace/job.html", "marketplace/hire-post.html",
      "marketplace/hire-manage.html", "marketplace/hire-proposals.html"]) {
      assert.equal(checker.includes(`"${page}"`), true, `${page} must be registered in the information architecture`);
    }
    const adapters = readFileSync(join(REPOSITORY_ROOT, "website", "assets", "adapters.js"), "utf8");
    for (const endpoint of ["GET /marketplace/jobs", "GET /marketplace/jobs/:id", "POST /buyer/jobs",
      "GET /buyer/jobs", "GET /buyer/jobs/:id", "PATCH /buyer/jobs/:id", "POST /buyer/jobs/:id/cancel",
      "POST /buyer/jobs/:id/award", "GET /creator/proposal", "POST /creator/proposal",
      "GET /creator/proposal/:id", "PATCH /creator/proposal/:id", "POST /creator/proposal/:id/withdraw"]) {
      assert.equal(adapters.includes(`"${endpoint}"`), true, `${endpoint} must be in the adapter contract`);
    }
    const site = readFileSync(join(REPOSITORY_ROOT, "website", "assets", "site.js"), "utf8");
    assert.equal(site.includes("hire"), true, "site.js must dispatch the hire controllers");
  });
});
