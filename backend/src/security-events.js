/**
 * The single normalization boundary for security signals.
 *
 * Every part of the backend reports suspicious behaviour here — the HTTP layer, the account/session layer, and the
 * developer control plane — instead of writing its own security rows. An event has a closed shape: event type,
 * source category, severity, result, safe account/session references, a route category, bounded metadata, a
 * correlation identifier, and (once assigned) an incident reference. Raw credentials never cross this boundary:
 * passwords, password hashes, access/refresh tokens, API keys, provider and Minecraft credentials, email addresses,
 * and request bodies are all refused or replaced with a fixed marker, and oversized metadata is truncated or the
 * event rejected.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { newSecurityEventId } from "./ids.js";

const EVENT_TYPES = Object.freeze({
  AUTHENTICATION_FAILED: "AUTHENTICATION_FAILED",
  AUTHENTICATION_SUCCEEDED: "AUTHENTICATION_SUCCEEDED",
  AUTHENTICATION_SUCCEEDED_AFTER_FAILURES: "AUTHENTICATION_SUCCEEDED_AFTER_FAILURES",
  SESSION_REFRESH_FAILED: "SESSION_REFRESH_FAILED",
  SESSION_INVALID_CREDENTIAL: "SESSION_INVALID_CREDENTIAL",
  SESSION_REVOKED_REUSE: "SESSION_REVOKED_REUSE",
  SESSION_EXPIRED_REUSE: "SESSION_EXPIRED_REUSE",
  RATE_LIMIT_VIOLATION: "RATE_LIMIT_VIOLATION",
  UNAUTHORIZED_ACCESS_ATTEMPT: "UNAUTHORIZED_ACCESS_ATTEMPT",
  MALFORMED_SECURITY_REQUEST: "MALFORMED_SECURITY_REQUEST",
  SUSPICIOUS_ACCOUNT_ACTIVITY: "SUSPICIOUS_ACCOUNT_ACTIVITY",
  DEVELOPER_AUTHENTICATION_FAILED: "DEVELOPER_AUTHENTICATION_FAILED",
  HIGH_IMPACT_ACTION_FAILED: "HIGH_IMPACT_ACTION_FAILED",
  CONFIRMATION_ATTEMPT_INVALID: "CONFIRMATION_ATTEMPT_INVALID",
  REQUEST_BURST: "REQUEST_BURST",
});

const SOURCE_CATEGORIES = Object.freeze({
  USER_AUTH: "USER_AUTH",
  DEVELOPER_AUTH: "DEVELOPER_AUTH",
  SESSION: "SESSION",
  RATE_LIMIT: "RATE_LIMIT",
  AUTHORIZATION: "AUTHORIZATION",
  REQUEST: "REQUEST",
  CONFIRMATION: "CONFIRMATION",
  ACCOUNT: "ACCOUNT",
});

const RESULTS = Object.freeze({
  SUCCESS: "SUCCESS",
  FAILURE: "FAILURE",
  DENIED: "DENIED",
  THROTTLED: "THROTTLED",
  INVALID: "INVALID",
  BLOCKED: "BLOCKED",
});

const SEVERITIES = Object.freeze(["LOW", "MEDIUM", "HIGH", "CRITICAL"]);

/** Default severity and source category per signal, so callers cannot inflate or forget either one. */
const EVENT_PROFILES = Object.freeze({
  [EVENT_TYPES.AUTHENTICATION_FAILED]: { severity: "LOW", sourceCategory: SOURCE_CATEGORIES.USER_AUTH, result: RESULTS.FAILURE },
  [EVENT_TYPES.AUTHENTICATION_SUCCEEDED]: { severity: "LOW", sourceCategory: SOURCE_CATEGORIES.USER_AUTH, result: RESULTS.SUCCESS },
  [EVENT_TYPES.AUTHENTICATION_SUCCEEDED_AFTER_FAILURES]: { severity: "MEDIUM", sourceCategory: SOURCE_CATEGORIES.USER_AUTH, result: RESULTS.SUCCESS },
  [EVENT_TYPES.SESSION_REFRESH_FAILED]: { severity: "LOW", sourceCategory: SOURCE_CATEGORIES.SESSION, result: RESULTS.FAILURE },
  [EVENT_TYPES.SESSION_INVALID_CREDENTIAL]: { severity: "MEDIUM", sourceCategory: SOURCE_CATEGORIES.SESSION, result: RESULTS.INVALID },
  [EVENT_TYPES.SESSION_REVOKED_REUSE]: { severity: "HIGH", sourceCategory: SOURCE_CATEGORIES.SESSION, result: RESULTS.DENIED },
  [EVENT_TYPES.SESSION_EXPIRED_REUSE]: { severity: "MEDIUM", sourceCategory: SOURCE_CATEGORIES.SESSION, result: RESULTS.DENIED },
  [EVENT_TYPES.RATE_LIMIT_VIOLATION]: { severity: "MEDIUM", sourceCategory: SOURCE_CATEGORIES.RATE_LIMIT, result: RESULTS.THROTTLED },
  [EVENT_TYPES.UNAUTHORIZED_ACCESS_ATTEMPT]: { severity: "HIGH", sourceCategory: SOURCE_CATEGORIES.AUTHORIZATION, result: RESULTS.DENIED },
  [EVENT_TYPES.MALFORMED_SECURITY_REQUEST]: { severity: "LOW", sourceCategory: SOURCE_CATEGORIES.REQUEST, result: RESULTS.INVALID },
  [EVENT_TYPES.SUSPICIOUS_ACCOUNT_ACTIVITY]: { severity: "MEDIUM", sourceCategory: SOURCE_CATEGORIES.ACCOUNT, result: RESULTS.FAILURE },
  [EVENT_TYPES.DEVELOPER_AUTHENTICATION_FAILED]: { severity: "MEDIUM", sourceCategory: SOURCE_CATEGORIES.DEVELOPER_AUTH, result: RESULTS.FAILURE },
  [EVENT_TYPES.HIGH_IMPACT_ACTION_FAILED]: { severity: "MEDIUM", sourceCategory: SOURCE_CATEGORIES.AUTHORIZATION, result: RESULTS.FAILURE },
  [EVENT_TYPES.CONFIRMATION_ATTEMPT_INVALID]: { severity: "MEDIUM", sourceCategory: SOURCE_CATEGORIES.CONFIRMATION, result: RESULTS.INVALID },
  [EVENT_TYPES.REQUEST_BURST]: { severity: "MEDIUM", sourceCategory: SOURCE_CATEGORIES.REQUEST, result: RESULTS.THROTTLED },
});

/** Field names that must never be persisted, whatever a caller passes. Values are replaced, never copied. */
const SENSITIVE_KEY_PATTERN = /(pass|secret|token|digest|hash|credential|authorization|cookie|api[_-]?key|apikey|private|minecraft|session[_-]?id|refresh|bearer|otp|code)/i;
/** A value that looks like a raw credential is replaced even when its key looks harmless. */
const CREDENTIAL_SHAPED_VALUE = /^[A-Za-z0-9._~+/=-]{32,}$/;
const EMAIL_SHAPED_VALUE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const REDACTED = "[redacted]";
const MAXIMUM_METADATA_KEYS = 12;
const MAXIMUM_METADATA_DEPTH = 2;
const MAXIMUM_METADATA_STRING = 120;
const MAXIMUM_METADATA_ITEMS = 12;

// `usr_`/`dvl_` are real account identifiers; `ref_` is the keyed digest used when only an attempted address is known.
const ACCOUNT_REFERENCE_PATTERN = /^(?:(usr|dvl)_[0-9a-f-]{36}|ref_[0-9a-f]{40})$/;
const SESSION_REFERENCE_PATTERN = /^(ses|dvs)_[0-9a-f-]{36}$/;
const SOURCE_REFERENCE_PATTERN = /^[0-9a-f]{16,64}$/;
const CORRELATION_PATTERN = /^[A-Za-z0-9-]{8,64}$/;
const ROUTE_CATEGORY_PATTERN = /^[a-z0-9:_-]{1,64}$/;

function optionalReference(value, pattern, code = ErrorCode.SECURITY_EVENT_INVALID) {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string" || !pattern.test(value)) throw new AccountApiError(code);
  return value;
}

function sanitizeValue(value, depth = 0) {
  if (value === null || typeof value === "boolean") return value;
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value === "string") {
    if (CREDENTIAL_SHAPED_VALUE.test(value) || EMAIL_SHAPED_VALUE.test(value)) return REDACTED;
    // Control characters would corrupt any downstream rendering, so the value is replaced rather than cleaned.
    if (/[\u0000-\u001f\u007f]/.test(value)) return REDACTED;
    return value.length > MAXIMUM_METADATA_STRING ? `${value.slice(0, MAXIMUM_METADATA_STRING)}…` : value;
  }
  if (Array.isArray(value)) {
    if (depth >= MAXIMUM_METADATA_DEPTH) return [];
    return value.slice(0, MAXIMUM_METADATA_ITEMS).map((entry) => sanitizeValue(entry, depth + 1));
  }
  if (typeof value === "object") {
    if (depth >= MAXIMUM_METADATA_DEPTH) return {};
    return sanitizeMetadata(value, depth + 1);
  }
  return REDACTED;
}

/** Returns a bounded, secret-free copy of caller-supplied metadata. Unknown structures are dropped, not stringified. */
export function sanitizeMetadata(metadata, depth = 0) {
  if (metadata === null || metadata === undefined) return {};
  if (typeof metadata !== "object" || Array.isArray(metadata)) return {};
  const result = {};
  for (const [key, value] of Object.entries(metadata).slice(0, MAXIMUM_METADATA_KEYS)) {
    if (typeof key !== "string" || key.length === 0 || key.length > 64 || /[\u0000-\u001f\u007f]/.test(key)) continue;
    result[key] = SENSITIVE_KEY_PATTERN.test(key) ? REDACTED : sanitizeValue(value, depth);
  }
  return result;
}

function encodeMetadata(metadata, maximumCharacters) {
  const sanitized = sanitizeMetadata(metadata);
  let encoded = JSON.stringify(sanitized);
  if (encoded.length <= maximumCharacters) return encoded;
  // Oversized payloads are truncated down to their smallest safe form, then rejected if they are still too large.
  const trimmed = {};
  for (const [key, value] of Object.entries(sanitized)) {
    trimmed[key] = typeof value === "string" ? value.slice(0, 40) : value;
    encoded = JSON.stringify(trimmed);
    if (encoded.length <= maximumCharacters) return encoded;
  }
  throw new AccountApiError(ErrorCode.SECURITY_EVENT_INVALID);
}

/**
 * Validates and freezes one signal into the stored event shape. The caller supplies intent (event type, references,
 * optional metadata); severity, source category, and result come from the closed profile table.
 */
export function normalizeSecurityEvent(input, { configuration, occurredAt, correlationId, incidentId = null } = {}) {
  if (input === null || typeof input !== "object" || Array.isArray(input)) {
    throw new AccountApiError(ErrorCode.SECURITY_EVENT_INVALID);
  }
  const profile = EVENT_PROFILES[input.eventType];
  if (!profile) throw new AccountApiError(ErrorCode.SECURITY_EVENT_INVALID);
  if (typeof correlationId !== "string" || !CORRELATION_PATTERN.test(correlationId)) {
    throw new AccountApiError(ErrorCode.SECURITY_EVENT_INVALID);
  }
  const timestamp = occurredAt ?? new Date().toISOString();
  if (typeof timestamp !== "string" || Number.isNaN(Date.parse(timestamp))) {
    throw new AccountApiError(ErrorCode.SECURITY_EVENT_INVALID);
  }
  const severity = input.severity ?? profile.severity;
  if (!SEVERITIES.includes(severity)) throw new AccountApiError(ErrorCode.SECURITY_EVENT_INVALID);
  const routeCategory = input.routeCategory === undefined || input.routeCategory === null
    ? null
    : input.routeCategory;
  if (routeCategory !== null && (typeof routeCategory !== "string" || !ROUTE_CATEGORY_PATTERN.test(routeCategory))) {
    throw new AccountApiError(ErrorCode.SECURITY_EVENT_INVALID);
  }
  return Object.freeze({
    eventId: newSecurityEventId(),
    eventType: input.eventType,
    severity,
    sourceCategory: input.sourceCategory ?? profile.sourceCategory,
    accountReference: optionalReference(input.accountReference, ACCOUNT_REFERENCE_PATTERN),
    sessionReference: optionalReference(input.sessionReference, SESSION_REFERENCE_PATTERN),
    routeCategory,
    result: input.result ?? profile.result,
    sourceReference: optionalReference(input.sourceReference, SOURCE_REFERENCE_PATTERN),
    correlationId,
    incidentId: optionalReference(incidentId, /^inc_[0-9a-f-]{36}$/),
    metadataJson: encodeMetadata(input.metadata, configuration.security.bounds.metadataCharacters),
    occurredAt: timestamp,
    recordedAt: new Date().toISOString(),
    reasons: Object.freeze([]),
  });
}

/** Persists a normalized event. Retention keeps the table bounded; nothing here updates or deletes history. */
export function persistSecurityEvent(database, event, { incidentId = null } = {}) {
  database.prepare(
    `INSERT INTO security_events
       (event_id, event_type, severity, source_category, account_reference, session_reference, route_category,
        result, signal_count, source_reference, correlation_id, incident_id, metadata_json, occurred_at, recorded_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)`,
  ).run(
    event.eventId,
    event.eventType,
    event.severity,
    event.sourceCategory,
    event.accountReference,
    event.sessionReference,
    event.routeCategory,
    event.result,
    event.sourceReference,
    event.correlationId,
    incidentId ?? event.incidentId,
    event.metadataJson,
    event.occurredAt,
    event.recordedAt,
  );
  return event.eventId;
}

function decodeMetadata(row) {
  try {
    const parsed = JSON.parse(row.metadata_json);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

export function rowToSecurityEvent(row) {
  return {
    eventId: row.event_id,
    eventType: row.event_type,
    severity: row.severity,
    sourceCategory: row.source_category,
    accountReference: row.account_reference,
    sessionReference: row.session_reference,
    routeCategory: row.route_category,
    result: row.result,
    signalCount: Number(row.signal_count ?? 1),
    sourceReference: row.source_reference,
    correlationId: row.correlation_id,
    incidentId: row.incident_id,
    metadata: decodeMetadata(row),
    occurredAt: row.occurred_at,
    recordedAt: row.recorded_at,
  };
}

/**
 * Coalesces a repeated identical signal into the event that already represents it: the stored row keeps one row of
 * bounded metadata but its `signal_count` grows. Detection sums that count, so a high-rate attack is counted
 * accurately while storage stays bounded by the deduplication window rather than by attacker throughput.
 */
export function coalesceSecurityEvent(database, eventId) {
  database.prepare("UPDATE security_events SET signal_count = signal_count + 1, recorded_at = ? WHERE event_id = ?")
    .run(new Date().toISOString(), eventId);
}

/** Lists stored events newest first with optional severity/category filters; the page size is always bounded. */
export function listSecurityEvents(database, { limit = 50, severity = null, sourceCategory = null, eventType = null } = {}) {
  const clauses = [];
  const parameters = [];
  if (severity) { clauses.push("severity = ?"); parameters.push(severity); }
  if (sourceCategory) { clauses.push("source_category = ?"); parameters.push(sourceCategory); }
  if (eventType) { clauses.push("event_type = ?"); parameters.push(eventType); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  const rows = database.prepare(
    `SELECT * FROM security_events ${where} ORDER BY occurred_at DESC, event_id DESC LIMIT ?`,
  ).all(...parameters, limit);
  return rows.map(rowToSecurityEvent);
}

export function countSecurityEvents(database, { since, eventType, accountReference = null, sessionReference = null, sourceReference = null } = {}) {
  const clauses = ["occurred_at >= ?"];
  const parameters = [since];
  if (eventType) { clauses.push("event_type = ?"); parameters.push(eventType); }
  if (accountReference) { clauses.push("account_reference = ?"); parameters.push(accountReference); }
  if (sessionReference) { clauses.push("session_reference = ?"); parameters.push(sessionReference); }
  if (sourceReference) { clauses.push("source_reference = ?"); parameters.push(sourceReference); }
  const row = database.prepare(
    `SELECT COUNT(*) AS count FROM security_events WHERE ${clauses.join(" AND ")}`,
  ).get(...parameters);
  return Number(row?.count ?? 0);
}

/** Bounded retention. Only aggregate history is removed; incidents and their audit trail are untouched here. */
export function cleanupExpiredSecurityEvents(database, nowMillis, retentionSeconds) {
  const cutoff = new Date(nowMillis - retentionSeconds * 1000).toISOString();
  const events = database.prepare("DELETE FROM security_events WHERE occurred_at <= ?").run(cutoff);
  return Number(events.changes ?? 0);
}

export {
  EVENT_TYPES as SECURITY_EVENT_TYPE,
  SOURCE_CATEGORIES as SECURITY_SOURCE_CATEGORY,
  RESULTS as SECURITY_EVENT_RESULT,
  SEVERITIES as SECURITY_SEVERITY_ORDER,
  EVENT_PROFILES as SECURITY_EVENT_PROFILES,
  REDACTED as SECURITY_REDACTED_MARKER,
};
