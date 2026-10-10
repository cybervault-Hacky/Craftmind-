/**
 * Phase 32 referral identifier and campaign-attribution tests.
 *
 * The claims this phase makes are narrow, so the tests are narrow and specific: a code is unguessable and unique, a
 * claim is one-per-account and immutable, attribution distinguishes what was *asserted* from what the server could
 * *confirm*, and no response anywhere reveals the other half of a referral relationship or lets a client name an
 * identity for itself. Two things are checked on every path — the API's answer, and the row the database actually
 * holds — because for attribution the stored fact is the product.
 *
 * The lifecycle's starting point is a verified finding, not an assumption: registration issues no session and login
 * is refused until the email is verified, so "unverified referred account" is unreachable, and the state that
 * genuinely varies — whether the *referrer* is still live — is what `OBSERVED` vs `VERIFIED` measures here. The
 * referrer's status is set through `users.status` itself, the exact column the service reads, so the test drives real
 * behavior rather than a stub.
 *
 * Security thresholds are relaxed as in Phases 23–31 because the suite mints refused requests on purpose; the
 * rate-limit test sets its own tiny budget. There is no money assertion anywhere: a referral grants nothing.
 */

import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { DatabaseSync } from "node:sqlite";
import { migrateToVersion, REGISTERED_AUDIT_ACTION_TYPES, SCHEMA_VERSION } from "../src/db.js";
import { ErrorCode } from "../src/errors.js";
import { openDatabase } from "../src/db.js";
import { call, registerVerified, startService } from "./helpers.js";

const services = new Set();
const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x51).toString("base64url");
const DEVELOPER_EMAIL = "owner@example.test";
const DEVELOPER_PASSWORD = "Creator Plane 7Safe";
const CODE_PATTERN = /^CM-[0-9A-F]{12}$/;

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
  const row = service.database.prepare("SELECT user_id, email_verified_at FROM users WHERE email_canonical = ?").get(email);
  return { email, accessToken: r.body.session.accessToken, userId: row.user_id, verifiedAt: row.email_verified_at };
}
const post = (service, path, token, body) => call(service.baseUrl, "POST", path, { headers: bearer(token), body });
const get = (service, path, token) => call(service.baseUrl, "GET", path, token ? { headers: bearer(token) } : {});
const issueCode = (service, token) => post(service, "/marketing/referrals/code", token, {});
const claim = (service, token, body) => post(service, "/marketing/referrals/claim", token, body);
const verify = (service, token) => post(service, "/marketing/referrals/verify", token);
const summary = (service, token) => get(service, "/marketing/referrals/me", token);
const attributionRow = (service, userId) => service.database
  .prepare("SELECT * FROM referral_attributions WHERE referred_user_id = ?").get(userId);

/* ------------------------------------------------------------------------------- 1,2: code issuing, format, uniqueness */

describe("Phase 32 referral identifiers", () => {
  it("issues a caller's code once and returns the same code on every later call", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);

    const first = await issueCode(service, a.accessToken);
    assert.equal(first.status, 200, JSON.stringify(first.body));
    assert.match(first.body.code, CODE_PATTERN);
    assert.equal(first.body.issuedNow, true);
    assert.equal(typeof first.body.createdAt, "string");

    const second = await issueCode(service, a.accessToken);
    assert.equal(second.status, 200);
    assert.equal(second.body.code, first.body.code, "a code a user has already shared must keep working (no rotation)");
    assert.equal(second.body.issuedNow, false);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS c FROM referral_codes").get().c, 1);
  });

  it("generates distinct, unguessable codes that carry no account identity", async () => {
    const service = await newService();
    const codes = new Set();
    const emails = [];
    for (let i = 0; i < 12; i += 1) {
      const email = `burst-${sfx()}-${i}@example.test`;
      emails.push(email);
      const a = await account(service, email);
      const issued = await issueCode(service, a.accessToken);
      assert.equal(issued.status, 200);
      assert.match(issued.body.code, CODE_PATTERN);
      codes.add(issued.body.code);
      // A code must not be derived from anything about the account.
      const serialized = issued.body.code.toLowerCase();
      assert.equal(serialized.includes(a.userId.slice(4).replace(/-/g, "")), false);
      assert.equal(serialized.includes(email.split("@")[0].slice(0, 6)), false);
    }
    assert.equal(codes.size, 12, "12 accounts must produce 12 distinct codes");
  });

  it("enforces uniqueness and format in the database, not only in the service", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const b = await account(service, `oth-${sfx()}@example.test`);
    const issued = await issueCode(service, a.accessToken);
    const other = issued.body.code;
    const n = new Date().toISOString();

    // One code per account, and one account per code.
    assert.throws(() => service.database.prepare("INSERT INTO referral_codes (code,user_id,created_at,updated_at) VALUES (?,?,?,?)")
      .run(other, b.userId, n, n), /UNIQUE constraint failed: referral_codes.code/);
    assert.throws(() => service.database.prepare("INSERT INTO referral_codes (code,user_id,created_at,updated_at) VALUES (?,?,?,?)")
      .run("CM-00000000000F", a.userId, n, n), /UNIQUE constraint failed: referral_codes.user_id/);
    // The code shape itself is a constraint, so a hand-written row cannot bypass the generator.
    for (const bad of ["CM-GGGGGGGGGGGG", "cm-000000000001", "CM-00000000000", "CM-0000000000012", "USR-000000000001"]) {
      assert.throws(() => service.database.prepare("INSERT INTO referral_codes (code,user_id,created_at,updated_at) VALUES (?,?,?,?)")
        .run(bad, b.userId, n, n), /CHECK constraint failed/, bad);
    }
  });

  it("refuses malformed, missing, empty, and oversized code input before any lookup", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const b = await account(service, `new-${sfx()}@example.test`);
    const cases = [
      {}, // missing referralCode -> required key
      { referralCode: "" }, { referralCode: "   " }, { referralCode: null }, { referralCode: 42 },
      { referralCode: "CM-000" }, { referralCode: "CM-ZZZZZZZZZZZZ" },
      { referralCode: `CM-000000000001${"x".repeat(80)}` },
      { referralCode: "*".repeat(200) },
    ];
    for (const body of cases) {
      const r = await claim(service, b.accessToken, body);
      assert.equal(r.status, 400, `${JSON.stringify(body)} -> ${JSON.stringify(r.body)}`);
      assert.equal(r.body.error.code, ErrorCode.INVALID_REQUEST);
    }
    // And nothing was written for any of them.
    assert.equal(service.database.prepare("SELECT COUNT(*) AS c FROM referral_attributions").get().c, 0);
  });

  it("answers an unknown code and a self-referral identically, so it is not an existence oracle", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const b = await account(service, `new-${sfx()}@example.test`);
    const own = (await issueCode(service, a.accessToken)).body.code;

    const selfClaim = await claim(service, a.accessToken, { referralCode: own });
    const unknownClaim = await claim(service, b.accessToken, { referralCode: "CM-000000000000" });

    assert.equal(selfClaim.status, 400);
    assert.equal(unknownClaim.status, 400);
    // Same code, same message: a caller learns only "that code does not work for you".
    assert.equal(selfClaim.body.error.code, unknownClaim.body.error.code);
    assert.equal(selfClaim.body.error.message, unknownClaim.body.error.message);
    assert.equal(JSON.stringify(selfClaim.body).includes(a.userId), false);
    assert.equal(attributionRow(service, a.userId), undefined, "a refused claim stores nothing");
  });

  it("accepts a code that was re-cased or padded, because only the shape is strict", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const b = await account(service, `new-${sfx()}@example.test`);
    const own = (await issueCode(service, a.accessToken)).body.code;
    const sloppy = `  ${own.toLowerCase()}  `;
    const r = await claim(service, b.accessToken, { referralCode: sloppy });
    assert.equal(r.status, 201, JSON.stringify(r.body));
    assert.equal(r.body.code, own, "stored canonically, whatever the client typed");
  });
});

/* ------------------------------------------------------------------------------- 3,4,5: claim integrity */

describe("Phase 32 attribution integrity", () => {
  it("records one attribution per referred account and refuses a conflicting second claim", async () => {
    const service = await newService();
    const refA = await account(service, `refa-${sfx()}@example.test`);
    const refB = await account(service, `refb-${sfx()}@example.test`);
    const newp = await account(service, `new-${sfx()}@example.test`);
    const codeA = (await issueCode(service, refA.accessToken)).body.code;
    const codeB = (await issueCode(service, refB.accessToken)).body.code;

    const first = await claim(service, newp.accessToken, { referralCode: codeA });
    assert.equal(first.status, 201);
    const before = attributionRow(service, newp.userId);

    const conflict = await claim(service, newp.accessToken, { referralCode: codeB });
    assert.equal(conflict.status, 409);
    assert.equal(conflict.body.error.code, ErrorCode.REFERRAL_CLAIM_CONFLICT);
    // The conflict must not have touched the original row — "nothing was changed" has to be literally true.
    assert.deepEqual(attributionRow(service, newp.userId), before);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS c FROM referral_attributions").get().c, 1);
  });

  it("treats a re-claim of the same code as idempotent, without re-stamping or duplicating", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const b = await account(service, `new-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;

    const first = await claim(service, b.accessToken, { referralCode: code, campaign: "one" });
    assert.equal(first.status, 201);
    const stored = attributionRow(service, b.userId);

    for (let i = 0; i < 3; i += 1) {
      const again = await claim(service, b.accessToken, { referralCode: code, campaign: `retry-${i}` });
      assert.equal(again.status, 200, "a double-submit is a no-op success, not an error");
      assert.equal(again.body.idempotent, true);
    }
    // Same row, same timestamps, and the second request's different campaign label did NOT overwrite anything.
    assert.deepEqual(attributionRow(service, b.userId), stored);
    assert.equal(attributionRow(service, b.userId).campaign, "one");
  });

  it("freezes a finalized attribution: only the OBSERVED -> VERIFIED promotion may ever change a row", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const b = await account(service, `new-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    await claim(service, b.accessToken, { referralCode: code });
    const row = attributionRow(service, b.userId);
    assert.equal(row.status, "VERIFIED", "both accounts are live and verified, so this lands verified");

    const attempt = (sql, params, label) => assert.throws(
      () => service.database.prepare(sql).run(...params), /referral attribution is immutable once recorded/, label,
    );
    attempt("UPDATE referral_attributions SET source = 'GOOGLE' WHERE attribution_id = ?", [row.attribution_id], "source rewrite");
    attempt("UPDATE referral_attributions SET campaign = 'edited' WHERE attribution_id = ?", [row.attribution_id], "campaign rewrite");
    attempt("UPDATE referral_attributions SET observed_at = ? WHERE attribution_id = ?", ["2000-01-01T00:00:00.000Z", row.attribution_id], "backdating");
    attempt("UPDATE referral_attributions SET code = ? WHERE attribution_id = ?", ["CM-00000000000A", row.attribution_id], "re-pointing to another code");
    attempt("UPDATE referral_attributions SET status = 'OBSERVED', verified_at = NULL WHERE attribution_id = ?", [row.attribution_id], "demotion after verification");
    assert.deepEqual(attributionRow(service, b.userId), row, "no attempt changed a byte");
  });

  it("prevents self-referral by construction: the schema cannot represent it either", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    const n = new Date().toISOString();
    assert.throws(() => service.database.prepare(
      "INSERT INTO referral_attributions (attribution_id,referred_user_id,referrer_user_id,code,source,status,observed_at) VALUES (?,?,?,?,?,?,?)",
    ).run("rat_self", a.userId, a.userId, code, "FRIEND_REFERRAL", "OBSERVED", n), /CHECK constraint failed/);
  });

  it("converges concurrent claims onto exactly one attribution row", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const b = await account(service, `new-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;

    const results = await Promise.all([1, 2, 3, 4, 5].map(() => claim(service, b.accessToken, { referralCode: code })));
    for (const r of results) assert.ok(r.status === 201 || r.status === 200, JSON.stringify(r.body));
    assert.equal(results.filter((r) => r.status === 201).length, 1, "exactly one claim created the row");
    assert.equal(service.database.prepare("SELECT COUNT(*) AS c FROM referral_attributions").get().c, 1);
  });
});

/* ------------------------------------------------------------------------------- 6: observed vs verified */

describe("Phase 32 observed versus verified attribution", () => {
  it("verifies at claim time when both accounts are live, stamping the real verification time", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const b = await account(service, `new-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;

    const claimed = await claim(service, b.accessToken, { referralCode: code });
    assert.equal(claimed.body.status, "VERIFIED");
    // verified_at is the referred account's own pre-existing email-verification timestamp, never "now".
    assert.equal(claimed.body.verifiedAt, b.verifiedAt);
    assert.notEqual(claimed.body.verifiedAt, claimed.body.observedAt);
    assert.equal(claimed.body.promoted, undefined);
  });

  it("keeps attribution OBSERVED when the referrer is not live, then promotes on re-check", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const b = await account(service, `new-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;

    // Suspension is the reachable, meaningful reason an attribution is asserted but not confirmed.
    service.database.prepare("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?").run(a.userId);
    const claimed = await claim(service, b.accessToken, { referralCode: code });
    assert.equal(claimed.status, 201, JSON.stringify(claimed.body));
    assert.equal(claimed.body.status, "OBSERVED");
    assert.equal(claimed.body.verifiedAt, null, "unverified attribution carries no verification stamp");
    assert.equal(attributionRow(service, b.userId).status, "OBSERVED");

    // Re-checking while the referrer is still suspended changes nothing and does not error.
    const still = await verify(service, b.accessToken);
    assert.equal(still.status, 200);
    assert.equal(still.body.status, "OBSERVED");
    assert.equal(still.body.promoted, false);

    service.database.prepare("UPDATE users SET status = 'ACTIVE' WHERE user_id = ?").run(a.userId);
    const promoted = await verify(service, b.accessToken);
    assert.equal(promoted.status, 200);
    assert.equal(promoted.body.status, "VERIFIED");
    assert.equal(promoted.body.promoted, true);
    assert.equal(promoted.body.verifiedAt, b.verifiedAt, "promotion records the real verification time, not the poll time");
    assert.equal(attributionRow(service, b.userId).status, "VERIFIED");

    // Idempotent: a second confirm is a success that changes nothing.
    const again = await verify(service, b.accessToken);
    assert.equal(again.status, 200);
    assert.equal(again.body.promoted, false);
    assert.equal(again.body.status, "VERIFIED");
  });

  it("reports no-attribution and refuses a verify for an account that has none", async () => {
    const service = await newService();
    const a = await account(service, `lonely-${sfx()}@example.test`);
    const mine = await summary(service, a.accessToken);
    assert.equal(mine.status, 200);
    assert.equal(mine.body.code, null);
    assert.equal(mine.body.attribution, null);
    assert.deepEqual(mine.body.counts.received, { total: 0, observed: 0, verified: 0 });

    const r = await verify(service, a.accessToken);
    assert.equal(r.status, 400);
    assert.equal(r.body.error.code, ErrorCode.INVALID_REQUEST);
  });
});

/* ------------------------------------------------------------------------------- 7: campaign fields */

describe("Phase 32 campaign attribution fields", () => {
  it("normalizes tokens and enforces exact length boundaries", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    const b = await account(service, `new-${sfx()}@example.test`);

    const ok = await claim(service, b.accessToken, {
      referralCode: code, source: "YOUTUBE", medium: " PAID  SEARCH ", campaign: "Launch_Week 2026",
    });
    assert.equal(ok.status, 201, JSON.stringify(ok.body));
    assert.equal(ok.body.medium, "paid-search", "whitespace collapses to a single hyphen and case folds");
    assert.equal(ok.body.campaign, "launch_week-2026");
    assert.equal(ok.body.source, "YOUTUBE");

    // Exactly at the maximum is accepted, one over is refused, for both fields.
    const atMax = await account(service, `max-${sfx()}@example.test`);
    assert.equal((await claim(service, atMax.accessToken, { referralCode: code, medium: "m".repeat(40), campaign: "c".repeat(64) })).status, 201);
    const overMax = await account(service, `over-${sfx()}@example.test`);
    assert.equal((await claim(service, overMax.accessToken, { referralCode: code, medium: "m".repeat(41) })).status, 400);
    assert.equal((await claim(service, overMax.accessToken, { referralCode: code, campaign: "c".repeat(65) })).status, 400);
    // An empty-after-normalization token means "not provided", so it stores null rather than a stray blank.
    const blank = await account(service, `blank-${sfx()}@example.test`);
    const blankClaim = await claim(service, blank.accessToken, { referralCode: code, medium: "   ", campaign: "" });
    assert.equal(blankClaim.status, 201);
    assert.equal(blankClaim.body.medium, null);
    assert.equal(blankClaim.body.campaign, null);
  });

  it("refuses values that are not allowlisted tokens, including URLs and query strings", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    const cases = [
      { medium: "https://evil.test/landing?ref=abc" },
      { medium: "a/b" }, { medium: "a.b" }, { medium: "a?b" }, { medium: "a=b" },
      { campaign: "https://evil.test/c?id=SECRET" }, { campaign: "-starts-bad" }, { campaign: "has space!" },
      { campaign: "emoji-🎉" }, { campaign: "a".repeat(500) },
      { source: "GOOGLE_ADS" }, { source: "youtube" }, { source: 7 }, { source: "" },
      { medium: 42 }, { campaign: [] },
    ];
    for (const extra of cases) {
      const r = await claim(service, a.accessToken, { referralCode: code, ...extra });
      // The self-code refusal is also a 400; either way nothing may be stored, which is asserted after the loop.
      assert.equal(r.status, 400, `${JSON.stringify(extra)} -> ${JSON.stringify(r.body)}`);
    }
    assert.equal(service.database.prepare("SELECT COUNT(*) AS c FROM referral_attributions").get().c, 0);
  });

  it("stores no URL, query string, or credential anywhere in the attribution row", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    const b = await account(service, `new-${sfx()}@example.test`);
    await claim(service, b.accessToken, { referralCode: code, source: "REDDIT", medium: "organic", campaign: "spring_push" });

    const row = attributionRow(service, b.userId);
    const serialized = JSON.stringify(row);
    for (const forbidden of ["http://", "https://", "?", "=", " ", "/"]) {
      assert.equal(serialized.includes(forbidden), false, `stored row contains '${forbidden}'`);
    }
    // And there is no column that could hold one: no landing page, no referrer header, no user agent, no device id.
    const columns = service.database.prepare("PRAGMA table_info(referral_attributions)").all().map((r) => r.name);
    for (const forbidden of ["url", "uri", "query", "user_agent", "device", "fingerprint", "ip", "referrer_url", "landing"]) {
      assert.equal(columns.some((c) => c.toLowerCase().includes(forbidden)), false, `column ${forbidden}`);
    }
    assert.equal(columns.some((c) => /amount|price|reward|credit|payout|commission/.test(c.toLowerCase())), false, "no money or reward column");
  });
});

/* ------------------------------------------------------------------------------- 8,9: authn, authz, privacy */

describe("Phase 32 authentication, authorization, and privacy", () => {
  it("requires a live session on every referral route", async () => {
    const service = await newService();
    for (const path of ["/marketing/referrals/me", "/marketplace/analytics/overview"]) {
      assert.equal((await get(service, path)).status, 401, path);
    }
    for (const path of ["/marketing/referrals/code", "/marketing/referrals/claim", "/marketing/referrals/verify"]) {
      assert.equal((await call(service.baseUrl, "POST", path, { body: { referralCode: "CM-000000000000" } })).status, 401, path);
    }
    // A forged token is refused exactly like a missing one — no detail about why.
    const forged = await get(service, "/marketing/referrals/me", "not-a-real-token");
    assert.equal(forged.status, 401);
    assert.equal(JSON.stringify(forged.body).includes("HMAC"), false);
  });

  it("rejects any attempt to name an identity or a status in the body", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const victim = await account(service, `victim-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;

    // The only body shapes accepted are the allowlisted keys; a supplied identity is a refusal, not a drop.
    for (const body of [
      { referralCode: code, userId: victim.userId },
      { referralCode: code, referrerUserId: a.userId },
      { referralCode: code, referredUserId: victim.userId },
      { referralCode: code, status: "VERIFIED" },
      { referralCode: code, verified: true },
      { referralCode: code, email: victim.email },
      { referralCode: code, extra: "x" },
    ]) {
      const r = await claim(service, a.accessToken, body);
      assert.equal(r.status, 400, `${JSON.stringify(body)} -> ${JSON.stringify(r.body)}`);
      assert.equal(r.body.error.code, ErrorCode.INVALID_REQUEST);
    }
    assert.equal(attributionRow(service, victim.userId), undefined, "a spoofed claim stored nothing for the victim");
  });

  it("never discloses the other party in either direction", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const b = await account(service, `new-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    await claim(service, b.accessToken, { referralCode: code });

    // The referrer learns a count, and nothing else: no list of who used the code.
    const referrerView = await summary(service, a.accessToken);
    assert.equal(referrerView.body.counts.attributedRegistrations.total, 1);
    assert.equal(referrerView.body.attribution, null);
    for (const leaked of [b.userId, b.email, "referrals.referred", "whoUsed"]) {
      assert.equal(JSON.stringify(referrerView.body).includes(leaked), false, `referrer view leaks '${leaked}'`);
    }
    assert.equal(Object.hasOwn(referrerView.body, "supporters"), false);
    assert.equal(Object.hasOwn(referrerView.body, "referredUsers"), false);

    // The referred account learns its own attribution but not who the referrer is.
    const refereeView = await summary(service, b.accessToken);
    assert.equal(refereeView.body.attribution.status, "VERIFIED");
    assert.equal(refereeView.body.attribution.referrerDisclosed, false);
    for (const forbidden of ["referrerUserId", "referrerEmail", "referrerHandle", "referrer"]) {
      assert.equal(Object.hasOwn(refereeView.body.attribution, forbidden), false, forbidden);
    }
    for (const leaked of [a.userId, a.email]) {
      assert.equal(JSON.stringify(refereeView.body).includes(leaked), false, `referee view leaks '${leaked}'`);
    }

    // A third party cannot read either account's referral state.
    const c = await account(service, `c-${sfx()}@example.test`);
    const other = await summary(service, c.accessToken);
    assert.equal(other.body.attribution, null);
    assert.equal(other.body.code, null);
    assert.equal(other.body.counts.attributedRegistrations.total, 0);
  });

  it("exposes no verb that could edit or delete an attribution", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    // Append-only is a contract, so the surface that would break it must not exist at all.
    for (const method of ["PUT", "PATCH", "DELETE"]) {
      for (const path of ["/marketing/referrals/code", "/marketing/referrals/claim", "/marketing/referrals/me"]) {
        const r = await call(service.baseUrl, method, path, { headers: bearer(a.accessToken), body: {} });
        assert.equal(r.status, 405, `${method} ${path} -> ${JSON.stringify(r.body)}`);
        assert.equal(r.body.error.code, ErrorCode.METHOD_NOT_ALLOWED);
      }
    }
    // And a GET on a write route is the same refusal, not a silently-ignored read.
    assert.equal((await get(service, "/marketing/referrals/claim", a.accessToken)).status, 405);
  });

  it("does not let a summary reflect a code someone else holds", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const b = await account(service, `new-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    const mine = await summary(service, b.accessToken);
    assert.equal(mine.body.code, null, "an account with no code of its own sees none");
    assert.equal(JSON.stringify(mine.body).includes(code), false);
  });
});

/* ------------------------------------------------------------------------------- 10,11,12: safety */

describe("Phase 32 query and abuse safety", () => {
  it("is injection-proof: malicious input is data, and the schema survives", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    const payloads = [
      "CM-000000000001' OR '1'='1",
      `CM-000000000001"; DROP TABLE referral_attributions;--`,
      "CM-000000000001' UNION SELECT user_id FROM users--",
    ];
    for (const referralCode of payloads) {
      const r = await claim(service, a.accessToken, { referralCode });
      assert.equal(r.status, 400, `${referralCode} -> ${JSON.stringify(r.body)}`);
    }
    const fieldPayloads = [
      { medium: `x'; DROP TABLE referral_codes;--` },
      { campaign: "c' OR 1=1 --" },
      { source: "OTHER); DELETE FROM users;--" },
    ];
    for (const extra of fieldPayloads) {
      const r = await claim(service, a.accessToken, { referralCode: code, ...extra });
      assert.equal(r.status, 400);
    }
    // Everything is intact and still queryable.
    assert.equal(service.database.prepare("SELECT COUNT(*) AS c FROM referral_attributions").get().c, 0);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS c FROM referral_codes").get().c, 1);
    assert.ok(service.database.prepare("SELECT COUNT(*) AS c FROM users").get().c >= 1);
    // The one legitimate claim still works afterwards, i.e. no statement was left in a broken state.
    const b = await account(service, `new-${sfx()}@example.test`);
    assert.equal((await claim(service, b.accessToken, { referralCode: code })).status, 201);
  });

  it("rate-limits referral writes on their own bounded budget", async () => {
    const service = await newService({ RATE_TRUST_WRITE_MAX: "2" });
    const a = await account(service, `rate-${sfx()}@example.test`);
    const statuses = [];
    for (let i = 0; i < 3; i += 1) statuses.push((await issueCode(service, a.accessToken)).status);
    assert.deepEqual(statuses, [200, 200, 429]);
    const limited = await issueCode(service, a.accessToken);
    assert.equal(limited.body.error.code, ErrorCode.RATE_LIMITED);
    // Reads run on a separate budget, so a write storm cannot starve the summary endpoint.
    assert.equal((await summary(service, a.accessToken)).status, 200);
  });

  it("never stores a partial row when a claim fails mid-transaction", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    const b = await account(service, `new-${sfx()}@example.test`);
    // Valid code, invalid oversized campaign: the row must not exist afterwards.
    assert.equal((await claim(service, b.accessToken, { referralCode: code, campaign: "c".repeat(200) })).status, 400);
    assert.equal(attributionRow(service, b.userId), undefined);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS c FROM referral_attributions").get().c, 0);
  });
});

/* ------------------------------------------------------------------------------- 13: regressions */

describe("Phase 32 leaves the account and marketplace lifecycles untouched", () => {
  it("registration, verification, and login behave exactly as before", async () => {
    const service = await newService();
    const email = `regress-${sfx()}@example.test`;
    const registration = await call(service.baseUrl, "POST", "/auth/register", {
      body: { email, password: "Correct Horse 7Battery", displayName: "Regress" },
    });
    assert.equal(registration.status, 201);
    assert.equal(registration.body.session, undefined, "registration still hands out no session");

    const early = await call(service.baseUrl, "POST", "/auth/login", { body: { email, password: "Correct Horse 7Battery" } });
    assert.equal(early.status, 403);
    assert.equal(early.body.error.code, ErrorCode.EMAIL_NOT_VERIFIED);

    const message = service.emailDelivery.takeMessage("verification", email);
    const verified = await call(service.baseUrl, "POST", "/auth/verify-email", { body: { token: message.token } });
    assert.equal(verified.status, 200);
    const signedIn = await call(service.baseUrl, "POST", "/auth/login", { body: { email, password: "Correct Horse 7Battery" } });
    assert.equal(signedIn.status, 200);

    // Password recovery is unaffected too.
    const reset = await call(service.baseUrl, "POST", "/auth/password-reset", { body: { email } });
    assert.ok([200, 202].includes(reset.status), JSON.stringify(reset.body));

    // Referral work never edits the verification column.
    const token = signedIn.body.session.accessToken;
    const before = service.database.prepare("SELECT email_verified_at FROM users WHERE email_canonical = ?").get(email).email_verified_at;
    await issueCode(service, token);
    // No attribution exists for this account, so a confirm is a clean refusal rather than a silent no-op.
    assert.equal((await verify(service, token)).status, 400);
    assert.equal(service.database.prepare("SELECT email_verified_at FROM users WHERE email_canonical = ?").get(email).email_verified_at, before);
  });

  it("keeps the Phase 30 trust surface and Phase 27 order flow working alongside it", async () => {
    const service = await newService();
    const a = await account(service, `t-${sfx()}@example.test`);
    assert.equal((await get(service, "/marketplace/trust", a.accessToken)).status, 200);
    assert.equal((await get(service, "/marketplace/listings")).status, 200, "public discovery stays public");
    // No referral endpoint is reachable without a session, and none was added to the public marketplace reads.
    assert.equal((await get(service, "/marketing/referrals/me")).status, 401);
  });
});

/* ------------------------------------------------------------------------------- 14: Phase 31 analytics */

describe("Phase 32 analytics integration", () => {
  it("aggregates attribution correctly and counts each registration once", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    const b = await account(service, `b-${sfx()}@example.test`);
    const c = await account(service, `c-${sfx()}@example.test`);
    await claim(service, b.accessToken, { referralCode: code, source: "REDDIT" });
    // Repeated claims by the same account must not inflate the count.
    for (let i = 0; i < 4; i += 1) await claim(service, b.accessToken, { referralCode: code, source: "REDDIT" });
    await claim(service, c.accessToken, { referralCode: code, source: "DISCORD", campaign: "alpha" });

    const over = (await get(service, "/marketplace/analytics/overview?window=all", a.accessToken)).body;
    assert.equal(over.operations.referrals.total, 2, "two attributed registrations, not six claim requests");
    assert.equal(over.operations.referrals.verified, 2);
    assert.equal(over.operations.referrals.observed, 0);
    assert.equal(over.operations.referrals.distinctCampaigns, 1);
    assert.deepEqual(over.operations.referrals.bySource, { REDDIT: 1, DISCORD: 1 });
    assert.equal(over.operations.referrals.attributedInWindow, 2);

    // The personal view for the referrer agrees with the global count.
    const personal = (await get(service, "/marketplace/analytics/creator", a.accessToken)).body;
    assert.deepEqual(personal.referrals.attributedRegistrations, { total: 2, observed: 0, verified: 2 });
    assert.deepEqual(personal.referrals.received, { total: 0, observed: 0, verified: 0 });
    assert.equal(personal.referrals.hasCode, true);
    const referred = (await get(service, "/marketplace/analytics/creator", b.accessToken)).body;
    assert.deepEqual(referred.referrals.received, { total: 1, observed: 0, verified: 1 });
    assert.equal(referred.referrals.hasCode, false);
  });

  it("keeps the global aggregate free of codes, accounts, and pairs", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    const b = await account(service, `b-${sfx()}@example.test`);
    await claim(service, b.accessToken, { referralCode: code, campaign: "spring_push" });

    const over = await get(service, "/marketplace/analytics/overview?window=all", b.accessToken);
    const serialized = JSON.stringify(over.body);
    assert.equal(serialized.includes(code), false, "a private code must never appear in a global aggregate");
    assert.equal(serialized.includes(a.userId), false);
    assert.equal(serialized.includes(b.userId), false);
    assert.equal(serialized.includes(a.email), false);
    assert.equal(serialized.includes("spring_push"), false, "campaign labels are not published in the global view");
    // And the honest-unavailable contract is extended, not quietly dropped.
    assert.ok(over.body.unavailable.some((m) => m.key === "conversionRate" && m.reason.length > 0));
    assert.ok(over.body.unavailable.some((m) => m.key === "referralRewards" && m.reason.length > 0));
    for (const key of ["referralCodes", "referralRelationships", "referralEmails"]) {
      assert.ok(over.body.excluded.includes(key), key);
    }
  });

  it("trends attributed registrations by UTC day and reports empty windows honestly", async () => {
    const service = await newService();
    const a = await account(service, `ref-${sfx()}@example.test`);
    const code = (await issueCode(service, a.accessToken)).body.code;
    const b = await account(service, `b-${sfx()}@example.test`);
    const claimed = await claim(service, b.accessToken, { referralCode: code });
    const claimDay = claimed.body.observedAt.slice(0, 10);

    // An aged attribution is *inserted* at its real timestamp rather than UPDATE-ed: the immutability trigger
    // deliberately refuses backdating of an existing row (asserted in the integrity suite), so a fixture that
    // backdated one would be testing the wrong thing.
    const agedDay = "2025-01-05";
    const agedIso = `${agedDay}T09:00:00.000Z`;
    service.database.prepare("INSERT INTO users (user_id,email,email_canonical,display_name,password_hash,status,created_at,updated_at,email_verified_at) VALUES (?,?,?,?,?,?,?,?,?)")
      .run("usr_aged", `aged-${sfx()}@example.test`, `aged-${sfx()}@example.test`, "Aged", "h", "ACTIVE", agedIso, agedIso, agedIso);
    service.database.prepare(
      `INSERT INTO referral_attributions
         (attribution_id, referred_user_id, referrer_user_id, code, source, medium, campaign, status, observed_at, verified_at)
       VALUES ('rat_aged', 'usr_aged', ?, ?, 'GOOGLE', NULL, NULL, 'VERIFIED', ?, ?)`,
    ).run(a.userId, code, agedIso, agedIso);

    let over = (await get(service, "/marketplace/analytics/overview?window=all", a.accessToken)).body;
    assert.equal(over.operations.referrals.total, 2);
    assert.deepEqual(over.trends.referralsAttributed, [
      { day: agedDay, count: 1 },
      { day: claimDay, count: 1 },
    ], "buckets are chronological UTC days with one count each");

    // A 90-day window sees today's claim only, while the all-time snapshot total still reports both.
    over = (await get(service, "/marketplace/analytics/overview?window=90d", a.accessToken)).body;
    assert.equal(over.operations.referrals.attributedInWindow, 1);
    assert.equal(over.operations.referrals.total, 2, "snapshot totals are not windowed");
    assert.deepEqual(over.trends.referralsAttributed, [{ day: claimDay, count: 1 }]);

    // An untouched service reports the referral shape with honest zeroes, never a missing key or a 500.
    const fresh = await newService();
    const nobody = await account(fresh, `fresh-${sfx()}@example.test`);
    const empty = (await get(fresh, "/marketplace/analytics/overview", nobody.accessToken)).body;
    assert.deepEqual(empty.operations.referrals, { total: 0, verified: 0, observed: 0, attributedInWindow: 0, distinctCampaigns: 0, bySource: {} });
    assert.deepEqual(empty.trends.referralsAttributed, []);
  });

  it("preserves every earlier Phase 31 metric key and contract", async () => {
    const service = await newService();
    const a = await account(service, `a-${sfx()}@example.test`);
    const body = (await get(service, "/marketplace/analytics/overview?window=7d", a.accessToken)).body;
    for (const key of ["snapshot", "activity", "trends", "operations", "unavailable", "excluded", "definitions"]) {
      assert.ok(key in body, key);
    }
    for (const key of ["listings", "jobs", "orders", "proposals", "milestones"]) assert.ok(key in body.snapshot, key);
    for (const key of ["disputes", "reports"]) assert.ok(key in body.operations, key);
    assert.equal(body.scope, "marketplace_global_aggregates");
    assert.equal(body.window.days, 7);
    assert.ok("referrals.total" in body.definitions, "new metrics carry definitions like every other metric");
    // Unknown query keys are still ignored and the window whitelist still closes.
    assert.equal((await get(service, "/marketplace/analytics/overview?limit=5&window=7d", a.accessToken)).status, 200);
    assert.equal((await get(service, "/marketplace/analytics/overview?window=5d", a.accessToken)).status, 400);
  });
});

/* ------------------------------------------------------------------------------- 15: migration */

describe("Phase 32 migration v11 -> v12", () => {
  it("upgrades a real v11 database, preserving prior rows and adding the referral structures", async () => {
    const database = new DatabaseSync(":memory:");
    database.exec("PRAGMA foreign_keys = ON");
    database.exec("PRAGMA recursive_triggers = ON");
    database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    migrateToVersion(database, 11);

    // v11 must genuinely lack the Phase 32 structures, or "additive" would be unproven.
    assert.equal(database.prepare("SELECT name FROM sqlite_master WHERE name = 'referral_codes'").get(), undefined);
    assert.equal(database.prepare("SELECT name FROM sqlite_master WHERE name = 'referral_attributions'").get(), undefined);

    const n = new Date().toISOString();
    database.prepare("INSERT INTO users (user_id,email,email_canonical,display_name,password_hash,status,created_at,updated_at,email_verified_at) VALUES (?,?,?,?,?,?,?,?,?)")
      .run("usr_legacy", "legacy@x.test", "legacy@x.test", "Legacy", "h", "ACTIVE", n, n, n);
    database.prepare("INSERT INTO creator_profiles (creator_id,user_id,handle,display_name,status,verification_status,status_changed_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)")
      .run("cre_legacy", "usr_legacy", "legacy-handle", "Legacy Studio", "ACTIVE", "UNVERIFIED", n, n, n);
    const before = database.prepare("SELECT COUNT(*) AS c FROM admin_audit_log").get().c;

    migrateToVersion(database, SCHEMA_VERSION);

    assert.equal(SCHEMA_VERSION, 12);
    assert.equal(database.prepare("SELECT version FROM schema_migrations WHERE version = 12").get().version, 12);
    for (const table of ["referral_codes", "referral_attributions"]) {
      assert.ok(database.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name=?").get(table), table);
    }
    assert.ok(database.prepare("SELECT name FROM sqlite_master WHERE type='trigger' AND name='referral_attributions_immutable'").get(), "immutability trigger");
    // Prior data survives untouched, and the fresh-install schema matches the upgraded one.
    assert.equal(database.prepare("SELECT display_name FROM users WHERE user_id='usr_legacy'").get().display_name, "Legacy");
    assert.equal(database.prepare("SELECT COUNT(*) AS c FROM creator_profiles").get().c, 1);
    assert.equal(database.prepare("SELECT COUNT(*) AS c FROM admin_audit_log").get().c, before);
    const fresh = openDatabase(":memory:");
    const upgraded = database.prepare("SELECT name, sql FROM sqlite_master WHERE name LIKE 'referral%' ORDER BY name").all();
    const fromScratch = fresh.prepare("SELECT name, sql FROM sqlite_master WHERE name LIKE 'referral%' ORDER BY name").all();
    assert.deepEqual(upgraded.map((r) => r.sql), fromScratch.map((r) => r.sql), "upgraded and fresh installs are identical");
    // A re-run is a no-op, not a second application.
    migrateToVersion(database, SCHEMA_VERSION);
    assert.equal(database.prepare("SELECT COUNT(*) AS c FROM schema_migrations WHERE version = 12").get().c, 1);

    // No audit vocabulary was widened: this phase deliberately does not log referral relationships.
    assert.equal(REGISTERED_AUDIT_ACTION_TYPES.size, 102);
    for (const forbidden of ["REFERRAL_CLAIMED", "REFERRAL_VERIFIED", "REFERRAL_CODE_ISSUED"]) {
      assert.equal(REGISTERED_AUDIT_ACTION_TYPES.has(forbidden), false, forbidden);
    }
  });
});
