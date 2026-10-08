/**
 * Phase 20 response boundaries: who can invoke what, what the AI may return, and how the developer-facing Security
 * Center stays read-only. All fixtures are local; no external system is contacted.
 */

import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { call, registerVerified } from "./helpers.js";
import {
  ACCOUNT_A,
  SOURCE_A,
  actionRows,
  auditRows,
  driveSignals,
  incidentRows,
  notificationRows,
  protectionRows,
  startSecurityService,
} from "./security-support.js";
import { authorizeSecurityAction, decideSecurityResponse } from "../src/security-policy.js";
import { invokeSecurityTool } from "../src/security-tools.js";

const services = new Set();

async function newService(overrides = {}, options = {}) {
  const service = await startSecurityService(overrides, options);
  services.add(service);
  const close = service.close.bind(service);
  service.close = async () => { services.delete(service); await close(); };
  return service;
}

afterEach(async () => { await Promise.all([...services].map((service) => service.close())); });

const BOOTSTRAP_SECRET = Buffer.alloc(32, 0x7a).toString("base64url");
const OWNER_EMAIL = "security-center-owner@example.test";
const OWNER_PASSWORD = "Security Center 7Pass";

const BOOTSTRAP_ENV = Object.freeze({
  CRAFTMIND_DEV_BOOTSTRAP_EMAIL: OWNER_EMAIL,
  CRAFTMIND_DEV_BOOTSTRAP_SECRET: BOOTSTRAP_SECRET,
});

async function ownerToken(service) {
  const bootstrap = await call(service.baseUrl, "POST", "/developer/auth/bootstrap", {
    body: { secret: BOOTSTRAP_SECRET, password: OWNER_PASSWORD },
  });
  assert.equal(bootstrap.status, 201, JSON.stringify(bootstrap.body));
  const login = await call(service.baseUrl, "POST", "/developer/auth/login", {
    body: { email: OWNER_EMAIL, password: OWNER_PASSWORD },
  });
  assert.equal(login.status, 200, JSON.stringify(login.body));
  return login.body.session.accessToken;
}

function developerInvoke(service, token, tool, args = {}) {
  return call(service.baseUrl, "POST", "/developer/tools/invoke", {
    headers: { Authorization: `Bearer ${token}` },
    body: { tool, arguments: args },
  });
}

describe("Phase 20 response boundaries and Security Center", () => {
  it("refuses an unauthorized automated action even when the caller knows the tool name", async () => {
    const service = await newService();
    const invocation = { tool: "security.releaseProtection", arguments: { protectionId: `prt_${"0".repeat(8)}-0000-0000-0000-000000000000`, releaseReason: "forged" } };

    assert.throws(
      () => invokeSecurityTool({ database: service.database, configuration: service.configuration, invocation, authorization: null }),
      (error) => error.code === "SECURITY_ACTION_NOT_AUTHORIZED",
    );
    assert.throws(
      () => invokeSecurityTool({ database: service.database, configuration: service.configuration, invocation: { tool: "security.eraseEverything", arguments: {} }, authorization: null }),
      (error) => error.code === "SECURITY_ACTION_UNKNOWN",
    );
    // A valid-looking envelope for a *different* tool is still refused.
    const plan = decideSecurityResponse({
      assessment: {
        category: "BRUTE_FORCE", severity: "HIGH", thresholdsKey: "bruteForce", count: 12, subjectKind: "ACCOUNT",
        subjectReference: ACCOUNT_A, dedupKey: `BRUTE_FORCE:ACCOUNT:${ACCOUNT_A}`, reason: "12 authentication failures",
      },
      risk: { level: "HIGH", score: 60, reasons: ["12 authentication failures in 2 minutes"] },
      configuration: service.configuration,
    });
    const envelope = authorizeSecurityAction({ plan, toolName: "security.applyRateLimit", configuration: service.configuration });
    assert.throws(
      () => invokeSecurityTool({ database: service.database, configuration: service.configuration, invocation, authorization: envelope }),
      (error) => error.code === "SECURITY_ACTION_NOT_AUTHORIZED",
    );
    assert.equal(actionRows(service).length, 0);
  });

  it("keeps the automated response tools unreachable from a developer session", async () => {
    const service = await newService(BOOTSTRAP_ENV);
    const token = await ownerToken(service);

    for (const tool of ["security.applyRateLimit", "security.protectAccount", "security.revokeSession", "security.createIncident"]) {
      const attempt = await developerInvoke(service, token, tool, {});
      assert.equal(attempt.status, 400, tool);
      assert.equal(attempt.body.error.code, "DEVELOPER_TOOL_UNKNOWN", tool);
      assert.equal(attempt.body.result ?? null, null, "no security result may be returned");
    }
    assert.equal(actionRows(service).length, 0, "a developer session cannot trigger an automated response");
    const audit = service.database.prepare(
      "SELECT actor_kind, action_type, outcome FROM admin_audit_log WHERE action_type = 'unknown_tool'",
    ).all();
    assert.ok(audit.length >= 4);
    assert.ok(audit.every((row) => row.actor_kind === "DEVELOPER"));
  });

  it("answers the AI security summary with prose only, audits it as AI, and executes nothing", async () => {
    const providerRequests = [];
    const service = await newService(BOOTSTRAP_ENV, {
      developerAiProvider: {
        async selectToolCall(request) {
          providerRequests.push(request);
          return { message: "The CRITICAL incident is already contained; no further automated action is required." };
        },
      },
    });
    const token = await ownerToken(service);
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 30, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });
    const incident = incidentRows(service)[0];
    const actionsBefore = actionRows(service).length;

    const response = await call(service.baseUrl, "POST", "/developer/ai/security-summary", {
      headers: { Authorization: `Bearer ${token}` },
      body: { incidentId: incident.incident_id, prompt: "Summarize this incident." },
    });
    assert.equal(response.status, 200, JSON.stringify(response.body));
    assert.equal(typeof response.body.message, "string");
    assert.equal(Object.hasOwn(response.body, "tool"), false, "the security mode returns prose only");
    assert.equal(response.body.incidentReference, incident.reference);

    const request = providerRequests.at(-1);
    assert.equal(request.mode, "SECURITY_ANALYSIS");
    assert.equal(request.responseContract, "MESSAGE_ONLY");
    assert.equal(request.maximumToolCalls, 0);
    assert.deepEqual(request.tools, []);
    assert.equal(JSON.stringify(request).includes(OWNER_PASSWORD), false);
    assert.equal(JSON.stringify(request.securityContext).includes("session"), false, "the brief carries no credentials");

    assert.equal(actionRows(service).length, actionsBefore, "the AI executed no security action");
    const audits = service.database.prepare(
      "SELECT actor_kind, action_type, outcome, actor_developer_id FROM admin_audit_log WHERE action_type = 'developer_ai_security_summary'",
    ).all();
    assert.equal(audits.length, 1);
    assert.equal(audits[0].actor_kind, "AI");
    assert.equal(audits[0].outcome, "SUCCESS");
    assert.notEqual(audits[0].actor_developer_id, null, "an AI action is attributed to the developer who asked");
  });

  it("refuses an AI tool proposal in the security mode instead of acting on it", async () => {
    const service = await newService(BOOTSTRAP_ENV, {
      developerAiProvider: {
        async selectToolCall() {
          return { message: "Applying a source limit now.", toolCall: { name: "security.applyRateLimit", arguments: {} } };
        },
      },
    });
    const token = await ownerToken(service);
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 30, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });
    const actionsBefore = actionRows(service).length;

    const response = await call(service.baseUrl, "POST", "/developer/ai/security-summary", {
      headers: { Authorization: `Bearer ${token}` },
      body: { prompt: "What should we do?" },
    });
    assert.equal(response.status, 502);
    assert.equal(response.body.error.code, "DEVELOPER_AI_RESPONSE_INVALID");
    assert.equal(actionRows(service).length, actionsBefore);
    const audits = service.database.prepare(
      "SELECT outcome FROM admin_audit_log WHERE action_type = 'developer_ai_security_summary'",
    ).all();
    assert.equal(audits.at(-1).outcome, "FAILURE");
  });

  it("requires a developer session for the Security Center and returns stored facts only", async () => {
    const service = await newService(BOOTSTRAP_ENV);
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 30, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });

    const anonymous = await developerInvoke(service, "not-a-developer-token", "securityOverview");
    assert.equal(anonymous.status, 401);
    const user = await registerVerified(service, { email: "regular-user@example.test" });
    const asUser = await developerInvoke(service, user.body.session.accessToken, "securityOverview");
    assert.equal(asUser.status, 401, "an ordinary account session is not a developer session");

    const token = await ownerToken(service);
    const overview = await developerInvoke(service, token, "securityOverview");
    assert.equal(overview.status, 200, JSON.stringify(overview.body));
    assert.equal(overview.body.result.engine.aiDependent, false);
    const incidents = await developerInvoke(service, token, "listSecurityIncidents", { limit: 10 });
    assert.equal(incidents.status, 200);
    assert.equal(incidents.body.result.incidents.length, 1);
    const events = await developerInvoke(service, token, "listSecurityEvents", { limit: 100, eventType: "AUTHENTICATION_FAILED" });
    assert.equal(events.status, 200, JSON.stringify(events.body));
    assert.ok(events.body.result.events.length >= 1);
    // The Security Center exposes the classified event with its opaque digest, never a raw address or credential.
    const serialized = JSON.stringify(events.body);
    assert.equal(serialized.includes("127.0.0.1"), false);
    assert.equal(serialized.includes(OWNER_PASSWORD), false);
    assert.equal(serialized.includes(OWNER_EMAIL), false);
    const notifications = await developerInvoke(service, token, "listSecurityNotifications", { status: "UNREAD" });
    assert.equal(notifications.status, 200);
    assert.ok(notifications.body.result.notifications.length >= 1);
    // No mutating security tool exists in the developer registry, however it is named.
    for (const name of ["releaseProtection", "resolveSecurityIncident", "applySecurityProtection"]) {
      const attempt = await developerInvoke(service, token, name, {});
      assert.equal(attempt.body.error.code, "DEVELOPER_TOOL_UNKNOWN", name);
    }
  });

  it("creates one alert per incident inside the cooldown and audits the suppression instead of failing", async () => {
    const service = await newService();
    driveSignals(service.engine, { eventType: "AUTHENTICATION_FAILED", count: 30, accountReference: ACCOUNT_A, sourceReference: SOURCE_A, result: "FAILURE" });
    assert.equal(notificationRows(service).length, 1);
    assert.equal(protectionRows(service).some((row) => row.mode === "DENY"), true);
    const incident = incidentRows(service)[0];

    // A second urgent plan for the same incident inside the notification cooldown is suppressed, not duplicated.
    const assessment = {
      category: "BRUTE_FORCE", severity: "CRITICAL", thresholdsKey: "bruteForce", count: 30, subjectKind: "ACCOUNT",
      subjectReference: ACCOUNT_A, dedupKey: `BRUTE_FORCE:ACCOUNT:${ACCOUNT_A}`, reason: "30 authentication failures",
    };
    const plan = decideSecurityResponse({
      assessment,
      risk: { level: "CRITICAL", score: 90, reasons: ["30 authentication failures in 2 minutes"] },
      configuration: service.configuration,
    });
    const envelope = authorizeSecurityAction({ plan, toolName: "security.notifyDeveloper", configuration: service.configuration });
    const outcome = invokeSecurityTool({
      database: service.database,
      configuration: service.configuration,
      invocation: { tool: "security.notifyDeveloper", arguments: { priority: "CRITICAL" } },
      authorization: envelope,
      context: { incidentId: incident.incident_id, correlationId: "response-boundary-0001" },
    });
    assert.equal(outcome.resultCode, "NOTIFICATION_COOLDOWN");
    assert.equal(notificationRows(service).length, 1);

    // A deliberate suppression is a benign skip: it is audited as a success, not as a failure.
    const alertAudits = auditRows(service).filter((row) => row.action_type === "SECURITY_ALERT_CREATED");
    assert.equal(alertAudits.at(-1).outcome, "SUCCESS");
    assert.ok(alertAudits.length >= 2, "both the alert and its suppression are recorded");
  });
});
