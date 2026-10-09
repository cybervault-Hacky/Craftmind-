/**
 * Phase 23 creator and server capability tests.
 *
 * The suite follows the same rule as the phase it tests: **the server decides, and ownership is structural.** A client
 * cannot name an owner, choose a role, set a status, declare a verification, read another account's workspace, change
 * another account's profile, or publish anything. Where a request is refused, the test asserts both the typed refusal
 * and that nothing changed in the database.
 *
 * The security thresholds are raised here on purpose. This suite deliberately produces many refused requests, and the
 * Phase 20 protections (correctly) treat sustained refusals from one source as abuse; raising the thresholds keeps the
 * assertions about *refusal codes* rather than about a protection that the suite itself provoked. Rate limits are tested
 * on their own, with their own small budgets.
 */

import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { afterEach, describe, it } from "node:test";
import { migrateToVersion, SCHEMA_VERSION } from "../src/db.js";
import { hashPassword } from "../src/passwords.js";
import { newDeveloperId } from "../src/ids.js";
import {
  createCreatorProfile,
  creatorEligibility,
  creatorProfileRowForUser,
  listCreatorProfilesInTransaction,
} from "../src/creator-profiles.js";
import {
  applyServerStatusInTransaction,
  createServerWorkspace,
  listServerWorkspacesInTransaction,
  serverWorkspaceRowBySlug,
} from "../src/server-workspaces.js";
import {
  canCreateCreatorContent,
  canManageOwnedContent,
  canManageServer,
  canPublishCreatorContent,
  CAPABILITY_STATE,
} from "../src/capabilities.js";
import { authorizeResourceAccess, RESOURCE_KIND } from "../src/ownership.js";
import { developerAiToolCall } from "../src/admin-tools.js";
import { RESERVED_HANDLES } from "../src/creator-catalog.js";
import { call, registerVerified, startService, TEST_SECRET } from "./helpers.js";

const services = new Set();
const temporaryDirectories = new Set();
const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x51).toString("base64url");
const DEVELOPER_EMAIL = "owner@example.test";
const DEVELOPER_PASSWORD = "Creator Plane 7Safe";

/**
 * Generous budgets and raised thresholds by default: this suite is about refusal semantics, and it produces a lot of
 * refused requests. The rate-limit test sets its own small budgets.
 */
const RELAXED = Object.freeze({
  RATE_CREATOR_READ_MAX: "1000",
  RATE_CREATOR_WRITE_MAX: "1000",
  RATE_SERVER_READ_MAX: "1000",
  RATE_SERVER_WRITE_MAX: "1000",
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
  const directory = mkdtempSync(join(tmpdir(), "craftmind-phase23-"));
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
  const developerId = service.database
    .prepare("SELECT developer_id FROM developer_accounts WHERE email_canonical = ?")
    .get(DEVELOPER_EMAIL).developer_id;
  return { accessToken: login.body.session.accessToken, developerId };
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

/** Grants a plan through the developer tool, which is the only way a paid plan is ever applied. */
async function grantPlan(service, owner, email, plan = "CREATOR", days = 30) {
  return confirmedTool(service, owner.accessToken, "grantMembership", {
    email, plan, days, reason: "Phase 23 test grant",
  });
}

/** An account holding the Creator entitlement. */
async function creatorAccount(service, owner, email) {
  const account = await signedInAccount(service, email);
  await grantPlan(service, owner, email, "CREATOR");
  return account;
}

function profileRow(service, userId) {
  return service.database.prepare("SELECT * FROM creator_profiles WHERE user_id = ?").get(userId);
}

function accountIdFor(service, email) {
  return service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(email).user_id;
}

function auditRows(service, actionType) {
  return service.database
    .prepare("SELECT * FROM admin_audit_log WHERE action_type = ? ORDER BY occurred_at, audit_id")
    .all(actionType);
}

/** Every key in a response body, at any depth, so a structural privacy assertion is not fooled by nesting. */
function collectKeys(value, keys = new Set()) {
  if (Array.isArray(value)) {
    for (const item of value) collectKeys(item, keys);
  } else if (value !== null && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      keys.add(key);
      collectKeys(item, keys);
    }
  }
  return keys;
}

// ---------------------------------------------------------------------------- A. creator entitlement and identity

describe("Phase 23 creator identity and entitlement", () => {
  it("refuses creator profile creation without the Creator entitlement", async () => {
    const service = await newService();
    const account = await signedInAccount(service, "free@example.test");
    const attempt = await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken),
      body: { handle: "free-studio", displayName: "Free Studio" },
    });
    assert.equal(attempt.status, 403);
    assert.equal(attempt.body.error.code, "CREATOR_ENTITLEMENT_REQUIRED");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 0);
    // The refusal is attributable: the entitlement engine writes the denial into the one audit log.
    assert.equal(auditRows(service, "ENTITLEMENT_DENIED").length, 1);
  });

  it("creates one profile per account and normalizes the handle", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "studio@example.test");
    const created = await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken),
      body: { handle: "  My   Studio  ", displayName: "My Studio", bio: "Interiors and redstone.", category: "Interiors" },
    });
    assert.equal(created.status, 201, JSON.stringify(created.body));
    assert.equal(created.body.profile.handle, "my-studio");
    assert.equal(created.body.profile.status, "ACTIVE");
    assert.equal(created.body.profile.verification, "UNVERIFIED");
    assert.equal(created.body.profile.displayName, "My Studio");
    // Ownership is structural: the row belongs to the authenticated account, which no request mentioned.
    const userId = accountIdFor(service, "studio@example.test");
    assert.equal(profileRow(service, userId).user_id, userId);
    assert.equal(profileRow(service, userId).handle, "my-studio");
  });

  it("refuses a second profile for the same account", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "dupe@example.test");
    const first = await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken), body: { handle: "dupe-one", displayName: "Dupe One" },
    });
    assert.equal(first.status, 201);
    const second = await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken), body: { handle: "dupe-two", displayName: "Dupe Two" },
    });
    assert.equal(second.status, 409);
    assert.equal(second.body.error.code, "CREATOR_PROFILE_EXISTS");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 1);
  });

  it("refuses a client-supplied owner and any unknown body field", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "owner-field@example.test");
    const other = await creatorAccount(service, owner, "victim@example.test");
    const victimId = accountIdFor(service, "victim@example.test");
    for (const injected of [{ ownerId: victimId }, { userId: victimId }, { accountId: victimId }, { status: "ACTIVE" }, { verification: "VERIFIED" }, { plan: "CREATOR" }]) {
      const attempt = await call(service.baseUrl, "POST", "/creator/profile", {
        headers: bearer(account.accessToken),
        body: { handle: "shadow-handle", displayName: "Shadow", ...injected },
      });
      assert.equal(attempt.status, 400, JSON.stringify(attempt.body));
      assert.equal(attempt.body.error.code, "INVALID_REQUEST");
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 0);
    assert.equal(profileRow(service, victimId), undefined);
    assert.equal(other.accessToken.length > 0, true);
  });

  it("updates only the authenticated account's profile", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const first = await creatorAccount(service, owner, "first@example.test");
    const second = await creatorAccount(service, owner, "second@example.test");
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(first.accessToken), body: { handle: "first-studio", displayName: "First", bio: "Original bio." },
    });
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(second.accessToken), body: { handle: "second-studio", displayName: "Second" },
    });
    const updated = await call(service.baseUrl, "PATCH", "/creator/profile", {
      headers: bearer(first.accessToken), body: { bio: "Updated bio.", category: "Redstone" },
    });
    assert.equal(updated.status, 200, JSON.stringify(updated.body));
    assert.equal(updated.body.profile.bio, "Updated bio.");
    assert.equal(updated.body.profile.category, "Redstone");
    // The other account is untouched, and its own patch never reached the first profile.
    const secondRow = profileRow(service, accountIdFor(service, "second@example.test"));
    assert.equal(secondRow.bio, "");
    assert.equal(secondRow.category, null);
    const forged = await call(service.baseUrl, "PATCH", "/creator/profile", {
      headers: bearer(second.accessToken), body: { displayName: "Hijacked", ownerId: accountIdFor(service, "first@example.test") },
    });
    assert.equal(forged.status, 400);
    assert.equal(profileRow(service, accountIdFor(service, "first@example.test")).display_name, "First");
  });

  it("refuses HTML, unsafe references, oversized text, and deceptive characters", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "fields@example.test");
    const base = { handle: "field-studio", displayName: "Fields" };
    const badPayloads = [
      { ...base, bio: "<script>alert(1)</script>" },
      { ...base, bio: "a".repeat(601) },
      { ...base, displayName: "x".repeat(41) },
      { ...base, displayName: "Line\u2028break" },
      { ...base, avatarReference: "javascript:alert(1)" },
      { ...base, avatarReference: "http://insecure.example/avatar.png" },
      { ...base, avatarReference: "https://user:pass@example.test/avatar.png" },
      { ...base, category: "Anything I Want" },
      { ...base, handle: "ab" },
      { ...base, handle: "has spaces ok?" },
      { ...base, handle: "café-studio" },
      { ...base, handle: "x".repeat(33) },
    ];
    for (const payload of badPayloads) {
      const attempt = await call(service.baseUrl, "POST", "/creator/profile", {
        headers: bearer(account.accessToken), body: payload,
      });
      assert.equal(attempt.status, 400, `payload ${JSON.stringify(payload).slice(0, 80)} must be refused`);
      assert.equal(attempt.body.error.code, "INVALID_REQUEST");
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 0);
    // A valid payload still works afterwards: the refusals above are validation, not a broken route.
    const created = await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken),
      body: { ...base, avatarReference: "https://cdn.example.test/avatar.png" },
    });
    assert.equal(created.status, 201, JSON.stringify(created.body));
    assert.equal(created.body.profile.avatarReference, "https://cdn.example.test/avatar.png");
  });

  it("never lets a client choose a reserved handle", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    for (const reserved of ["developer", "admin", "api", "account", "creator", "marketplace", "membership", "settings"]) {
      assert.equal(RESERVED_HANDLES.includes(reserved), true, `${reserved} must be reserved`);
      const account = await creatorAccount(service, owner, `${reserved}-probe@example.test`);
      const attempt = await call(service.baseUrl, "POST", "/creator/profile", {
        headers: bearer(account.accessToken),
        body: { handle: reserved, displayName: "Reserved Probe" },
      });
      assert.equal(attempt.status, 400, reserved);
      assert.equal(attempt.body.error.code, "CREATOR_HANDLE_RESERVED");
    }
    // Case and separator variants normalize into the same reserved value and are refused too.
    const account = await creatorAccount(service, owner, "variant-probe@example.test");
    for (const variant of ["Admin", "DEVELOPER", "  api  ", "market_place"]) {
      const attempt = await call(service.baseUrl, "POST", "/creator/profile", {
        headers: bearer(account.accessToken), body: { handle: variant, displayName: "Variant" },
      });
      assert.equal(attempt.status, 400, variant);
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 0);
  });

  it("refuses a handle that another creator already holds", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const first = await creatorAccount(service, owner, "taken-first@example.test");
    const second = await creatorAccount(service, owner, "taken-second@example.test");
    const created = await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(first.accessToken), body: { handle: "shared-studio", displayName: "Shared Studio" },
    });
    assert.equal(created.status, 201);
    const clash = await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(second.accessToken), body: { handle: "Shared Studio", displayName: "Shared Studio Two" },
    });
    assert.equal(clash.status, 409);
    assert.equal(clash.body.error.code, "CREATOR_HANDLE_UNAVAILABLE");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 1);
  });
});

// ---------------------------------------------------------------------------- B. public projection and privacy

describe("Phase 23 public creator profile", () => {
  it("publishes only the safe projection to an anonymous reader", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "public@example.test");
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken),
      body: { handle: "public-studio", displayName: "Public Studio", bio: "Sells nothing yet.", category: "Structures" },
    });
    const read = await call(service.baseUrl, "GET", "/creators/public-studio");
    assert.equal(read.status, 200, JSON.stringify(read.body));
    assert.deepEqual(Object.keys(read.body.profile).sort(), [
      "avatarReference", "bio", "category", "createdAt", "displayName", "handle", "updatedAt", "verification", "verified",
    ]);
    const text = JSON.stringify(read.body);
    for (const forbidden of ["public@example.test", "usr_", "crt_", "CREATOR", "credits", "session", "plan", "member"]) {
      assert.equal(text.includes(forbidden), false, `the public projection must not contain ${forbidden}`);
    }
  });

  it("cannot be distinguished from a missing profile when the creator is not active", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "suspended-public@example.test");
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken), body: { handle: "suspended-studio", displayName: "Suspended Studio" },
    });
    const unknown = await call(service.baseUrl, "GET", "/creators/never-existed");
    assert.equal(unknown.status, 404);
    assert.equal(unknown.body.error.code, "CREATOR_PROFILE_NOT_FOUND");
    await confirmedTool(service, owner.accessToken, "suspendCreator", {
      handle: "suspended-studio", reason: "Phase 23 moderation test",
    });
    const suspended = await call(service.baseUrl, "GET", "/creators/suspended-studio");
    assert.equal(suspended.status, 404);
    assert.equal(suspended.body.error.code, "CREATOR_PROFILE_NOT_FOUND");
    // The suspension reason is not in the response, and the two failures are byte-for-byte identical in shape.
    assert.equal(JSON.stringify(suspended.body).includes("moderation"), false);
    assert.deepEqual(Object.keys(unknown.body.error), Object.keys(suspended.body.error));
  });

  it("keeps an invalid handle out of the public route entirely", async () => {
    const service = await newService();
    for (const candidate of ["Admin", "x", "a".repeat(40), "with%20space"]) {
      const read = await call(service.baseUrl, "GET", `/creators/${candidate}`);
      assert.equal([400, 404].includes(read.status), true, candidate);
      assert.equal(read.body.profile, undefined);
    }
  });
});

// ---------------------------------------------------------------------------- C. membership, entitlement, suspension

describe("Phase 23 creator status, verification, and developer control", () => {
  it("blocks protected creator operations while suspended and restores them afterwards", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "status@example.test");
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken), body: { handle: "status-studio", displayName: "Status Studio" },
    });

    const suspended = await confirmedTool(service, owner.accessToken, "suspendCreator", {
      handle: "status-studio", reason: "Phase 23 suspension test",
    });
    assert.equal(suspended.status, "SUSPENDED");
    const update = await call(service.baseUrl, "PATCH", "/creator/profile", {
      headers: bearer(account.accessToken), body: { displayName: "Should Not Apply" },
    });
    assert.equal(update.status, 403);
    assert.equal(update.body.error.code, "CREATOR_PROFILE_SUSPENDED");
    assert.equal(profileRow(service, accountIdFor(service, "status@example.test")).display_name, "Status Studio");
    // Reading your own state still works: suspension is not deletion.
    const read = await call(service.baseUrl, "GET", "/creator/profile", { headers: bearer(account.accessToken) });
    assert.equal(read.status, 200);
    assert.equal(read.body.profile.status, "SUSPENDED");

    const restored = await confirmedTool(service, owner.accessToken, "restoreCreator", {
      handle: "status-studio", reason: "Phase 23 restoration test",
    });
    assert.equal(restored.status, "ACTIVE");
    const afterRestore = await call(service.baseUrl, "PATCH", "/creator/profile", {
      headers: bearer(account.accessToken), body: { displayName: "Restored Studio" },
    });
    assert.equal(afterRestore.status, 200, JSON.stringify(afterRestore.body));
    assert.equal(afterRestore.body.profile.displayName, "Restored Studio");
  });

  it("moves the internal verification marker and records every move", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "verify@example.test");
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken), body: { handle: "verify-studio", displayName: "Verify Studio" },
    });

    const pending = await confirmedTool(service, owner.accessToken, "verifyCreator", {
      handle: "verify-studio", state: "PENDING", reason: "Phase 23 review opening",
    });
    assert.equal(pending.verification, "PENDING");
    const verified = await confirmedTool(service, owner.accessToken, "verifyCreator", {
      handle: "verify-studio", reason: "Phase 23 internal marker",
    });
    assert.equal(verified.verification, "VERIFIED");
    assert.equal(verified.verificationScope, "INTERNAL_MARKER_ONLY");
    const row = profileRow(service, accountIdFor(service, "verify@example.test"));
    assert.equal(row.verification_status, "VERIFIED");
    assert.equal(typeof row.verified_at, "string");

    const revoked = await confirmedTool(service, owner.accessToken, "revokeCreatorVerification", {
      handle: "verify-studio", reason: "Phase 23 marker withdrawn",
    });
    assert.equal(revoked.verification, "REVOKED");
    assert.equal(profileRow(service, accountIdFor(service, "verify@example.test")).verified_at, null);

    // Domain history and the one audit log both describe what happened, by whom.
    const history = service.database
      .prepare("SELECT * FROM creator_status_history WHERE change_type = 'VERIFICATION' ORDER BY occurred_at, history_id")
      .all();
    assert.equal(history.length, 3);
    assert.deepEqual(history.map((entry) => entry.to_value), ["PENDING", "VERIFIED", "REVOKED"]);
    assert.equal(history.every((entry) => entry.actor_kind === "DEVELOPER"), true);
    assert.equal(history[0].actor_developer_id, owner.developerId);
    const audited = auditRows(service, "CREATOR_VERIFICATION_CHANGED");
    assert.equal(audited.length, 3);
    assert.equal(audited.every((row) => row.actor_kind === "DEVELOPER" && row.outcome === "SUCCESS"), true);
    // Each confirmed action is logged twice by the tool registry — PREPARED when the action stops at the boundary,
    // SUCCESS when a developer confirms it — so two verifications are four rows and one revocation is two. Counts by
    // outcome, not sequence: audit ids are not time-ordered, so two rows sharing a millisecond have no defined order.
    const verifyAudit = auditRows(service, "verify_creator");
    assert.equal(verifyAudit.length, 4);
    assert.equal(verifyAudit.filter((row) => row.outcome === "PREPARED").length, 2);
    assert.equal(verifyAudit.filter((row) => row.outcome === "SUCCESS").length, 2);
    assert.equal(verifyAudit.every((row) => row.actor_kind === "DEVELOPER"), true);
    const revokeAudit = auditRows(service, "revoke_creator_verification");
    assert.equal(revokeAudit.length, 2);
    assert.equal(revokeAudit.filter((row) => row.outcome === "SUCCESS").length, 1);
    // The audited metadata is bounded and carries no secret.
    for (const row of audited) {
      assert.equal(row.metadata_json.length <= 4096, true);
      assert.equal(/token|password|secret/i.test(row.metadata_json), false);
    }
  });

  it("refuses verification from a normal account, a read-only developer role, and the AI boundary", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "unauthorized-verify@example.test");
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken), body: { handle: "guarded-studio", displayName: "Guarded Studio" },
    });
    const targetId = accountIdFor(service, "unauthorized-verify@example.test");

    // A normal account is not a developer at all.
    const asUser = await invokeTool(service, account.accessToken, "verifyCreator", {
      handle: "guarded-studio", reason: "not allowed",
    });
    assert.equal(asUser.status, 401);
    assert.equal(profileRow(service, targetId).verification_status, "UNVERIFIED");

    // A DEVELOPER-role developer account may read creator state and may not change it. The account is inserted as an
    // operator would create one — directly, with a real password hash — because this phase adds no account-provisioning
    // endpoint and inventing one to make a test convenient would be a new privileged surface.
    const readerId = newDeveloperId();
    const timestamp = new Date().toISOString();
    service.database.prepare(
      `INSERT INTO developer_accounts (developer_id, email, email_canonical, password_hash, role, status, created_at, updated_at, last_login_at)
       VALUES (?, 'reader@example.test', 'reader@example.test', ?, 'DEVELOPER', 'ACTIVE', ?, ?, NULL)`,
    ).run(readerId, await hashPassword(DEVELOPER_PASSWORD), timestamp, timestamp);
    const readerLogin = await call(service.baseUrl, "POST", "/developer/auth/login", {
      body: { email: "reader@example.test", password: DEVELOPER_PASSWORD },
    });
    assert.equal(readerLogin.status, 200, JSON.stringify(readerLogin.body));
    const readerToken = readerLogin.body.session.accessToken;
    const readAllowed = await invokeTool(service, readerToken, "inspectCreator", { handle: "guarded-studio" });
    assert.equal(readAllowed.status, 200);
    for (const [tool, args] of [
      ["verifyCreator", { handle: "guarded-studio", reason: "read-only role must not verify" }],
      ["suspendCreator", { handle: "guarded-studio", reason: "read-only role must not suspend" }],
      ["suspendServer", { slug: "guarded-studio", reason: "read-only role must not suspend" }],
    ]) {
      const denied = await invokeTool(service, readerToken, tool, args);
      assert.equal(denied.status, 403, `${tool}: ${JSON.stringify(denied.body)}`);
      assert.equal(denied.body.error.code, "DEVELOPER_ACCESS_DENIED");
    }
    assert.equal(profileRow(service, targetId).verification_status, "UNVERIFIED");
    assert.equal(profileRow(service, targetId).status, "ACTIVE");
    assert.ok(service.database.prepare(
      "SELECT 1 FROM admin_audit_log WHERE actor_developer_id = ? AND action_type = 'verify_creator' AND outcome = 'DENIED'",
    ).get(readerId));

    // The AI boundary: a proposed creator mutation stops at the confirmation boundary and changes nothing. The AI never
    // reaches the action — the tool registry, the authorization check, and the confirmation step are all between them.
    // The actor is assembled exactly as the AI route assembles it: a real developer identity bound to a real,
    // unexpired session row. A fabricated session object is refused by the same boundary before any tool runs.
    const actor = {
      developer_id: owner.developerId,
      role: "OWNER",
      session: service.database.prepare(
        "SELECT session_id, access_digest FROM developer_sessions WHERE developer_id = ? ORDER BY issued_at DESC LIMIT 1",
      ).get(owner.developerId),
    };
    const proposal = developerAiToolCall(service.database, service.configuration, actor,
      { name: "suspendCreator", arguments: { handle: "guarded-studio", reason: "AI must not execute this" } },
      { configuration: service.configuration, schemaVersion: SCHEMA_VERSION });
    assert.equal(proposal.confirmationRequired, true);
    assert.equal(profileRow(service, targetId).status, "ACTIVE");
    assert.equal(auditRows(service, "CREATOR_STATUS_CHANGED").length, 0);
    assert.ok(service.database.prepare(
      "SELECT 1 FROM admin_audit_log WHERE action_type = 'suspend_creator' AND outcome = 'PREPARED'",
    ).get());
  });
});

// ---------------------------------------------------------------------------- D. server workspaces

describe("Phase 23 server workspaces", () => {
  it("refuses workspace creation without the Server entitlement", async () => {
    const service = await newService();
    const account = await signedInAccount(service, "free-server@example.test");
    const attempt = await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(account.accessToken), body: { slug: "free-workspace", displayName: "Free Workspace" },
    });
    assert.equal(attempt.status, 403);
    assert.equal(attempt.body.error.code, "SERVER_ENTITLEMENT_REQUIRED");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM server_workspaces").get().count, 0);
  });

  it("derives the owner from the session and writes exactly one immutable owner row", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "server-owner@example.test");
    await grantPlan(service, owner, "server-owner@example.test", "SERVER");
    const created = await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(account.accessToken),
      body: { slug: "Craft Minds SMP", displayName: "Craft Minds SMP", description: "A survival workspace." },
    });
    assert.equal(created.status, 201, JSON.stringify(created.body));
    assert.equal(created.body.server.slug, "craft-minds-smp");
    assert.equal(created.body.server.role, "OWNER");
    assert.equal(created.body.server.status, "ACTIVE");
    const userId = accountIdFor(service, "server-owner@example.test");
    const row = serverWorkspaceRowBySlug(service.database, "craft-minds-smp");
    assert.equal(row.owner_user_id, userId);
    const members = service.database.prepare("SELECT * FROM server_members WHERE server_id = ?").all(row.server_id);
    assert.equal(members.length, 1);
    assert.equal(members[0].role, "OWNER");
    assert.equal(members[0].user_id, userId);
    // Ownership is immutable at the database level, whatever any future code tries.
    assert.throws(() => service.database.prepare("UPDATE server_members SET role = 'ADMIN' WHERE member_id = ?").run(members[0].member_id), /ownership cannot be changed/);
    assert.throws(() => service.database.prepare("DELETE FROM server_members WHERE member_id = ?").run(members[0].member_id), /ownership cannot be removed/);
    assert.throws(() => service.database.prepare(
      "INSERT INTO server_members (member_id, server_id, user_id, role, created_at) VALUES ('svm_second', ?, ?, 'OWNER', ?)",
    ).run(row.server_id, userId, new Date().toISOString()), /exactly one owner/);
  });

  it("refuses a client-supplied owner, role, status, or malformed payload", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "server-fields@example.test");
    await grantPlan(service, owner, "server-fields@example.test", "SERVER");
    // The injected owner is a real, unrelated account: the payload must be refused even when its forged field names
    // an account that exists.
    await signedInAccount(service, "server-owner@example.test");
    const victimId = accountIdFor(service, "server-owner@example.test");
    for (const injected of [{ ownerId: victimId }, { userId: victimId }, { role: "OWNER" }, { status: "ARCHIVED" }, { serverId: "srv_forged" }]) {
      const attempt = await call(service.baseUrl, "POST", "/servers", {
        headers: bearer(account.accessToken),
        body: { slug: "injected-workspace", displayName: "Injected", ...injected },
      });
      assert.equal(attempt.status, 400, JSON.stringify(attempt.body));
    }
    const missingName = await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(account.accessToken), body: { slug: "nameless" },
    });
    assert.equal(missingName.status, 400);
    const oversized = await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(account.accessToken), body: { slug: "verbose", displayName: "Verbose", description: "d".repeat(601) },
    });
    assert.equal(oversized.status, 400);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM server_workspaces").get().count, 0);
  });

  it("reserves service paths, refuses slug collisions, and enforces the configured bound", async () => {
    const service = await newService({ SERVER_MAX_WORKSPACES_PER_ACCOUNT: "2" });
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "bounded@example.test");
    await grantPlan(service, owner, "bounded@example.test", "SERVER");

    const reserved = await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(account.accessToken), body: { slug: "admin", displayName: "Reserved" },
    });
    assert.equal(reserved.status, 400);
    assert.equal(reserved.body.error.code, "SERVER_SLUG_RESERVED");

    const first = await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(account.accessToken), body: { slug: "workspace-one", displayName: "Workspace One" },
    });
    assert.equal(first.status, 201);
    const collision = await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(account.accessToken), body: { slug: "Workspace One", displayName: "Workspace One Copy" },
    });
    assert.equal(collision.status, 409);
    assert.equal(collision.body.error.code, "SERVER_SLUG_UNAVAILABLE");

    const second = await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(account.accessToken), body: { slug: "workspace-two", displayName: "Workspace Two" },
    });
    assert.equal(second.status, 201);
    const third = await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(account.accessToken), body: { slug: "workspace-three", displayName: "Workspace Three" },
    });
    assert.equal(third.status, 409);
    assert.equal(third.body.error.code, "SERVER_LIMIT_REACHED");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM server_workspaces").get().count, 2);
  });

  it("scopes reads and updates to the owner", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const ownerAccount = await signedInAccount(service, "scoped-owner@example.test");
    const otherAccount = await signedInAccount(service, "scoped-other@example.test");
    await grantPlan(service, owner, "scoped-owner@example.test", "SERVER");
    await grantPlan(service, owner, "scoped-other@example.test", "SERVER");
    await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(ownerAccount.accessToken), body: { slug: "scoped-workspace", displayName: "Scoped Workspace" },
    });

    const foreignRead = await call(service.baseUrl, "GET", "/servers/scoped-workspace", { headers: bearer(otherAccount.accessToken) });
    assert.equal(foreignRead.status, 404);
    assert.equal(foreignRead.body.error.code, "SERVER_NOT_FOUND");
    const unknownRead = await call(service.baseUrl, "GET", "/servers/never-existed", { headers: bearer(otherAccount.accessToken) });
    assert.equal(unknownRead.status, 404);
    assert.deepEqual(Object.keys(foreignRead.body.error), Object.keys(unknownRead.body.error));

    const foreignUpdate = await call(service.baseUrl, "PATCH", "/servers/scoped-workspace", {
      headers: bearer(otherAccount.accessToken), body: { displayName: "Hijacked Workspace" },
    });
    assert.equal(foreignUpdate.status, 404);
    assert.equal(serverWorkspaceRowBySlug(service.database, "scoped-workspace").display_name, "Scoped Workspace");

    const ownerUpdate = await call(service.baseUrl, "PATCH", "/servers/scoped-workspace", {
      headers: bearer(ownerAccount.accessToken), body: { displayName: "Renamed Workspace", description: "Now with a description." },
    });
    assert.equal(ownerUpdate.status, 200, JSON.stringify(ownerUpdate.body));
    assert.equal(ownerUpdate.body.server.displayName, "Renamed Workspace");
    const listed = await call(service.baseUrl, "GET", "/servers", { headers: bearer(ownerAccount.accessToken) });
    assert.equal(listed.status, 200);
    assert.equal(listed.body.count, 1);
    assert.equal(listed.body.maximum, 3);

    // A member with a lower role can read the workspace and cannot manage it — and the client can never set that role.
    const workspace = serverWorkspaceRowBySlug(service.database, "scoped-workspace");
    service.database.prepare(
      "INSERT INTO server_members (member_id, server_id, user_id, role, created_at) VALUES ('svm_member', ?, ?, 'MEMBER', ?)",
    ).run(workspace.server_id, accountIdFor(service, "scoped-other@example.test"), new Date().toISOString());
    const memberRead = await call(service.baseUrl, "GET", "/servers/scoped-workspace", { headers: bearer(otherAccount.accessToken) });
    assert.equal(memberRead.status, 200);
    assert.equal(memberRead.body.server.role, "MEMBER");
    const memberUpdate = await call(service.baseUrl, "PATCH", "/servers/scoped-workspace", {
      headers: bearer(otherAccount.accessToken), body: { displayName: "Member Rename" },
    });
    assert.equal(memberUpdate.status, 403);
    assert.equal(memberUpdate.body.error.code, "SERVER_ACCESS_DENIED");
    const roleAttempt = await call(service.baseUrl, "PATCH", "/servers/scoped-workspace", {
      headers: bearer(otherAccount.accessToken), body: { role: "OWNER" },
    });
    assert.equal(roleAttempt.status, 400);
    assert.equal(serverWorkspaceRowBySlug(service.database, "scoped-workspace").display_name, "Renamed Workspace");
  });

  it("suspends and archives protected operations without deleting anything", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await signedInAccount(service, "suspended-server@example.test");
    await grantPlan(service, owner, "suspended-server@example.test", "SERVER");
    await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(account.accessToken), body: { slug: "suspendable", displayName: "Suspendable" },
    });

    const suspended = await confirmedTool(service, owner.accessToken, "suspendServer", {
      slug: "suspendable", reason: "Phase 23 workspace suspension",
    });
    assert.equal(suspended.status, "SUSPENDED");
    const blocked = await call(service.baseUrl, "PATCH", "/servers/suspendable", {
      headers: bearer(account.accessToken), body: { displayName: "Nope" },
    });
    assert.equal(blocked.status, 409);
    assert.equal(blocked.body.error.code, "SERVER_SUSPENDED");
    // The record survives: the owner can still read it, and the owner member row is intact.
    const read = await call(service.baseUrl, "GET", "/servers/suspendable", { headers: bearer(account.accessToken) });
    assert.equal(read.status, 200);
    assert.equal(read.body.server.operational, false);
    assert.equal(serverWorkspaceRowBySlug(service.database, "suspendable").display_name, "Suspendable");

    await confirmedTool(service, owner.accessToken, "restoreServer", {
      slug: "suspendable", reason: "Phase 23 workspace restoration",
    });
    const afterRestore = await call(service.baseUrl, "PATCH", "/servers/suspendable", {
      headers: bearer(account.accessToken), body: { displayName: "Restored Workspace" },
    });
    assert.equal(afterRestore.status, 200, JSON.stringify(afterRestore.body));

    const archived = await confirmedTool(service, owner.accessToken, "archiveServer", {
      slug: "suspendable", reason: "Phase 23 archiving",
    });
    assert.equal(archived.status, "ARCHIVED");
    const archivedUpdate = await call(service.baseUrl, "PATCH", "/servers/suspendable", {
      headers: bearer(account.accessToken), body: { displayName: "Archived Rename" },
    });
    assert.equal(archivedUpdate.status, 409);
    assert.equal(serverWorkspaceRowBySlug(service.database, "suspendable").status, "ARCHIVED");
    const audits = auditRows(service, "SERVER_STATUS_CHANGED");
    assert.deepEqual(audits.map((row) => row.outcome), ["SUCCESS", "SUCCESS", "SUCCESS"]);
  });
});

// ---------------------------------------------------------------------------- E. ownership and capability boundary

describe("Phase 23 ownership and marketplace authorization boundary", () => {
  it("authorizes ownership only through server-held relationships", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "ownership@example.test");
    const other = await signedInAccount(service, "ownership-other@example.test");
    const ownerId = accountIdFor(service, "ownership@example.test");
    const otherId = accountIdFor(service, "ownership-other@example.test");

    assert.throws(
      () => authorizeResourceAccess(service.database, {
        resourceKind: RESOURCE_KIND.CREATOR_PROFILE,
        resourceOwnerUserId: ownerId,
        actorUserId: otherId,
        denialActionType: "CREATOR_ACCESS_DENIED",
      }),
      (error) => error.code === "OWNERSHIP_REQUIRED",
    );
    assert.equal(auditRows(service, "CREATOR_ACCESS_DENIED").length, 1);
    const allowed = authorizeResourceAccess(service.database, {
      resourceKind: RESOURCE_KIND.CREATOR_PROFILE,
      resourceOwnerUserId: ownerId,
      actorUserId: ownerId,
      denialActionType: "CREATOR_ACCESS_DENIED",
    });
    assert.equal(allowed.authorized, true);
    assert.equal(allowed.relationship, "OWNER");
    assert.equal(account.accessToken.length > 0, true);
  });

  it("answers the four Phase 24 capability questions without inventing a success", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const free = await signedInAccount(service, "cap-free@example.test");
    const creator = await creatorAccount(service, owner, "cap-creator@example.test");
    const freeId = accountIdFor(service, "cap-free@example.test");
    const creatorId = accountIdFor(service, "cap-creator@example.test");

    const freeDecision = canCreateCreatorContent(service.database, service.configuration, freeId);
    assert.equal(freeDecision.allowed, false);
    assert.equal(freeDecision.state, CAPABILITY_STATE.DENIED);

    const withoutProfile = canCreateCreatorContent(service.database, service.configuration, creatorId);
    assert.equal(withoutProfile.allowed, false);
    assert.equal(withoutProfile.reasons[0].includes("No creator profile"), true);

    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(creator.accessToken), body: { handle: "cap-studio", displayName: "Cap Studio" },
    });
    assert.equal(canCreateCreatorContent(service.database, service.configuration, creatorId).allowed, true);
    assert.equal(canManageOwnedContent(service.database, service.configuration, {
      actorAccountId: creatorId, contentOwnerAccountId: creatorId,
    }).allowed, true);
    assert.equal(canManageOwnedContent(service.database, service.configuration, {
      actorAccountId: freeId, contentOwnerAccountId: creatorId,
    }).allowed, false);

    // Phase 25 replaced the placeholder with a real prerequisite evaluation: this account holds the entitlement and
    // a profile but never accepted the creator agreement, so publishing is honestly DENIED with that reason — not a
    // fabricated FEATURE_NOT_IMPLEMENTED and not a bypass.
    const publish = canPublishCreatorContent(service.database, service.configuration, creatorId);
    assert.equal(publish.allowed, false);
    assert.equal(publish.state, CAPABILITY_STATE.DENIED);
    assert.equal(publish.code, null);
    assert.equal(publish.wouldBeEligible, true);
    assert.equal(publish.agreementRecorded, false);
    assert.equal(publish.reasons.length > 0, true);
    assert.equal(publish.reasons.some((reason) => reason.toLowerCase().includes("agreement")), true);

    await grantPlan(service, owner, "cap-creator@example.test", "SERVER");
    await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(creator.accessToken), body: { slug: "cap-workspace", displayName: "Cap Workspace" },
    });
    const workspace = serverWorkspaceRowBySlug(service.database, "cap-workspace");
    assert.equal(canManageServer(service.database, service.configuration, {
      actorAccountId: creatorId, serverId: workspace.server_id,
    }).allowed, true);
    assert.equal(canManageServer(service.database, service.configuration, {
      actorAccountId: freeId, serverId: workspace.server_id,
    }).allowed, false);
  });

  it("reports the account's own capability state without duplicating Phase 22", async () => {
    const service = await newService();
    const account = await signedInAccount(service, "capabilities@example.test");
    const free = await call(service.baseUrl, "GET", "/account/capabilities", { headers: bearer(account.accessToken) });
    assert.equal(free.status, 200, JSON.stringify(free.body));
    assert.deepEqual(free.body.membership, { plan: "FREE", status: "ACTIVE" });
    assert.deepEqual(free.body.creator, { exists: false, handle: null, status: null, verification: null, entitled: false, eligible: false });
    assert.deepEqual(free.body.servers, { count: 0, maximum: 3 });
    assert.equal(JSON.stringify(free.body).includes("credit"), false, "the capability read must not duplicate the balance");

    const eligibility = await call(service.baseUrl, "GET", "/creator/eligibility", { headers: bearer(account.accessToken) });
    assert.equal(eligibility.status, 200);
    assert.equal(eligibility.body.eligibility.eligible, false);
    assert.equal(eligibility.body.eligibility.entitlement.granted, false);
    const publishing = eligibility.body.eligibility.capabilities.find((entry) => entry.key === "CREATOR_PUBLISH");
    assert.equal(publishing.state, "AVAILABLE");
    assert.equal(publishing.available, false);
    assert.equal(publishing.reason.length > 0, true);
  });

  it("keeps the creator profile when the plan lapses, and refuses operations until it returns", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "lapsed@example.test");
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken), body: { handle: "lapsed-studio", displayName: "Lapsed Studio" },
    });
    const userId = accountIdFor(service, "lapsed@example.test");
    // The plan expires by moving the window into the past: expiry is derived on read, not scheduled.
    service.database.prepare("UPDATE membership_accounts SET starts_at = ?, ends_at = ? WHERE user_id = ?").run(
      "2020-01-01T00:00:00.000Z", "2020-01-02T00:00:00.000Z", userId,
    );
    const update = await call(service.baseUrl, "PATCH", "/creator/profile", {
      headers: bearer(account.accessToken), body: { displayName: "Cannot Apply" },
    });
    assert.equal(update.status, 403);
    assert.equal(update.body.error.code, "CREATOR_ENTITLEMENT_REQUIRED");
    const read = await call(service.baseUrl, "GET", "/creator/profile", { headers: bearer(account.accessToken) });
    assert.equal(read.status, 200);
    assert.equal(read.body.profile.handle, "lapsed-studio");
    assert.equal(read.body.profile.displayName, "Lapsed Studio");
    assert.equal(read.body.eligibility.entitlement.granted, false);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles WHERE user_id = ?").get(userId).count, 1);
  });
});

// ---------------------------------------------------------------------------- F. developer controls, abuse, and migration

describe("Phase 23 developer controls and abuse resistance", () => {
  it("exposes bounded read tools and never leaks another account's data", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "listed@example.test");
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken), body: { handle: "listed-studio", displayName: "Listed Studio", category: "Landscaping" },
    });
    const listed = await invokeTool(service, owner.accessToken, "listCreatorProfiles", { limit: 5 });
    assert.equal(listed.status, 200);
    assert.equal(listed.body.result.returned, 1);
    assert.deepEqual(Object.keys(listed.body.result.profiles[0]).sort(), [
      "createdAt", "displayName", "handle", "status", "verification",
    ]);
    const inspected = await invokeTool(service, owner.accessToken, "inspectCreator", { handle: "listed-studio", includeHistory: true });
    assert.equal(inspected.status, 200);
    assert.equal(inspected.body.result.owner.email, "listed@example.test");
    assert.equal(inspected.body.result.history.length, 1);
    assert.equal(JSON.stringify(inspected.body).includes("password"), false);
    const unknown = await invokeTool(service, owner.accessToken, "inspectCreator", { handle: "never-existed" });
    assert.equal(unknown.status, 404);
    assert.equal(unknown.body.error.code, "CREATOR_PROFILE_NOT_FOUND");
  });

  it("rate-limits creator writes, server writes, and public creator reads on the existing limiter", async () => {
    const service = await newService({
      RATE_CREATOR_WRITE_MAX: "2",
      RATE_CREATOR_READ_MAX: "3",
      RATE_SERVER_WRITE_MAX: "2",
      RATE_SERVER_READ_MAX: "1000",
    });
    const owner = await ownerSession(service);
    // Three accounts, so the second write is not refused as a duplicate profile: this test is about the limiter.
    const first = await creatorAccount(service, owner, "limit-one@example.test");
    const second = await creatorAccount(service, owner, "limit-two@example.test");
    const third = await creatorAccount(service, owner, "limit-three@example.test");
    const writes = [
      await call(service.baseUrl, "POST", "/creator/profile", {
        headers: bearer(first.accessToken), body: { handle: "limited-one", displayName: "Limited One" },
      }),
      await call(service.baseUrl, "POST", "/creator/profile", {
        headers: bearer(second.accessToken), body: { handle: "limited-two", displayName: "Limited Two" },
      }),
      await call(service.baseUrl, "POST", "/creator/profile", {
        headers: bearer(third.accessToken), body: { handle: "limited-three", displayName: "Limited Three" },
      }),
    ];
    assert.deepEqual(writes.map((response) => response.status), [201, 201, 429]);
    assert.equal(writes[2].body.error.code, "RATE_LIMITED");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 2);

    // The public read is limited on its own bucket, even though it is anonymous.
    const readStatuses = [];
    for (let attempt = 0; attempt < 5; attempt += 1) {
      readStatuses.push((await call(service.baseUrl, "GET", "/creators/limited-one")).status);
    }
    assert.deepEqual(readStatuses.slice(0, 3), [200, 200, 200]);
    assert.deepEqual(readStatuses.slice(3), [429, 429]);

    // Server writes have a separate bucket: creator traffic must not consume it.
    const serverAccount = await signedInAccount(service, "limit-server@example.test");
    await grantPlan(service, owner, "limit-server@example.test", "SERVER");
    const serverWrites = [];
    for (const slug of ["limited-workspace-one", "limited-workspace-two", "limited-workspace-three"]) {
      serverWrites.push((await call(service.baseUrl, "POST", "/servers", {
        headers: bearer(serverAccount.accessToken), body: { slug, displayName: slug },
      })).status);
    }
    assert.deepEqual(serverWrites, [201, 201, 429]);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM server_workspaces").get().count, 2);
  });

  it("audits every workspace and creator security-relevant outcome in the one log", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "audited@example.test");
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken), body: { handle: "audited-studio", displayName: "Audited Studio" },
    });
    await grantPlan(service, owner, "audited@example.test", "SERVER");
    await call(service.baseUrl, "PATCH", "/creator/profile", {
      headers: bearer(account.accessToken), body: { bio: "Updated." },
    });
    await call(service.baseUrl, "POST", "/servers", {
      headers: bearer(account.accessToken), body: { slug: "audited-workspace", displayName: "Audited Workspace" },
    });
    await call(service.baseUrl, "PATCH", "/servers/audited-workspace", {
      headers: bearer(account.accessToken), body: { description: "Updated." },
    });
    const created = auditRows(service, "CREATOR_PROFILE_CREATED");
    const updated = auditRows(service, "CREATOR_PROFILE_UPDATED");
    const serverCreated = auditRows(service, "SERVER_CREATED");
    const serverUpdated = auditRows(service, "SERVER_UPDATED");
    assert.equal(created.length, 1);
    assert.equal(created[0].actor_kind, "SYSTEM");
    assert.equal(created[0].target_user_id, accountIdFor(service, "audited@example.test"));
    assert.equal(updated.length, 1);
    assert.equal(serverCreated.length, 1);
    assert.equal(serverUpdated.length, 1);
    assert.equal(serverUpdated[0].metadata_json.includes("audited-workspace"), true);
    // No audit row of this phase carries a credential.
    for (const row of [...created, ...updated, ...serverCreated, ...serverUpdated]) {
      assert.equal(/secret|token|password|digest/i.test(row.metadata_json), false);
    }
  });

  it("refuses oversized payloads before they reach a handler", async () => {
    const service = await newService({ MAX_BODY_BYTES: "2048" });
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "oversized@example.test");
    const huge = await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken),
      body: { handle: "huge-studio", displayName: "Huge", bio: "b".repeat(8000) },
    });
    assert.equal(huge.status, 413);
    assert.equal(huge.body.error.code, "REQUEST_TOO_LARGE");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 0);
  });

  it("preserves a Phase 22 database through the version 6 migration", () => {
    // A Phase 22 database is built the way it really comes into being: the migrations up to version 5 run first, then
    // rows are written by the Phase 22 schema, and only then is version 6 applied on top.
    const database = new DatabaseSync(temporaryDatabasePath());
    database.exec("PRAGMA foreign_keys = ON");
    database.exec("PRAGMA recursive_triggers = ON");
    database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    migrateToVersion(database, 5);
    const timestamp = "2026-01-01T00:00:00.000Z";
    database.exec(`
      INSERT INTO users (user_id, email, email_canonical, password_hash, display_name, status, email_verified_at, created_at, updated_at)
        VALUES ('usr_legacy', 'legacy@example.com', 'legacy@example.com', 'hash', 'Legacy', 'ACTIVE', '${timestamp}', '${timestamp}', '${timestamp}');
      INSERT INTO guest_identities (guest_identity_id, created_at, last_seen_at, linked_user_id, linked_at)
        VALUES ('guest_legacy_012345678', '${timestamp}', '${timestamp}', 'usr_legacy', '${timestamp}');
      INSERT INTO membership_accounts (membership_id, user_id, plan, status, source, starts_at, ends_at, granted_by, created_at, updated_at)
        VALUES ('mbr_legacy', 'usr_legacy', 'CREATOR', 'ACTIVE', 'DEVELOPER_GRANT', '${timestamp}', '2026-06-01T00:00:00.000Z', NULL, '${timestamp}', '${timestamp}');
      INSERT INTO developer_accounts (developer_id, email, email_canonical, password_hash, role, status, created_at, updated_at)
        VALUES ('dev_legacy', 'dev@example.test', 'dev@example.test', 'hash', 'OWNER', 'ACTIVE', '${timestamp}', '${timestamp}');
      INSERT INTO admin_audit_log (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
        VALUES ('aud_legacy', 'DEVELOPER', 'dev_legacy', 'grant_membership', 'usr_legacy', NULL, '${timestamp}', 'SUCCESS', '{}');
    `);
    const before = {
      credits: database.prepare("SELECT COUNT(*) AS count FROM credit_ledger").get().count,
      audit: database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log").get().count,
      transitions: database.prepare("SELECT COUNT(*) AS count FROM membership_transitions").get().count,
    };
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 5);

    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(SCHEMA_VERSION, 11);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 11);
    // Every Phase 22 row survives, with its values.
    const membership = database.prepare("SELECT * FROM membership_accounts WHERE user_id = 'usr_legacy'").get();
    assert.equal(membership.plan, "CREATOR");
    assert.equal(membership.status, "ACTIVE");
    assert.equal(membership.ends_at, "2026-06-01T00:00:00.000Z");
    assert.equal(database.prepare("SELECT email FROM users WHERE user_id = 'usr_legacy'").get().email, "legacy@example.com");
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM guest_identities").get().count, 1);
    assert.equal(database.prepare("SELECT linked_user_id FROM guest_identities WHERE guest_identity_id = 'guest_legacy_012345678'").get().linked_user_id, "usr_legacy");
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM developer_accounts").get().count, 1);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM credit_ledger").get().count, before.credits);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM membership_transitions").get().count, before.transitions);
    assert.equal(database.prepare("SELECT metadata_json FROM admin_audit_log WHERE audit_id = 'aud_legacy'").get().metadata_json, "{}");
    assert.equal(database.prepare("SELECT outcome FROM admin_audit_log WHERE audit_id = 'aud_legacy'").get().outcome, "SUCCESS");
    // The rebuilt tables kept their Phase 20 protections rather than losing them to the rename.
    assert.throws(() => database.prepare("UPDATE admin_audit_log SET outcome = 'DENIED' WHERE audit_id = 'aud_legacy'").run(), /append-only/);
    assert.throws(() => database.prepare("DELETE FROM admin_audit_log WHERE audit_id = 'aud_legacy'").run(), /append-only/);
    // The Phase 23 tables exist, and the append-only guarantees are enforced by the database, not by convention.
    for (const table of ["creator_profiles", "creator_status_history", "server_workspaces", "server_members", "marketplace_listings"]) {
      assert.equal(database.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?").get(table)?.name, table);
    }
    database.exec(`
      INSERT INTO creator_profiles (creator_id, user_id, handle, display_name, bio, category, avatar_reference, status, verification_status, status_changed_at, verified_at, created_at, updated_at)
        VALUES ('crt_x', 'usr_legacy', 'legacy-studio', 'Legacy Studio', '', NULL, NULL, 'ACTIVE', 'UNVERIFIED', '${timestamp}', NULL, '${timestamp}', '${timestamp}');
      INSERT INTO creator_status_history (history_id, creator_id, user_id, change_type, from_value, to_value, reason, actor_kind, actor_developer_id, occurred_at)
        VALUES ('cth_x', 'crt_x', 'usr_legacy', 'STATUS', NULL, 'ACTIVE', 'Created for the migration test', 'SYSTEM', NULL, '${timestamp}');
    `);
    assert.throws(() => database.prepare("UPDATE creator_status_history SET to_value = 'SUSPENDED'").run(), /append-only/);
    assert.throws(() => database.prepare("DELETE FROM creator_status_history").run(), /append-only/);
    // Re-running the migration is a no-op: the version row is the guard.
    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 11);
    assert.equal(database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 1);
    database.close();
  });

  it("keeps the portal-only surfaces honest: no public server directory and no publish endpoint", async () => {
    const service = await newService();
    const published = await call(service.baseUrl, "POST", "/creator/publish", { body: {} });
    assert.equal(published.status, 400);
    assert.equal(published.body.error.code, "INVALID_REQUEST");
    const directory = await call(service.baseUrl, "GET", "/servers");
    assert.equal(directory.status, 401);
    assert.equal(directory.body.error.code, "AUTHENTICATION_REQUIRED");
    const servers = await call(service.baseUrl, "GET", "/servers/anything");
    assert.equal(servers.status, 401);
  });

  it("keeps every creator and server response free of credentials and internal identifiers", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "privacy@example.test");
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken), body: { handle: "privacy-studio", displayName: "Privacy Studio" },
    });
    const responses = [
      { route: "GET /creator/profile", response: await call(service.baseUrl, "GET", "/creator/profile", { headers: bearer(account.accessToken) }) },
      { route: "GET /creator/eligibility", response: await call(service.baseUrl, "GET", "/creator/eligibility", { headers: bearer(account.accessToken) }) },
      { route: "GET /account/capabilities", response: await call(service.baseUrl, "GET", "/account/capabilities", { headers: bearer(account.accessToken) }) },
      { route: "GET /creators/:handle", response: await call(service.baseUrl, "GET", "/creators/privacy-studio") },
    ];
    for (const { route, response } of responses) {
      assert.equal(response.status, 200, route);
      const text = JSON.stringify(response.body);
      for (const forbidden of ["session_id", "access_digest", "refresh", "password", "usr_", "crt_", "cth_", "mbr_", "ses_"]) {
        assert.equal(text.includes(forbidden), false, `${forbidden} appeared in ${route}`);
      }
      assert.equal(/SELECT |sqlite/i.test(text), false, route);
      // A structural check on the field names too: no response may grow a credential-shaped key later.
      for (const key of collectKeys(response.body)) {
        assert.equal(/token|password|secret|digest|session|email|credit|balance/i.test(key), false, `${key} in ${route}`);
      }
    }
    // The developer listing, which is a control-plane surface, still refuses to carry an internal account id.
    const listed = await invokeTool(service, owner.accessToken, "listServerWorkspaces", {});
    assert.equal(listed.status, 200);
    assert.equal(JSON.stringify(listed.body.result).includes("usr_"), false);
  });

  it("is idempotent about the schema and its own reads", async () => {
    const service = await newService();
    const owner = await ownerSession(service);
    const account = await creatorAccount(service, owner, "repeat@example.test");
    await call(service.baseUrl, "POST", "/creator/profile", {
      headers: bearer(account.accessToken), body: { handle: "repeat-studio", displayName: "Repeat Studio" },
    });
    const first = await call(service.baseUrl, "GET", "/creator/profile", { headers: bearer(account.accessToken) });
    const second = await call(service.baseUrl, "GET", "/creator/profile", { headers: bearer(account.accessToken) });
    assert.deepEqual(first.body.profile, second.body.profile);
    // Reading state creates nothing: one profile, one eligibility read, no duplicated history rows.
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM creator_profiles").get().count, 1);
    const eligibility = creatorEligibility(service.database, service.configuration, accountIdFor(service, "repeat@example.test"));
    assert.equal(eligibility.profile.exists, true);
    assert.equal(eligibility.profile.status, "ACTIVE");
    const listed = listCreatorProfilesInTransaction(service.database, { limit: 10 });
    assert.equal(listed.length, 1);
    assert.equal(listServerWorkspacesInTransaction(service.database, accountIdFor(service, "repeat@example.test")).length, 0);
    // Direct module entry points refuse an unknown account rather than creating state for it.
    assert.throws(() => creatorEligibility(service.database, service.configuration, "usr_missing"), (error) => error.code === "ACCOUNT_NOT_FOUND");
    assert.throws(() => createCreatorProfile(service.database, service.configuration, {
      userId: "usr_missing", handle: "missing-studio", displayName: "Missing",
    }), (error) => error.code === "ACCOUNT_NOT_FOUND");
    assert.throws(() => applyServerStatusInTransaction(service.database, service.configuration, {
      serverId: "srv_missing", status: "SUSPENDED", reason: "no such workspace", actorDeveloperId: owner.developerId,
    }), (error) => error.code === "SERVER_NOT_FOUND");
    assert.throws(() => createServerWorkspace(service.database, service.configuration, {
      userId: "usr_missing", slug: "missing-workspace", displayName: "Missing",
    }), (error) => error.code === "ACCOUNT_NOT_FOUND");
    assert.equal(profileRow(service, accountIdFor(service, "repeat@example.test")).handle, "repeat-studio");
  });
});
