#!/usr/bin/env node
/**
 * Restores a CraftMind SQLite database from a snapshot made by `backup-database.mjs`.
 *
 *   node --no-warnings=ExperimentalWarning scripts/restore-database.mjs --from FILE [--database PATH] [--force] [--dry-run]
 *
 * Order of operations, and why each step is here:
 *
 *   1. The snapshot is opened read-only and verified (`PRAGMA integrity_check`) and its migration watermark is
 *      compared with this build's `SCHEMA_VERSION`. A snapshot from a newer schema is refused: restoring it would
 *      produce a database this code cannot read, and the operator would find that out from a 500 rather than here.
 *   2. `--dry-run` stops after step 1 and reports what it would do. This is the check to run before a maintenance
 *      window, and the mode the tests use, because it cannot damage anything.
 *   3. The snapshot is copied to `TARGET.restoring`, then renamed over the target. A failure mid-copy therefore
 *      leaves the previous database file in place instead of leaving nothing.
 *   4. The restored file is verified again the same way.
 *
 * The service must be stopped first. SQLite cannot swap the file underneath an open connection: a restore that races
 * a live process produces a database the running service is reading stale pages of, so this tool does not try to be
 * clever about it and the procedure says so plainly. `--force` is what acknowledges that a target already exists.
 *
 * Like the backup tool, this never reads an application secret: it is a file operation on a database path.
 */

import { existsSync } from "node:fs";
import { basename, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { inspectBackup, restoreBackup } from "../src/db-maintenance.js";
import { SCHEMA_VERSION } from "../src/db.js";

const HELP = `usage: node scripts/restore-database.mjs --from FILE [--database PATH] [--force] [--dry-run]

  --from      the snapshot file to restore (required)
  --database  the SQLite file to write; defaults to the DATABASE_URL environment variable
  --force     required to replace a database file that already exists (stop the service first)
  --dry-run   verify the snapshot and report, without writing anything

A snapshot taken by an older build is restored only if its schema version matches this build (v${SCHEMA_VERSION}).`;

function parseArguments(argv) {
  const options = { from: null, database: null, force: false, dryRun: false, help: false };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--help" || argument === "-h") { options.help = true; continue; }
    if (argument === "--force") { options.force = true; continue; }
    if (argument === "--dry-run") { options.dryRun = true; continue; }
    if (argument === "--from" || argument === "--database") {
      const value = argv[index + 1];
      if (typeof value !== "string" || value.startsWith("--")) throw new Error(`${argument} requires a value`);
      options[argument.slice(2)] = value;
      index += 1;
      continue;
    }
    throw new Error(`unknown argument: ${argument}`);
  }
  return options;
}

export function main(argv = process.argv.slice(2), { log = (line) => process.stdout.write(`${line}\n`) } = {}) {
  const options = parseArguments(argv);
  if (options.help) { log(HELP); return 0; }
  if (!options.from) throw new Error("--from must name the snapshot file to restore");
  const source = resolve(options.from);
  if (!existsSync(source)) throw new Error("the snapshot file does not exist");

  const configured = (options.database ?? process.env.DATABASE_URL ?? "").trim();
  if (!configured) throw new Error("DATABASE_URL (or --database) must name the SQLite file to restore into");
  if (configured === ":memory:") throw new Error("refusing to restore into an in-memory database: there is no file to write");
  const target = resolve(configured);
  if (resolve(source) === target) throw new Error("the snapshot and the target are the same file");

  const verified = inspectBackup(source);
  if (!verified.schemaCurrent) {
    throw new Error(`this build understands schema v${SCHEMA_VERSION} and the snapshot is v${verified.schemaVersion}; restore with the matching release, or migrate after restoring`);
  }

  const result = restoreBackup(source, target, { force: options.force, dryRun: options.dryRun });
  log(JSON.stringify({
    event: options.dryRun ? "database_restore_planned" : "database_restore_completed",
    file: basename(result.source),
    target: options.dryRun ? undefined : basename(result.target),
    bytes: result.bytes,
    schemaVersion: result.schemaVersion,
    integrity: options.dryRun ? verified.integrity : result.integrity,
    replaced: result.replaced === true,
  }));
  return 0;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try { process.exitCode = main(); }
  catch (error) {
    process.stderr.write(`restore failed: ${error instanceof Error ? error.message : "unknown error"}\n`);
    process.exitCode = 1;
  }
}
