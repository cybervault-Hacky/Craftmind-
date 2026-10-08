/** Shared deterministic fixtures for the Phase 20 security suites. Local-only: no external system is contacted. */

import { SecurityEngine } from "../src/security-engine.js";
import { startService, TEST_SECRET } from "./helpers.js";

export const SILENT_LOGGER = { info() {}, warn() {}, error() {} };

/** Controllable clock so bounded windows and expiries are asserted deterministically instead of by sleeping. */
export function createClock(startMillis = Date.now()) {
  const state = { millis: startMillis };
  return {
    now: () => state.millis,
    advanceSeconds(seconds) { state.millis += seconds * 1000; return state.millis; },
    advanceMinutes(minutes) { state.millis += minutes * 60_000; return state.millis; },
  };
}

export function hexReference(seed, length = 40) {
  return seed.repeat(Math.ceil(length / seed.length)).slice(0, length);
}

export const ACCOUNT_A = `ref_${hexReference("a")}`;
export const ACCOUNT_B = `ref_${hexReference("b")}`;
export const SOURCE_A = hexReference("c");

/**
 * Starts a service whose security engine runs on an injectable clock, so HTTP requests and engine-driven simulations
 * share exactly one notion of "now".
 */
export async function startSecurityService(overrides = {}, options = {}) {
  const clock = options.clock ?? createClock();
  const service = await startService(overrides, { ...options, securityEngine: null });
  const engine = new SecurityEngine({
    database: service.database,
    configuration: service.configuration,
    logger: SILENT_LOGGER,
    now: clock.now,
  });
  // The service keeps its own engine for live traffic; tests drive this clocked instance against the same database.
  service.engine = engine;
  service.clock = clock;
  return service;
}

/** Drives `count` normalized signals through the engine, exactly as a burst of real traffic would arrive. */
export function driveSignals(engine, { eventType, count, accountReference = null, sessionReference = null, sourceReference = null, result, metadata = {} }) {
  const outcomes = [];
  for (let index = 0; index < count; index += 1) {
    outcomes.push(engine.recordSignal({
      eventType,
      result,
      accountReference,
      sessionReference,
      sourceReference,
      metadata,
    }));
  }
  return outcomes;
}

export function incidentRows(service) {
  return service.database.prepare("SELECT * FROM security_incidents ORDER BY detected_at, incident_id").all();
}

export function protectionRows(service) {
  return service.database.prepare("SELECT * FROM security_rate_limit_state ORDER BY applied_at").all();
}

export function actionRows(service) {
  return service.database.prepare("SELECT * FROM security_actions ORDER BY occurred_at, action_id").all();
}

export function notificationRows(service) {
  return service.database.prepare("SELECT * FROM security_notifications ORDER BY created_at").all();
}

export function auditRows(service, actionPrefix = "SECURITY_") {
  return service.database.prepare(
    "SELECT * FROM admin_audit_log WHERE action_type LIKE ? ORDER BY occurred_at, audit_id",
  ).all(`${actionPrefix}%`);
}

/**
 * Dump of the security-relevant tables only (events, incidents, actions, notifications, protections, audit log). The
 * general `databaseText` helper includes `users`, where an account's own email legitimately lives.
 */
export function securityDatabaseText(service) {
  const tables = [
    "security_events", "security_incidents", "security_actions", "security_notifications",
    "security_rate_limit_state", "admin_audit_log",
  ];
  const parts = [];
  for (const table of tables) {
    for (const row of service.database.prepare(`SELECT * FROM ${table}`).all()) parts.push(JSON.stringify(row));
  }
  return parts.join("\n");
}

/** Full dump of every stored string, for "no credential ever reached the database" assertions. */
export function databaseText(service) {
  const tables = service.database.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all()
    .map((row) => row.name)
    .filter((name) => !name.startsWith("sqlite_"));
  const parts = [];
  for (const table of tables) {
    for (const row of service.database.prepare(`SELECT * FROM ${table}`).all()) {
      parts.push(JSON.stringify(row));
    }
  }
  return parts.join("\n");
}

export { TEST_SECRET };
