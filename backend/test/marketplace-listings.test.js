/**
 * Phase 25 marketplace listing tests.
 *
 * The suite follows the same rule as the phase it tests: **the session is the owner, persistence is a real database
 * row, publication is a real state transition behind real prerequisites, and search only ever sees PUBLISHED rows.**
 * A client cannot name an owner, cannot smuggle server-controlled fields, cannot read another creator's drafts, and
 * cannot make public search leak a row that was never published. Where a request is refused, the test asserts both
 * the typed refusal and that nothing changed — and where a refusal *should* leave a trace, it asserts the audit row
 * survived the rollback.
 *
 * Search tests cover every declared filter, combined filters, bounded pagination with a stable order, and literal
 * handling of wildcard/injection text. There is no relevance, trending, or popularity claim anywhere: ordering is
 * published-at with a deterministic tie-breaker, and an empty result is a success.
 *
 * Security thresholds are raised on purpose (same as Phases 23–24): this suite deliberately produces many refused
 * requests, and the Phase 20 protections correctly treat that as abuse. Rate limits get their own small budget in
 * their own test.
 */

import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { afterEach, describe, it } from "node:test";
import { migrateToVersion, SCHEMA_VERSION } from "../src/db.js";
import { CREATOR_AGREEMENT_VERSION } from "../src/onboarding.js";
import { canPublishCreatorContent, CAPABILITY_STATE } from "../src/capabilities.js";
import { call, loginCall, register, registerVerified, startService } from "./helpers.js";

const services = new Set();
const temporaryDirectories = new Set();
const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x51).toString("base64url");
const DEVELOPER_EMAIL = "owner@example.test";
const DEVELOPER_PASSWORD = "Creator Plane 7Safe";

const RELAXED = Object.freeze({
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
  const directory = mkdtempSync(join(tmpdir(), "craftmind-phase25-"));
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
    email, plan, days, reason: "Phase 25 test grant",
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

function draftBody(overrides = {}) {
  return {
    title: "Skybound Longhouse",
    description: "A cozy longhouse built in the clouds, with a full interior and redstone lighting.",
    category: "Structures",
    edition: "java",
    minecraftVersions: ["1.20.1"],
    ...overrides,
  };
}

async function createDraft(service, creator, overrides = {}) {
  const created = await call(service.baseUrl, "POST", "/creator/listing", {
    headers: bearer(creator.accessToken), body: draftBody(overrides),
  });
  assert.equal(created.status, 201, JSON.stringify(created.body));
  return created.body;
}

async function publishDraft(service, creator, listingId) {
  const published = await call(service.baseUrl, "POST", `/creator/listing/${listingId}/publish`, {
    headers: bearer(creator.accessToken),
  });
  assert.equal(published.status, 200, JSON.stringify(published.body));
  return published.body;
}

function auditRows(service, actionType) {
  return service.database
    .prepare("SELECT * FROM admin_audit_log WHERE action_type = ? ORDER BY occurred_at, audit_id")
    .all(actionType);
}

function listingRow(service, listingId) {
  return service.database.prepare("SELECT * FROM marketplace_listings WHERE listing_id = ?").get(listingId);
}

/**
 * Inserts a listing row directly, for search/pagination tests that need deterministic published_at values without
 * depending on wall-clock resolution. Rows are exactly what the API would have written.
 */
function seedListing(service, { id, creatorId, title, description = "Seeded listing description for discovery tests.",
  category = "Structures", edition = "java", versions = ["1.20.1"], loaders = ["Fabric"], tags = [],
  status = "PUBLISHED", publishedAt = "2026-01-01T00:00:00.000Z", updatedAt = publishedAt }) {
  service.database.prepare(
    `INSERT INTO marketplace_listings
       (listing_id, creator_id, title, description, category, subcategory, edition, minecraft_versions,
        loaders, tags, image_references, status, published_at, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, '', ?, ?, ?, ?, '[]', ?, ?, ?, ?)`,
  ).run(id, creatorId, title, description, category, edition, JSON.stringify(versions), JSON.stringify(loaders),
    JSON.stringify(tags), status, publishedAt, publishedAt, updatedAt);
  return id;
}

function seededCreator(service, handle, displayName) {
  const timestamp = "2026-01-01T00:00:00.000Z";
  const userId = `usr_${handle.replaceAll("-", "")}`.slice(0, 40).padEnd(40, "0").slice(0, 40);
  const creatorId = `crt_${handle.replaceAll("-", "")}`.slice(0, 40).padEnd(40, "0").slice(0, 40);
  service.database.prepare(
    `INSERT INTO users (user_id, email, email_canonical, password_hash, display_name, status, email_verified_at, created_at, updated_at)
     VALUES (?, ?, ?, 'hash', ?, 'ACTIVE', ?, ?, ?)`,
  ).run(userId, `${handle}@example.test`, `${handle}@example.test`, displayName, timestamp, timestamp, timestamp);
  service.database.prepare(
    `INSERT INTO creator_profiles (creator_id, user_id, handle, display_name, bio, category, avatar_reference, status, verification_status, status_changed_at, verified_at, created_at, updated_at)
     VALUES (?, ?, ?, ?, '', NULL, NULL, 'ACTIVE', 'UNVERIFIED', ?, NULL, ?, ?)`,
  ).run(creatorId, userId, handle, displayName, timestamp, timestamp, timestamp);
  return creatorId;
}

// ------------------------------------------------------------------ A. persistence (scenarios 1–3)

describe("Phase 25 listing persistence", () => {
  it("1. initializes the fresh schema at v8 with the listing table, constraints, indexes, and audit types", async () => {
    const service = await newService();
    assert.equal(SCHEMA_VERSION, 8);
    const version = service.database.prepare("SELECT MAX(version) AS version FROM schema_migrations").get().version;
    assert.equal(version, 8);
    const columns = service.database.prepare("PRAGMA table_info(marketplace_listings)").all().map((row) => row.name);
    for (const column of ["listing_id", "creator_id", "title", "description", "category", "edition",
      "minecraft_versions", "loaders", "tags", "image_references", "status", "published_at", "created_at", "updated_at"]) {
      assert.equal(columns.includes(column), true, column);
    }
    const indexes = service.database.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'marketplace_listings%'").all()
      .map((row) => row.name).sort();
    assert.deepEqual(indexes, [
      "marketplace_listings_by_category",
      "marketplace_listings_by_creator",
      "marketplace_listings_by_edition",
      "marketplace_listings_by_published",
    ]);
    // The four listing actions are declared types; unknown actions are still refused by the database.
    const timestamp = "2026-01-01T00:00:00.000Z";
    const userId = seededCreator(service, "schema-studio", "Schema Studio");
    for (const actionType of ["LISTING_CREATED", "LISTING_UPDATED", "LISTING_PUBLISHED", "LISTING_ARCHIVED"]) {
      service.database.prepare(
        `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
         VALUES (?, 'SYSTEM', NULL, ?, NULL, NULL, ?, 'SUCCESS', '{}')`,
      ).run(`aud_${actionType.toLowerCase()}`, actionType, timestamp);
    }
    assert.throws(() => service.database.prepare(
      `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       VALUES ('aud_not_a_listing', 'SYSTEM', NULL, 'LISTING_DELETED', NULL, NULL, ?, 'SUCCESS', '{}')`.replace("?", `'${timestamp}'`),
    ).run(), /CHECK constraint failed/);
    // Table-level CHECKs: unknown category and a PUBLISHED row without published_at are refused.
    assert.throws(() => seedListing(service, {
      id: "lst_ffffffff-ffff-4fff-8fff-fffffffffff1", creatorId: userId, title: "Bad Category", category: "NotACategory",
    }), /CHECK constraint failed/);
    assert.throws(() => seedListing(service, {
      id: "lst_ffffffff-ffff-4fff-8fff-fffffffffff2", creatorId: userId, title: "Missing Timestamp",
      status: "PUBLISHED", publishedAt: null, updatedAt: timestamp,
    }), /NOT NULL|CHECK constraint failed/);
  });

  it("2. migrates a version 7 database to v8 without losing data or audit history", () => {
    const database = new DatabaseSync(temporaryDatabasePath());
    database.exec("PRAGMA foreign_keys = ON");
    database.exec("PRAGMA recursive_triggers = ON");
    database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    migrateToVersion(database, 7);
    const timestamp = "2026-01-01T00:00:00.000Z";
    database.exec(`
      INSERT INTO users (user_id, email, email_canonical, password_hash, display_name, status, email_verified_at, created_at, updated_at)
        VALUES ('usr_p24', 'p24@example.com', 'p24@example.com', 'hash', 'Phase24', 'ACTIVE', '${timestamp}', '${timestamp}', '${timestamp}');
      INSERT INTO creator_profiles (creator_id, user_id, handle, display_name, bio, category, avatar_reference, status, verification_status, status_changed_at, verified_at, created_at, updated_at)
        VALUES ('crt_p24', 'usr_p24', 'phase24-studio', 'Phase24 Studio', '', NULL, NULL, 'ACTIVE', 'UNVERIFIED', '${timestamp}', NULL, '${timestamp}', '${timestamp}');
      INSERT INTO seller_onboarding (user_id, referral_source, referral_detail, editions, minecraft_versions, loaders, agreement_version, agreed_at, completed_at, created_at, updated_at)
        VALUES ('usr_p24', 'YOUTUBE', '', '["java"]', '["1.20.1"]', '["Fabric"]', 'creator-agreement-2026-10', '${timestamp}', '${timestamp}', '${timestamp}', '${timestamp}');
      INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
        VALUES ('aud_p24', 'SYSTEM', NULL, 'SELLER_ONBOARDING_SAVED', 'usr_p24', NULL, '${timestamp}', 'SUCCESS', '{"source":"phase24"}');
    `);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 7);
    const appliedAt = database.prepare("SELECT applied_at FROM schema_migrations WHERE version = 7").get().applied_at;

    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(SCHEMA_VERSION, 8);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 8);
    assert.equal(database.prepare("SELECT applied_at FROM schema_migrations WHERE version = 7").get().applied_at, appliedAt);
    // Prior rows survive with their values.
    assert.equal(database.prepare("SELECT display_name FROM users WHERE user_id = 'usr_p24'").get().display_name, "Phase24");
    assert.equal(database.prepare("SELECT handle FROM creator_profiles WHERE creator_id = 'crt_p24'").get().handle, "phase24-studio");
    assert.equal(database.prepare("SELECT agreement_version FROM seller_onboarding WHERE user_id = 'usr_p24'").get().agreement_version, "creator-agreement-2026-10");
    const legacyAudit = database.prepare("SELECT * FROM admin_audit_log WHERE audit_id = 'aud_p24'").get();
    assert.equal(legacyAudit.action_type, "SELLER_ONBOARDING_SAVED");
    assert.equal(legacyAudit.metadata_json, '{"source":"phase24"}');
    // The new table exists and starts empty — additive only.
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM marketplace_listings").get().count, 0);
    // The rebuilt audit log accepts listing actions, still refuses unknown ones, and stays append-only.
    database.exec(`
      INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
        VALUES ('aud_p25', 'SYSTEM', NULL, 'LISTING_CREATED', 'usr_p24', NULL, '${timestamp}', 'SUCCESS', '{"listingId":"lst_seed"}');
    `);
    assert.throws(() => database.prepare(
      `INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
        VALUES ('aud_bad', 'SYSTEM', NULL, 'LISTING_DELETED', NULL, NULL, '${timestamp}', 'SUCCESS', '{}')`,
    ).run(), /CHECK constraint failed/);
    assert.throws(() => database.prepare("UPDATE admin_audit_log SET outcome = 'DENIED' WHERE audit_id = 'aud_p24'").run(), /append-only/);
    assert.throws(() => database.prepare("DELETE FROM admin_audit_log WHERE audit_id = 'aud_p24'").run(), /append-only/);
    database.close();
  });

  it("3. re-running the v8 migration is a no-op", () => {
    const database = new DatabaseSync(temporaryDatabasePath());
    database.exec("PRAGMA foreign_keys = ON");
    database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    migrateToVersion(database, SCHEMA_VERSION);
    const versions = database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count;
    const indexes = database.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'index' AND name LIKE 'marketplace_listings%'").get().count;
    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, versions);
    assert.equal(versions, 8);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type = 'index' AND name LIKE 'marketplace_listings%'").get().count, indexes);
    database.close();
  });
});

// ------------------------------------------------------------------ B. lifecycle and ownership (scenarios 4–14)

describe("Phase 25 listing lifecycle", () => {
  it("4. creates a valid draft persisted under the session creator", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "seller@example.test", "seller-studio");
    const draft = await createDraft(service, creator, { subcategory: "Housing", tags: ["longhouse", "cozy"] });
    assert.equal(draft.status, "DRAFT");
    assert.equal(draft.publishedAt, null);
    assert.match(draft.id, /^lst_[0-9a-f-]{36}$/);
    const row = listingRow(service, draft.id);
    assert.equal(row.status, "DRAFT");
    assert.equal(row.published_at, null);
    assert.equal(row.created_at, row.updated_at);
    const audits = auditRows(service, "LISTING_CREATED");
    assert.equal(audits.length, 1);
    assert.equal(audits[0].outcome, "SUCCESS");
    assert.equal(JSON.parse(audits[0].metadata_json).listingId, draft.id);
    const own = await call(service.baseUrl, "GET", "/creator/listing", { headers: bearer(creator.accessToken) });
    assert.equal(own.status, 200);
    assert.equal(own.body.counts.total, 1);
    assert.equal(own.body.counts.draft, 1);
  });

  it("5. refuses invalid and oversized listing content without writing anything", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "invalid@example.test", "invalid-studio");
    const cases = [
      { title: "Hi", description: draftBody().description },
      { title: "x".repeat(121), description: draftBody().description },
      { title: "Valid title here", description: "too short" },
      { title: "Valid title here", description: "x".repeat(5001) },
      { title: "Valid title here", description: draftBody().description, category: "Explosions" },
      { title: "Valid title here", description: draftBody().description, edition: "not-an-edition" },
      { title: "Valid title here", description: draftBody().description, minecraftVersions: ["banana"] },
      { title: "Valid title here", description: draftBody().description, tags: Array.from({ length: 11 }, (_, index) => `tag-${index}`) },
      { title: "Valid title here", description: draftBody().description, unexpectedKey: true },
    ];
    for (const body of cases) {
      const refused = await call(service.baseUrl, "POST", "/creator/listing", {
        headers: bearer(creator.accessToken), body: { ...draftBody(), ...body },
      });
      assert.equal(refused.status, 400, JSON.stringify(refused.body));
      assert.equal(refused.body.error.code, "INVALID_REQUEST");
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM marketplace_listings").get().count, 0);
    assert.equal(auditRows(service, "LISTING_CREATED").length, 0);
  });

  it("6. refuses listing creation without an authenticated session", async () => {
    const service = await newService();
    const anonymous = await call(service.baseUrl, "POST", "/creator/listing", { body: draftBody() });
    assert.equal(anonymous.status, 401);
    assert.equal(anonymous.body.error.code, "AUTHENTICATION_REQUIRED");
    const forged = await call(service.baseUrl, "POST", "/creator/listing", {
      headers: bearer("not-a-real-token"), body: draftBody(),
    });
    assert.equal(forged.status, 401);
    const own = await call(service.baseUrl, "GET", "/creator/listing");
    assert.equal(own.status, 401);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM marketplace_listings").get().count, 0);
  });

  it("7. refuses listing creation for an unverified email — no session, no listing", async () => {
    const service = await newService();
    const created = await register(service.baseUrl, { email: "unverified@example.test" });
    assert.equal(created.status, 201);
    const signedIn = await loginCall(service.baseUrl, { email: "unverified@example.test" });
    assert.equal(signedIn.status, 403);
    assert.equal(signedIn.body.error.code, "EMAIL_NOT_VERIFIED");
    const attempt = await call(service.baseUrl, "POST", "/creator/listing", { body: draftBody() });
    assert.equal(attempt.status, 401);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM marketplace_listings").get().count, 0);
  });

  it("8. enforces the real publishing prerequisites for drafts: entitlement, then profile", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    // Verified but FREE: entitled for nothing.
    const free = await signedInAccount(service, "free@example.test");
    const freeAttempt = await call(service.baseUrl, "POST", "/creator/listing", {
      headers: bearer(free.accessToken), body: draftBody(),
    });
    assert.equal(freeAttempt.status, 403);
    assert.equal(freeAttempt.body.error.code, "CREATOR_ENTITLEMENT_REQUIRED");
    // Entitled but no creator profile yet: the missing profile resource answers with Phase 23's uniform 404
    // (CREATOR_PROFILE_NOT_FOUND), never with a silently created draft.
    const entitled = await signedInAccount(service, "nop@example.test");
    await grantPlan(service, owner, "nop@example.test");
    const profileAttempt = await call(service.baseUrl, "POST", "/creator/listing", {
      headers: bearer(entitled.accessToken), body: draftBody(),
    });
    assert.equal(profileAttempt.status, 404);
    assert.equal(profileAttempt.body.error.code, "CREATOR_PROFILE_NOT_FOUND");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM marketplace_listings").get().count, 0);
    // The capability answer stays honest for the FREE account, and creating drafts never grants the plan.
    const publish = canPublishCreatorContent(service.database, service.configuration,
      service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get("free@example.test").user_id);
    assert.equal(publish.allowed, false);
    assert.equal(publish.state, CAPABILITY_STATE.DENIED);
    assert.equal(publish.code, null);
    assert.equal(publish.wouldBeEligible, false);
    const membership = service.database.prepare("SELECT plan, status FROM membership_accounts WHERE user_id = ?")
      .get(service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get("free@example.test").user_id);
    assert.equal(membership.plan, "FREE", "listing attempts must never escalate the plan");
  });

  it("9. rejects server-controlled fields from the client on create and update", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "control@example.test", "control-studio");
    for (const field of ["id", "listingId", "creatorId", "ownerId", "status", "publishedAt", "createdAt", "verification"]) {
      const refused = await call(service.baseUrl, "POST", "/creator/listing", {
        headers: bearer(creator.accessToken), body: { ...draftBody(), [field]: "client-supplied" },
      });
      assert.equal(refused.status, 400, `${field}: ${JSON.stringify(refused.body)}`);
    }
    const draft = await createDraft(service, creator);
    const patchRefused = await call(service.baseUrl, "PATCH", `/creator/listing/${draft.id}`, {
      headers: bearer(creator.accessToken), body: { status: "PUBLISHED", publishedAt: "2020-01-01T00:00:00.000Z" },
    });
    assert.equal(patchRefused.status, 400);
    const row = listingRow(service, draft.id);
    assert.equal(row.status, "DRAFT");
    assert.equal(row.published_at, null);
  });

  it("10. keeps every listing owner-only across accounts — reads, edits, publish, and archive", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const victim = await eligibleCreator(service, owner, "victim@example.test", "victim-studio");
    const attacker = await eligibleCreator(service, owner, "attacker@example.test", "attacker-studio");
    const draft = await createDraft(service, victim, { title: "Victim Secret Project" });
    const publish = await publishDraft(service, victim, draft.id);
    assert.equal(publish.status, "PUBLISHED");

    const read = await call(service.baseUrl, "GET", `/creator/listing/${draft.id}`, { headers: bearer(attacker.accessToken) });
    assert.equal(read.status, 404);
    assert.equal(read.body.error.code, "LISTING_NOT_FOUND");
    const patch = await call(service.baseUrl, "PATCH", `/creator/listing/${draft.id}`, {
      headers: bearer(attacker.accessToken), body: { title: "Attacker Took Over This Title" },
    });
    assert.equal(patch.status, 404);
    const republish = await call(service.baseUrl, "POST", `/creator/listing/${draft.id}/publish`, { headers: bearer(attacker.accessToken) });
    assert.equal(republish.status, 404);
    const archive = await call(service.baseUrl, "POST", `/creator/listing/${draft.id}/archive`, { headers: bearer(attacker.accessToken) });
    assert.equal(archive.status, 404);
    // Nothing changed, and the refusal left an audit trace that survived the rollback.
    const row = listingRow(service, draft.id);
    assert.equal(row.title, "Victim Secret Project");
    assert.equal(row.status, "PUBLISHED");
    const denials = auditRows(service, "CREATOR_ACCESS_DENIED");
    assert.equal(denials.length >= 4, true, JSON.stringify(denials));
    for (const denial of denials) {
      assert.equal(denial.outcome, "DENIED");
      const metadata = JSON.parse(denial.metadata_json);
      assert.equal(metadata.resourceKind, "LISTING");
      assert.equal(metadata.reason, "not_owner");
    }
    // The attacker's own list is empty — they gained nothing.
    const own = await call(service.baseUrl, "GET", "/creator/listing", { headers: bearer(attacker.accessToken) });
    assert.equal(own.body.counts.total, 0);
  });

  it("11. updates an owned draft with validation on the merged result, audited by field name", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "editor@example.test", "editor-studio");
    const draft = await createDraft(service, creator);
    const patched = await call(service.baseUrl, "PATCH", `/creator/listing/${draft.id}`, {
      headers: bearer(creator.accessToken), body: { title: "Retitled Longhouse", subcategory: "Cabins" },
    });
    assert.equal(patched.status, 200, JSON.stringify(patched.body));
    assert.equal(patched.body.title, "Retitled Longhouse");
    assert.equal(patched.body.subcategory, "Cabins");
    assert.equal(patched.body.description, draft.description, "unmentioned fields are preserved, never wiped");
    const row = listingRow(service, draft.id);
    assert.equal(row.title, "Retitled Longhouse");
    const updates = auditRows(service, "LISTING_UPDATED");
    assert.equal(updates.length, 1);
    assert.deepEqual(JSON.parse(updates[0].metadata_json).fields.sort(), ["subcategory", "title"]);

    // An invalid merged document is refused and nothing changes — including no new audit row.
    const refused = await call(service.baseUrl, "PATCH", `/creator/listing/${draft.id}`, {
      headers: bearer(creator.accessToken), body: { title: "no" },
    });
    assert.equal(refused.status, 400);
    assert.equal(listingRow(service, draft.id).title, "Retitled Longhouse");
    assert.equal(auditRows(service, "LISTING_UPDATED").length, 1);
  });

  it("12. publishes only with every prerequisite, guards the state machine, and audits both outcomes", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "pub@example.test", "pub-studio");
    const draft = await createDraft(service, creator);
    const published = await call(service.baseUrl, "POST", `/creator/listing/${draft.id}/publish`, {
      headers: bearer(creator.accessToken),
    });
    assert.equal(published.status, 200, JSON.stringify(published.body));
    assert.equal(published.body.status, "PUBLISHED");
    assert.ok(published.body.publishedAt);
    const again = await call(service.baseUrl, "POST", `/creator/listing/${draft.id}/publish`, {
      headers: bearer(creator.accessToken),
    });
    assert.equal(again.status, 409);
    assert.equal(again.body.error.code, "LISTING_STATE_CONFLICT");
    const audits = auditRows(service, "LISTING_PUBLISHED");
    assert.equal(audits.length, 1);
    assert.equal(JSON.parse(audits[0].metadata_json).listingId, draft.id);

    // A creator with the entitlement and a profile but no accepted agreement is blocked at publish, honestly.
    const partial = await signedInAccount(service, "partial@example.test");
    await grantPlan(service, owner, "partial@example.test");
    const profile = await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(partial.accessToken), body: { handle: "partial-studio", displayName: "Partial Studio" },
    });
    assert.equal(profile.status, 201, JSON.stringify(profile.body));
    const blockedDraft = await call(service.baseUrl, "POST", "/creator/listing", {
      headers: bearer(partial.accessToken), body: draftBody({ title: "Blocked Before Agreement" }),
    });
    assert.equal(blockedDraft.status, 201);
    const blocked = await call(service.baseUrl, "POST", `/creator/listing/${blockedDraft.body.id}/publish`, {
      headers: bearer(partial.accessToken),
    });
    assert.equal(blocked.status, 403);
    assert.equal(blocked.body.error.code, "LISTING_PUBLISH_BLOCKED");
    assert.match(blocked.body.error.message, /agreement/i);
    assert.equal(listingRow(service, blockedDraft.body.id).status, "DRAFT", "a blocked publish must not flip state");
    const denial = auditRows(service, "CREATOR_ACCESS_DENIED").find((row) => JSON.parse(row.metadata_json).reason === "agreement_missing");
    assert.ok(denial, "the blocked publish must leave an auditable denial");
    assert.equal(denial.outcome, "DENIED");
    // Draft listings stay out of public search either way.
    const search = await call(service.baseUrl, "GET", "/marketplace/listings");
    assert.equal(search.body.total, 1);
  });

  it("13. archives owned listings with state guards and an audit that names the previous status", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "archive@example.test", "archive-studio");
    const draft = await createDraft(service, creator);
    const archived = await call(service.baseUrl, "POST", `/creator/listing/${draft.id}/archive`, {
      headers: bearer(creator.accessToken),
    });
    assert.equal(archived.status, 200, JSON.stringify(archived.body));
    assert.equal(archived.body.status, "ARCHIVED");
    const again = await call(service.baseUrl, "POST", `/creator/listing/${draft.id}/archive`, {
      headers: bearer(creator.accessToken),
    });
    assert.equal(again.status, 409);
    assert.equal(again.body.error.code, "LISTING_STATE_CONFLICT");
    const patch = await call(service.baseUrl, "PATCH", `/creator/listing/${draft.id}`, {
      headers: bearer(creator.accessToken), body: { title: "Cannot Edit An Archive" },
    });
    assert.equal(patch.status, 409);
    const audits = auditRows(service, "LISTING_ARCHIVED");
    assert.equal(audits.length, 1);
    assert.equal(JSON.parse(audits[0].metadata_json).previousStatus, "DRAFT");
    const own = await call(service.baseUrl, "GET", "/creator/listing", { headers: bearer(creator.accessToken) });
    assert.deepEqual(own.body.counts, { total: 1, draft: 0, published: 0, archived: 1 });
  });

  it("14. treats repeated creates as distinct drafts and repeated state changes as conflicts", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "repeat@example.test", "repeat-studio");
    const first = await createDraft(service, creator);
    const second = await createDraft(service, creator);
    assert.notEqual(first.id, second.id, "identical content is two drafts, not a silent dedupe");
    const rows = service.database.prepare("SELECT COUNT(*) AS count FROM marketplace_listings").get().count;
    assert.equal(rows, 2);
    assert.equal(auditRows(service, "LISTING_CREATED").length, 2);
    // Publish → publish is 409; archive → archive is 409; each transition audited exactly once.
    await publishDraft(service, creator, first.id);
    const doublePublish = await call(service.baseUrl, "POST", `/creator/listing/${first.id}/publish`, {
      headers: bearer(creator.accessToken),
    });
    assert.equal(doublePublish.status, 409);
    await call(service.baseUrl, "POST", `/creator/listing/${first.id}/archive`, { headers: bearer(creator.accessToken) });
    const doubleArchive = await call(service.baseUrl, "POST", `/creator/listing/${first.id}/archive`, {
      headers: bearer(creator.accessToken),
    });
    assert.equal(doubleArchive.status, 409);
    assert.equal(auditRows(service, "LISTING_PUBLISHED").length, 1);
    assert.equal(auditRows(service, "LISTING_ARCHIVED").length, 1);
  });
});

// ------------------------------------------------------------------ C. public search and detail (scenarios 15–23)

describe("Phase 25 public search and discovery", () => {
  it("15. shows published listings publicly while drafts and archived rows stay hidden", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "visible@example.test", "visible-studio");
    const publishedDraft = await createDraft(service, creator, { title: "Published Hiddenaway" });
    await publishDraft(service, creator, publishedDraft.id);
    const draft = await createDraft(service, creator, { title: "Draft Secretbase" });
    const toArchive = await createDraft(service, creator, { title: "Archived Forgotten Temple" });
    await call(service.baseUrl, "POST", `/creator/listing/${toArchive.id}/archive`, { headers: bearer(creator.accessToken) });

    const search = await call(service.baseUrl, "GET", "/marketplace/listings");
    assert.equal(search.status, 200);
    assert.equal(search.body.total, 1);
    assert.deepEqual(search.body.items.map((item) => item.title), ["Published Hiddenaway"]);
    assert.equal(await call(service.baseUrl, "GET", `/marketplace/listings/${draft.id}`).then((r) => r.status), 404);
    assert.equal(await call(service.baseUrl, "GET", `/marketplace/listings/${toArchive.id}`).then((r) => r.status), 404);
    const detail = await call(service.baseUrl, "GET", `/marketplace/listings/${publishedDraft.id}`);
    assert.equal(detail.status, 200);
    assert.equal(detail.body.title, "Published Hiddenaway");
    // The owner still sees all three states through their own list.
    const own = await call(service.baseUrl, "GET", "/creator/listing", { headers: bearer(creator.accessToken) });
    assert.deepEqual(own.body.counts, { total: 3, draft: 1, published: 1, archived: 1 });
  });

  it("16. matches free text across title, description, and tags — case-insensitively, bounded", async () => {
    const service = await newService();
    const creatorId = seededCreator(service, "search-studio", "Search Studio");
    seedListing(service, {
      id: "lst_00000000-0000-4000-8000-000000000001", creatorId, title: "Cobblestone Castle",
      description: "A medieval fortress build.", tags: ["castle", "medieval"],
    });
    seedListing(service, {
      id: "lst_00000000-0000-4000-8000-000000000002", creatorId, title: "Meadow Cottage",
      description: "Comes with a redstone lamp wiring diagram inside.", tags: ["cozy"],
    });
    seedListing(service, {
      id: "lst_00000000-0000-4000-8000-000000000003", creatorId, title: "Mesa Mining Camp",
      description: "A survival spawn build.", tags: ["ocean", "mining"],
    });
    const search = (query) => call(service.baseUrl, "GET", `/marketplace/listings?${query}`);
    assert.equal((await search("q=cobblestone")).body.total, 1, "title match");
    assert.equal((await search("q=lamp")).body.total, 1, "description match");
    assert.equal((await search("q=ocean")).body.total, 1, "tag match");
    assert.equal((await search("q=COBBLESTONE")).body.total, 1, "case-insensitive");
    assert.equal((await search("q=cast")).body.total, 1, "substring match");
    const empty = await search("q=there-is-no-such-listing");
    assert.equal(empty.status, 200, "no results is a success, not an error");
    assert.equal(empty.body.total, 0);
    assert.deepEqual(empty.body.items, []);
    assert.ok(!Number.isNaN(Date.parse(empty.body.searchedAt)), "accurate metadata is an ISO timestamp");
  });

  it("17. filters by category", async () => {
    const service = await newService();
    const creatorId = seededCreator(service, "cat-studio", "Cat Studio");
    seedListing(service, { id: "lst_00000000-0000-4000-8000-000000000011", creatorId, title: "Castle Keep", category: "Structures" });
    seedListing(service, { id: "lst_00000000-0000-4000-8000-000000000012", creatorId, title: "Copper Factory", category: "Redstone" });
    const structures = await call(service.baseUrl, "GET", "/marketplace/listings?category=Structures");
    assert.equal(structures.body.total, 1);
    assert.equal(structures.body.items[0].category, "Structures");
    const redstone = await call(service.baseUrl, "GET", "/marketplace/listings?category=Redstone");
    assert.equal(redstone.body.total, 1);
    assert.equal(redstone.body.items[0].category, "Redstone");
    const nonsense = await call(service.baseUrl, "GET", "/marketplace/listings?category=Explosions");
    assert.equal(nonsense.status, 400, "an unknown category is a typed 400, not an empty success");
    assert.equal(nonsense.body.error.code, "INVALID_REQUEST");
  });

  it("18. filters by edition, Minecraft version, and loader", async () => {
    const service = await newService();
    const creatorId = seededCreator(service, "compat-studio", "Compat Studio");
    seedListing(service, {
      id: "lst_00000000-0000-4000-8000-000000000021", creatorId, title: "Java Garden",
      edition: "java", versions: ["1.20.1"], loaders: ["Fabric"],
    });
    seedListing(service, {
      id: "lst_00000000-0000-4000-8000-000000000022", creatorId, title: "Java Foundry",
      edition: "java", versions: ["1.21.0"], loaders: ["Forge"],
    });
    seedListing(service, {
      id: "lst_00000000-0000-4000-8000-000000000023", creatorId, title: "Bedrock Bay",
      edition: "bedrock", versions: ["1.20.1"], loaders: [],
    });
    const java = await call(service.baseUrl, "GET", "/marketplace/listings?edition=java");
    assert.equal(java.body.total, 2);
    const bedrock = await call(service.baseUrl, "GET", "/marketplace/listings?edition=bedrock");
    assert.equal(bedrock.body.total, 1);
    const v1201 = await call(service.baseUrl, "GET", "/marketplace/listings?version=1.20.1");
    assert.equal(v1201.body.total, 2);
    const fabric = await call(service.baseUrl, "GET", "/marketplace/listings?loader=Fabric");
    assert.equal(fabric.body.total, 1);
    assert.equal(fabric.body.items[0].title, "Java Garden");
    const forge = await call(service.baseUrl, "GET", "/marketplace/listings?loader=Forge");
    assert.equal(forge.body.total, 1);
    const unknownLoader = await call(service.baseUrl, "GET", "/marketplace/listings?loader=NotALoader");
    assert.equal(unknownLoader.status, 400);
    const unknownEdition = await call(service.baseUrl, "GET", "/marketplace/listings?edition=console");
    assert.equal(unknownEdition.status, 400);
  });

  it("19. filters by the real creator handle, and an unknown handle is an empty success", async () => {
    const service = await newService();
    const alpha = seededCreator(service, "alpha-studio", "Alpha Studio");
    const beta = seededCreator(service, "beta-studio", "Beta Studio");
    seedListing(service, { id: "lst_00000000-0000-4000-8000-000000000031", creatorId: alpha, title: "Alpha Fortress" });
    seedListing(service, { id: "lst_00000000-0000-4000-8000-000000000032", creatorId: alpha, title: "Alpha Harbor" });
    seedListing(service, { id: "lst_00000000-0000-4000-8000-000000000033", creatorId: beta, title: "Beta Workshop" });
    const betaList = await call(service.baseUrl, "GET", "/marketplace/listings?creator=beta-studio");
    assert.equal(betaList.body.total, 1);
    assert.equal(betaList.body.items[0].creator.handle, "beta-studio");
    assert.equal(betaList.body.items[0].creator.displayName, "Beta Studio");
    const alphaList = await call(service.baseUrl, "GET", "/marketplace/listings?creator=alpha-studio");
    assert.equal(alphaList.body.total, 2);
    const unknown = await call(service.baseUrl, "GET", "/marketplace/listings?creator=nobody-studio");
    assert.equal(unknown.status, 200);
    assert.equal(unknown.body.total, 0);
  });

  it("20. combines every filter with free text and paginates in a stable, deterministic order", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "combo@example.test", "combo-studio");
    const other = await eligibleCreator(service, owner, "combob@example.test", "combob-studio");
    const base = await createDraft(service, creator,
      { title: "Cobblestone Castle", category: "Structures", loaders: ["Fabric"] });
    await publishDraft(service, creator, base.id);
    const noiseA = await createDraft(service, creator,
      { title: "Cobblestone Hut", category: "Structures", loaders: ["Forge"] });
    await publishDraft(service, creator, noiseA.id);
    const noiseB = await createDraft(service, other, { title: "Cobblestone Manor", category: "Decorations" });
    await publishDraft(service, other, noiseB.id);

    const combined = await call(service.baseUrl, "GET",
      "/marketplace/listings?q=cobblestone&category=Structures&edition=java&version=1.20.1&loader=Fabric&creator=combo-studio");
    assert.equal(combined.status, 200);
    assert.equal(combined.body.total, 1);
    assert.equal(combined.body.items[0].id, base.id);
    const impossible = await call(service.baseUrl, "GET",
      "/marketplace/listings?q=cobblestone&category=Redstone&edition=bedrock");
    assert.equal(impossible.status, 200);
    assert.equal(impossible.body.total, 0);

    // Pagination: seed more published rows with distinct timestamps and check boundaries plus stable order.
    const creatorRow = service.database.prepare("SELECT creator_id FROM creator_profiles WHERE handle = ?").get("combo-studio");
    for (let index = 0; index < 30; index += 1) {
      seedListing(service, {
        id: `lst_00000000-0000-4000-8000-${(index + 100).toString(16).padStart(12, "0")}`,
        creatorId: creatorRow.creator_id,
        title: `Discovery Row ${index.toString().padStart(2, "0")}`,
        publishedAt: `2027-02-${(index + 1).toString().padStart(2, "0")}T00:00:00.000Z`,
      });
    }
    const page1 = await call(service.baseUrl, "GET", "/marketplace/listings?limit=10&offset=0");
    const page1Again = await call(service.baseUrl, "GET", "/marketplace/listings?limit=10&offset=0");
    assert.deepEqual(page1.body.items.map((item) => item.id), page1Again.body.items.map((item) => item.id),
      "the same page is byte-stable across calls");
    const page2 = await call(service.baseUrl, "GET", "/marketplace/listings?limit=10&offset=10");
    const overlap = page1.body.items.filter((item) => page2.body.items.some((other2) => other2.id === item.id));
    assert.deepEqual(overlap, [], "pages never overlap");
    const firstTitles = page1.body.items.map((item) => item.title);
    assert.deepEqual(firstTitles, ["Discovery Row 29", "Discovery Row 28", "Discovery Row 27", "Discovery Row 26",
      "Discovery Row 25", "Discovery Row 24", "Discovery Row 23", "Discovery Row 22", "Discovery Row 21",
      "Discovery Row 20"], "newest published first");
    assert.equal(page1.body.hasMore, true);
    const lastPage = await call(service.baseUrl, "GET", "/marketplace/listings?limit=10&offset=40");
    assert.equal(lastPage.status, 200);
    assert.deepEqual(lastPage.body.items, []);
    assert.equal(lastPage.body.hasMore, false);
    assert.equal(page1.body.limit, 10);
    assert.equal(page1.body.total, 33, "total counts every published row, not just the page");
  });

  it("21. enforces bounded pagination and query limits with typed 400s", async () => {
    const service = await newService();
    for (const query of ["limit=0", "limit=49", "limit=abc", "offset=-1", "offset=100001",
      `q=${"x".repeat(201)}`]) {
      const refused = await call(service.baseUrl, "GET", `/marketplace/listings?${query}`);
      assert.equal(refused.status, 400, query);
      assert.equal(refused.body.error.code, "INVALID_REQUEST", query);
    }
    const beyond = await call(service.baseUrl, "GET", "/marketplace/listings?offset=100000");
    assert.equal(beyond.status, 200, "an offset past the end is an empty page, not an error");
    assert.deepEqual(beyond.body.items, []);
    assert.equal(beyond.body.hasMore, false);
    assert.equal(beyond.body.total, 0);
  });

  it("22. treats SQL wildcards and injection text as literal search text", async () => {
    const service = await newService();
    const creatorId = seededCreator(service, "literal-studio", "Literal Studio");
    seedListing(service, {
      id: "lst_00000000-0000-4000-8000-000000000041", creatorId, title: "100% Sandstone Den",
    });
    seedListing(service, {
      id: "lst_00000000-0000-4000-8000-000000000042", creatorId, title: "Plain Oak House",
    });
    const percent = await call(service.baseUrl, "GET", "/marketplace/listings?q=%25");
    assert.equal(percent.status, 200);
    assert.equal(percent.body.total, 1, "a bare % matches only the literal percent sign, not everything");
    assert.equal(percent.body.items[0].title, "100% Sandstone Den");
    const underscore = await call(service.baseUrl, "GET", "/marketplace/listings?q=_");
    assert.equal(underscore.body.total, 0, "a bare _ is not a single-character wildcard");
    const injection = await call(service.baseUrl, "GET", `/marketplace/listings?q=${encodeURIComponent("' OR 1=1 --")}`);
    assert.equal(injection.status, 200, "injection text is ordinary text — never a 500");
    assert.equal(injection.body.total, 0);
    const union = await call(service.baseUrl, "GET", `/marketplace/listings?q=${encodeURIComponent("'; DROP TABLE marketplace_listings; --")}`);
    assert.equal(union.status, 200);
    assert.equal(union.body.total, 0);
    assert.ok(service.database.prepare("SELECT COUNT(*) AS count FROM marketplace_listings").get().count >= 2,
      "the table is intact after every hostile query");
  });

  it("23. answers an empty marketplace with accurate pagination metadata", async () => {
    const service = await newService();
    const empty = await call(service.baseUrl, "GET", "/marketplace/listings?limit=5");
    assert.equal(empty.status, 200);
    assert.equal(empty.body.total, 0);
    assert.equal(empty.body.limit, 5);
    assert.equal(empty.body.offset, 0);
    assert.equal(empty.body.hasMore, false);
    assert.deepEqual(empty.body.items, []);
    assert.ok(Date.parse(empty.body.searchedAt) > 0);
  });
});

// ------------------------------------------------------------------ D. response hygiene, audits, limits (scenarios 24–28)

describe("Phase 25 response hygiene, audits, and limits", () => {
  it("24. exposes only the intended fields publicly, and full lifecycle fields to the owner", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "fields@example.test", "fields-studio");
    const draft = await createDraft(service, creator, { tags: ["clean"] });
    await publishDraft(service, creator, draft.id);
    const [list, detail, own] = await Promise.all([
      call(service.baseUrl, "GET", "/marketplace/listings"),
      call(service.baseUrl, "GET", `/marketplace/listings/${draft.id}`),
      call(service.baseUrl, "GET", `/creator/listing/${draft.id}`, { headers: bearer(creator.accessToken) }),
    ]);
    const publicKeys = ["category", "creator", "description", "edition", "id", "imageReferences", "loaders",
      "minecraftVersions", "publishedAt", "subcategory", "summary", "tags", "title"].sort();
    assert.deepEqual(Object.keys(list.body.items[0]).sort(), publicKeys);
    assert.deepEqual(Object.keys(detail.body).sort(), publicKeys);
    const publicText = JSON.stringify(list.body) + JSON.stringify(detail.body);
    for (const secret of ["user_id", "creator_id", "userId", "creatorId", "status", "email", "usr_", "crt_",
      "verification", "requestId", "STACK"]) {
      assert.equal(publicText.includes(secret), false, `public responses must not leak ${secret}`);
    }
    // The owner sees the lifecycle fields the public must not.
    assert.equal(own.body.status, "PUBLISHED");
    // Attribution is real: handle and display name come from the creator profile, not a client claim.
    assert.equal(detail.body.creator.handle, "fields-studio");
    assert.equal(detail.body.creator.displayName, "fields-studio Studio");
    assert.equal(detail.body.creator.avatarReference, null, "honest null, never a placeholder image");
    assert.equal(typeof detail.body.summary, "string");
    assert.ok(detail.body.summary.length <= 180);
  });

  it("25. keeps owner counts and views truthful through the whole lifecycle", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "counts@example.test", "counts-studio");
    const one = await createDraft(service, creator, { title: "Count One" });
    const two = await createDraft(service, creator, { title: "Count Two" });
    await publishDraft(service, creator, two.id);
    const three = await createDraft(service, creator, { title: "Count Three" });
    await call(service.baseUrl, "POST", `/creator/listing/${three.id}/archive`, { headers: bearer(creator.accessToken) });
    const own = await call(service.baseUrl, "GET", "/creator/listing", { headers: bearer(creator.accessToken) });
    assert.equal(own.status, 200);
    assert.deepEqual(own.body.counts, { total: 3, draft: 1, published: 1, archived: 1 });
    assert.equal(own.body.listings.length, 3);
    const statuses = Object.fromEntries(own.body.listings.map((item) => [item.title, item.status]));
    assert.equal(statuses["Count One"], "DRAFT");
    assert.equal(statuses["Count Two"], "PUBLISHED");
    assert.equal(statuses["Count Three"], "ARCHIVED");
    const detail = await call(service.baseUrl, "GET", `/creator/listing/${one.id}`, { headers: bearer(creator.accessToken) });
    assert.equal(detail.status, 200);
    assert.equal(detail.body.status, "DRAFT");
    assert.equal(detail.body.publishedAt, null);
  });

  it("26. audits every success with listing metadata, and denials survive rollback", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "audit@example.test", "audit-studio");
    const other = await eligibleCreator(service, owner, "audito@example.test", "audito-studio");
    const draft = await createDraft(service, creator);
    await call(service.baseUrl, "PATCH", `/creator/listing/${draft.id}`, {
      headers: bearer(creator.accessToken), body: { title: "Audited Retitle" },
    });
    await publishDraft(service, creator, draft.id);
    await call(service.baseUrl, "POST", `/creator/listing/${draft.id}/archive`, { headers: bearer(creator.accessToken) });
    for (const [actionType, expected] of [["LISTING_CREATED", 1], ["LISTING_UPDATED", 1],
      ["LISTING_PUBLISHED", 1], ["LISTING_ARCHIVED", 1]]) {
      const rows = auditRows(service, actionType);
      assert.equal(rows.length, expected, actionType);
      assert.equal(rows[0].outcome, "SUCCESS");
      const metadata = JSON.parse(rows[0].metadata_json);
      assert.equal(metadata.listingId, draft.id);
      // Audit metadata is structured and bounded — no listing body, no account email, no SQL.
      assert.equal(JSON.stringify(metadata).includes("SELECT"), false);
      assert.equal(JSON.stringify(metadata).includes("@example.test"), false);
    }
    // A refused cross-account patch: denial audit present, body unchanged, no LISTING_UPDATED row.
    const before = auditRows(service, "LISTING_UPDATED").length;
    const refused = await call(service.baseUrl, "PATCH", `/creator/listing/${draft.id}`, {
      headers: bearer(other.accessToken), body: { title: "Not Yours" },
    });
    assert.equal(refused.status, 404);
    assert.equal(listingRow(service, draft.id).title, "Audited Retitle");
    assert.equal(auditRows(service, "LISTING_UPDATED").length, before, "the denial must not record an update");
    const denials = auditRows(service, "CREATOR_ACCESS_DENIED");
    assert.ok(denials.length >= 1);
    assert.equal(denials.at(-1).outcome, "DENIED");
  });

  it("27. returns sanitized, typed errors for every listing failure shape", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "errors@example.test", "errors-studio");
    const draft = await createDraft(service, creator);
    await publishDraft(service, creator, draft.id);
    const scenarios = [
      await call(service.baseUrl, "GET", "/marketplace/listings/lst_ffffffff-ffff-4fff-8fff-fffffffffff0"),
      await call(service.baseUrl, "GET", `/creator/listing/${draft.id}`, { headers: bearer("bad-token") }),
      await call(service.baseUrl, "PATCH", `/creator/listing/${draft.id}`, {
        headers: bearer(creator.accessToken), body: { title: "x" },
      }),
      await call(service.baseUrl, "POST", `/creator/listing/${draft.id}/publish`, { headers: bearer(creator.accessToken) }),
      await call(service.baseUrl, "PATCH", `/creator/listing/${draft.id}`, {
        headers: bearer(creator.accessToken), body: { title: "Published Is Read Only" },
      }),
    ];
    for (const scenario of scenarios) {
      assert.ok(scenario.status >= 400);
      const text = JSON.stringify(scenario.body);
      assert.ok(scenario.body.error?.code, text);
      assert.ok(scenario.body.error?.message?.length > 0, text);
      assert.ok(scenario.body.error?.requestId, text);
      for (const leak of ["stack", "sqlite", "SQLITE", "node:internal", "SELECT ", "Bearer ", "/home/"]) {
        assert.equal(text.includes(leak), false, `${scenario.body.error.code} leaked ${leak}`);
      }
    }
    // Malformed JSON and a bad content type stay in the typed contract too.
    const malformed = await call(service.baseUrl, "POST", "/creator/listing", {
      headers: bearer(creator.accessToken), raw: "{not json",
    });
    assert.equal(malformed.status, 400);
    assert.equal(malformed.body.error.code, "INVALID_REQUEST");
  });

  it("28. gives listing writes, owner reads, and public search their own rate-limit budgets", async () => {
    const service = await newService({ RATE_CREATOR_WRITE_MAX: "2", RATE_CREATOR_READ_MAX: "2" });
    const owner = await ownerSession(service);
    const creator = await eligibleCreator(service, owner, "throttle@example.test", "throttle-studio");
    const first = await call(service.baseUrl, "POST", "/creator/listing", {
      headers: bearer(creator.accessToken), body: draftBody({ title: "Throttle One" }),
    });
    assert.equal(first.status, 201);
    const second = await call(service.baseUrl, "POST", "/creator/listing", {
      headers: bearer(creator.accessToken), body: draftBody({ title: "Throttle Two" }),
    });
    assert.equal(second.status, 201);
    const third = await call(service.baseUrl, "POST", "/creator/listing", {
      headers: bearer(creator.accessToken), body: draftBody({ title: "Throttle Three" }),
    });
    assert.equal(third.status, 429);
    assert.equal(third.body.error.code, "RATE_LIMITED");
    // The owner-read bucket is separate from writes.
    assert.equal((await call(service.baseUrl, "GET", "/creator/listing", { headers: bearer(creator.accessToken) })).status, 200);
    assert.equal((await call(service.baseUrl, "GET", "/creator/listing", { headers: bearer(creator.accessToken) })).status, 200);
    assert.equal((await call(service.baseUrl, "GET", "/creator/listing", { headers: bearer(creator.accessToken) })).status, 429);
    // Public search has its own bucket; reaching across it does not leak a listing.
    assert.equal((await call(service.baseUrl, "GET", "/marketplace/listings")).status, 200);
    assert.equal((await call(service.baseUrl, "GET", "/marketplace/listings")).status, 200);
    assert.equal((await call(service.baseUrl, "GET", "/marketplace/listings")).status, 429);
    assert.equal(listingRow(service, first.body.id).title, "Throttle One", "rate limiting never mutates data");
  });
});
