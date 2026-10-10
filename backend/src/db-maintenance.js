/**
 * Database backup and restoration for the one persistent store this service has: a SQLite file in WAL mode.
 *
 * `VACUUM INTO` is used rather than a file copy. The difference matters precisely because the journal is WAL: copying
 * `craftmind-auth.db` while writes are in flight produces a database whose latest commits live in a `-wal` sidecar
 * that the copy did not include, i.e. a snapshot that is silently behind, and a `-wal` file copied separately is not
 * mergeable into an arbitrary copy of the main file. `VACUUM INTO` asks SQLite itself to write a fresh, complete,
 * internally consistent database file from the live connection — the supported online-backup path, with no external
 * binary and no dependency.
 *
 * Restoration is deliberately a *file* operation on a stopped service, not a live SQL merge, and it verifies the
 * artifact before and after. Nothing here ever reads or reports user data: the result objects carry sizes, a schema
 * version and an integrity verdict, never row contents, row counts, or names.
 */

import { DatabaseSync } from "node:sqlite";
import { chmodSync, copyFileSync, existsSync, mkdirSync, renameSync, rmSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve, sep } from "node:path";

import { SCHEMA_VERSION } from "./db.js";

/** Backups are mode 0600 and their directory 0700: the file *is* the database, so it inherits database-file secrecy. */
const BACKUP_DIRECTORY_MODE = 0o700;
const BACKUP_FILE_MODE = 0o600;

const DEFAULT_PUBLIC_DIRECTORY = dirname(dirname(fileURLToPath(import.meta.url))) + sep + "public";

function isWithin(parentDirectory, candidatePath) {
  const parent = resolve(parentDirectory);
  const candidate = resolve(candidatePath);
  return candidate === parent || candidate.startsWith(parent + sep);
}

/**
 * A backup is a full copy of every account, credential hash and marketplace row the service holds. Letting one land
 * inside a directory the same process also serves as static files would publish the database over HTTP, so the
 * destination is refused rather than warned about — and the check is done before SQLite is asked to write anything.
 */
export function assertBackupDestinationAllowed(destinationPath, { publicDirectories = [DEFAULT_PUBLIC_DIRECTORY] } = {}) {
  const target = resolve(destinationPath);
  for (const served of publicDirectories) {
    if (served && isWithin(served, target)) {
      throw new Error("a backup destination must not be inside a served website directory");
    }
  }
  return target;
}

/** The migration watermark is what a restore must agree on before the file is trusted. */
const SCHEMA_WATERMARK_QUERY = "SELECT MAX(version) AS version FROM schema_migrations";

/** Opens a snapshot read-only and asks SQLite whether the file is whole. A corrupt artifact must never pass as a backup. */
export function inspectBackup(backupPath) {
  const path = resolve(backupPath);
  if (!existsSync(path)) throw new Error("backup file does not exist");
  let database;
  try {
    database = new DatabaseSync(path, { readOnly: true });
    const integrity = database.prepare("PRAGMA integrity_check").get()?.integrity_check;
    // A file with no migration table is not corrupt, it is simply not a CraftMind database: reported as version 0 so
    // the caller's verification step rejects it, rather than as a thrown "no such table" that would escape past the
    // cleanup and leave an unverified artifact on disk.
    let schemaVersion = 0;
    try {
      schemaVersion = Number(database.prepare(SCHEMA_WATERMARK_QUERY).get()?.version ?? 0);
    } catch {
      schemaVersion = 0;
    }
    const tableNames = database.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all().map((row) => row.name);
    return {
      integrity: integrity === "ok" ? "ok" : "failed",
      schemaVersion,
      tables: tableNames.length,
      bytes: statSync(path).size,
      // The migration watermark has to match the code that will read this file, or a restore would "succeed" and the
      // first request would fail on a missing table.
      schemaCurrent: schemaVersion === SCHEMA_VERSION,
    };
  } finally {
    try { database?.close(); } catch { /* nothing to unwind */ }
  }
}

/**
 * Writes a consistent snapshot of `database` to `destinationPath` and verifies it.
 *
 * @param {DatabaseSync} database an open handle (file-backed or in-memory — both are supported by `VACUUM INTO`)
 * @param {string} destinationPath absolute or relative path of the file to create; it must not already exist
 */
export function createBackup(database, destinationPath, { publicDirectories, timestamp = new Date() } = {}) {
  const target = assertBackupDestinationAllowed(destinationPath, { publicDirectories });
  if (existsSync(target)) throw new Error("backup destination already exists; refusing to overwrite a backup");
  mkdirSync(dirname(target), { recursive: true, mode: BACKUP_DIRECTORY_MODE });
  // Parameterized on purpose: a path from a CLI argument goes in as a bound value, never spliced into SQL text.
  database.prepare("VACUUM INTO ?").run(target);
  chmodSync(target, BACKUP_FILE_MODE);
  const inspected = inspectBackup(target);
  if (inspected.integrity !== "ok" || !inspected.schemaCurrent) {
    // A backup that fails verification is worse than no backup, because it invites a restore that cannot work. It is
    // removed so an operator is never handed a file that looks finished.
    rmSync(target, { force: true });
    throw new Error(`the snapshot did not verify (${inspected.integrity === "ok" ? "schema mismatch" : "integrity check failed"})`);
  }
  return {
    createdAt: timestamp.toISOString(),
    path: target,
    bytes: inspected.bytes,
    schemaVersion: inspected.schemaVersion,
    integrity: inspected.integrity,
    tables: inspected.tables,
  };
}

/**
 * Restores `backupPath` over `targetPath`.
 *
 * The backup is verified first, the new file is written beside the target and renamed into place, and the result is
 * verified again — so a failed copy leaves the previous file intact rather than leaving nothing. The service must be
 * stopped: SQLite has no way to swap the file out from under an open connection.
 */
export function restoreBackup(backupPath, targetPath, { force = false, dryRun = false } = {}) {
  const source = resolve(backupPath);
  const target = resolve(targetPath);
  const before = inspectBackup(source);
  if (before.integrity !== "ok") throw new Error("refusing to restore a backup that fails its integrity check");
  if (!before.schemaCurrent) {
    throw new Error(`refusing to restore a snapshot at schema v${before.schemaVersion}; this build expects v${SCHEMA_VERSION}`);
  }
  if (dryRun) {
    return { dryRun: true, source, target, bytes: before.bytes, schemaVersion: before.schemaVersion, replaced: false };
  }
  if (existsSync(target) && !force) {
    throw new Error("the target database already exists; pass --force only after the service is stopped");
  }
  assertBackupDestinationAllowed(target);
  mkdirSync(dirname(target), { recursive: true, mode: BACKUP_DIRECTORY_MODE });
  // Sidecars belong to the connection that wrote them, not to this file. A stale -wal/-shm next to a restored
  // database would be replayed into it, so they are removed with the file they came from — and only alongside an
  // explicit --force, which is the "I know a live database is here" signal.
  if (existsSync(target)) {
    for (const suffix of ["-wal", "-shm"]) {
      rmSync(target + suffix, { force: true });
    }
  }
  const staging = `${target}.restoring`;
  // Sampled once, before the write: `replaced` has to answer the question an operator is actually asking — was
  // something already here and overwritten — not whether the copy succeeded. Reporting `true` after restoring into a
  // fresh path would claim a clobber that never happened.
  const targetPreexisted = existsSync(target);
  try {
    copyFileSync(source, staging);
    // Match the permissions of the file being replaced when there is one; a fresh restore gets database-file mode.
    chmodSync(staging, targetPreexisted ? (statSync(target).mode & 0o777) || BACKUP_FILE_MODE : BACKUP_FILE_MODE);
    renameSync(staging, target);
  } catch (error) {
    rmSync(staging, { force: true });
    throw error;
  }
  const after = inspectBackup(target);
  return {
    dryRun: false,
    source,
    target,
    replaced: targetPreexisted,
    bytes: after.bytes,
    schemaVersion: after.schemaVersion,
    integrity: after.integrity,
    sourceBytes: before.bytes,
  };
}

/** Default snapshot name for a run, derived from the clock rather than from a path or an account. */
export function defaultBackupFileName(timestamp = new Date()) {
  // No `:` in the name, so the file survives archives, object stores and every shell's quoting rules.
  const stamp = timestamp.toISOString().replace(/[:.]/g, "-").replace(/-\d{3}Z$/, "Z");
  return `craftmind-auth-backup-${stamp}.db`;
}

export { BACKUP_FILE_MODE, BACKUP_DIRECTORY_MODE };
