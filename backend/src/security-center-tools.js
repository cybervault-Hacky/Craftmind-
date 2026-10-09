/**
 * Developer-facing Security Center tools.
 *
 * These are read-only registry entries (`mutating: false`), so they run through the Phase 19 tool boundary: developer
 * session required, role checked, strict input schema, bounded page sizes, and one audit record per call. They expose
 * stored, already-sanitized security data only — never a raw credential, request body, client address, or secret.
 *
 * The automated response tools in `security-tools.js` are deliberately *not* reachable from here: a developer session
 * cannot invoke an autonomous security action, and the AI boundary cannot invoke either family.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { SECURITY_SOURCE_CATEGORY, SECURITY_EVENT_TYPE, SECURITY_SEVERITY_ORDER } from "./security-events.js";
import { SECURITY_THREAT_CATEGORIES } from "./security-tools.js";

const INCIDENT_STATUSES = Object.freeze(["OPEN", "CONTAINED", "INVESTIGATING", "RESOLVED"]);
const NOTIFICATION_STATUSES = Object.freeze(["UNREAD", "READ", "ACKNOWLEDGED"]);
const INCIDENT_ID_PATTERN = /^inc_[0-9a-f-]{36}$/;

function schemaFor(properties) {
  return { type: "object", properties, additionalProperties: false };
}

function strictObject(input, optionalKeys = []) {
  if (input === null || typeof input !== "object" || Array.isArray(input)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  if (Object.keys(input).some((key) => !optionalKeys.includes(key))) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return input;
}

function boundedLimit(value, { fallback, maximum }) {
  if (value === undefined) return fallback;
  if (!Number.isInteger(value) || value < 1 || value > maximum) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return value;
}

function enumerated(value, allowed) {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string" || !allowed.includes(value)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return value;
}

function requireEngine(context) {
  const engine = context?.securityEngine;
  if (!engine || typeof engine.overview !== "function") {
    throw new AccountApiError(ErrorCode.SECURITY_ENGINE_UNAVAILABLE);
  }
  return engine;
}

export const SECURITY_CENTER_TOOLS = Object.freeze({
  securityOverview: {
    audit: "security_overview", mutating: false, roles: ["OWNER", "ADMIN", "DEVELOPER"],
    description: "Read the Security Center overview: incident counts, active automated protections, and engine facts.",
    schema: schemaFor({}),
    resolve(input) { strictObject(input, []); return {}; },
    execute(database, args, actor, context) {
      const overview = requireEngine(context).overview();
      return { result: overview, auditMetadata: { openIncidents: overview.openIncidents, criticalIncidents: overview.criticalIncidents, activeProtections: overview.activeProtections } };
    },
  },
  listSecurityIncidents: {
    audit: "list_security_incidents", mutating: false, roles: ["OWNER", "ADMIN", "DEVELOPER"],
    description: "List security incidents newest first with an optional status or severity filter.",
    schema: schemaFor({
      limit: { type: "integer", minimum: 1, maximum: 100 },
      status: { type: "string", enum: [...INCIDENT_STATUSES] },
      severity: { type: "string", enum: [...SECURITY_SEVERITY_ORDER] },
    }),
    resolve(input) {
      strictObject(input, ["limit", "status", "severity"]);
      return {
        limit: boundedLimit(input.limit, { fallback: 25, maximum: 100 }),
        status: enumerated(input.status, INCIDENT_STATUSES),
        severity: enumerated(input.severity, SECURITY_SEVERITY_ORDER),
      };
    },
    execute(database, args, actor, context) {
      const incidents = requireEngine(context).listIncidents(args);
      return { result: { incidents }, auditMetadata: { returned: incidents.length, status: args.status ?? "ANY" } };
    },
  },
  getSecurityIncident: {
    audit: "get_security_incident", mutating: false, roles: ["OWNER", "ADMIN", "DEVELOPER"],
    description: "Read one incident with its detection reasons, automated actions, related events, and audit trail.",
    schema: schemaFor({ incidentId: { type: "string", pattern: "^inc_[0-9a-f-]{36}$" } }),
    resolve(input) {
      strictObject(input, ["incidentId"]);
      if (typeof input.incidentId !== "string" || !INCIDENT_ID_PATTERN.test(input.incidentId)) {
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      return { incidentId: input.incidentId };
    },
    execute(database, args, actor, context) {
      const detail = requireEngine(context).getIncident(args.incidentId);
      return { result: detail, auditMetadata: { reference: detail.incident.reference, status: detail.incident.status } };
    },
  },
  listSecurityEvents: {
    audit: "list_security_events", mutating: false, roles: ["OWNER", "ADMIN", "DEVELOPER"],
    description: "List bounded security event history with optional severity, category, or event-type filters.",
    schema: schemaFor({
      limit: { type: "integer", minimum: 1, maximum: 200 },
      severity: { type: "string", enum: [...SECURITY_SEVERITY_ORDER] },
      sourceCategory: { type: "string", enum: Object.values(SECURITY_SOURCE_CATEGORY) },
      eventType: { type: "string", enum: Object.values(SECURITY_EVENT_TYPE) },
    }),
    resolve(input) {
      strictObject(input, ["limit", "severity", "sourceCategory", "eventType"]);
      return {
        limit: boundedLimit(input.limit, { fallback: 50, maximum: 200 }),
        severity: enumerated(input.severity, SECURITY_SEVERITY_ORDER),
        sourceCategory: enumerated(input.sourceCategory, Object.values(SECURITY_SOURCE_CATEGORY)),
        eventType: enumerated(input.eventType, Object.values(SECURITY_EVENT_TYPE)),
      };
    },
    execute(database, args, actor, context) {
      const events = requireEngine(context).listEvents(args);
      return { result: { events }, auditMetadata: { returned: events.length, severity: args.severity ?? "ANY" } };
    },
  },
  listSecurityActions: {
    audit: "list_security_actions", mutating: false, roles: ["OWNER", "ADMIN", "DEVELOPER"],
    description: "List the automated protection actions that were applied, skipped, or failed, with their results.",
    schema: schemaFor({ limit: { type: "integer", minimum: 1, maximum: 100 }, incidentId: { type: "string", pattern: "^inc_[0-9a-f-]{36}$" } }),
    resolve(input) {
      strictObject(input, ["limit", "incidentId"]);
      if (input.incidentId !== undefined && (typeof input.incidentId !== "string" || !INCIDENT_ID_PATTERN.test(input.incidentId))) {
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      return { limit: boundedLimit(input.limit, { fallback: 50, maximum: 100 }), incidentId: input.incidentId ?? null };
    },
    execute(database, args, actor, context) {
      const actions = requireEngine(context).listActions(args);
      return { result: { actions }, auditMetadata: { returned: actions.length } };
    },
  },
  listSecurityNotifications: {
    audit: "list_security_notifications", mutating: false, roles: ["OWNER", "ADMIN", "DEVELOPER"],
    description: "List in-dashboard developer security alerts (the Phase 20 notification foundation).",
    schema: schemaFor({ limit: { type: "integer", minimum: 1, maximum: 100 }, status: { type: "string", enum: [...NOTIFICATION_STATUSES] } }),
    resolve(input) {
      strictObject(input, ["limit", "status"]);
      return {
        limit: boundedLimit(input.limit, { fallback: 25, maximum: 100 }),
        status: enumerated(input.status, NOTIFICATION_STATUSES),
      };
    },
    execute(database, args, actor, context) {
      const notifications = requireEngine(context).listNotifications(args);
      return { result: { notifications }, auditMetadata: { returned: notifications.length } };
    },
  },
});

export { INCIDENT_STATUSES, NOTIFICATION_STATUSES };
