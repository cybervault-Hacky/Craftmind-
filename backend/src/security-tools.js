/**
 * The closed registry of authorized automated security response tools.
 *
 * Every tool has a strict argument schema, bounded values, one explicit action type, a safe result, and an audit
 * record. There is deliberately no `security.execute`, `security.run`, or `security.admin` — the registry cannot
 * express an arbitrary command, SQL statement, file write, source-code change, configuration change, account
 * deletion, permanent ban, or privilege grant. Execution additionally requires an unexpired authorization envelope
 * issued by the security policy, so neither a client, a developer session, nor the AI boundary can invoke these tools
 * directly.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { AUDIT_ACTOR_KIND, AUDIT_OUTCOME, appendAuditRecord } from "./audit.js";
import { isSecurityAuthorizationValid } from "./security-policy.js";
import {
  newIncidentReference,
  newSecurityActionId,
  newSecurityIncidentId,
  newSecurityNotificationId,
  newSecurityProtectionId,
} from "./ids.js";

const SCOPE_VALUES = Object.freeze(["SOURCE", "ACCOUNT", "SESSION"]);
const MODE_VALUES = Object.freeze(["THROTTLE", "DENY"]);
const PROTECTION_CATEGORIES = Object.freeze([
  "USER_AUTH", "DEVELOPER_AUTH", "SESSION", "DEVELOPER_ADMIN", "CONFIRMATION", "SECURITY_EVENT", "INCIDENT", "ANY",
]);
const THREAT_CATEGORIES = Object.freeze([
  "BRUTE_FORCE", "SESSION_ABUSE", "RATE_LIMIT_ABUSE", "UNAUTHORIZED_ACCESS", "CONFIRMATION_ABUSE", "REQUEST_ABUSE",
]);
const SUBJECT_KINDS = Object.freeze(["ACCOUNT", "SESSION", "SOURCE", "DEVELOPER", "ROUTE"]);
const SEVERITY_VALUES = Object.freeze(["LOW", "MEDIUM", "HIGH", "CRITICAL"]);
const RESOLUTIONS = Object.freeze(["AUTO_RESOLVED_NO_FURTHER_ACTIVITY", "DEVELOPER_REVIEWED_AS_BENIGN", "CONTAINED_AND_CLOSED"]);
const REFERENCE_PATTERNS = Object.freeze({
  ACCOUNT: /^(?:(usr|dvl)_[0-9a-f-]{36}|ref_[0-9a-f]{40})$/,
  SESSION: /^(ses|dvs)_[0-9a-f-]{36}$/,
  SOURCE: /^[0-9a-f]{16,64}$/,
  DEVELOPER: /^dvl_[0-9a-f-]{36}$/,
  ROUTE: /^[a-z0-9:_-]{1,64}$/,
});
const MAXIMUM_REASONS = 6;
const MAXIMUM_REASON_CHARACTERS = 200;
/** Decline-to-act results: the system deliberately did not duplicate, re-tighten, or re-alert. These are not failures. */
const BENIGN_SKIP_CODES = new Set([
  "INCIDENT_DEDUPLICATED",
  "PROTECTION_TIGHTENED",
  "SESSION_ALREADY_REVOKED",
  "NOTIFICATION_COOLDOWN",
  "PROTECTION_NOT_ACTIVE",
  "INCIDENT_NOT_OPEN",
]);

function fail(code) {
  return new AccountApiError(code);
}

function strictObject(input, requiredKeys, optionalKeys = []) {
  if (input === null || typeof input !== "object" || Array.isArray(input)) throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
  const keys = Object.keys(input);
  if (keys.some((key) => !requiredKeys.includes(key) && !optionalKeys.includes(key)) ||
      requiredKeys.some((key) => !Object.hasOwn(input, key))) {
    throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
  }
  return input;
}

function boundedInteger(value, { minimum, maximum }) {
  if (!Number.isInteger(value) || value < minimum || value > maximum) throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
  return value;
}

function boundedString(value, { minimum = 1, maximum = MAXIMUM_REASON_CHARACTERS, pattern = null } = {}) {
  if (typeof value !== "string") throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
  if (value.length < minimum || value.length > maximum) throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
  if (/[\u0000-\u001f\u007f]/.test(value)) throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
  if (pattern && !pattern.test(value)) throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
  return value;
}

function enumeration(value, allowed) {
  if (!allowed.includes(value)) throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
  return value;
}

function referenceOf(kind, value) {
  return boundedString(value, { minimum: 8, maximum: 80, pattern: REFERENCE_PATTERNS[kind] });
}

function boundedReasons(reasons) {
  if (!Array.isArray(reasons) || reasons.length === 0 || reasons.length > MAXIMUM_REASONS) {
    throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
  }
  return reasons.map((reason) => boundedString(reason, { minimum: 1, maximum: MAXIMUM_REASON_CHARACTERS }));
}

function subjectFromContext(context, kind) {
  const reference = context?.references?.[kind];
  if (typeof reference !== "string" || !REFERENCE_PATTERNS[kind]?.test(reference)) return null;
  return reference;
}

function isoTimestamp(value) {
  return new Date(value).toISOString();
}

function insertAction(database, record) {
  const actionId = newSecurityActionId();
  database.prepare(
    `INSERT INTO security_actions
       (action_id, incident_id, action_type, tool_name, scope, subject_reference, authorized_by, policy_id,
        arguments_json, result, result_code, reversible, expires_at, released_at, occurred_at)
     VALUES (?, ?, ?, ?, ?, ?, 'SECURITY_POLICY', ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    actionId,
    record.incidentId,
    record.actionType,
    record.toolName,
    record.scope,
    record.subjectReference ?? null,
    record.policyId,
    record.argumentsJson,
    record.result,
    record.resultCode ?? null,
    record.reversible ? 1 : 0,
    record.expiresAt ?? null,
    null,
    record.occurredAt,
  );
  return actionId;
}

/** Applies (or tightens) one temporary protection. DENY is impossible for any scope except SOURCE, by validation here
 *  and by a database CHECK constraint, so an ambiguous signal can never hard-lock an account. */
function applyProtection(database, configuration, { arguments: args, authorization, context, now }) {
  const scope = enumeration(args.scope, SCOPE_VALUES);
  const mode = enumeration(args.mode ?? "THROTTLE", MODE_VALUES);
  if (mode === "DENY" && scope !== "SOURCE") throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
  const category = enumeration(args.category, PROTECTION_CATEGORIES);
  const subjectKind = enumeration(args.subjectKind ?? scope, SUBJECT_KINDS);
  const subjectReference = args.subjectReference ?? subjectFromContext(context, subjectKind);
  if (!subjectReference) return { result: "SKIPPED", resultCode: "NO_SUBJECT_REFERENCE", reversible: true };
  const maximum = boundedInteger(args.maximum, { minimum: 1, maximum: configuration.security.response.throttleMaximum * 2 });
  const windowMs = boundedInteger(args.windowMs, { minimum: 1000, maximum: 3_600_000 });
  const durationMs = boundedInteger(args.durationMs, { minimum: 1000, maximum: configuration.security.response.maximumProtectionSeconds * 1000 });
  const reason = boundedString(args.reason, { minimum: 1, maximum: configuration.security.bounds.reasonCharacters });
  const expiresAt = isoTimestamp(now + durationMs);

  const existing = database.prepare(
    `SELECT * FROM security_rate_limit_state
      WHERE subject_reference = ? AND category = ? AND released_at IS NULL AND expires_at > ?
      ORDER BY applied_at DESC LIMIT 1`,
  ).get(subjectReference, category, isoTimestamp(now));
  if (existing) {
    // The tighter bound always wins; an existing hard rejection is never downgraded to a throttle.
    const nextMode = existing.mode === "DENY" ? "DENY" : mode;
    const nextMaximum = mode === "DENY" || existing.mode === "DENY" ? 1 : Math.min(existing.maximum, maximum);
    const nextExpiry = Date.parse(existing.expires_at) > Date.parse(expiresAt) ? existing.expires_at : expiresAt;
    database.prepare(
      `UPDATE security_rate_limit_state SET mode = ?, maximum = ?, window_ms = ?, expires_at = ?,
         policy_id = ?, reason = ?, incident_id = COALESCE(incident_id, ?)
       WHERE protection_id = ?`,
    ).run(nextMode, nextMaximum, Math.max(existing.window_ms, windowMs), nextExpiry, authorization.policyId,
      reason, context?.incidentId ?? null, existing.protection_id);
    return { result: "APPLIED", resultCode: "PROTECTION_TIGHTENED", protectionId: existing.protection_id, expiresAt: nextExpiry, reversible: true };
  }
  const protectionId = newSecurityProtectionId();
  database.prepare(
    `INSERT INTO security_rate_limit_state
       (protection_id, incident_id, scope, subject_reference, category, mode, maximum, window_ms, reason, policy_id,
        applied_at, expires_at, released_at, release_reason)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)`,
  ).run(protectionId, context?.incidentId ?? null, scope, subjectReference, category, mode, maximum, windowMs,
    reason, authorization.policyId, isoTimestamp(now), expiresAt);
  return { result: "APPLIED", resultCode: "PROTECTION_APPLIED", protectionId, expiresAt, reversible: true };
}

const TOOLS = Object.freeze({
  "security.createIncident": {
    actionType: "SECURITY_INCIDENT_CREATED",
    scope: "INCIDENT",
    reversible: false,
    description: "Create or escalate the single active incident that deduplicates identical signals.",
    validate(args) {
      strictObject(args, ["severity", "threatCategory", "subjectKind", "subjectReference", "riskScore", "riskLevel", "reasons", "deduplicationKey", "observedCount", "thresholdsKey"]);
      return {
        severity: enumeration(args.severity, SEVERITY_VALUES),
        thresholdsKey: boundedString(args.thresholdsKey, { minimum: 3, maximum: 64, pattern: /^[A-Za-z]+$/ }),
        threatCategory: enumeration(args.threatCategory, THREAT_CATEGORIES),
        subjectKind: enumeration(args.subjectKind, SUBJECT_KINDS),
        subjectReference: referenceOf(args.subjectKind, args.subjectReference),
        riskScore: boundedInteger(args.riskScore, { minimum: 0, maximum: 100 }),
        riskLevel: enumeration(args.riskLevel, SEVERITY_VALUES),
        reasons: boundedReasons(args.reasons),
        observedCount: boundedInteger(args.observedCount, { minimum: 1, maximum: 1_000_000 }),
        deduplicationKey: boundedString(args.deduplicationKey, { minimum: 4, maximum: 200, pattern: /^[A-Za-z0-9_:-]+$/ }),
      };
    },
    execute(database, configuration, { arguments: args, context, now }) {
      const timestamp = isoTimestamp(now);
      const existing = database.prepare(
        `SELECT * FROM security_incidents WHERE dedup_key = ? AND status <> 'RESOLVED'
          ORDER BY detected_at DESC LIMIT 1`,
      ).get(args.deduplicationKey);
      if (existing) {
        const escalated = SEVERITY_VALUES.indexOf(args.severity) > SEVERITY_VALUES.indexOf(existing.severity);
        const severity = escalated ? args.severity : existing.severity;
        const reasons = [...new Set([...JSON.parse(existing.detection_reasons_json), ...args.reasons])]
          .slice(0, configuration.security.bounds.reasonsPerIncident);
        // An escalation is a new, more serious band that is contained immediately; sustained activity at the same or a
        // lower band after containment is what marks the incident as needing investigation.
        const status = !escalated && existing.status === "CONTAINED" ? "INVESTIGATING" : existing.status;
        database.prepare(
          `UPDATE security_incidents SET severity = ?, status = ?, risk_score = MAX(risk_score, ?),
             detection_reasons_json = ?, event_count = MAX(event_count, ?), last_activity_at = ?
           WHERE incident_id = ?`,
        ).run(severity, status, args.riskScore, JSON.stringify(reasons), args.observedCount, timestamp, existing.incident_id);
        return {
          result: "SKIPPED",
          resultCode: "INCIDENT_DEDUPLICATED",
          incidentId: existing.incident_id,
          incidentReference: existing.reference,
          severity,
          status,
          escalated: severity !== existing.severity,
          reversible: false,
        };
      }
      let inserted = null;
      for (let attempt = 0; attempt < 5 && !inserted; attempt += 1) {
        const incidentId = newSecurityIncidentId();
        try {
          database.prepare(
            `INSERT INTO security_incidents
               (incident_id, reference, severity, status, threat_category, subject_kind, subject_reference,
                risk_score, detection_reasons_json, dedup_key, event_count, correlation_id, detected_at,
                last_activity_at, contained_at, resolved_at, resolution, created_at)
             VALUES (?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)`,
          ).run(
            incidentId, newIncidentReference(), args.severity, args.threatCategory, args.subjectKind,
            args.subjectReference, args.riskScore, JSON.stringify(args.reasons), args.deduplicationKey,
            args.observedCount, context?.correlationId ?? "unspecified-correlation", timestamp, timestamp, timestamp,
          );
          inserted = { incidentId, reference: database.prepare("SELECT reference FROM security_incidents WHERE incident_id = ?").get(incidentId).reference };
        } catch (error) {
          if (attempt === 4) throw error;
        }
      }
      return {
        result: "APPLIED",
        resultCode: "INCIDENT_CREATED",
        incidentId: inserted.incidentId,
        incidentReference: inserted.reference,
        severity: args.severity,
        status: "OPEN",
        reversible: false,
      };
    },
  },
  "security.applyRateLimit": {
    actionType: "SECURITY_RATE_LIMIT_APPLIED",
    scope: "SOURCE",
    reversible: true,
    description: "Apply or tighten one temporary, bounded protection for a source, account, or session.",
    validate(args) {
      strictObject(args, ["scope", "category", "mode", "maximum", "windowMs", "durationMs", "reason"], ["subjectKind", "subjectReference"]);
      if (args.mode === "DENY" && args.scope !== "SOURCE") throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
      return args;
    },
    execute(database, configuration, options) {
      return applyProtection(database, configuration, options);
    },
  },
  "security.rejectAbusiveRequest": {
    actionType: "SECURITY_REQUEST_REJECTED",
    scope: "SOURCE",
    reversible: true,
    description: "Temporarily reject requests from one clearly abusive source; never applied to an account.",
    validate(args) {
      strictObject(args, ["scope", "category", "mode", "maximum", "windowMs", "durationMs", "reason"], ["subjectKind", "subjectReference"]);
      if (args.scope !== "SOURCE") throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
      return { ...args, mode: "DENY" };
    },
    execute(database, configuration, options) {
      return applyProtection(database, configuration, { ...options, arguments: { ...options.arguments, mode: "DENY" } });
    },
  },
  "security.revokeSession": {
    actionType: "SECURITY_SESSION_REVOKED",
    scope: "SESSION",
    reversible: false,
    description: "Revoke one suspicious or compromised session credential. The credential is rejected afterwards.",
    validate(args) {
      strictObject(args, ["durationMs", "reason"], ["subjectReference"]);
      return args;
    },
    execute(database, configuration, { arguments: args, context, now }) {
      const sessionReference = args.subjectReference ?? subjectFromContext(context, "SESSION");
      if (!sessionReference) return { result: "SKIPPED", resultCode: "NO_SESSION_REFERENCE", reversible: false };
      const timestamp = isoTimestamp(now);
      const table = sessionReference.startsWith("dvs_") ? "developer_sessions" : "sessions";
      const row = database.prepare(`SELECT revoked_at FROM ${table} WHERE session_id = ?`).get(sessionReference);
      if (!row) return { result: "SKIPPED", resultCode: "SESSION_NOT_FOUND", sessionReference, reversible: false };
      if (row.revoked_at !== null) return { result: "SKIPPED", resultCode: "SESSION_ALREADY_REVOKED", sessionReference, reversible: false };
      const revoked = database.prepare(`UPDATE ${table} SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL`)
        .run(timestamp, sessionReference);
      if (Number(revoked.changes ?? 0) !== 1) return { result: "SKIPPED", resultCode: "SESSION_ALREADY_REVOKED", sessionReference, reversible: false };
      return { result: "APPLIED", resultCode: "SESSION_REVOKED", sessionReference, reversedAt: timestamp, reversible: false };
    },
  },
  "security.protectAccount": {
    actionType: "SECURITY_ACCOUNT_PROTECTED",
    scope: "ACCOUNT",
    reversible: true,
    description: "Temporarily throttle a targeted account. This is a progressive, expiring protection — never a lockout or a ban.",
    validate(args) {
      strictObject(args, ["scope", "category", "mode", "maximum", "windowMs", "durationMs", "reason"], ["subjectKind", "subjectReference"]);
      if (args.scope !== "ACCOUNT") throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
      if (args.mode !== "THROTTLE") throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
      return args;
    },
    execute(database, configuration, options) {
      return applyProtection(database, configuration, options);
    },
  },
  "security.notifyDeveloper": {
    actionType: "SECURITY_ALERT_CREATED",
    scope: "DEVELOPER",
    reversible: false,
    description: "Create a bounded in-dashboard developer security alert; no email/SMS/push infrastructure is involved.",
    validate(args) {
      strictObject(args, ["priority"]);
      return { priority: enumeration(args.priority, SEVERITY_VALUES) };
    },
    execute(database, configuration, { arguments: args, context, now }) {
      const incident = database.prepare("SELECT * FROM security_incidents WHERE incident_id = ?").get(context?.incidentId);
      if (!incident) return { result: "SKIPPED", resultCode: "NO_INCIDENT_CONTEXT", reversible: false };
      const cooldownStart = new Date(now - configuration.security.response.notificationCooldownMs).toISOString();
      const recent = database.prepare(
        `SELECT notification_id FROM security_notifications
          WHERE incident_id = ? AND priority = ? AND created_at >= ? LIMIT 1`,
      ).get(incident.incident_id, args.priority, cooldownStart);
      if (recent) return { result: "SKIPPED", resultCode: "NOTIFICATION_COOLDOWN", reversible: false };
      const notificationId = newSecurityNotificationId();
      const title = `SECURITY ALERT: ${incident.threat_category} (${incident.severity})`;
      const body = [
        `Threat: ${incident.threat_category}`,
        `Incident: ${incident.reference}`,
        `Status: ${incident.status}`,
        `Risk score: ${incident.risk_score}`,
      ].join(" · ").slice(0, 512);
      database.prepare(
        `INSERT INTO security_notifications
           (notification_id, incident_id, priority, title, body, threat_category, status, created_at)
         VALUES (?, ?, ?, ?, ?, ?, 'UNREAD', ?)`,
      ).run(notificationId, incident.incident_id, args.priority, title.slice(0, 120), body, incident.threat_category, isoTimestamp(now));
      return { result: "APPLIED", resultCode: "ALERT_CREATED", notificationId, incidentReference: incident.reference, reversible: false };
    },
  },
  "security.releaseProtection": {
    actionType: "SECURITY_PROTECTION_RELEASED",
    scope: "SOURCE",
    reversible: false,
    description: "Release one temporary protection before its expiry. Automated protections are reversible by design.",
    validate(args) {
      strictObject(args, ["protectionId", "releaseReason"]);
      return {
        protectionId: boundedString(args.protectionId, { minimum: 40, maximum: 48, pattern: /^prt_[0-9a-f-]{36}$/ }),
        releaseReason: boundedString(args.releaseReason, { minimum: 1, maximum: 200 }),
      };
    },
    execute(database, configuration, { arguments: args, now }) {
      const released = database.prepare(
        `UPDATE security_rate_limit_state SET released_at = ?, release_reason = ?
          WHERE protection_id = ? AND released_at IS NULL`,
      ).run(isoTimestamp(now), args.releaseReason, args.protectionId);
      if (Number(released.changes ?? 0) !== 1) return { result: "SKIPPED", resultCode: "PROTECTION_NOT_ACTIVE", reversible: false };
      return { result: "APPLIED", resultCode: "PROTECTION_RELEASED", protectionId: args.protectionId, reversible: false };
    },
  },
  "security.resolveIncident": {
    actionType: "SECURITY_INCIDENT_RESOLVED",
    scope: "INCIDENT",
    reversible: false,
    description: "Close an incident with an explicit, human-readable resolution.",
    validate(args) {
      strictObject(args, ["incidentId", "resolution"]);
      return {
        incidentId: boundedString(args.incidentId, { minimum: 40, maximum: 48, pattern: /^inc_[0-9a-f-]{36}$/ }),
        resolution: enumeration(args.resolution, RESOLUTIONS),
      };
    },
    execute(database, configuration, { arguments: args, now }) {
      const timestamp = isoTimestamp(now);
      const resolved = database.prepare(
        `UPDATE security_incidents SET status = 'RESOLVED', resolved_at = ?, resolution = ?
          WHERE incident_id = ? AND status <> 'RESOLVED'`,
      ).run(timestamp, args.resolution, args.incidentId);
      if (Number(resolved.changes ?? 0) !== 1) return { result: "SKIPPED", resultCode: "INCIDENT_NOT_OPEN", reversible: false };
      return { result: "APPLIED", resultCode: "INCIDENT_RESOLVED", incidentId: args.incidentId, resolution: args.resolution, reversible: false };
    },
  },
});

function requiresIncident(toolName) {
  return toolName !== "security.createIncident";
}

/**
 * Validates, authorizes, executes, and audits exactly one registered security action.
 *
 * @param {object} options
 * @param {import('node:sqlite').DatabaseSync} options.database
 * @param {object} options.configuration
 * @param {{ tool: string, arguments: object }} options.invocation the requested tool and its untrusted arguments
 * @param {object} options.authorization an envelope produced only by `security-policy.js`
 * @param {{ correlationId?: string, incidentId?: string|null, references?: object }} options.context
 */
export function invokeSecurityTool({ database, configuration, invocation, authorization, context = {}, now = Date.now() }) {
  if (invocation === null || typeof invocation !== "object" || Array.isArray(invocation) ||
      Object.keys(invocation).length !== 2 || typeof invocation.tool !== "string" || !Object.hasOwn(invocation, "arguments")) {
    throw fail(ErrorCode.SECURITY_ACTION_ARGUMENTS_INVALID);
  }
  const tool = Object.hasOwn(TOOLS, invocation.tool) ? TOOLS[invocation.tool] : null;
  // Fail closed: an unregistered name (including anything an AI or client invents) is refused before any effect.
  if (!tool) throw fail(ErrorCode.SECURITY_ACTION_UNKNOWN);
  if (!isSecurityAuthorizationValid(authorization, invocation.tool, { now })) {
    throw fail(ErrorCode.SECURITY_ACTION_NOT_AUTHORIZED);
  }
  if (requiresIncident(invocation.tool) && typeof context.incidentId !== "string") {
    throw fail(ErrorCode.SECURITY_ACTION_NOT_AUTHORIZED);
  }
  const args = tool.validate(invocation.arguments ?? {});
  const outcome = tool.execute(database, configuration, { arguments: args, authorization, context, now });
  const result = outcome?.result === "APPLIED" ? "APPLIED" : "SKIPPED";
  const actionId = insertAction(database, {
    incidentId: outcome?.incidentId ?? context.incidentId,
    actionType: tool.actionType,
    toolName: invocation.tool,
    scope: tool.scope,
    subjectReference: outcome?.sessionReference ?? outcome?.protectionId ?? null,
    policyId: authorization.policyId,
    argumentsJson: JSON.stringify({
      scope: tool.scope,
      mode: args.mode ?? null,
      maximum: args.maximum ?? null,
      durationMs: args.durationMs ?? null,
    }).slice(0, 1024),
    result,
    resultCode: outcome?.resultCode ?? null,
    reversible: Boolean(outcome?.reversible),
    expiresAt: outcome?.expiresAt ?? null,
    occurredAt: isoTimestamp(now),
  });
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM_SECURITY,
    actionType: tool.actionType,
    targetUserId: args.subjectReference?.startsWith("usr_") ? args.subjectReference : context?.references?.ACCOUNT?.startsWith("usr_") ? context.references.ACCOUNT : null,
    incidentId: outcome?.incidentId ?? context.incidentId ?? null,
    outcome: result === "APPLIED" || BENIGN_SKIP_CODES.has(outcome?.resultCode)
      ? AUDIT_OUTCOME.SUCCESS
      : AUDIT_OUTCOME.FAILURE,
    metadata: {
      policyId: authorization.policyId,
      tool: invocation.tool,
      resultCode: outcome?.resultCode ?? "UNKNOWN",
      riskLevel: authorization.riskLevel,
    },
    occurredAt: isoTimestamp(now),
  });
  return Object.freeze({
    actionId,
    tool: invocation.tool,
    actionType: tool.actionType,
    result,
    resultCode: outcome?.resultCode ?? "UNKNOWN",
    incidentId: outcome?.incidentId ?? context.incidentId ?? null,
    incidentReference: outcome?.incidentReference ?? null,
    protectionId: outcome?.protectionId ?? null,
    notificationId: outcome?.notificationId ?? null,
    sessionReference: outcome?.sessionReference ?? null,
    expiresAt: outcome?.expiresAt ?? null,
    reversible: Boolean(outcome?.reversible),
  });
}

/** The closed tool contract, exposed for documentation and tests — never a database handle or executable function. */
export function securityToolSpecifications() {
  return Object.entries(TOOLS).map(([name, tool]) => ({
    name,
    actionType: tool.actionType,
    scope: tool.scope,
    reversible: tool.reversible,
    description: tool.description,
  }));
}

export {
  TOOLS as SECURITY_RESPONSE_TOOLS,
  THREAT_CATEGORIES as SECURITY_THREAT_CATEGORIES,
  RESOLUTIONS as SECURITY_INCIDENT_RESOLUTIONS,
};
