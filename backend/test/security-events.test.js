/** Phase 20 security event model, normalization boundary, redaction, bounding, and session classification. */

import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { normalizeSecurityEvent } from "../src/security-events.js";
import { call, registerVerified, startService } from "./helpers.js";
import {
  ACCOUNT_A,
  SOURCE_A,
  databaseText,
  protectionRows,
  startSecurityService,
} from "./security-support.js";

const services = new Set();

async function newService(overrides = {}, options = {}) {
  const service = await startService(overrides, options);
  services.add(service);
  const close = service.close.bind(service);
  service.close = async () => { services.delete(service); await close(); };
  return service;
}

async function newSecurityService(overrides = {}, options = {}) {
  const service = await startSecurityService(overrides, options);
  services.add(service);
  const close = service.close.bind(service);
  service.close = async () => { services.delete(service); await close(); };
  return service;
}

afterEach(async () => { await Promise.all([...services].map((service) => service.close())); });

describe("Phase 20 security event normalization", () => {
  it("stores the closed event shape with derived severity, source category, and opaque references", async () => {
    const service = await newSecurityService();
    const outcome = service.engine.recordSignal({
      eventType: "AUTHENTICATION_FAILED",
      result: "FAILURE",
      accountReference: ACCOUNT_A,
      sourceReference: SOURCE_A,
      routeCategory: "auth_login",
      metadata: { attempt: 1, window: "bounded" },
    });
    assert.equal(outcome.recorded, true);
    const row = service.database.prepare("SELECT * FROM security_events ORDER BY occurred_at DESC LIMIT 1").get();
    assert.equal(row.event_type, "AUTHENTICATION_FAILED");
    assert.equal(row.severity, "LOW");
    assert.equal(row.source_category, "USER_AUTH");
    assert.equal(row.result, "FAILURE");
    assert.equal(row.account_reference, ACCOUNT_A);
    assert.equal(row.source_reference, SOURCE_A);
    assert.equal(row.route_category, "auth_login");
    assert.match(row.event_id, /^sev_[0-9a-f-]{36}$/);
    assert.match(row.correlation_id, /^[A-Za-z0-9-]{8,64}$/);
    assert.equal(row.incident_id, null);
    assert.deepEqual(JSON.parse(row.metadata_json), { attempt: 1, window: "bounded" });
    // A single failure is monitored only: no incident, no protection, no alert.
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM security_incidents").get().count, 0);
    assert.equal(protectionRows(service).length, 0);
  });

  it("rejects an unregistered event type and an oversized payload instead of storing either", async () => {
    const service = await newSecurityService();
    const rejected = service.engine.recordSignal({ eventType: "TOTALLY_MADE_UP_SIGNAL" });
    assert.equal(rejected.recorded, false);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM security_events").get().count, 0);

    const oversized = {};
    for (let index = 0; index < 12; index += 1) {
      // Long-but-harmless text stays visible (only credential-shaped values are replaced), so the size limit is what
      // rejects this payload rather than redaction shrinking it below the boundary by accident.
      oversized[`field_${index}`] = Array.from({ length: 12 }, () => Array.from({ length: 12 }, () => "note text ".repeat(20)));
    }
    assert.throws(
      () => normalizeSecurityEvent({ eventType: "AUTHENTICATION_FAILED", metadata: oversized },
        { configuration: service.configuration, correlationId: "correlation-test-0001" }),
      (error) => error.code === "SECURITY_EVENT_INVALID",
    );
    const direct = service.engine.recordSignal({
      eventType: "AUTHENTICATION_FAILED",
      correlationId: "not a valid correlation id!!",
    });
    assert.equal(direct.recorded, false);
  });

  it("redacts credentials, tokens, addresses, and credential-shaped values before storage", async () => {
    const service = await newSecurityService();
    const secretToken = `${"Zk9".repeat(12)}token`;
    service.engine.recordSignal({
      eventType: "SUSPICIOUS_ACCOUNT_ACTIVITY",
      result: "FAILURE",
      accountReference: ACCOUNT_A,
      metadata: {
        password: "Correct Horse 7Battery",
        refreshToken: secretToken,
        authorization: `Bearer ${secretToken}`,
        apiKey: "provider-secret-value",
        email: "builder@example.test",
        note: "ordinary bounded note",
      },
    });
    const row = service.database.prepare("SELECT metadata_json FROM security_events ORDER BY occurred_at DESC LIMIT 1").get();
    assert.equal(row.metadata_json.includes("Correct Horse 7Battery"), false);
    assert.equal(row.metadata_json.includes(secretToken), false);
    assert.equal(row.metadata_json.includes("builder@example.test"), false);
    assert.equal(row.metadata_json.includes("provider-secret-value"), false);
    assert.equal(row.metadata_json.includes("[redacted]"), true);
    assert.equal(row.metadata_json.includes("ordinary bounded note"), true);
    const text = databaseText(service);
    for (const secret of ["Correct Horse 7Battery", secretToken, "builder@example.test", "provider-secret-value"]) {
      assert.equal(text.includes(secret), false, "a secret must never reach any stored row");
    }
  });

  it("bounds event volume with an ingest deduplication window and retention cleanup", async () => {
    const service = await newSecurityService();
    service.clock.advanceSeconds(0);
    for (let index = 0; index < 50; index += 1) {
      service.engine.recordSignal({ eventType: "AUTHENTICATION_FAILED", accountReference: ACCOUNT_A, sourceReference: SOURCE_A });
    }
    const stored = service.database.prepare("SELECT COUNT(*) AS count FROM security_events").get().count;
    assert.equal(stored, 1, "identical signals inside the deduplication window collapse to one stored event");

    service.clock.advanceSeconds(2);
    service.engine.recordSignal({ eventType: "AUTHENTICATION_FAILED", accountReference: ACCOUNT_A, sourceReference: SOURCE_A });
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM security_events").get().count, 2);

    // Retention removes only aggregate history; incidents and audit records are untouched here.
    service.database.prepare(
      "UPDATE security_events SET occurred_at = ?",
    ).run(new Date(service.clock.now() - 40 * 86_400_000).toISOString());
    const cleaned = service.engine.cleanup();
    assert.equal(cleaned.events >= 0, true);
    assert.equal(service.database.prepare("SELECT COUNT(*) AS count FROM security_events").get().count, 0);
  });

  it("classifies rejected session credentials internally without exposing anything to a client", async () => {
    const service = await newSecurityService();
    const session = await registerVerified(service);
    const accessToken = session.body.session.accessToken;
    assert.equal(service.engine.classifySessionFailure({ kind: "user", token: accessToken }).state, "ACTIVE");

    await call(service.baseUrl, "POST", "/auth/logout", { body: { accessToken } });
    const revoked = service.engine.classifySessionFailure({ kind: "user", token: accessToken });
    assert.equal(revoked.state, "REVOKED");
    assert.match(revoked.sessionReference, /^ses_[0-9a-f-]{36}$/);
    assert.match(revoked.accountReference, /^usr_[0-9a-f-]{36}$/);
    assert.equal(service.engine.classifySessionFailure({ kind: "user", token: "not-a-real-token-value-at-all" }).state, "INVALID");

    // The classification is internal: the public response for a revoked session stays the Phase 17/18 contract.
    const rejected = await call(service.baseUrl, "GET", "/auth/me", { headers: { Authorization: `Bearer ${accessToken}` } });
    assert.equal(rejected.status, 401);
    assert.equal(rejected.body.error.code, "SESSION_INVALID");
  });

  it("records a rejected request as a normalized signal without storing the request body or address", async () => {
    const service = await newService();
    const failed = await call(service.baseUrl, "POST", "/auth/login", {
      body: { email: "ghost@example.test", password: "Wrong Password 9" },
    });
    assert.equal(failed.status, 401);
    const rows = service.database.prepare("SELECT * FROM security_events WHERE event_type = 'AUTHENTICATION_FAILED'").all();
    assert.equal(rows.length, 1);
    // Unknown accounts still correlate through a keyed digest, never through the attempted address.
    assert.match(rows[0].account_reference, /^ref_[0-9a-f]{40}$/);
    assert.match(rows[0].source_reference, /^[0-9a-f]{40}$/);
    const text = databaseText(service);
    assert.equal(text.includes("ghost@example.test"), false);
    assert.equal(text.includes("Wrong Password 9"), false);
    assert.equal(text.includes("127.0.0.1"), false, "no raw client address may be stored");
    assert.equal(service.logs.join("\n").includes("Wrong Password 9"), false);
  });
});
