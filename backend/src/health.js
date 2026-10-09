/**
 * Liveness and readiness, kept apart because they answer different questions and are consumed by different actors.
 *
 * **Liveness** asks only whether this process is running its event loop. It touches no database and no dependency, so
 * a transient storage problem can never make an orchestrator kill a process that is healthy and would recover on its
 * own. **Readiness** asks whether this process may safely accept requests right now, which for this service means one
 * thing: the SQLite handle is usable *and* the schema is at the version the running code expects. A deployment that
 * runs migrations separately gets a real signal here rather than a 500 on the first request.
 *
 * Both are deliberately minimal. Neither reveals a path, a connection string, a row count, a table name, an error
 * message, or anything about an account: `SELECT 1` proves reachability, and `schema_migrations` proves the schema.
 * There is no version string, uptime counter, memory figure or dependency detail, because each of those is either
 * information an unauthenticated caller has no need of or a number nobody has measured.
 */

import { SCHEMA_VERSION } from "./db.js";

/** The probe queries are bounded to one row each; nothing here scans user data. */
const READINESS_QUERY = "SELECT 1 AS reachable";
const SCHEMA_QUERY = "SELECT MAX(version) AS version FROM schema_migrations";

/** Never derived from the error: an operator sees a category, a client sees nothing. */
function classifyFailure(error) {
  const code = typeof error?.code === "string" ? error.code : "";
  if (code === "ERR_INVALID_STATE" || /closed/i.test(String(error?.message ?? ""))) return "CLOSED";
  if (/locked/i.test(String(error?.message ?? ""))) return "BUSY";
  return "UNAVAILABLE";
}

/** Liveness: process-level only, and it must be impossible for this to fail while the loop is turning. */
export function livenessPayload() {
  return Object.freeze({ status: "alive", service: "craftmind-auth" });
}

/**
 * Readiness over the live database handle.
 *
 * @returns {{ status: "ok" | "unavailable", httpStatus: number, payload: object }}
 */
export function readiness(database) {
  let reachable = false;
  let failure = null;
  try {
    reachable = database?.prepare(READINESS_QUERY).get()?.reachable === 1;
  } catch (error) {
    failure = classifyFailure(error);
  }
  if (!reachable) {
    return {
      httpStatus: 503,
      status: "unavailable",
      payload: Object.freeze({ status: "unavailable", service: "craftmind-auth", database: "unreachable", failure }),
    };
  }

  let version = null;
  try {
    version = Number(database.prepare(SCHEMA_QUERY).get()?.version ?? 0);
  } catch {
    // A missing migration table is exactly the state a half-initialized deployment produces: not ready, and the
    // client does not need to know why.
    version = 0;
  }
  if (version !== SCHEMA_VERSION) {
    return {
      httpStatus: 503,
      status: "unavailable",
      payload: Object.freeze({
        status: "unavailable", service: "craftmind-auth", database: "reachable",
        schema: version > SCHEMA_VERSION ? "ahead_of_code" : "behind_code",
      }),
    };
  }

  // `schemaVersion` stays in the body because existing operators already read it; it is the version the code was
  // built for, which is a deployment fact and not sensitive.
  return {
    httpStatus: 200,
    status: "ok",
    payload: Object.freeze({
      status: "ok",
      service: "craftmind-auth",
      schemaVersion: SCHEMA_VERSION,
      database: "reachable",
      schema: "current",
    }),
  };
}
