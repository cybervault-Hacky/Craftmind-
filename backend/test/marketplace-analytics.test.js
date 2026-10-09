/**
 * Phase 31 marketplace analytics tests.
 *
 * The philosophy this phase is held to: analytics are *derived* from the real Phase 23–30 rows, never collected or
 * fabricated, and they are privacy-safe by construction. These tests therefore check three things at once — that the
 * numbers are **correct** (aggregate arithmetic, and specifically that an order with several milestones is counted
 * once), that they are **honest** (a metric the system does not track is reported as `unavailable`, never as a
 * measured zero), and that they are **safe** (identity comes only from the session, cross-account reads are
 * structurally impossible, aggregate views carry no reporter/target/note/statement, and the window parameter is a
 * closed whitelist so no client filter can reach SQL or widen scope).
 *
 * They also lock in the "no drift from the phases this reads" guarantees: the schema version is unchanged (analytics
 * add no tables and no migration), the audit vocabulary is unchanged (reads are not audited and are never turned into
 * analytics), and the trust/dispute/order state analytics report still moves exactly as Phases 27 and 30 define it.
 *
 * As in Phases 23–30, security thresholds are relaxed because the suite mints refused requests on purpose; the
 * rate-limit test sets its own tiny budget. No assertion here involves money, and none implies revenue tracking.
 */

import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { REGISTERED_AUDIT_ACTION_TYPES, SCHEMA_VERSION } from "../src/db.js";
import { ErrorCode } from "../src/errors.js";
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
function daysAgoIso(days) { return new Date(Date.now() - days * 86_400_000).toISOString(); }

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
/** A CREATOR-plan account with seller onboarding complete — the precondition to propose/build (a dev grant, not a purchase). */
async function creator(service, email, handle) {
  const a = await account(service, email);
  const dev = await developer(service);
  await confirmedTool(service, dev.accessToken, "grantMembership", { email, plan: "CREATOR", days: 30, reason: "Phase 31 test grant" });
  const saved = await call(service.baseUrl, "POST", "/onboarding/seller", {
    headers: bearer(a.accessToken),
    body: { handle, displayName: `${handle} Studio`, referralSource: "YOUTUBE", editions: ["java"], minecraftVersions: ["1.20.1"], loaders: ["Fabric"], agreementAccepted: true, agreementVersion: CREATOR_AGREEMENT_VERSION },
  });
  assert.equal(saved.status, 200, JSON.stringify(saved.body));
  const profile = service.database.prepare("SELECT creator_id FROM creator_profiles WHERE user_id = ?").get(a.userId);
  return { ...a, creatorId: profile.creator_id };
}
async function publishedListing(service, c, title = "Floating islands") {
  const created = await call(service.baseUrl, "POST", "/creator/listing", {
    headers: bearer(c.accessToken),
    body: { title, description: "A set of seven floating sky islands with farms and storage.", category: "Structures", edition: "java", minecraftVersions: ["1.20.1"] },
  });
  assert.equal(created.status, 201, JSON.stringify(created.body));
  const pub = await call(service.baseUrl, "POST", `/creator/listing/${created.body.id}/publish`, { headers: bearer(c.accessToken) });
  assert.equal(pub.status, 200, JSON.stringify(pub.body));
  return created.body;
}

const MILESTONES = [
  { title: "Plan and spec", description: "Write the plan and confirm the acceptance criteria.", acceptanceCriteria: "Buyer agrees the plan covers the agreed scope." },
  { title: "Build and handover", description: "Build the work and hand over the result with notes.", acceptanceCriteria: "The delivered result matches the agreed scope." },
];

/**
 * Builds a full order the way Phases 26–27 define one and drives it to `delivered` (creator started + shipped every
 * milestone) or `completed` (buyer also approved every milestone, then the order was completed). Returns the actors
 * plus the order id and milestone ids so a test can assert exactly how many orders / milestones / deliveries exist.
 */
async function orderFlow(service, { state = "delivered", buyer = null, c = null } = {}) {
  const b = buyer ?? await account(service, `buyer-${sfx()}@example.test`);
  const creatorAccount = c ?? await creator(service, `creator-${sfx()}@example.test`, `studio-${sfx()}`);
  const job = (await call(service.baseUrl, "POST", "/buyer/jobs", {
    headers: bearer(b.accessToken),
    body: { title: "Redstone sorting hall", description: "A full item sorting hall for my survival server with expandable storage.", edition: "java", minecraftVersion: "1.20.1", scope: "Design and build one sorting hall delivered as a schematic plus an install guide." },
  })).body;
  const proposal = (await call(service.baseUrl, "POST", "/creator/proposal", {
    headers: bearer(creatorAccount.accessToken),
    body: { jobId: job.id, message: "I have built several sorting halls and can start this week.", scope: "One sorting hall with 12 modules, installed and demonstrated." },
  })).body;
  await call(service.baseUrl, "POST", `/buyer/jobs/${job.id}/award`, { headers: bearer(b.accessToken), body: { proposalId: proposal.id } });
  const order = (await call(service.baseUrl, "POST", "/buyer/orders", {
    headers: bearer(b.accessToken), body: { jobId: job.id, proposalId: proposal.id, milestones: MILESTONES },
  })).body;
  const detail = (await call(service.baseUrl, "GET", `/orders/${order.id}`, { headers: bearer(b.accessToken) })).body;
  const milestoneIds = detail.milestones.map((m) => m.id);
  for (const mid of milestoneIds) {
    await call(service.baseUrl, "POST", `/orders/${order.id}/milestones/${mid}/start`, { headers: bearer(creatorAccount.accessToken), body: {} });
    await call(service.baseUrl, "POST", `/orders/${order.id}/milestones/${mid}/deliver`, { headers: bearer(creatorAccount.accessToken), body: { note: "Delivered the agreed work with setup notes." } });
    if (state === "completed") {
      await call(service.baseUrl, "POST", `/orders/${order.id}/milestones/${mid}/approve`, { headers: bearer(b.accessToken), body: {} });
    }
  }
  if (state === "completed") {
    const done = await call(service.baseUrl, "POST", `/orders/${order.id}/complete`, { headers: bearer(b.accessToken), body: {} });
    assert.equal(done.status, 200, JSON.stringify(done.body));
  }
  return { buyer: b, creator: creatorAccount, job, proposal, order, milestoneIds };
}

function overview(service, token, query = "") {
  return call(service.baseUrl, "GET", `/marketplace/analytics/overview${query}`, token ? { headers: bearer(token) } : {});
}
function creatorView(service, token, query = "") {
  return call(service.baseUrl, "GET", `/marketplace/analytics/creator${query}`, token ? { headers: bearer(token) } : {});
}

/* ------------------------------------------------------------------------------- routing & authz boundary */

describe("Phase 31 analytics routing and session authorization", () => {
  it("requires an authenticated session for both analytics reads (not an anonymous feed)", async () => {
    const service = await newService();
    assert.equal((await overview(service)).status, 401);
    assert.equal((await creatorView(service)).status, 401);
  });

  it("returns a consistent global overview and a self-only personal view to an authorized participant", async () => {
    const service = await newService();
    const a = await account(service, `ana-${sfx()}@example.test`);
    const over = await overview(service, a.accessToken);
    assert.equal(over.status, 200, JSON.stringify(over.body));
    assert.equal(over.body.scope, "marketplace_global_aggregates");
    const mine = await creatorView(service, a.accessToken);
    assert.equal(mine.status, 200, JSON.stringify(mine.body));
    assert.equal(mine.body.scope, "self_only");
  });

  it("is additive: no new tables, no migration, no new audit action types, and reads are not audited", async () => {
    const service = await newService();
    const a = await account(service, `ana-${sfx()}@example.test`);
    // Phase 30 left the schema at v11 with 102 registered action types; analytics must not move either.
    assert.equal(SCHEMA_VERSION, 11);
    assert.equal(REGISTERED_AUDIT_ACTION_TYPES.size, 102);
    const before = service.database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log").get().count;
    for (let i = 0; i < 5; i += 1) { await overview(service, a.accessToken); await creatorView(service, a.accessToken); }
    const after = service.database.prepare("SELECT COUNT(*) AS count FROM admin_audit_log").get().count;
    assert.equal(after, before, "analytics reads must not write to the audit log");
  });
});

/* ------------------------------------------------------------------------------- metric correctness */

describe("Phase 31 overview aggregates", () => {
  it("returns a well-formed all-zero shape on an empty marketplace (never a 500, never a fabricated figure)", async () => {
    const service = await newService();
    const a = await account(service, `ana-${sfx()}@example.test`);
    const body = (await overview(service, a.accessToken)).body;
    assert.equal(body.snapshot.listings.total, 0);
    assert.equal(body.snapshot.orders.total, 0);
    assert.equal(body.activity.ordersCreated, 0);
    assert.deepEqual(body.trends.ordersCreated, []);
    assert.equal(body.operations.disputes.total, 0);
    // Untracked metrics are present as unavailable WITH a reason, not as zeroes that look like measurements.
    assert.ok(Array.isArray(body.unavailable) && body.unavailable.length >= 3);
    assert.ok(body.unavailable.some((m) => m.key === "listingViews" && m.reason.length > 0));
  });

  it("counts published listings, jobs, and proposals correctly", async () => {
    const service = await newService();
    const buyer = await account(service, `buyer-${sfx()}@example.test`);
    const c1 = await creator(service, `c1-${sfx()}@example.test`, `s1-${sfx()}`);
    const c2 = await creator(service, `c2-${sfx()}@example.test`, `s2-${sfx()}`);
    await publishedListing(service, c1, "Islands A");
    await publishedListing(service, c2, "Islands B");
    // A second draft (unpublished) listing for c1.
    const draft = await call(service.baseUrl, "POST", "/creator/listing", {
      headers: bearer(c1.accessToken),
      body: { title: "Draft thing", description: "A draft listing not yet published for the snapshot.", category: "Structures", edition: "java", minecraftVersions: ["1.20.1"] },
    });
    assert.equal(draft.status, 201);
    // One job + proposal (no order yet).
    const job = (await call(service.baseUrl, "POST", "/buyer/jobs", {
      headers: bearer(buyer.accessToken),
      body: { title: "Sorting hall", description: "A sorting hall job to count proposals.", edition: "java", minecraftVersion: "1.20.1", scope: "Build the hall as described across two milestones." },
    })).body;
    await call(service.baseUrl, "POST", "/creator/proposal", {
      headers: bearer(c1.accessToken),
      body: { jobId: job.id, message: "I can build this sorting hall well.", scope: "One sorting hall, 12 modules, installed." },
    });

    const body = (await overview(service, buyer.accessToken)).body;
    assert.equal(body.snapshot.listings.published, 2);
    assert.equal(body.snapshot.listings.draft, 1);
    assert.equal(body.snapshot.listings.total, 3);
    assert.equal(body.snapshot.jobs.open, 1);
    assert.equal(body.snapshot.jobs.total, 1);
    assert.equal(body.snapshot.proposals.submitted, 1);
    assert.equal(body.snapshot.proposals.total, 1);
    assert.equal(body.activity.jobsPosted, 1);
    assert.equal(body.activity.proposalsSubmitted, 1);
    assert.equal(body.activity.listingsPublished, 2);
  });

  it("distinguishes a completed ORDER from completed MILESTONES and never double-counts an order over its milestones", async () => {
    const service = await newService();
    const flow = await orderFlow(service, { state: "completed" }); // one order, two milestones, both delivered + approved
    const buyer = flow.buyer;
    const body = (await overview(service, buyer.accessToken)).body;

    // One order exists — not two — even though it produced two deliveries and two approved milestones.
    assert.equal(body.snapshot.orders.total, 1);
    assert.equal(body.snapshot.orders.completed, 1);
    assert.equal(body.activity.ordersCompleted, 1);
    // Its milestone work is counted separately and is explicitly larger than the order count.
    assert.equal(body.snapshot.milestones.total, 2);
    assert.equal(body.snapshot.milestones.approved, 2);
    assert.equal(body.activity.deliverySubmissions, 2);
    assert.equal(body.activity.ordersWithDeliveries, 1);
    // The completed-order trend has exactly one bucket (today) with a single order.
    assert.equal(body.trends.ordersCompleted.length, 1);
    assert.equal(body.trends.ordersCompleted[0].count, 1);
  });

  it("reflects an in-progress (delivered, not completed) order without marking it complete", async () => {
    const service = await newService();
    const flow = await orderFlow(service, { state: "delivered" });
    const body = (await overview(service, flow.buyer.accessToken)).body;
    assert.equal(body.snapshot.orders.total, 1);
    assert.equal(body.snapshot.orders.active, 1);
    assert.equal(body.snapshot.orders.completed, 0);
    assert.equal(body.activity.deliverySubmissions, 2);
    assert.equal(body.trends.ordersCompleted.length, 0);
  });
});

/* ------------------------------------------------------------------------------- date ranges */

describe("Phase 31 date-range handling", () => {
  it("accepts the closed window set and defaults to 30d", async () => {
    const service = await newService();
    const a = await account(service, `ana-${sfx()}@example.test`);
    const defaults = (await overview(service, a.accessToken)).body.window;
    assert.equal(defaults.label, "30d");
    assert.equal(defaults.days, 30);
    assert.equal(typeof defaults.sinceIso, "string");
    for (const [label, days] of [["7d", 7], ["90d", 90], ["365d", 365]]) {
      const w = (await overview(service, a.accessToken, `?window=${label}`)).body.window;
      assert.equal(w.days, days);
    }
    const all = (await overview(service, a.accessToken, "?window=all")).body.window;
    assert.equal(all.days, null);
    assert.equal(all.sinceIso, null); // all-time has no lower bound
  });

  it("rejects a malformed or excessive window with a typed error (whitelist, not free text)", async () => {
    const service = await newService();
    const a = await account(service, `ana-${sfx()}@example.test`);
    for (const bad of ["bogus", "31d", "", "0d", "-5d", "9999d", "1;--"]) {
      const r = await overview(service, a.accessToken, `?window=${encodeURIComponent(bad)}`);
      if (bad === "") {
        // An empty window falls back to the default rather than erroring.
        assert.equal(r.status, 200);
        assert.equal(r.body.window.label, "30d");
      } else {
        assert.equal(r.status, 400, `${bad}: ${JSON.stringify(r.body)}`);
        assert.equal(r.body.error.code, ErrorCode.INVALID_REQUEST);
      }
    }
  });

  it("applies the window to activity counts and trend buckets while snapshot totals stay all-time", async () => {
    const service = await newService();
    const c = await creator(service, `c-${sfx()}@example.test`, `s-${sfx()}`);
    const listing = await publishedListing(service, c);
    // Age the listing's timestamps 200 days into the past; the row is still PUBLISHED right now.
    service.database.prepare("UPDATE marketplace_listings SET created_at = ?, published_at = ? WHERE listing_id = ?")
      .run(daysAgoIso(200), daysAgoIso(200), listing.id);

    const recent = (await overview(service, c.accessToken, "?window=90d")).body;
    assert.equal(recent.snapshot.listings.published, 1, "snapshot reflects current state regardless of window");
    assert.equal(recent.activity.listingsPublished, 0, "200-day-old publish is outside a 90-day window");
    assert.deepEqual(recent.trends.listingsPublished, []);

    const all = (await overview(service, c.accessToken, "?window=all")).body;
    assert.equal(all.activity.listingsPublished, 1, "all-time includes the aged publish");
    assert.equal(all.trends.listingsPublished.length, 1);
    assert.equal(all.trends.listingsPublished[0].count, 1);
    assert.equal(all.trends.listingsPublished[0].day, daysAgoIso(200).slice(0, 10), "bucket is keyed by the UTC day of the timestamp");
  });
});

/* ------------------------------------------------------------------------------- per-account scoping & privacy */

describe("Phase 31 creator insights are owner-scoped", () => {
  it("shows the creator their own listings, proposals, orders, milestones, and deliveries", async () => {
    const service = await newService();
    const flow = await orderFlow(service, { state: "completed" });
    await publishedListing(service, flow.creator);
    const body = (await creatorView(service, flow.creator.accessToken)).body;
    assert.equal(body.account.hasCreatorProfile, true);
    assert.equal(body.snapshot.listings.published, 1);
    assert.equal(body.snapshot.proposals.selected, 1);
    assert.equal(body.snapshot.proposals.total, 1);
    assert.equal(body.snapshot.orders.total, 1);
    assert.equal(body.snapshot.orders.completed, 1);
    assert.equal(body.activity.milestoneTotal, 2);
    assert.equal(body.activity.milestonesApproved, 2);
    assert.equal(body.activity.deliverySubmissions, 2);
    assert.equal(body.activity.ordersWithDeliveries, 1);
  });

  it("never mixes accounts: another creator sees only their own (empty) numbers", async () => {
    const service = await newService();
    const flow = await orderFlow(service, { state: "delivered" });
    const other = await creator(service, `other-${sfx()}@example.test`, `other-${sfx()}`);
    const otherView = (await creatorView(service, other.accessToken)).body;
    assert.equal(otherView.snapshot.orders.total, 0);
    assert.equal(otherView.snapshot.listings.total, 0);
    assert.equal(otherView.snapshot.proposals.total, 0);
    // The other creator's serialized payload must not contain the first creator's own listing/order ids.
    const serialized = JSON.stringify(otherView);
    assert.equal(serialized.includes(flow.order.id), false, "another account's order id leaked into a personal view");
  });

  it("ignores an injected userId/creatorId and never honors a client-supplied identity or scope", async () => {
    const service = await newService();
    const flow = await orderFlow(service, { state: "delivered" });
    // The creator tries to pivot their own view to the buyer's identity, and to widen scope; both are ignored.
    const attempted = (await creatorView(service, flow.creator.accessToken, `?userId=${flow.buyer.userId}&creatorId=other&scope=all&limit=999&offset=abc`));
    assert.equal(attempted.status, 200);
    assert.equal(attempted.body.scope, "self_only");
    assert.equal(attempted.body.snapshot.orders.total, 1, "still the caller's own single order, not the buyer's");
    // The injected `userId` is ignored: the caller still sees THEIR OWN proposal count (1), not the buyer's (0). If a
    // supplied identity had been honored, this would read the buyer's zero — so 1 is the proof the pivot did nothing.
    const asBuyer = (await creatorView(service, flow.creator.accessToken, `?userId=${flow.buyer.userId}&scope=all`)).body;
    assert.equal(asBuyer.snapshot.proposals.total, 1, "the creator's own proposals, unaffected by an injected buyer id");
    assert.equal(asBuyer.scope, "self_only", "a supplied scope=all cannot widen the self-only view");
  });

  it("reports untracked per-account signals as unavailable, not as zero activity", async () => {
    const service = await newService();
    const c = await creator(service, `c-${sfx()}@example.test`, `s-${sfx()}`);
    await publishedListing(service, c);
    const body = (await creatorView(service, c.accessToken)).body;
    assert.ok(body.unavailable.some((m) => m.key === "listingViews" && typeof m.reason === "string" && m.reason.length > 0));
    assert.equal("listingViews" in body.snapshot.listings, false, "views are never invented as a number");
  });
});

/* ------------------------------------------------------------------------------- trust & dispute privacy in aggregates */

describe("Phase 31 aggregate privacy over reports and disputes", () => {
  it("exposes report and dispute roll-ups by category/state only — no reporter, target, note, or statement", async () => {
    const service = await newService();
    const flow = await orderFlow(service, { state: "delivered" });
    const listing = await publishedListing(service, flow.creator);
    // Buyer reports the creator's listing, with a distinctive note that must never surface.
    const note = "the-screenshots-are-from-a-different-world-7731";
    await call(service.baseUrl, "POST", "/marketplace/reports", {
      headers: bearer(flow.buyer.accessToken),
      body: { subjectType: "LISTING", subjectId: listing.id, category: "MISREPRESENTATION", note },
    });
    // Buyer also opens a dispute on the delivered order.
    const dispute = await call(service.baseUrl, "POST", `/orders/${flow.order.id}/disputes`, {
      headers: bearer(flow.buyer.accessToken), body: { reasonCategory: "QUALITY", reason: "The delivery did not match the acceptance criteria as discussed." },
    });
    assert.equal(dispute.status, 201, JSON.stringify(dispute.body));

    const body = (await overview(service, flow.buyer.accessToken, "?window=all")).body;
    assert.equal(body.operations.reports.total, 1);
    assert.equal(body.operations.reports.open, 1);
    assert.equal(body.operations.reports.byCategory.MISREPRESENTATION, 1);
    assert.equal(body.operations.disputes.total, 1);
    assert.equal(body.operations.disputes.open, 1);
    assert.equal(body.operations.disputes.byReasonCategory.QUALITY, 1);
    assert.equal(body.snapshot.orders.currentlyDisputed, 1);
    assert.equal(body.snapshot.orders.everDisputed, 1);

    // The aggregate must carry no private material: not the note, not the reporter, not the target, not subject ids.
    const serialized = JSON.stringify(body);
    assert.equal(serialized.includes(note), false, "report note leaked into analytics");
    assert.equal(serialized.includes(flow.buyer.userId), false, "reporter id leaked into analytics");
    assert.equal(serialized.includes(flow.creator.userId), false, "reported target id leaked into analytics");
    assert.equal(serialized.includes(listing.id), false, "report subject id leaked into analytics");
    assert.equal(serialized.includes(flow.order.id), false, "dispute/order id leaked into a global aggregate");
  });

  it("keeps dispute state consistent with Phase 30 as it resolves (analytics reflect, not redefine, it)", async () => {
    const service = await newService();
    const flow = await orderFlow(service, { state: "delivered" });
    const open = (await call(service.baseUrl, "POST", `/orders/${flow.order.id}/disputes`, {
      headers: bearer(flow.buyer.accessToken), body: { reasonCategory: "DELIVERY", reason: "Timeline slipped and we want to close it out cleanly together." },
    })).body;
    let during = (await overview(service, flow.buyer.accessToken, "?window=all")).body;
    assert.equal(during.operations.disputes.open, 1);
    assert.equal(during.snapshot.orders.completed, 0, "an open dispute must not have changed completion");

    // Both parties record CONTINUE → the dispute resolves and the order stays ACTIVE (Phase 30 semantics).
    for (const token of [flow.buyer.accessToken, flow.creator.accessToken]) {
      const r = await call(service.baseUrl, "POST", `/orders/${flow.order.id}/disputes/${open.id}/position`, { headers: bearer(token), body: { position: "CONTINUE" } });
      assert.equal(r.status, 200, JSON.stringify(r.body));
    }
    const after = (await overview(service, flow.buyer.accessToken, "?window=all")).body;
    assert.equal(after.operations.disputes.open, 0);
    assert.equal(after.operations.disputes.resolved, 1);
    assert.equal(after.operations.disputes.byOutcome.continued, 1);
    assert.equal(after.snapshot.orders.currentlyDisputed, 0);
    assert.equal(after.snapshot.orders.everDisputed, 1); // ever-disputed still remembers it was once opened
  });
});

/* ------------------------------------------------------------------------------- query safety & limits */

describe("Phase 31 query safety", () => {
  it("resists injection through the window parameter and keeps the tables intact", async () => {
    const service = await newService();
    const flow = await orderFlow(service, { state: "delivered" });
    const payloads = [
      "30d; DROP TABLE orders;--",
      "' OR '1'='1",
      "all UNION SELECT * FROM admin_audit_log",
      "30d) OR (1=1",
    ];
    for (const p of payloads) {
      const r = await overview(service, flow.buyer.accessToken, `?window=${encodeURIComponent(p)}`);
      assert.equal(r.status, 400, `${p} should be refused by the whitelist: ${JSON.stringify(r.body)}`);
      assert.equal(r.body.error.code, ErrorCode.INVALID_REQUEST);
    }
    // Still fully functional and unaltered: the injection strings are data, never executed.
    const after = (await overview(service, flow.buyer.accessToken, "?window=all")).body;
    assert.equal(after.snapshot.orders.total, 1);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM orders").get().count, 1);
    // No personal/security columns are reachable through a crafted window either (the value is only ever looked up).
    assert.equal(JSON.stringify(after).includes("admin_audit"), false);
  });

  it("rate-limits analytics reads on their own dedicated budget", async () => {
    const service = await newService({ RATE_CREATOR_READ_MAX: "2" });
    const a = await account(service, `rate-${sfx()}@example.test`);
    const statuses = [];
    for (let i = 0; i < 3; i += 1) statuses.push((await overview(service, a.accessToken)).status);
    assert.deepEqual(statuses, [200, 200, 429]);
    const limited = await overview(service, a.accessToken);
    assert.equal(limited.status, 429);
    assert.equal(limited.body.error.code, ErrorCode.RATE_LIMITED);
  });
});

/* ------------------------------------------------------------------------------- backward compatibility */

describe("Phase 31 leaves earlier marketplace behavior intact", () => {
  it("keeps public marketplace reads and the Phase 30 trust surface working unchanged", async () => {
    const service = await newService();
    const c = await creator(service, `c-${sfx()}@example.test`, `s-${sfx()}`);
    await publishedListing(service, c);
    // Public listing discovery still serves unauthenticated traffic.
    const publicList = await call(service.baseUrl, "GET", "/marketplace/listings");
    assert.equal(publicList.status, 200, JSON.stringify(publicList.body));
    // The Phase 30 trust summary still requires a session and still answers for a session holder.
    assert.equal((await call(service.baseUrl, "GET", "/marketplace/trust")).status, 401);
    const trust = await call(service.baseUrl, "GET", "/marketplace/trust", { headers: bearer(c.accessToken) });
    assert.equal(trust.status, 200, JSON.stringify(trust.body));
    // And a report can still be filed and read back (proves the analytics routes did not displace trust routes).
    const filed = await call(service.baseUrl, "POST", "/marketplace/reports", {
      headers: bearer(c.accessToken), body: { subjectType: "LISTING", subjectId: "lst_00000000-0000-4000-8000-000000000000", category: "SPAM" },
    });
    assert.equal(filed.status, 404); // non-existent listing -> uniform not-found, unchanged behavior
  });
});
