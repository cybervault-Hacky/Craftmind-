/**
 * Phase 30 marketplace trust tests: reports, the avoid/block list, and a per-account trust posture.
 *
 * Everything here is non-monetary and privacy-preserving: a report can flag a listing, a job, or the counterparty of
 * the reporter's own order; the party a report is about sees only the category, the subject, and that an open report
 * exists — never who filed it and never the note. A block can only reach a counterparty the caller genuinely has a
 * one-to-one relationship with (an order, or a proposer on one of their jobs), and its only effect is to stop new
 * proposals connecting the two accounts. No endpoint accepts a reporter, target, blocked account, status, or amount;
 * all of it is derived server-side. No table, column, or assertion here involves money.
 *
 * Security thresholds are relaxed as in Phases 23–27 because the suite mints refused requests on purpose.
 */

import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { SCHEMA_VERSION, REGISTERED_AUDIT_ACTION_TYPES } from "../src/db.js";
import { CREATOR_AGREEMENT_VERSION } from "../src/onboarding.js";
import { call, registerVerified, startService } from "./helpers.js";

const services = new Set();
const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x51).toString("base64url");
const DEVELOPER_EMAIL = "owner@example.test";
const DEVELOPER_PASSWORD = "Creator Plane 7Safe";

const RELAXED = Object.freeze({
  RATE_CREATOR_WRITE_MAX: "1000", RATE_CREATOR_READ_MAX: "1000",
  RATE_JOB_WRITE_MAX: "1000", RATE_PROPOSAL_WRITE_MAX: "1000",
  RATE_ORDER_CREATE_MAX: "1000", RATE_ORDER_WRITE_MAX: "1000", RATE_DELIVERY_WRITE_MAX: "1000", RATE_REVISION_WRITE_MAX: "1000",
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

function bearer(t) { return { Authorization: `Bearer ${t}` }; }
function sfx() { return Math.random().toString(36).slice(2, 8); }

async function account(service, email) {
  const r = await registerVerified(service, { email });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  const userId = service.database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(email).user_id;
  return { email, accessToken: r.body.session.accessToken, userId };
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
  const p = await call(service.baseUrl, "POST", "/developer/tools/invoke", { headers: bearer(token), body: { tool, arguments: args } });
  assert.equal(p.status, 200, JSON.stringify(p.body));
  const c = await call(service.baseUrl, "POST", "/developer/tools/confirm", { headers: bearer(token), body: { confirmationToken: p.body.result.confirmationToken } });
  assert.equal(c.status, 200, JSON.stringify(c.body));
  return c.body.result;
}
async function sellerAccount(service, dev, email, handle) {
  const a = await account(service, email);
  await confirmedTool(service, dev.accessToken, "grantMembership", { email, plan: "CREATOR", days: 30, reason: "Phase 30 test grant" });
  const saved = await call(service.baseUrl, "POST", "/onboarding/seller", {
    headers: bearer(a.accessToken),
    body: { handle, displayName: `${handle} Studio`, referralSource: "YOUTUBE", editions: ["java"], minecraftVersions: ["1.20.1"], loaders: ["Fabric"], agreementAccepted: true, agreementVersion: CREATOR_AGREEMENT_VERSION },
  });
  assert.equal(saved.status, 200, JSON.stringify(saved.body));
  return a;
}
async function publishedListing(service, creator) {
  const created = await call(service.baseUrl, "POST", "/creator/listing", {
    headers: bearer(creator.accessToken),
    body: { title: "Floating islands", description: "A set of seven floating sky islands with farms and storage.", category: "Structures", edition: "java", minecraftVersions: ["1.20.1"] },
  });
  assert.equal(created.status, 201, JSON.stringify(created.body));
  const pub = await call(service.baseUrl, "POST", `/creator/listing/${created.body.id}/publish`, { headers: bearer(creator.accessToken) });
  assert.equal(pub.status, 200, JSON.stringify(pub.body));
  return created.body;
}
function audits(service, actionType) {
  return service.database.prepare("SELECT * FROM admin_audit_log WHERE action_type = ? ORDER BY occurred_at, audit_id").all(actionType);
}

describe("Phase 30 schema (trust tables, no money)", () => {
  it("opens at v11 with the trust tables and widened audit vocabulary", async () => {
    const service = await newService();
    assert.equal(SCHEMA_VERSION, 12);
    for (const table of ["marketplace_reports", "marketplace_blocks"]) {
      assert.ok(service.database.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?").get(table), table);
    }
    for (const a of ["REPORT_FILED", "REPORT_WITHDRAWN", "REPORT_ACCESS_DENIED", "BLOCK_ADDED", "BLOCK_REMOVED", "BLOCK_ENFORCED"]) {
      assert.equal(REGISTERED_AUDIT_ACTION_TYPES.has(a), true, a);
    }
    // No money column anywhere on the trust tables.
    for (const table of ["marketplace_reports", "marketplace_blocks"]) {
      const cols = service.database.prepare(`PRAGMA table_info(${table})`).all().map((r) => r.name);
      for (const forbidden of ["amount", "price", "balance", "refund", "payout", "commission", "credit"]) {
        assert.equal(cols.some((c) => c.toLowerCase().includes(forbidden)), false, `${table}.${forbidden}`);
      }
    }
  });
});

describe("Phase 30 reports", () => {
  it("files a report on a listing and shows the owner a stripped-down view", async () => {
    const service = await newService();
    const dev = await developer(service);
    const buyer = await account(service, `buyer-${sfx()}@example.test`);
    const creator = await sellerAccount(service, dev, `seller-${sfx()}@example.test`, `studio-${sfx()}`);
    const listing = await publishedListing(service, creator);

    const filed = await call(service.baseUrl, "POST", "/marketplace/reports", {
      headers: bearer(buyer.accessToken),
      body: { subjectType: "LISTING", subjectId: listing.id, category: "MISREPRESENTATION", note: "The screenshots are from a different world than the description." },
    });
    assert.equal(filed.status, 201, JSON.stringify(filed.body));
    assert.equal(filed.body.status, "OPEN");
    assert.equal(filed.body.note.includes("different world"), true); // the reporter sees their own note

    const mine = await call(service.baseUrl, "GET", "/marketplace/reports", { headers: bearer(buyer.accessToken) });
    assert.equal(mine.body.total, 1);

    const aboutMe = await call(service.baseUrl, "GET", "/marketplace/reports/about-me", { headers: bearer(creator.accessToken) });
    assert.equal(aboutMe.status, 200);
    assert.equal(aboutMe.body.openCount, 1);
    assert.equal(aboutMe.body.reports[0].category, "MISREPRESENTATION");
    const serialized = JSON.stringify(aboutMe.body);
    // The target never learns the reporter's id or the free-text note.
    assert.equal(serialized.includes(buyer.userId), false, "reporter id leaked to the target");
    assert.equal(serialized.includes("different world"), false, "reporter note leaked to the target");
    assert.equal("reporterUserId" in aboutMe.body.reports[0], false);
    assert.equal("note" in aboutMe.body.reports[0], false);

    assert.equal(audits(service, "REPORT_FILED").length, 1);
    assert.equal(audits(service, "REPORT_FILED")[0].metadata_json.includes("different world"), false); // note never audited
  });

  it("lets a creator report a buyer's job; the buyer is the target", async () => {
    const service = await newService();
    const buyer = await account(service, `buyer-${sfx()}@example.test`);
    const job = (await call(service.baseUrl, "POST", "/buyer/jobs", {
      headers: bearer(buyer.accessToken),
      body: { title: "Spammy?", description: "A job that is really an ad.", edition: "java", minecraftVersion: "1.20.1", scope: "Whatever the ad says." },
    })).body;
    const dev = await developer(service);
    const creator = await sellerAccount(service, dev, `seller-${sfx()}@example.test`, `studio-${sfx()}`);
    const filed = await call(service.baseUrl, "POST", "/marketplace/reports", {
      headers: bearer(creator.accessToken), body: { subjectType: "JOB", subjectId: job.id, category: "SPAM" },
    });
    assert.equal(filed.status, 201, JSON.stringify(filed.body));
    const aboutMe = await call(service.baseUrl, "GET", "/marketplace/reports/about-me", { headers: bearer(buyer.accessToken) });
    assert.equal(aboutMe.body.openCount, 1);
    assert.equal(aboutMe.body.reports[0].subjectType, "JOB");
  });

  it("restricts an ORDER report to that order's participants with a uniform not-found", async () => {
    const service = await newService();
    const dev = await developer(service);
    const suffix = sfx();
    const buyer = await account(service, `buyer-${suffix}@example.test`);
    const creator = await sellerAccount(service, dev, `seller-${suffix}@example.test`, `studio-${suffix}`);
    const job = (await call(service.baseUrl, "POST", "/buyer/jobs", { headers: bearer(buyer.accessToken), body: { title: "Order job", description: "A job that becomes an order for dispute reporting.", edition: "java", minecraftVersion: "1.20.1", scope: "Build the thing, then we report if it goes wrong." } })).body;
    const proposal = (await call(service.baseUrl, "POST", "/creator/proposal", { headers: bearer(creator.accessToken), body: { jobId: job.id, message: "I can take this job and deliver well.", scope: "Deliver the requested work as described." } })).body;
    await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, { headers: bearer(buyer.accessToken), body: { proposalId: proposal.id } });
    const order = (await call(service.baseUrl, "POST", "/buyer/orders", { headers: bearer(buyer.accessToken), body: { jobId: job.id, proposalId: proposal.id, milestones: [{ title: "Only", description: "One milestone for the order-report test.", acceptanceCriteria: "It is done." }] } })).body;

    // The buyer reports the creator (the counterparty), and it shows against the creator.
    const filed = await call(service.baseUrl, "POST", "/marketplace/reports", { headers: bearer(buyer.accessToken), body: { subjectType: "ORDER", subjectId: order.id, category: "CONDUCT", note: "Stopped responding after the milestone." } });
    assert.equal(filed.status, 201);
    const creatorAbout = await call(service.baseUrl, "GET", "/marketplace/reports/about-me", { headers: bearer(creator.accessToken) });
    assert.equal(creatorAbout.body.openCount, 1);
    // Regression guard: the report is *about the creator*, so the buyer who filed it must not see it back in their own
    // about-me inbox. An earlier read derived the target by joining the order to both participants, which leaked a
    // filer's own report into their inbox and double-counted it; the stored, CHECK-enforced `target_user_id` fixes it.
    const buyerAbout = await call(service.baseUrl, "GET", "/marketplace/reports/about-me", { headers: bearer(buyer.accessToken) });
    assert.deepEqual(buyerAbout.body.reports, []);
    assert.equal(buyerAbout.body.openCount, 0);
    assert.equal((await call(service.baseUrl, "GET", "/marketplace/trust", { headers: bearer(creator.accessToken) })).body.receivedOpenReports, 1);
    assert.equal((await call(service.baseUrl, "GET", "/marketplace/trust", { headers: bearer(buyer.accessToken) })).body.receivedOpenReports, 0);
    const stranger = await account(service, `stranger-${sfx()}@example.test`);
    const tried = await call(service.baseUrl, "POST", "/marketplace/reports", { headers: bearer(stranger.accessToken), body: { subjectType: "ORDER", subjectId: order.id, category: "SPAM" } });
    assert.equal(tried.status, 404);
    assert.equal(tried.body.error.code, "ORDER_NOT_FOUND");
  });

  it("refuses a duplicate open report, withdraws once, and hides a non-owner's report", async () => {
    const service = await newService();
    const dev = await developer(service);
    const suffix = sfx();
    const buyer = await account(service, `buyer-${suffix}@example.test`);
    const creator = await sellerAccount(service, dev, `seller-${suffix}@example.test`, `studio-${suffix}`);
    const listing = await publishedListing(service, creator);
    const body = { subjectType: "LISTING", subjectId: listing.id, category: "SPAM" };
    const first = await call(service.baseUrl, "POST", "/marketplace/reports", { headers: bearer(buyer.accessToken), body });
    assert.equal(first.status, 201);
    const dup = await call(service.baseUrl, "POST", "/marketplace/reports", { headers: bearer(buyer.accessToken), body });
    assert.equal(dup.status, 409);
    assert.equal(dup.body.error.code, "REPORT_STATE_CONFLICT");

    // Another account cannot withdraw this report; treated as not-found, with a denial audit.
    const other = await account(service, `other-${suffix}@example.test`);
    const notMine = await call(service.baseUrl, "POST", `/marketplace/reports/${first.body.id}/withdraw`, { headers: bearer(other.accessToken) });
    assert.equal(notMine.status, 404);
    assert.equal(notMine.body.error.code, "REPORT_NOT_FOUND");
    assert.equal(audits(service, "REPORT_ACCESS_DENIED").length, 1);

    const withdrawn = await call(service.baseUrl, "POST", `/marketplace/reports/${first.body.id}/withdraw`, { headers: bearer(buyer.accessToken) });
    assert.equal(withdrawn.status, 200);
    assert.equal(withdrawn.body.status, "WITHDRAWN");
    const aboutAfter = await call(service.baseUrl, "GET", "/marketplace/reports/about-me", { headers: bearer(creator.accessToken) });
    assert.equal(aboutAfter.body.openCount, 0);
    const again = await call(service.baseUrl, "POST", `/marketplace/reports/${first.body.id}/withdraw`, { headers: bearer(buyer.accessToken) });
    assert.equal(again.status, 409);
  });

  it("rejects malformed reports and unknown subjects without leaking existence", async () => {
    const service = await newService();
    const buyer = await account(service, `buyer-${sfx()}@example.test`);
    assert.equal((await call(service.baseUrl, "POST", "/marketplace/reports", { headers: bearer(buyer.accessToken), body: { subjectType: "WIDGET", subjectId: "lst_x", category: "SPAM" } })).status, 400);
    assert.equal((await call(service.baseUrl, "POST", "/marketplace/reports", { headers: bearer(buyer.accessToken), body: { subjectType: "LISTING", subjectId: "lst_does_not_exist", category: "SPAM" } })).status, 404);
    assert.equal((await call(service.baseUrl, "POST", "/marketplace/reports", { headers: bearer(buyer.accessToken), body: { subjectType: "LISTING", subjectId: "lst_" + "0".repeat(36), category: "NOT_REAL" } })).status, 400);
    // A client cannot name a reporter, a target, or a status.
    assert.equal((await call(service.baseUrl, "POST", "/marketplace/reports", { headers: bearer(buyer.accessToken), body: { subjectType: "LISTING", subjectId: "lst_" + "0".repeat(36), category: "SPAM", status: "RESOLVED" } })).status, 400);
  });
});

describe("Phase 30 blocks (creator protection)", () => {
  async function jobWithProposal(service) {
    const dev = await developer(service);
    const s = sfx();
    const buyer = await account(service, `buyer-${s}@example.test`);
    const creator = await sellerAccount(service, dev, `seller-${s}@example.test`, `studio-${s}`);
    const job = (await call(service.baseUrl, "POST", "/buyer/jobs", { headers: bearer(buyer.accessToken), body: { title: "Beacon farm", description: "Build a beacon-based farm with storage.", edition: "java", minecraftVersion: "1.20.1", scope: "One farm, delivered and shown." } })).body;
    const proposal = (await call(service.baseUrl, "POST", "/creator/proposal", { headers: bearer(creator.accessToken), body: { jobId: job.id, message: "I build efficient farms and can start now.", scope: "One beacon farm delivered as requested." } })).body;
    return { dev, buyer, creator, job, proposal };
  }

  it("blocks a proposer derived from one of the buyer's jobs, and the block stops new proposals", async () => {
    const service = await newService();
    const { buyer, creator, proposal } = await jobWithProposal(service);
    const blocked = await call(service.baseUrl, "POST", "/marketplace/blocks", { headers: bearer(buyer.accessToken), body: { sourceType: "PROPOSAL", sourceId: proposal.id, reason: "Ghosted after proposing." } });
    assert.equal(blocked.status, 200, JSON.stringify(blocked.body));
    assert.equal(blocked.body.alreadyBlocked, false);
    assert.equal(blocked.body.blockedUserId, creator.userId);

    // Re-blocking the same account is an idempotent no-op.
    const again = await call(service.baseUrl, "POST", "/marketplace/blocks", { headers: bearer(buyer.accessToken), body: { sourceType: "PROPOSAL", sourceId: proposal.id } });
    assert.equal(again.status, 200);
    assert.equal(again.body.alreadyBlocked, true);

    // Now a fresh job by the same buyer: the blocked creator cannot propose on it.
    const job2 = (await call(service.baseUrl, "POST", "/buyer/jobs", { headers: bearer(buyer.accessToken), body: { title: "Second build", description: "A second, unrelated build request for the block test.", edition: "java", minecraftVersion: "1.20.1", scope: "Deliver the second build as described." } })).body;
    const refused = await call(service.baseUrl, "POST", "/creator/proposal", { headers: bearer(creator.accessToken), body: { jobId: job2.id, message: "I can take the second job too.", scope: "Second job delivered as agreed." } });
    assert.equal(refused.status, 403, JSON.stringify(refused.body));
    assert.equal(refused.body.error.code, "MARKETPLACE_BLOCKED");
    assert.equal(audits(service, "BLOCK_ENFORCED").length, 1);
    // The proposal that predates the block is untouched — the guard only ever stops new proposals from connecting
    // the two accounts, and never rewinds or deletes anything already agreed.
    const stillThere = await call(service.baseUrl, "GET", `/creator/proposal/${proposal.id}`, { headers: bearer(creator.accessToken) });
    assert.equal(stillThere.status, 200);
    assert.equal(stillThere.body.status, "SUBMITTED");
  });

  it("a protected creator who blocked the buyer is equally shielded from their jobs", async () => {
    const service = await newService();
    const { buyer, creator, job, proposal } = await jobWithProposal(service);
    // Blocks derive from an ORDER or a PROPOSAL the caller actually holds. Here the creator earns an order (award +
    // create), then blocks the buyer through it; the buyer's next job can no longer be reached by this creator.
    await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, { headers: bearer(buyer.accessToken), body: { proposalId: proposal.id } });
    const order = (await call(service.baseUrl, "POST", "/buyer/orders", { headers: bearer(buyer.accessToken), body: { jobId: job.id, proposalId: proposal.id, milestones: [{ title: "Only", description: "One milestone for the block test.", acceptanceCriteria: "Done." }] } })).body;
    const blocked = await call(service.baseUrl, "POST", "/marketplace/blocks", { headers: bearer(creator.accessToken), body: { sourceType: "ORDER", sourceId: order.id } });
    assert.equal(blocked.status, 200, JSON.stringify(blocked.body));
    assert.equal(blocked.body.blockedUserId, buyer.userId);
    // A brand-new job posted by the blocked buyer: the creator cannot propose on it.
    const newJob = (await call(service.baseUrl, "POST", "/buyer/jobs", { headers: bearer(buyer.accessToken), body: { title: "Another", description: "Buyer opened a new job after being blocked by the creator.", edition: "java", minecraftVersion: "1.20.1", scope: "Deliver this too." } })).body;
    const refused = await call(service.baseUrl, "POST", "/creator/proposal", { headers: bearer(creator.accessToken), body: { jobId: newJob.id, message: "Attempting to reach this buyer after being blocked should fail.", scope: "Proposing to a buyer who blocked me." } });
    assert.equal(refused.status, 403);
    assert.equal(refused.body.error.code, "MARKETPLACE_BLOCKED");
  });

  it("cannot block an unrelated account, and listing my blocks is scoped to me", async () => {
    const service = await newService();
    const { buyer, creator, proposal } = await jobWithProposal(service);
    const unrelated = await account(service, `unrelated-${sfx()}@example.test`);
    // A proposal that is not on one of my jobs cannot be used as a block source.
    const tried = await call(service.baseUrl, "POST", "/marketplace/blocks", { headers: bearer(unrelated.accessToken), body: { sourceType: "PROPOSAL", sourceId: proposal.id } });
    assert.equal(tried.status, 404); // not the job owner → the proposal is simply not reachable
    const list = await call(service.baseUrl, "GET", "/marketplace/blocks", { headers: bearer(buyer.accessToken) });
    assert.equal(list.status, 200);
    assert.equal(list.body.blocks.length, 0);
    await call(service.baseUrl, "POST", "/marketplace/blocks", { headers: bearer(buyer.accessToken), body: { sourceType: "PROPOSAL", sourceId: proposal.id } });
    const after = await call(service.baseUrl, "GET", "/marketplace/blocks", { headers: bearer(buyer.accessToken) });
    assert.equal(after.body.blocks.length, 1);
    assert.equal(after.body.blocks[0].blockedUserId, creator.userId);
    // A different account's block list never shows it.
    assert.equal((await call(service.baseUrl, "GET", "/marketplace/blocks", { headers: bearer(creator.accessToken) })).body.blocks.length, 0);
  });

  it("removes a block (then proposals flow again) and reports a missing block", async () => {
    const service = await newService();
    const { buyer, creator, proposal } = await jobWithProposal(service);
    await call(service.baseUrl, "POST", "/marketplace/blocks", { headers: bearer(buyer.accessToken), body: { sourceType: "PROPOSAL", sourceId: proposal.id } });
    const removed = await call(service.baseUrl, "POST", "/marketplace/blocks/remove", { headers: bearer(buyer.accessToken), body: { blockedUserId: creator.userId } });
    assert.equal(removed.status, 200);
    assert.equal(removed.body.removed, true);
    const missing = await call(service.baseUrl, "POST", "/marketplace/blocks/remove", { headers: bearer(buyer.accessToken), body: { blockedUserId: creator.userId } });
    assert.equal(missing.status, 404);
    assert.equal(missing.body.error.code, "MARKETPLACE_BLOCK_NOT_FOUND");
    // After removing, the creator can propose on a new job.
    const job2 = (await call(service.baseUrl, "POST", "/buyer/jobs", { headers: bearer(buyer.accessToken), body: { title: "Third", description: "A new job after the block is lifted.", edition: "java", minecraftVersion: "1.20.1", scope: "Deliver after unblock." } })).body;
    const ok = await call(service.baseUrl, "POST", "/creator/proposal", { headers: bearer(creator.accessToken), body: { jobId: job2.id, message: "Now unblocked, this proposal should succeed.", scope: "Proposing after the block was removed." } });
    assert.equal(ok.status, 201, JSON.stringify(ok.body));
  });
});

describe("Phase 30 trust posture, auth, and limits", () => {
  it("summarizes the caller's own posture and requires a session", async () => {
    const service = await newService();
    const dev = await developer(service);
    const suffix = sfx();
    const buyer = await account(service, `buyer-${suffix}@example.test`);
    const creator = await sellerAccount(service, dev, `seller-${suffix}@example.test`, `studio-${suffix}`);
    const listing = await publishedListing(service, creator);
    await call(service.baseUrl, "POST", "/marketplace/reports", { headers: bearer(buyer.accessToken), body: { subjectType: "LISTING", subjectId: listing.id, category: "OTHER" } });

    const summary = await call(service.baseUrl, "GET", "/marketplace/trust", { headers: bearer(creator.accessToken) });
    assert.equal(summary.status, 200);
    assert.equal(summary.body.receivedOpenReports, 1);
    assert.equal(summary.body.filedOpenReports, 0);
    assert.equal(summary.body.scope, "self_visible_only");
    // The reporter's own posture counts the report they filed, not one against them.
    const buyerSummary = await call(service.baseUrl, "GET", "/marketplace/trust", { headers: bearer(buyer.accessToken) });
    assert.equal(buyerSummary.body.filedOpenReports, 1);
    assert.equal(buyerSummary.body.receivedOpenReports, 0);
    // The trust posture is a private read and requires a session.
    assert.equal((await call(service.baseUrl, "GET", "/marketplace/trust")).status, 401);
  });

  it("spends only the dedicated trust budget under load", async () => {
    const service = await newService({ RATE_TRUST_WRITE_MAX: "3", RATE_TRUST_WRITE_WINDOW_MS: "60000" });
    const buyer = await account(service, `buyer-${sfx()}@example.test`);
    for (let i = 0; i < 3; i += 1) {
      const r = await call(service.baseUrl, "POST", "/marketplace/reports", { headers: bearer(buyer.accessToken), body: { subjectType: "LISTING", subjectId: `lst_${"0".repeat(36)}`, category: "SPAM" } });
      assert.notEqual(r.status, 429, `request ${i + 1} should not be rate limited`); // each is a validation 404/409, still within budget
    }
    const limited = await call(service.baseUrl, "POST", "/marketplace/reports", { headers: bearer(buyer.accessToken), body: { subjectType: "LISTING", subjectId: `lst_${"0".repeat(36)}`, category: "SPAM" } });
    assert.equal(limited.status, 429);
    assert.equal(limited.body.error.code, "RATE_LIMITED");
  });
});
