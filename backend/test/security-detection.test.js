/**
 * Phase 20 detection, risk scoring, autonomous response, and the deterministic attack simulations.
 *
 * Every scenario is local and deterministic: signals are driven through the security engine on an injectable clock, or
 * through the real HTTP surface. No external system is contacted and no real-world target is tested.
 */

import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { call, registerVerified, startService, VALID_PASSWORD } from "./helpers.js";
import {
  ACCOUNT_A,
  SOURCE_A,
  actionRows,
  auditRows,
  databaseText,
  driveSignals,
  hexReference,
  incidentRows,
  notificationRows,
  protectionRows,
  securityDatabaseText,
  startSecurityService,
} from "./security-support.js";

const services = new Set();

async function newService(overrides = {}, options = {}) {
  const service = await startSecurityService(overrides, options);
  services.add(service);
  const close = service.close.bind(service);
  service.close = async () => { services.delete(service); await close(); };
  return service;
}

afterEach(async () => { await Promise.all([...services].map((service) => service.close())); });

const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x6d).toString("base64url");
const DEVELOPER_EMAIL = "security-owner@example.test";
const DEVELOPER_PASSWORD = "Security Owner 9Pass";

async function createOwnerSession(service) {
  await call(service.baseUrl, "POST", "/developer/auth/bootstrap", {
    body: { secret: BOOTSTRAP_SECRET, password: DEVELOPER_PASSWORD },
  });
  const login = await call(service.baseUrl, "POST", "/developer/auth/login", {
    body: { email: DEVELOPER_EMAIL, password: DEVELOPER_PASSWORD },
  });
  assert.equal(login.status, 200, JSON.stringify(login.body));
  return login.body.session.accessToken;
}

describe("Phase 20 attack simulations and autonomous response", () => {
  it("SIMULATION 1 — 30 failed logins in a bounded interval produce one CRITICAL incident, immediate protection, an alert, and audit entries", async () => {
    const service = await newService();
    const source = service.engine.sourceDigestFor({ socket: { remoteAddress: "127.0.0.1" } });
    const outcomes = driveSignals(service.engine, {
      eventType: "AUTHENTICATION_FAILED", count: 30, accountReference: ACCOUNT_A, sourceReference: source, result: "FAILURE",
    });
    const elevated = outcomes.filter((outcome) => outcome.level === "CRITICAL");
    assert.ok(elevated.length >= 1, "the threshold crossing must raise a critical assessment");
    assert.equal(elevated.at(-1).threatCategory, "BRUTE_FORCE");

    const incidents = incidentRows(service);
    assert.equal(incidents.length, 1, "one deduplicated incident, not thirty");
    const incident = incidents[0];
    assert.equal(incident.severity, "CRITICAL");
    assert.equal(incident.threat_category, "BRUTE_FORCE");
    assert.equal(incident.status, "CONTAINED");
    assert.match(incident.reference, /^SEC-[0-9A-F]{10}$/);
    assert.ok(incident.risk_score >= 75);
    assert.ok(incident.event_count >= 25);
    const reasons = JSON.parse(incident.detection_reasons_json);
    assert.ok(reasons.some((reason) => /authentication failures in 2 minutes/.test(reason)), JSON.stringify(reasons));
    assert.ok(reasons.length <= 6);

    const protections = protectionRows(service);
    const sourceDeny = protections.find((row) => row.scope === "SOURCE" && row.mode === "DENY");
    const accountThrottle = protections.find((row) => row.scope === "ACCOUNT" && row.mode === "THROTTLE");
    assert.ok(sourceDeny, "the abusive request source is rejected immediately");
    assert.equal(sourceDeny.category, "USER_AUTH");
    assert.ok(accountThrottle, "the targeted account is throttled (progressive, expiring, never a lockout)");
    assert.ok(accountThrottle.maximum >= 1);
    assert.ok(Date.parse(sourceDeny.expires_at) > Date.parse(sourceDeny.applied_at));

    const notifications = notificationRows(service);
    assert.equal(notifications.length, 1);
    assert.equal(notifications[0].priority, "CRITICAL");
    assert.equal(notifications[0].incident_id, incident.incident_id);
    assert.ok(notifications[0].title.includes("BRUTE_FORCE"));
    assert.ok(notifications[0].body.includes(incident.reference));
    assert.equal(notifications[0].status, "UNREAD");

    const audits = auditRows(service);
    const actionTypes = audits.map((row) => row.action_type);
    for (const expected of ["SECURITY_INCIDENT_CREATED", "SECURITY_RATE_LIMIT_APPLIED", "SECURITY_ALERT_CREATED"]) {
      assert.ok(actionTypes.includes(expected), `${expected} must be audited`);
    }
    for (const row of audits) {
      assert.equal(row.actor_kind, "SYSTEM_SECURITY");
      assert.equal(row.actor_developer_id, null, "an automated action is never attributed to a developer identity");
      assert.equal(row.incident_id, incident.incident_id);
      assert.equal(row.outcome, "SUCCESS");
    }
    assert.equal(
      service.database.prepare("SELECT COUNT(*) AS count FROM developer_action_confirmations").get().count,
      0,
      "no developer confirmation is consumed, prepared, or required for the automated protection",
    );

    // The very next request from that source is rejected with the existing typed rate-limit response.
    const blocked = await call(service.baseUrl, "POST", "/auth/login", {
      body: { email: "someone@example.test", password: VALID_PASSWORD },
    });
    assert.equal(blocked.status, 429);
    assert.equal(blocked.body.error.code, "RATE_LIMITED");
    assert.ok(Number(blocked.headers.get("retry-after")) >= 1);
  });

  it("SIMULATION 4 — a benign occasional failure is monitored, never blocked or escalated", async () => {
    const service = await newService();
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 1, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });
    driveSignals(service.engine, { eventType: "AUTHENTICATION_SUCCEEDED", count: 1, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "SUCCESS" });
    assert.equal(incidentRows(service).length, 0);
    assert.equal(protectionRows(service).length, 0);
    assert.equal(notificationRows(service).length, 0);
    assert.equal(actionRows(service).length, 0);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM security_events").get().count, 2);
    // Even just below the medium threshold nothing escalates (4 failures in the window, medium starts at 5).
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 3, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });
    assert.equal(incidentRows(service).length, 0);
    assert.equal(protectionRows(service).length, 0);
  });

  it("SIMULATION 5 — with no AI provider at all, detection and immediate protection still work", async () => {
    // No developerAiProvider is injected anywhere: the security path must not depend on one.
    const service = await newService({
      CRAFTMIND_DEV_BOOTSTRAP_EMAIL: DEVELOPER_EMAIL,
      CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
    }, { developerAiProvider: null });
    assert.equal(service.engine.overview().engine.aiDependent, false);
    driveSignals(service.engine, {
      eventType: "AUTHENTICATION_FAILED", count: 30, accountReference: ACCOUNT_B_REFERENCE(), sourceReference: SOURCE_A, result: "FAILURE",
    });
    assert.equal(incidentRows(service).length, 1);
    assert.equal(incidentRows(service)[0].severity, "CRITICAL");
    assert.ok(protectionRows(service).some((row) => row.mode === "DENY"));
    assert.equal(notificationRows(service).length, 1);

    // The AI endpoints report unavailability honestly instead of failing the security path.
    const owner = await createOwnerSession(service);
    const status = await call(service.baseUrl, "GET", "/developer/ai/status", { headers: { Authorization: `Bearer ${owner}` } });
    assert.equal(status.status, 200);
    assert.equal(status.body.available, false);
    const summary = await call(service.baseUrl, "POST", "/developer/ai/security-summary", {
      headers: { Authorization: `Bearer ${owner}` }, body: { prompt: "Summarize the recent security posture." },
    });
    assert.equal(summary.status, 503);
    assert.equal(summary.body.error.code, "DEVELOPER_AI_UNAVAILABLE");
    // The Security Center itself keeps working without AI.
    const incidents = await call(service.baseUrl, "POST", "/developer/tools/invoke", {
      headers: { Authorization: `Bearer ${owner}` }, body: { tool: "listSecurityIncidents", arguments: { limit: 10 } },
    });
    assert.equal(incidents.status, 200);
    assert.equal(incidents.body.result.incidents.length, 1);
  });

  it("MEDIUM escalation applies only bounded, progressive measures", async () => {
    const service = await newService();
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 6, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });
    const incidents = incidentRows(service);
    assert.equal(incidents.length, 1);
    assert.equal(incidents[0].severity, "MEDIUM");
    assert.equal(incidents[0].threat_category, "BRUTE_FORCE");
    const protections = protectionRows(service);
    assert.ok(protections.length >= 1);
    assert.equal(protections.some((row) => row.mode === "DENY"), false, "a medium signal must not hard-reject a source");
    assert.equal(protections.some((row) => row.scope === "ACCOUNT" && row.maximum > 1), true);
    assert.equal(notificationRows(service).length, 0, "medium risk raises no high-priority alert");
    assert.equal(actionRows(service).length >= 1, true);
  });

  it("RATE LIMIT ABUSE escalates to a temporary source rejection and an incident", async () => {
    const service = await newService();
    driveSignals(service.engine, { eventType: "RATE_LIMIT_VIOLATION", count: 15, sourceReference: SOURCE_A, result: "THROTTLED" });
    const incidents = incidentRows(service);
    assert.equal(incidents.length, 1);
    assert.equal(incidents[0].threat_category, "RATE_LIMIT_ABUSE");
    assert.equal(incidents[0].severity, "CRITICAL");
    const denied = protectionRows(service).find((row) => row.mode === "DENY");
    assert.ok(denied);
    assert.equal(denied.scope, "SOURCE");
    // Bounded and temporary by construction.
    const duration = Date.parse(denied.expires_at) - Date.parse(denied.applied_at);
    assert.ok(duration <= service.configuration.security.response.maximumProtectionSeconds * 1000);
    assert.ok(duration >= 1000);
  });

  it("UNAUTHORIZED ACCESS escalation throttles the actor and rejects the source without touching authorization", async () => {
    const service = await newService();
    driveSignals(service.engine, {
      eventType: "UNAUTHORIZED_ACCESS_ATTEMPT", count: 20, accountReference: `dvl_${"1".repeat(8)}-1111-1111-1111-111111111111`, sourceReference: SOURCE_A, result: "DENIED",
    });
    const incident = incidentRows(service)[0];
    assert.equal(incident.threat_category, "UNAUTHORIZED_ACCESS");
    assert.equal(incident.severity, "CRITICAL");
    assert.equal(incident.status, "CONTAINED");
    assert.ok(protectionRows(service).some((row) => row.scope === "ACCOUNT" && row.mode === "THROTTLE"));
    assert.ok(protectionRows(service).some((row) => row.scope === "SOURCE" && row.mode === "DENY"));
    // No privilege is ever granted or revoked by the security system: only developer tools change authorization.
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM developer_access_grants").get().count, 0);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM users").get().count, 0);
  });

  it("CONFIRMATION ABUSE escalates from throttling to a temporary source rejection", async () => {
    const service = await newService();
    driveSignals(service.engine, { eventType: "CONFIRMATION_ATTEMPT_INVALID", count: 12, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "INVALID" });
    const incident = incidentRows(service)[0];
    assert.equal(incident.threat_category, "CONFIRMATION_ABUSE");
    assert.equal(incident.severity, "CRITICAL");
    assert.ok(protectionRows(service).some((row) => row.category === "CONFIRMATION"));
  });

  it("SESSION ABUSE revokes the suspicious session and records the protection attempt", async () => {
    const service = await newService();
    const session = await registerVerified(service, { email: "session-abuse@example.test" });
    const accessToken = session.body.session.accessToken;
    await call(service.baseUrl, "POST", "/auth/logout", { body: { accessToken } });
    const classification = service.engine.classifySessionFailure({ kind: "user", token: accessToken });
    assert.equal(classification.state, "REVOKED");

    driveSignals(service.engine, {
      eventType: "SESSION_REVOKED_REUSE", count: 25, sessionReference: classification.sessionReference, accountReference: classification.accountReference, sourceReference: SOURCE_A, result: "DENIED",
    });
    const incidents = incidentRows(service);
    assert.equal(incidents.length, 1);
    assert.equal(incidents[0].threat_category, "SESSION_ABUSE");
    assert.equal(incidents[0].severity, "CRITICAL");
    assert.equal(incidents[0].status, "CONTAINED");
    const actions = actionRows(service);
    assert.ok(actions.some((row) => row.action_type === "SECURITY_SESSION_REVOKED"), "the session response is recorded");
    assert.ok(notificationRows(service).length >= 1);
    // Recorded reuse is rejected at the wire level for the whole class of stale credentials.
    const rejected = await call(service.baseUrl, "GET", "/auth/me", { headers: { Authorization: `Bearer ${accessToken}` } });
    assert.equal(rejected.status, 401);
  });

  it("REQUEST BURST from one source is detected through bounded request counting", async () => {
    const service = await newService();
    for (let index = 0; index < service.configuration.security.thresholds.requestBurst.medium; index += 1) {
      service.engine.noteRequest({ sourceReference: SOURCE_A, routeCategory: "auth_login" });
    }
    const bursts = service.database.prepare("SELECT * FROM security_events WHERE event_type = 'REQUEST_BURST'").all();
    assert.equal(bursts.length, 1);
    assert.equal(JSON.parse(bursts[0].metadata_json).requests, service.configuration.security.thresholds.requestBurst.medium);
    // Announced once per window: further requests do not create further signals.
    service.engine.noteRequest({ sourceReference: SOURCE_A, routeCategory: "auth_login" });
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM security_events WHERE event_type = 'REQUEST_BURST'").get().count, 1);
  });

  it("repeated identical signals escalate one incident instead of creating duplicates", async () => {
    const service = await newService();
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 6, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });
    const first = incidentRows(service)[0];
    assert.equal(first.severity, "MEDIUM");
    service.clock.advanceSeconds(3);
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 24, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });
    const incidents = incidentRows(service);
    assert.equal(incidents.length, 1, "the same deduplication key keeps one active incident");
    assert.equal(incidents[0].incident_id, first.incident_id);
    assert.equal(incidents[0].severity, "CRITICAL");
    assert.ok(incidents[0].event_count > first.event_count);
    assert.equal(incidents[0].status, "CONTAINED");
    assert.equal(
      service.database.prepare("SELECT COUNT(*) AS count FROM security_incidents WHERE dedup_key = ?").get(incidents[0].dedup_key).count,
      1,
    );
  });

  it("keeps every automated protection expiring and reversible, and its tracked state bounded", async () => {
    const service = await newService();
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 30, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });
    const active = protectionRows(service).filter((row) => row.released_at === null);
    assert.ok(active.length >= 2);
    const denied = active.find((row) => row.mode === "DENY");
    assert.equal(service.engine.activeProtection({ sourceReference: SOURCE_A, category: "USER_AUTH" }).mode, "DENY");

    // Explicit release through the policy-authorized tool path stops enforcement immediately.
    const released = service.engine.releaseProtection({ protectionId: denied.protection_id, reason: "verified benign after review" });
    assert.equal(released.result, "APPLIED");
    assert.equal(released.resultCode, "PROTECTION_RELEASED");
    assert.equal(service.engine.activeProtection({ sourceReference: SOURCE_A, category: "USER_AUTH" })?.mode ?? "NONE", "NONE");
    assert.ok(auditRows(service).some((row) => row.action_type === "SECURITY_PROTECTION_RELEASED"));

    // Expiry is the second reversibility path: nothing automated can outlive its window.
    service.clock.advanceMinutes(90);
    service.engine.releaseExpiredProtections();
    assert.equal(service.engine.activeProtection({ accountReference: ACCOUNT_A, category: "USER_AUTH" }), null);
    assert.equal(protectionRows(service).every((row) => row.released_at !== null), true);

    // In-memory tracking stays capped: 6000 distinct sources cannot grow process state without bound.
    for (let index = 0; index < 6000; index += 1) {
      service.engine.noteRequest({ sourceReference: hexReference(index.toString(16).padStart(4, "0")), routeCategory: "auth_login" });
    }
    const sizes = service.engine.trackedStateSizes();
    assert.ok(sizes.requestWindows <= sizes.capacity, `${sizes.requestWindows} must stay within ${sizes.capacity}`);
    assert.ok(sizes.deduplication <= sizes.capacity);
  });

  it("explains every elevated decision with structured, bounded reasons and never needs an opaque model", async () => {
    const service = await newService();
    // Two independent signal families against the same account: failures first, rate-limit violations alongside them,
    // then the burst of failures that escalates the incident while both families are still inside the window.
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 5, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });
    driveSignals(service.engine, { eventType: "RATE_LIMIT_VIOLATION", count: 12, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "THROTTLED" });
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 20, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });
    // Two families escalate here, and their detected_at instants are identical on a driven clock, so the ordering of
    // incident rows carries no meaning: select the brute-force incident explicitly.
    const incident = incidentRows(service).find((row) => row.threat_category === "BRUTE_FORCE");
    assert.ok(incident, "the brute-force incident must exist");
    const reasons = JSON.parse(incident.detection_reasons_json);
    assert.ok(Array.isArray(reasons) && reasons.length > 0);
    assert.ok(reasons.length <= service.configuration.security.bounds.reasonsPerIncident);
    for (const reason of reasons) {
      assert.equal(typeof reason, "string");
      assert.ok(reason.length > 0 && reason.length <= 200);
    }
    assert.ok(reasons.some((reason) => /25 authentication failures/.test(reason)));
    assert.ok(reasons.some((reason) => /concurrent/.test(reason)), JSON.stringify(reasons));
    assert.ok(incident.risk_score >= 0 && incident.risk_score <= 100);
  });

  it("never stores a password, token, or client address in any security record", async () => {
    const service = await newService();
    const session = await registerVerified(service, { email: "leak-check@example.test" });
    const refreshToken = session.body.session.refreshToken;
    await call(service.baseUrl, "POST", "/auth/refresh", { body: { refreshToken } });
    await call(service.baseUrl, "POST", "/auth/refresh", { body: { refreshToken } });
    await call(service.baseUrl, "POST", "/auth/login", { body: { email: "leak-check@example.test", password: "Wrong Password 9" } });
    // The account's own email legitimately lives in `users`; the security records and the audit trail must never
    // contain a credential, a token, or the attempted address.
    const text = securityDatabaseText(service);
    for (const secret of [VALID_PASSWORD, refreshToken, session.body.session.accessToken, "leak-check@example.test", "Wrong Password 9"]) {
      assert.equal(text.includes(secret), false, "no credential may appear in any stored row");
    }
    assert.equal(databaseText(service).includes("127.0.0.1"), false);
    assert.equal(service.logs.join("\n").includes("Wrong Password 9"), false);
  });
});

/** Late-bound helper so the simulation-5 account reference cannot collide with the shared fixture. */
function ACCOUNT_B_REFERENCE() {
  return `ref_${"d".repeat(40)}`;
}
