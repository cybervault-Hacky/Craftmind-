/**
 * Deterministic threat detection.
 *
 * Detection never depends on an AI provider, a network call, or a human: each rule counts stored signals of a closed
 * set inside a bounded window and compares the count with centralized thresholds from `configuration.security`.
 * Rules are intentionally simple and explainable, so every escalation can be justified with the count that produced
 * it (for example "25 authentication failures in 2 minutes").
 */

import { SECURITY_EVENT_TYPE } from "./security-events.js";

const SUBJECT_FIELDS = Object.freeze({
  ACCOUNT: { property: "accountReference", column: "account_reference" },
  SESSION: { property: "sessionReference", column: "session_reference" },
  SOURCE: { property: "sourceReference", column: "source_reference" },
});

/**
 * `subjectPreference` is the order in which subjects are evaluated for an event: the first subject the event actually
 * carries is the primary one that an incident is opened against.
 */
const DETECTION_RULES = Object.freeze([
  {
    category: "BRUTE_FORCE",
    thresholdsKey: "bruteForce",
    eventTypes: [SECURITY_EVENT_TYPE.AUTHENTICATION_FAILED],
    subjects: ["ACCOUNT", "SOURCE"],
    subjectPreference: ["ACCOUNT", "SOURCE"],
    activityLabel: "authentication failures",
    description: "Multiple failed authentication attempts for one account or one source inside a bounded window.",
  },
  {
    category: "BRUTE_FORCE",
    thresholdsKey: "developerAuthFailures",
    eventTypes: [SECURITY_EVENT_TYPE.DEVELOPER_AUTHENTICATION_FAILED],
    subjects: ["ACCOUNT", "SOURCE"],
    subjectPreference: ["ACCOUNT", "SOURCE"],
    activityLabel: "developer authentication failures",
    description: "Repeated developer sign-in failures, which are weighted more strictly than normal-user failures.",
  },
  {
    category: "SESSION_ABUSE",
    thresholdsKey: "sessionAbuse",
    eventTypes: [
      SECURITY_EVENT_TYPE.SESSION_INVALID_CREDENTIAL,
      SECURITY_EVENT_TYPE.SESSION_REVOKED_REUSE,
      SECURITY_EVENT_TYPE.SESSION_EXPIRED_REUSE,
      SECURITY_EVENT_TYPE.SESSION_REFRESH_FAILED,
    ],
    subjects: ["SESSION", "ACCOUNT", "SOURCE"],
    subjectPreference: ["SESSION", "ACCOUNT", "SOURCE"],
    activityLabel: "invalid session credential uses",
    description: "Repeated use of invalid, revoked, or expired session credentials.",
  },
  {
    category: "RATE_LIMIT_ABUSE",
    thresholdsKey: "rateLimitAbuse",
    eventTypes: [SECURITY_EVENT_TYPE.RATE_LIMIT_VIOLATION],
    subjects: ["SOURCE", "ACCOUNT"],
    subjectPreference: ["SOURCE", "ACCOUNT"],
    activityLabel: "rate-limit violations",
    description: "Repeated rate-limit violations across a bounded window.",
  },
  {
    category: "UNAUTHORIZED_ACCESS",
    thresholdsKey: "unauthorizedAccess",
    eventTypes: [
      SECURITY_EVENT_TYPE.UNAUTHORIZED_ACCESS_ATTEMPT,
      SECURITY_EVENT_TYPE.HIGH_IMPACT_ACTION_FAILED,
    ],
    subjects: ["ACCOUNT", "SOURCE"],
    subjectPreference: ["ACCOUNT", "SOURCE"],
    activityLabel: "unauthorized endpoint attempts",
    description: "Repeated attempts to reach protected developer/admin functionality without authorization.",
  },
  {
    category: "CONFIRMATION_ABUSE",
    thresholdsKey: "confirmationAbuse",
    eventTypes: [SECURITY_EVENT_TYPE.CONFIRMATION_ATTEMPT_INVALID],
    subjects: ["ACCOUNT", "SOURCE"],
    subjectPreference: ["ACCOUNT", "SOURCE"],
    activityLabel: "invalid confirmation attempts",
    description: "Repeated invalid or suspicious high-impact confirmation attempts.",
  },
  {
    category: "REQUEST_ABUSE",
    thresholdsKey: "malformedRequests",
    eventTypes: [SECURITY_EVENT_TYPE.MALFORMED_SECURITY_REQUEST],
    subjects: ["SOURCE", "ACCOUNT"],
    subjectPreference: ["SOURCE", "ACCOUNT"],
    activityLabel: "malformed security-sensitive requests",
    description: "Repeated malformed requests against security-sensitive routes.",
  },
  {
    category: "REQUEST_ABUSE",
    thresholdsKey: "requestBurst",
    eventTypes: [SECURITY_EVENT_TYPE.REQUEST_BURST],
    subjects: ["SOURCE"],
    subjectPreference: ["SOURCE"],
    activityLabel: "requests",
    // A burst's magnitude is the request count it recorded, not the number of burst signals, so one announcement per
    // window still measures the real traffic volume.
    magnitudeSql: "MAX(json_extract(metadata_json, '$.requests'))",
    description: "Abnormal request frequency from one source inside a short window.",
  },
]);

function windowLabel(windowMs) {
  const minutes = windowMs / 60_000;
  if (Number.isInteger(minutes) && minutes >= 1) return minutes === 1 ? "1 minute" : `${minutes} minutes`;
  return `${Math.round(windowMs / 1000)} seconds`;
}

function severityFor(count, thresholds) {
  if (count >= thresholds.critical) return "CRITICAL";
  if (count >= thresholds.high) return "HIGH";
  if (count >= thresholds.medium) return "MEDIUM";
  return "LOW";
}

/**
 * Evaluates every rule that the given event can contribute to and returns the assessments that reached at least
 * `MEDIUM`, ordered most severe first. The event itself is not re-read from the database; the caller passes the stored
 * event (or the normalized event it just persisted).
 */
export function evaluateThreats(database, configuration, { now = new Date(), event }) {
  const occurredAt = typeof now === "string" ? now : new Date(now).toISOString();
  const assessments = [];
  for (const rule of DETECTION_RULES) {
    if (!rule.eventTypes.includes(event.eventType)) continue;
    const thresholds = configuration.security.thresholds[rule.thresholdsKey];
    const since = new Date(Date.parse(occurredAt) - thresholds.windowMs).toISOString();
    for (const subjectKind of rule.subjects) {
      const field = SUBJECT_FIELDS[subjectKind];
      const subjectReference = event[field.property];
      if (!subjectReference) continue;
      const primary = rule.subjectPreference[0] === subjectKind;
      const count = countMatchingEvents(database, rule, field.column, subjectReference, since);
      const severity = severityFor(count, thresholds);
      if (severity === "LOW") continue;
      assessments.push(Object.freeze({
        category: rule.category,
        thresholdsKey: rule.thresholdsKey,
        subjectKind,
        subjectReference,
        count,
        windowMs: thresholds.windowMs,
        thresholds,
        severity,
        primary,
        description: rule.description,
        reason: `${count} ${rule.activityLabel} in ${windowLabel(thresholds.windowMs)}`,
      }));
    }
  }
  return assessments.sort((left, right) => {
    const rank = { CRITICAL: 3, HIGH: 2, MEDIUM: 1, LOW: 0 };
    if (rank[left.severity] !== rank[right.severity]) return rank[right.severity] - rank[left.severity];
    if (left.primary !== right.primary) return left.primary ? -1 : 1;
    return right.count - left.count;
  });
}

function countMatchingEvents(database, rule, field, subjectReference, since) {
  const placeholders = rule.eventTypes.map(() => "?").join(", ");
  // Counting rules sum the coalesced signal count; a magnitude rule reads its own measured value instead.
  const aggregate = rule.magnitudeSql ? `COALESCE(${rule.magnitudeSql}, 0)` : "COALESCE(SUM(signal_count), 0)";
  const row = database.prepare(
    `SELECT ${aggregate} AS count
       FROM security_events
      WHERE event_type IN (${placeholders}) AND ${field} = ? AND occurred_at >= ?`,
  ).get(...rule.eventTypes, subjectReference, since);
  return Number(row?.count ?? 0);
}

/**
 * Supporting context for the risk engine: what else is happening to the same subject inside the same window. This is
 * how one category's assessment can escalate because it is part of a broader pattern.
 */
export function gatherRiskContext(database, configuration, { now = new Date(), assessment, lookbackMs = 900_000 }) {
  const occurredAt = typeof now === "string" ? now : new Date(now).toISOString();
  const field = SUBJECT_FIELDS[assessment.subjectKind].column;
  const since = new Date(Date.parse(occurredAt) - lookbackMs).toISOString();
  const concurrentCategories = [];
  for (const rule of DETECTION_RULES) {
    if (rule.category === assessment.category && rule.thresholdsKey === assessment.thresholdsKey) continue;
    if (!rule.subjects.includes(assessment.subjectKind)) continue;
    const thresholds = configuration.security.thresholds[rule.thresholdsKey];
    const count = countMatchingEvents(database, rule, field, assessment.subjectReference, since);
    if (count >= thresholds.medium) {
      concurrentCategories.push({ category: rule.category, thresholdsKey: rule.thresholdsKey, count });
    }
  }
  const previousIncidents = Number(database.prepare(
    `SELECT COUNT(*) AS count FROM security_incidents
      WHERE dedup_key = ? AND detected_at >= ?`,
  ).get(dedupKeyFor(assessment), since).count ?? 0);
  const sensitiveRoutes = Number(database.prepare(
    `SELECT COUNT(*) AS count FROM security_events
      WHERE ${field} = ? AND occurred_at >= ? AND route_category IN ('developer_admin', 'developer_auth', 'developer_ai')`,
  ).get(assessment.subjectReference, since).count ?? 0);
  return Object.freeze({
    concurrentCategories: Object.freeze(concurrentCategories),
    previousIncidents,
    endpointSensitivity: sensitiveRoutes > 0 ? "HIGH" : "STANDARD",
  });
}

/**
 * Idempotency handle for incidents: one active incident per (category, subject) inside the deduplication window, so a
 * flood of identical signals escalates a single incident instead of creating an unbounded series of duplicates.
 */
export function dedupKeyFor(assessment) {
  return `${assessment.threatCategory ?? assessment.category}:${assessment.subjectKind}:${assessment.subjectReference}`.slice(0, 200);
}

export { DETECTION_RULES, severityFor, windowLabel };
