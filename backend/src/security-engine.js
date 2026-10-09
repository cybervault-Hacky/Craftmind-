/**
 * The autonomous security engine.
 *
 * One boundary that receives normalized signals, evaluates deterministic rules, scores risk, applies the predefined
 * response policy through authorized tools, records the incident/audit/notification trail, and answers the developer
 * Security Center queries. It is intentionally local and self-contained: no AI provider, no network call, and no human
 * approval is required for a predefined critical protection to run.
 *
 * Failure behaviour is fail-safe. Every ingestion path is wrapped: if the engine cannot evaluate a signal it logs a
 * bounded event type and returns, so the request continues under the existing authentication and rate-limit
 * protections. Enforcement fails closed — a bounded protection that is found is applied, a lookup that errors is
 * treated as "no bypass" rather than "allowed" because the pre-existing limits remain in force.
 */

import { randomUUID } from "node:crypto";
import { AccountApiError, ErrorCode } from "./errors.js";
import { canonicalizeEmail, developerTokenDigest, securityAccountDigest, securitySourceDigest, tokenDigest } from "./ids.js";
import {
  cleanupExpiredSecurityEvents,
  coalesceSecurityEvent,
  countSecurityEvents,
  listSecurityEvents,
  normalizeSecurityEvent,
  persistSecurityEvent,
} from "./security-events.js";
import { dedupKeyFor, evaluateThreats, gatherRiskContext } from "./security-detection.js";
import { scoreThreat } from "./security-risk.js";
import { SECURITY_TOOL, authorizeSecurityAction, decideSecurityResponse } from "./security-policy.js";
import { invokeSecurityTool, securityToolSpecifications } from "./security-tools.js";

const DAY_MS = 86_400_000;
const PROTECTIVE_TOOLS = new Set([
  SECURITY_TOOL.APPLY_RATE_LIMIT,
  SECURITY_TOOL.REJECT_ABUSIVE_REQUEST,
  SECURITY_TOOL.REVOKE_SESSION,
  SECURITY_TOOL.PROTECT_ACCOUNT,
]);
const SESSION_FAILURE_CODES = new Set([
  ErrorCode.SESSION_INVALID,
  ErrorCode.SESSION_EXPIRED,
  ErrorCode.REFRESH_FAILED,
  ErrorCode.DEVELOPER_SESSION_EXPIRED,
  ErrorCode.DEVELOPER_REFRESH_FAILED,
  ErrorCode.AUTHENTICATION_REQUIRED,
  ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED,
]);

export class SecurityEngine {
  #deduplication = new Map();
  #requestWindows = new Map();
  #respondedBands = new Map();
  #maximumTrackedSources = 5000;

  constructor({ database, configuration, logger = console, now = Date.now }) {
    this.database = database;
    this.configuration = configuration;
    this.logger = logger;
    this.now = now;
  }

  #iso(timestamp = this.now()) {
    return new Date(timestamp).toISOString();
  }

  #transaction(operation) {
    this.database.exec("BEGIN IMMEDIATE");
    try {
      const result = operation();
      this.database.exec("COMMIT");
      return result;
    } catch (error) {
      try { this.database.exec("ROLLBACK"); } catch { /* the transaction is already gone */ }
      throw error;
    }
  }

  #logFailure(event, errorType, extra = {}) {
    this.logger.warn?.(JSON.stringify({ event, errorType, ...extra }));
  }

  #bounded(map, now, windowMs) {
    if (map.size < this.#maximumTrackedSources) return;
    for (const [key, entry] of map) {
      const seenAt = typeof entry === "number" ? entry : entry.seenAt;
      if (now - seenAt >= windowMs) map.delete(key);
    }
    if (map.size >= this.#maximumTrackedSources) map.clear();
  }

  /**
   * True when a repeated identical signal should be stored as a new row; otherwise the caller coalesces it into the
   * event that already represents this (event type, subject) pair inside the deduplication window.
   */
  #shouldStore(event, now) {
    const key = `${event.eventType}:${event.accountReference ?? event.sessionReference ?? event.sourceReference ?? "none"}`;
    const windowMs = this.configuration.security.bounds.eventDeduplicationMs;
    const previous = this.#deduplication.get(key);
    if (previous !== undefined && now - previous.seenAt < windowMs) return false;
    this.#bounded(this.#deduplication, now, windowMs);
    this.#deduplication.set(key, { seenAt: now, eventId: event.eventId });
    return true;
  }

  #coalescedEventId(event, now) {
    const key = `${event.eventType}:${event.accountReference ?? event.sessionReference ?? event.sourceReference ?? "none"}`;
    const previous = this.#deduplication.get(key);
    return previous && now - previous.seenAt < this.configuration.security.bounds.eventDeduplicationMs
      ? previous.eventId
      : null;
  }

  /**
   * The response policy runs once per (incident signature, severity band) inside the incident deduplication window.
   * A flood of identical signals therefore cannot amplify audit/action writes without bound, while an escalation to a
   * higher band — including the predefined CRITICAL band — always runs its plan immediately.
   */
  #shouldRespond(dedupKey, level, now) {
    const key = `${dedupKey}:${level}`;
    const windowMs = this.configuration.security.bounds.incidentDeduplicationMs;
    const previous = this.#respondedBands.get(key);
    if (previous !== undefined && now - previous < windowMs) return false;
    this.#bounded(this.#respondedBands, now, windowMs);
    this.#respondedBands.set(key, now);
    return true;
  }

  #referencesFor(event) {
    return Object.freeze({
      ACCOUNT: event.accountReference,
      SESSION: event.sessionReference,
      SOURCE: event.sourceReference,
      DEVELOPER: event.accountReference?.startsWith("dvl_") ? event.accountReference : null,
      ROUTE: event.routeCategory,
    });
  }

  /**
   * Records one normalized signal and runs the detection/scoring/response path. Never throws: a security subsystem
   * failure must not turn into a request failure or an implicit bypass.
   */
  recordSignal(input) {
    let event;
    const now = this.now();
    try {
      event = normalizeSecurityEvent(input, {
        configuration: this.configuration,
        occurredAt: this.#iso(now),
        correlationId: input?.correlationId ?? randomUUID(),
      });
    } catch (error) {
      this.#logFailure("security_event_rejected", error?.name ?? "Error", { code: error instanceof AccountApiError ? error.code : "UNKNOWN" });
      return Object.freeze({ recorded: false, level: "LOW", incidentId: null, actions: [] });
    }
    const storeSeparately = this.#shouldStore(event, now);
    if (!storeSeparately) event = Object.freeze({ ...event, coalescedInto: this.#coalescedEventId(event, now) });
    try {
      return this.#transaction(() => this.#evaluateAndRespond(event, now, { storeSeparately }));
    } catch (error) {
      this.#logFailure("security_response_failed", error?.name ?? "Error", { eventType: event.eventType });
      return Object.freeze({ recorded: true, level: "LOW", incidentId: null, actions: [], failed: true });
    }
  }

  #evaluateAndRespond(event, now, { storeSeparately = true } = {}) {
    if (storeSeparately || !event.coalescedInto) {
      persistSecurityEvent(this.database, event);
    } else {
      coalesceSecurityEvent(this.database, event.coalescedInto);
    }
    const timestamp = this.#iso(now);
    // Write amplification guard: a source that already holds an active hard rejection is not re-evaluated on every
    // rejected request. The signal is still recorded (bounded by the ingest deduplication window); the incident,
    // protections, and audit trail already exist.
    if (event.sourceReference) {
      const contained = this.database.prepare(
        `SELECT protection_id FROM security_rate_limit_state
          WHERE subject_reference = ? AND mode = 'DENY' AND released_at IS NULL AND expires_at > ? LIMIT 1`,
      ).get(event.sourceReference, timestamp);
      if (contained) {
        return Object.freeze({ recorded: true, level: "LOW", incidentId: null, actions: [], alreadyContained: true, eventId: event.eventId });
      }
    }
    const assessments = evaluateThreats(this.database, this.configuration, { now: timestamp, event });
    if (assessments.length === 0) {
      return Object.freeze({ recorded: true, level: "LOW", incidentId: null, actions: [], eventId: event.eventId });
    }
    const primary = assessments[0];
    const assessment = Object.freeze({ ...primary, dedupKey: dedupKeyFor(primary) });
    const riskContext = gatherRiskContext(this.database, this.configuration, { now: timestamp, assessment });
    const risk = scoreThreat(assessment, riskContext);
    const summary = {
      recorded: true,
      eventId: event.eventId,
      level: risk.level,
      score: risk.score,
      reasons: risk.reasons,
      threatCategory: assessment.category,
      subjectKind: assessment.subjectKind,
    };
    if (!this.#shouldRespond(assessment.dedupKey, risk.level, now)) {
      // The band is unchanged and already handled: record the signal, do not re-run the same plan.
      const existing = this.database.prepare(
        "SELECT incident_id FROM security_incidents WHERE dedup_key = ? AND status <> 'RESOLVED' LIMIT 1",
      ).get(assessment.dedupKey);
      return Object.freeze({ ...summary, incidentId: existing?.incident_id ?? null, actions: Object.freeze([]), alreadyHandled: true });
    }
    const plan = decideSecurityResponse({
      assessment: { ...assessment, severity: risk.level },
      risk,
      configuration: this.configuration,
    });
    const references = this.#referencesFor(event);

    let incidentId = null;
    let protectiveActionApplied = false;
    const applied = [];
    for (const action of plan.actions) {
      const authorization = authorizeSecurityAction({ plan, toolName: action.tool, configuration: this.configuration, now });
      try {
        const outcome = invokeSecurityTool({
          database: this.database,
          configuration: this.configuration,
          invocation: { tool: action.tool, arguments: { ...action.arguments } },
          authorization,
          context: { correlationId: event.correlationId, incidentId, references },
          now,
        });
        if (action.tool === SECURITY_TOOL.CREATE_INCIDENT) incidentId = outcome.incidentId;
        if (outcome.result === "APPLIED" && PROTECTIVE_TOOLS.has(action.tool)) protectiveActionApplied = true;
        applied.push(outcome);
      } catch (error) {
        // One failed action must not cancel the rest of the plan; the failure is auditable through its own absence.
        this.#logFailure("security_action_failed", error?.name ?? "Error", { tool: action.tool });
      }
    }

    if (incidentId) {
      this.database.prepare(
        `UPDATE security_events SET incident_id = ?
          WHERE incident_id IS NULL AND (event_id = ? OR (source_reference IS NOT NULL AND source_reference = ?) OR (account_reference IS NOT NULL AND account_reference = ?))`,
      ).run(incidentId, event.eventId, event.sourceReference, event.accountReference);
      if (protectiveActionApplied) {
        this.database.prepare(
          `UPDATE security_incidents SET status = 'CONTAINED', contained_at = COALESCE(contained_at, ?)
            WHERE incident_id = ? AND status = 'OPEN'`,
        ).run(timestamp, incidentId);
      }
    }

    return Object.freeze({ ...summary, incidentId, actions: Object.freeze(applied) });
  }

  /**
   * Counts requests per source inside a bounded in-memory window and emits one burst signal when a source crosses the
   * configured threshold. The map is capped, so an attacker cannot grow process memory through this path.
   */
  noteRequest({ sourceReference, accountReference = null, routeCategory = null, correlationId }) {
    try {
      const burst = this.configuration.security.thresholds.requestBurst;
      const now = this.now();
      const existing = this.#requestWindows.get(sourceReference);
      let window = existing;
      if (!window || now - window.startedAt >= burst.windowMs) {
        if (this.#requestWindows.size >= this.#maximumTrackedSources) {
          for (const [key, entry] of this.#requestWindows) {
            if (now - entry.startedAt >= burst.windowMs) this.#requestWindows.delete(key);
          }
          if (this.#requestWindows.size >= this.#maximumTrackedSources) this.#requestWindows.clear();
        }
        window = { startedAt: now, count: 0, announced: false };
        this.#requestWindows.set(sourceReference, window);
      }
      window.count += 1;
      if (window.count < burst.medium || window.announced) return null;
      window.announced = true;
      return this.recordSignal({
        eventType: "REQUEST_BURST",
        result: "THROTTLED",
        sourceReference,
        accountReference,
        routeCategory,
        correlationId: correlationId ?? randomUUID(),
        metadata: { requests: window.count, windowMs: burst.windowMs },
      });
    } catch (error) {
      this.#logFailure("security_burst_evaluation_failed", error?.name ?? "Error");
      return null;
    }
  }

  /** Safe, internal classification of a rejected session credential. Nothing here is returned to a client. */
  classifySessionFailure({ kind, token }) {
    if (typeof token !== "string" || token.length === 0) return Object.freeze({ state: "UNKNOWN" });
    const digest = kind === "developer"
      ? developerTokenDigest(this.configuration.authSecret, "access-v1", token)
      : tokenDigest(this.configuration.authSecret, token);
    const table = kind === "developer" ? "developer_sessions" : "sessions";
    const columns = kind === "developer"
      ? "session_id, developer_id AS owner_id, revoked_at, access_expires_at AS expiry_at"
      : "session_id, user_id AS owner_id, revoked_at, access_expires_at AS expiry_at";
    let row = null;
    try {
      row = this.database.prepare(`SELECT ${columns} FROM ${table} WHERE access_digest = ?`).get(digest) ?? null;
      if (!row) row = this.database.prepare(`SELECT ${columns} FROM ${table} WHERE refresh_digest = ?`).get(digest) ?? null;
    } catch (error) {
      this.#logFailure("security_session_classification_failed", error?.name ?? "Error");
      return Object.freeze({ state: "UNKNOWN" });
    }
    if (!row) return Object.freeze({ state: "INVALID" });
    const state = row.revoked_at !== null
      ? "REVOKED"
      : (Number.isFinite(Date.parse(row.expiry_at)) && Date.parse(row.expiry_at) <= this.now() ? "EXPIRED" : "ACTIVE");
    return Object.freeze({
      state,
      sessionReference: row.session_id,
      accountReference: row.owner_id,
    });
  }

  /** The digest the security layer stores for a request source. Raw client addresses are never persisted. */
  sourceDigestFor(request) {
    return securitySourceDigest(this.configuration.authSecret, request?.socket?.remoteAddress ?? "unknown");
  }

  /**
   * Resolves the account correlation reference used for protections and detection: the real opaque account id when the
   * address exists, otherwise a keyed digest so repeated attempts against one address still correlate without the
   * address itself ever being stored.
   */
  accountReferenceForEmail(email, { kind = "user" } = {}) {
    const canonical = canonicalizeEmail(email);
    const table = kind === "developer" ? "developer_accounts" : "users";
    const column = kind === "developer" ? "developer_id" : "user_id";
    try {
      const row = this.database.prepare(`SELECT ${column} AS id FROM ${table} WHERE email_canonical = ?`).get(canonical);
      if (row?.id) return row.id;
    } catch (error) {
      this.#logFailure("security_account_lookup_failed", error?.name ?? "Error");
    }
    return securityAccountDigest(this.configuration.authSecret, canonical);
  }

  /**
   * Returns the tightest active protection that covers this request, or null. DENY wins over THROTTLE and `ANY`
   * category protections cover every category.
   */
  activeProtection({ sourceReference = null, accountReference = null, sessionReference = null, category }) {
    const subjects = [sourceReference, accountReference, sessionReference].filter(Boolean);
    if (subjects.length === 0) return null;
    const placeholders = subjects.map(() => "?").join(", ");
    const row = this.database.prepare(
      `SELECT * FROM security_rate_limit_state
        WHERE released_at IS NULL AND expires_at > ? AND subject_reference IN (${placeholders})
          AND (category = ? OR category = 'ANY')
        ORDER BY CASE mode WHEN 'DENY' THEN 0 ELSE 1 END, maximum ASC
        LIMIT 1`,
    ).get(this.#iso(), ...subjects, category);
    return row ?? null;
  }

  /**
   * Applies active protections to a request. A DENY rejects it outright; a THROTTLE consumes from the existing
   * process-local limiter with the tightened bound, so the stricter of the two always governs.
   */
  enforceProtection({ sourceReference = null, accountReference = null, sessionReference = null, category, rateLimiter = null }) {
    let protection;
    try {
      protection = this.activeProtection({ sourceReference, accountReference, sessionReference, category });
    } catch (error) {
      // Fail closed in the sense that matters: nothing is released and the pre-existing limits still run. Enforcement
      // never converts an internal error into "no protection exists".
      this.#logFailure("security_protection_lookup_failed", error?.name ?? "Error");
      return { enforced: false, mode: "UNKNOWN" };
    }
    if (!protection) return { enforced: false, mode: "NONE" };
    const retryAfterSeconds = Math.max(1, Math.ceil((Date.parse(protection.expires_at) - this.now()) / 1000));
    // Every error an autonomous protection raises is tagged: the caller must not record it as fresh evidence of abuse,
    // or the defensive response would itself escalate into new incidents (a feedback loop).
    if (protection.mode === "DENY") {
      const error = new AccountApiError(ErrorCode.RATE_LIMITED, undefined, { retryAfterSeconds });
      error.securityProtectionId = protection.protection_id;
      throw error;
    }
    if (rateLimiter) {
      try {
        rateLimiter.consume(`security-protection:${category}`, [protection.subject_reference, protection.protection_id], {
          maximum: protection.maximum,
          windowMs: protection.window_ms,
        });
      } catch (error) {
        if (error && typeof error === "object") error.securityProtectionId = protection.protection_id;
        throw error;
      }
    }
    return { enforced: true, mode: protection.mode, protectionId: protection.protection_id };
  }

  /**
   * Releases one protection early through the same policy-authorized tool path used everywhere else. Automated
   * protections are reversible by design; expiry is the default reversibility, and this is the explicit one used when
   * an incident is resolved or a developer decides the response can end.
   */
  releaseProtection({ protectionId, reason = "released after containment review" }) {
    const now = this.now();
    const plan = Object.freeze({
      policyId: "AUTO_PROTECTION_RELEASE_MEDIUM",
      threatCategory: "REQUEST_ABUSE",
      riskLevel: "MEDIUM",
      actions: Object.freeze([Object.freeze({ tool: SECURITY_TOOL.RELEASE_PROTECTION, arguments: Object.freeze({}) })]),
    });
    const authorization = authorizeSecurityAction({ plan, toolName: SECURITY_TOOL.RELEASE_PROTECTION, configuration: this.configuration, now });
    const protection = this.database.prepare("SELECT incident_id FROM security_rate_limit_state WHERE protection_id = ?").get(protectionId);
    return this.#transaction(() => invokeSecurityTool({
      database: this.database,
      configuration: this.configuration,
      invocation: { tool: SECURITY_TOOL.RELEASE_PROTECTION, arguments: { protectionId, releaseReason: String(reason).slice(0, 200) } },
      authorization,
      context: { correlationId: "protection-release", incidentId: protection?.incident_id ?? null, references: {} },
      now,
    }));
  }

  /** Releases expired protections explicitly so the reversible/temporary contract is visible in the data trail. */
  releaseExpiredProtections() {
    const timestamp = this.#iso();
    const released = this.database.prepare(
      `UPDATE security_rate_limit_state SET released_at = ?, release_reason = 'EXPIRED'
        WHERE released_at IS NULL AND expires_at <= ?`,
    ).run(timestamp, timestamp);
    return Number(released.changes ?? 0);
  }

  /** Closes incidents that stayed contained with no further activity inside the configured window. */
  resolveQuietIncidents({ quietMs = 3_600_000, limit = 100 } = {}) {
    const timestamp = this.#iso();
    const cutoff = new Date(this.now() - quietMs).toISOString();
    const rows = this.database.prepare(
      `SELECT incident_id FROM security_incidents
        WHERE status IN ('OPEN', 'CONTAINED') AND last_activity_at <= ? ORDER BY last_activity_at LIMIT ?`,
    ).all(cutoff, limit);
    let resolved = 0;
    for (const row of rows) {
      // Closing an incident also releases the protections it applied: containment ends with the incident.
      for (const protection of this.database.prepare(
        "SELECT protection_id FROM security_rate_limit_state WHERE incident_id = ? AND released_at IS NULL",
      ).all(row.incident_id)) {
        try {
          this.releaseProtection({ protectionId: protection.protection_id, reason: "incident auto-resolved with no further activity" });
        } catch (error) {
          this.#logFailure("security_protection_release_failed", error?.name ?? "Error");
        }
      }
      const now = this.now();
      const plan = Object.freeze({
        policyId: "AUTO_INCIDENT_CLOSURE_MEDIUM",
        threatCategory: "REQUEST_ABUSE",
        riskLevel: "MEDIUM",
        actions: Object.freeze([Object.freeze({ tool: SECURITY_TOOL.RESOLVE_INCIDENT, arguments: Object.freeze({}) })]),
      });
      const authorization = authorizeSecurityAction({ plan, toolName: SECURITY_TOOL.RESOLVE_INCIDENT, configuration: this.configuration, now });
      try {
        const outcome = invokeSecurityTool({
          database: this.database,
          configuration: this.configuration,
          invocation: {
            tool: SECURITY_TOOL.RESOLVE_INCIDENT,
            arguments: { incidentId: row.incident_id, resolution: "AUTO_RESOLVED_NO_FURTHER_ACTIVITY" },
          },
          authorization,
          context: { correlationId: "auto-closure", incidentId: row.incident_id, references: {} },
          now,
        });
        if (outcome.result === "APPLIED") resolved += 1;
      } catch (error) {
        this.#logFailure("security_incident_closure_failed", error?.name ?? "Error");
      }
    }
    return { resolved, closedAt: timestamp };
  }

  /** Bounded retention for events, notifications, resolved incidents, and released protections. */
  cleanup() {
    const nowMillis = this.now();
    const retention = this.configuration.security.retention;
    const events = cleanupExpiredSecurityEvents(this.database, nowMillis, retention.eventsSeconds);
    const notifications = this.database.prepare("DELETE FROM security_notifications WHERE created_at <= ?")
      .run(new Date(nowMillis - retention.notificationsSeconds * 1000).toISOString());
    const incidents = this.database.prepare(
      "DELETE FROM security_incidents WHERE status = 'RESOLVED' AND resolved_at <= ? AND NOT EXISTS (SELECT 1 FROM security_actions a WHERE a.incident_id = security_incidents.incident_id)",
    ).run(new Date(nowMillis - retention.resolvedIncidentsSeconds * 1000).toISOString());
    const protections = this.database.prepare(
      "DELETE FROM security_rate_limit_state WHERE released_at IS NOT NULL AND released_at <= ?",
    ).run(new Date(nowMillis - retention.releasedProtectionSeconds * 1000).toISOString());
    this.releaseExpiredProtections();
    return {
      events: Number(events ?? 0),
      notifications: Number(notifications.changes ?? 0),
      incidents: Number(incidents.changes ?? 0),
      protections: Number(protections.changes ?? 0),
    };
  }

  /**
   * Boundedness probe for verification: both in-memory trackers are capped, so an attacker cannot grow process memory
   * through security-event ingestion or request counting. Exposed as sizes only — never as the tracked data itself.
   */
  trackedStateSizes() {
    return Object.freeze({
      deduplication: this.#deduplication.size,
      requestWindows: this.#requestWindows.size,
      capacity: this.#maximumTrackedSources,
    });
  }

  // ------------------------------------------------------------------------------------------------ Security Center

  overview() {
    const database = this.database;
    const nowIso = this.#iso();
    const dayAgo = new Date(this.now() - DAY_MS).toISOString();
    const count = (sql, ...parameters) => Number(database.prepare(sql).get(...parameters)?.count ?? 0);
    const openIncidents = count("SELECT COUNT(*) AS count FROM security_incidents WHERE status = 'OPEN'");
    const containedIncidents = count("SELECT COUNT(*) AS count FROM security_incidents WHERE status = 'CONTAINED'");
    const investigatingIncidents = count("SELECT COUNT(*) AS count FROM security_incidents WHERE status = 'INVESTIGATING'");
    const criticalIncidents = count("SELECT COUNT(*) AS count FROM security_incidents WHERE severity = 'CRITICAL' AND status <> 'RESOLVED'");
    const resolvedIncidents = count("SELECT COUNT(*) AS count FROM security_incidents WHERE status = 'RESOLVED'");
    const eventsLast24h = countSecurityEvents(database, { since: dayAgo });
    const securityEventsLast24h = countSecurityEvents(database, { since: dayAgo, eventType: "AUTHENTICATION_FAILED" })
      + countSecurityEvents(database, { since: dayAgo, eventType: "DEVELOPER_AUTHENTICATION_FAILED" });
    const automatedActionsLast24h = count("SELECT COUNT(*) AS count FROM security_actions WHERE occurred_at >= ?", dayAgo);
    const activeProtections = count(
      "SELECT COUNT(*) AS count FROM security_rate_limit_state WHERE released_at IS NULL AND expires_at > ?", nowIso);
    const unreadNotifications = count("SELECT COUNT(*) AS count FROM security_notifications WHERE status = 'UNREAD'");
    const lastIncident = database.prepare(
      "SELECT reference, severity, threat_category, detected_at FROM security_incidents ORDER BY detected_at DESC LIMIT 1",
    ).get() ?? null;
    return {
      status: criticalIncidents > 0 ? "CRITICAL_ATTENTION" : openIncidents + investigatingIncidents > 0 ? "MONITORING" : "STABLE",
      openIncidents,
      containedIncidents,
      investigatingIncidents,
      criticalIncidents,
      resolvedIncidents,
      eventsLast24h,
      authenticationFailuresLast24h: securityEventsLast24h,
      automatedActionsLast24h,
      activeProtections,
      unreadNotifications,
      lastIncident: lastIncident ? {
        reference: lastIncident.reference,
        severity: lastIncident.severity,
        threatCategory: lastIncident.threat_category,
        detectedAt: lastIncident.detected_at,
      } : null,
      engine: {
        detection: "DETERMINISTIC_LOCAL_RULES",
        aiDependent: false,
        automatedCriticalResponse: true,
        developerConfirmationRequiredForAutomatedActions: false,
        rateLimitScope: "process-local",
        registeredResponseTools: securityToolSpecifications().map((tool) => tool.name),
      },
    };
  }

  listIncidents({ limit = 25, status = null, severity = null } = {}) {
    const clauses = [];
    const parameters = [];
    if (status) { clauses.push("status = ?"); parameters.push(status); }
    if (severity) { clauses.push("severity = ?"); parameters.push(severity); }
    const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
    return this.database.prepare(
      `SELECT * FROM security_incidents ${where} ORDER BY last_activity_at DESC, incident_id DESC LIMIT ?`,
    ).all(...parameters, limit).map((row) => this.#publicIncident(row));
  }

  #publicIncident(row) {
    let reasons = [];
    try { reasons = JSON.parse(row.detection_reasons_json); } catch { reasons = []; }
    return {
      incidentId: row.incident_id,
      reference: row.reference,
      severity: row.severity,
      status: row.status,
      threatCategory: row.threat_category,
      subjectKind: row.subject_kind,
      subjectReference: row.subject_reference,
      riskScore: row.risk_score,
      reasons: Array.isArray(reasons) ? reasons.slice(0, this.configuration.security.bounds.reasonsPerIncident) : [],
      eventCount: row.event_count,
      correlationId: row.correlation_id,
      detectedAt: row.detected_at,
      lastActivityAt: row.last_activity_at,
      containedAt: row.contained_at,
      resolvedAt: row.resolved_at,
      resolution: row.resolution,
    };
  }

  getIncident(incidentId) {
    const row = this.database.prepare("SELECT * FROM security_incidents WHERE incident_id = ?").get(incidentId);
    if (!row) throw new AccountApiError(ErrorCode.SECURITY_INCIDENT_NOT_FOUND);
    const incident = this.#publicIncident(row);
    return {
      incident,
      events: listSecurityEvents(this.database, { limit: 20 }).filter((event) => event.incidentId === incidentId).slice(0, 20),
      actions: this.listActions({ incidentId, limit: 20 }),
      audit: this.database.prepare(
        `SELECT action_type, outcome, incident_id, occurred_at, metadata_json
           FROM admin_audit_log WHERE incident_id = ? ORDER BY occurred_at DESC LIMIT 20`,
      ).all(incidentId).map((entry) => {
        let metadata = {};
        try { metadata = JSON.parse(entry.metadata_json); } catch { metadata = {}; }
        return {
          action: entry.action_type,
          outcome: entry.outcome,
          occurredAt: entry.occurred_at,
          metadata,
        };
      }),
    };
  }

  listEvents({ limit = 50, severity = null, sourceCategory = null, eventType = null } = {}) {
    return listSecurityEvents(this.database, { limit, severity, sourceCategory, eventType });
  }

  listActions({ limit = 50, incidentId = null } = {}) {
    const where = incidentId ? "WHERE incident_id = ?" : "";
    const parameters = incidentId ? [incidentId, limit] : [limit];
    return this.database.prepare(
      `SELECT * FROM security_actions ${where} ORDER BY occurred_at DESC, action_id DESC LIMIT ?`,
    ).all(...parameters).map((row) => {
      let argumentsValue = {};
      try { argumentsValue = JSON.parse(row.arguments_json); } catch { argumentsValue = {}; }
      return {
        actionId: row.action_id,
        incidentId: row.incident_id,
        actionType: row.action_type,
        tool: row.tool_name,
        scope: row.scope,
        subjectReference: row.subject_reference,
        policyId: row.policy_id,
        arguments: argumentsValue,
        result: row.result,
        resultCode: row.result_code,
        reversible: row.reversible === 1,
        expiresAt: row.expires_at,
        releasedAt: row.released_at,
        occurredAt: row.occurred_at,
      };
    });
  }

  listNotifications({ limit = 25, status = null } = {}) {
    const parameters = status ? [status, limit] : [limit];
    return this.database.prepare(
      `SELECT n.*, i.reference AS incident_reference, i.status AS incident_status
         FROM security_notifications n
         LEFT JOIN security_incidents i ON i.incident_id = n.incident_id
         ${status ? "WHERE n.status = ?" : ""}
        ORDER BY n.created_at DESC, n.notification_id DESC LIMIT ?`,
    ).all(...parameters).map((row) => ({
      notificationId: row.notification_id,
      incidentReference: row.incident_reference,
      priority: row.priority,
      title: row.title,
      body: row.body,
      threatCategory: row.threat_category,
      status: row.status,
      incidentStatus: row.incident_status,
      createdAt: row.created_at,
    }));
  }

  /**
   * A bounded, secret-free security brief for the Developer AI boundary and the dashboard. It contains only stored,
   * already-sanitized fields: counts, categories, statuses, reasons, and opaque references.
   */
  buildSecurityBrief({ incidentId = null, eventLimit = 10 } = {}) {
    const overview = this.overview();
    const incidents = incidentId
      ? this.listIncidents({ limit: 1 }).filter((incident) => incident.incidentId === incidentId)
      : this.listIncidents({ limit: 5 });
    if (incidentId && incidents.length === 0) throw new AccountApiError(ErrorCode.SECURITY_INCIDENT_NOT_FOUND);
    const detail = incidentId ? this.getIncident(incidentId) : null;
    return Object.freeze({
      generatedAt: this.#iso(),
      status: overview.status,
      counts: {
        openIncidents: overview.openIncidents,
        containedIncidents: overview.containedIncidents,
        investigatingIncidents: overview.investigatingIncidents,
        criticalIncidents: overview.criticalIncidents,
        activeProtections: overview.activeProtections,
        unreadNotifications: overview.unreadNotifications,
        eventsLast24h: overview.eventsLast24h,
      },
      incidents: (detail ? [detail.incident] : incidents).map((incident) => ({
        reference: incident.reference,
        severity: incident.severity,
        status: incident.status,
        threatCategory: incident.threatCategory,
        subjectKind: incident.subjectKind,
        riskScore: incident.riskScore,
        reasons: incident.reasons,
        eventCount: incident.eventCount,
        detectedAt: incident.detectedAt,
        lastActivityAt: incident.lastActivityAt,
        resolution: incident.resolution,
      })),
      recentActions: (detail ? detail.actions : this.listActions({ limit: 5 })).map((action) => ({
        actionType: action.actionType,
        tool: action.tool,
        scope: action.scope,
        result: action.result,
        resultCode: action.resultCode,
        reversible: action.reversible,
        occurredAt: action.occurredAt,
      })),
      recentEvents: this.listEvents({ limit: eventLimit }).map((event) => ({
        eventType: event.eventType,
        severity: event.severity,
        sourceCategory: event.sourceCategory,
        result: event.result,
        routeCategory: event.routeCategory,
        occurredAt: event.occurredAt,
      })),
      registeredResponseTools: securityToolSpecifications().map((tool) => tool.name),
      rateLimitScope: "process-local",
    });
  }
}

export { SESSION_FAILURE_CODES };
