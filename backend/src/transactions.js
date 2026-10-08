/**
 * The one write-transaction helper for account-scoped state (Phase 23).
 *
 * `BEGIN IMMEDIATE` takes SQLite's write lock at the start of the transaction rather than on the first write, so a
 * read-then-write sequence cannot be split by another writer. That is what makes "check the owner, then change the
 * row" safe under concurrency, and it is the only locking mechanism used — never an in-process counter or a JavaScript
 * flag, which would not survive a second process or a restart.
 *
 * Contention past `busy_timeout` is translated into a typed, retryable failure rather than a raw SQLite message, so a
 * SQL string can never reach a client. Every module that mutates account-owned state goes through this helper.
 *
 * It also settles the fate of the audit records an operation writes. On success they are flushed before the commit, so
 * a record never survives the change it describes being abandoned. On refusal they are flushed *after* the rollback:
 * the change is gone, but the refusal — the entitlement denial, the ownership failure, the suspended state — stays in
 * the log. Without that, every denied request would roll back its own evidence.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { beginAuditCapture, endAuditCapture, flushAuditCapture } from "./audit.js";

/** True when SQLite is telling us another writer holds the lock past `busy_timeout`. */
export function isLockContention(error) {
  const message = typeof error?.message === "string" ? error.message : "";
  return error?.code === "ERR_SQLITE_ERROR" && /locked|busy/i.test(message);
}

/**
 * Maps lock contention to a typed error. `busyCode` lets a caller say which subsystem was busy without inventing a
 * second failure shape.
 */
export function translateContention(error, busyCode = ErrorCode.STORAGE_BUSY) {
  if (error instanceof AccountApiError) return error;
  return isLockContention(error) ? new AccountApiError(busyCode) : error;
}

export function runTransaction(database, operation, { busyCode = ErrorCode.STORAGE_BUSY } = {}) {
  try {
    database.exec("BEGIN IMMEDIATE");
  } catch (error) {
    throw translateContention(error, busyCode);
  }
  const capture = beginAuditCapture();
  let operationSucceeded = false;
  try {
    const result = operation();
    operationSucceeded = true;
    flushAuditCapture(database, capture);
    database.exec("COMMIT");
    return result;
  } catch (error) {
    try {
      database.exec("ROLLBACK");
    } catch {
      // The statement itself failed, so there was nothing to roll back.
    }
    if (!operationSucceeded) {
      // The operation refused. Its held records — the denial that explains the refusal — are written now, after the
      // rollback, because a refusal must stay attributable even though the change it refused never happened. If a
      // record cannot be written here, the refusal still propagates: the log must never change an answer.
      try {
        flushAuditCapture(database, capture);
      } catch {
        // Best effort by design; validation already ran when the record was appended.
      }
    }
    throw translateContention(error, busyCode);
  } finally {
    endAuditCapture(capture);
  }
}
