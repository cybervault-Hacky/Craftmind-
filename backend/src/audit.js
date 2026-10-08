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
 * Audit capture frames (Phase 23).
 *
 * A refusal must leave a record even though the change it refused never happened — but a record inserted inside a
 * transaction that then rolls back disappears with it, which would make every denied request invisible to the one log.
 * `runTransaction` therefore opens a frame around an operation: appends made while a frame is open are validated and
 * held in memory, written together with the change when the transaction commits, and written *after* the rollback
 * when the operation refuses. Validation still happens at append time, so a bad record fails the operation itself,
 * exactly as an inline insert would.
 *
 * Nothing else uses frames. The developer control plane keeps its own savepoint discipline in `admin-tools.js`, and
 * direct appends outside any transaction insert immediately, as before.
 */
const captureFrames = [];

export function beginAuditCapture() {
  const frame = { records: [], flushedInTransaction: false };
  captureFrames.push(frame);
  return frame;
}

export function endAuditCapture(frame) {
  const top = captureFrames.pop();
  if (top !== frame) {
    // Frames are strictly nested; anything else means the stack was mutated across transactions, which would attach
    // one operation's records to another's. Fail closed rather than misattribute.
    throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  }
}

function writeAuditRow(database, row) {
  database.prepare(
    `INSERT INTO admin_audit_log
       (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(row.auditId, row.actorKind, row.actorDeveloperId, row.actionType, row.targetUserId, row.incidentId, row.occurredAt, row.outcome, row.metadataJson);
}

/**
 * Writes a frame's held records. Called inside the transaction for a successful operation (so the audit commits with
 * the change) and after the rollback for a refused one (so the refusal outlives the rolled-back change it describes).
 */
export function flushAuditCapture(database, frame) {
  for (const row of frame.records) writeAuditRow(database, row);
}

/**
 * Appends one audit record. The caller owns the surrounding transaction (the Phase 19 tool registry uses a savepoint
 * inside one), so a privileged action is never acknowledged unless its audit write commits with it. When a capture
 * frame is open — see `beginAuditCapture` — the validated record is held for the frame's flush instead.
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
  const row = {
    auditId: newAuditId(), actorKind, actorDeveloperId, actionType, targetUserId, incidentId, occurredAt, outcome, metadataJson: encoded,
  };
  const frame = captureFrames[captureFrames.length - 1];
  if (frame !== undefined) {
    frame.records.push(row);
    return;
  }
  writeAuditRow(database, row);
}
