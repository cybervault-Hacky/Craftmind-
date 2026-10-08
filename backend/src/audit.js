/**
 * The single append-only administrative audit boundary.
 *
 * Phase 19 introduced `admin_audit_log` with a developer actor. Phase 20 extends the same log (one record shape, one
 * set of append-only triggers, one migration) instead of adding a competing security audit trail. Every automated
 * security action is written here under the `SYSTEM_SECURITY` actor category, with an incident reference; developer
 * and AI-assisted actions keep their own categories. Nothing secret is ever written: callers pass bounded metadata
 * only, and a record larger than the schema's limit is refused rather than truncated into something misleading.
 */

import { newAuditId } from "./ids.js";
import { REGISTERED_AUDIT_ACTION_TYPES } from "./db.js";
import { AccountApiError, ErrorCode } from "./errors.js";

export const AUDIT_ACTOR_KIND = Object.freeze({
  DEVELOPER: "DEVELOPER",
  SYSTEM_SECURITY: "SYSTEM_SECURITY",
  AI: "AI",
  /**
   * Engine-initiated domain bookkeeping that belongs to no operator and to no security response: assigning an account
   * its free membership baseline, expiring a plan, recording the lapse of promotional credits, or refusing a
   * consumption for lack of credits. A `SYSTEM` actor always has a null developer identity, so a domain event can never
   * be mistaken for a decision by a person.
   */
  SYSTEM: "SYSTEM",
});

export const AUDIT_OUTCOME = Object.freeze({
  SUCCESS: "SUCCESS",
  FAILURE: "FAILURE",
  DENIED: "DENIED",
  PREPARED: "PREPARED",
  CANCELLED: "CANCELLED",
});

export const MAXIMUM_AUDIT_METADATA_CHARACTERS = 4096;

/**
 * Appends one audit record. The caller owns the surrounding transaction (the Phase 19 tool registry uses a savepoint
 * inside one), so a privileged action is never acknowledged unless its audit write commits with it.
 */
export function appendAuditRecord(database, {
  actorKind = AUDIT_ACTOR_KIND.DEVELOPER,
  actorDeveloperId = null,
  actionType,
  targetUserId = null,
  incidentId = null,
  outcome,
  metadata = {},
  occurredAt = new Date().toISOString(),
}) {
  if (!Object.values(AUDIT_ACTOR_KIND).includes(actorKind)) throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  if (!Object.values(AUDIT_OUTCOME).includes(outcome)) throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  if (actorKind === AUDIT_ACTOR_KIND.SYSTEM_SECURITY && actorDeveloperId !== null) {
    // An automated security action must never be attributed to a developer identity.
    throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  }
  if (actorKind === AUDIT_ACTOR_KIND.SYSTEM && actorDeveloperId !== null) {
    // Neither can engine bookkeeping.
    throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  }
  if (!REGISTERED_AUDIT_ACTION_TYPES.has(actionType)) {
    // Fail closed on an action type the log does not declare: the check keeps this table's CHECK constraint and this
    // module in step, so a new action is registered in `db.js` (and therefore in the schema) before it can be written.
    throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  }
  const encoded = JSON.stringify(metadata ?? {});
  if (typeof encoded !== "string" || encoded.length > MAXIMUM_AUDIT_METADATA_CHARACTERS) {
    throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  }
  database.prepare(
    `INSERT INTO admin_audit_log
       (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    newAuditId(),
    actorKind,
    actorDeveloperId,
    actionType,
    targetUserId,
    incidentId,
    occurredAt,
    outcome,
    encoded,
  );
}
