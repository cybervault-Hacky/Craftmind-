#!/usr/bin/env node
/**
 * Takes a consistent snapshot of a CraftMind SQLite database.
 *
 *   node --no-warnings=ExperimentalWarning scripts/backup-database.mjs [--to DIR] [--name FILE] [--database PATH]
 *
 * `DATABASE_URL` (or `--database PATH`) names the live file. The snapshot is written by SQLite's own `VACUUM INTO`, so
 * it is safe to run while the service is serving traffic, and it is verified with `PRAGMA integrity_check` plus the
 * migration watermark before this command reports success. It opens the live database **read-only**, so it can never
 * migrate, upgrade or otherwise touch the file it is supposed to be protecting.
 *
 * Nothing about the contents is printed: no table names, no row counts, no values. And this tool deliberately does
 * not read `AUTH_SECRET` or any other application secret — a backup job needs a file path and nothing else, which is
 * what lets it run from a scheduler with a minimal credential surface.
 */

import { existsSync } from "node:fs";
import { basename, dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { DatabaseSync } from "node:sqlite";

import { createBackup, defaultBackupFileName } from "../src/db-maintenance.js";

const HELP = `usage: node scripts/backup-database.mjs [--to DIR] [--name FILE] [--database PATH]

  --to        directory for the snapshot (default: a "backups" directory beside the database file)
  --name      file name inside it (default: timestamped, UTC)
  --database  the SQLite file to snapshot; defaults to the DATABASE_URL environment variable

Read-only with respect to the live database, and it refuses to write inside a served website directory.`;

function parseArguments(argv) {
  const options = { to: null, name: null, database: null, help: false };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--help" || argument === "-h") { options.help = true; continue; }
    if (argument === "--to" || argument === "--name" || argument === "--database") {
      const value = argv[index + 1];
      if (typeof value !== "string" || value.startsWith("--")) throw new Error(`${argument} requires a value`);
      options[argument.slice(2)] = value;
      index += 1;
      continue;
    }
    throw new Error(`unknown argument: ${argument}`);
  }
  if (options.name && (options.name.includes("/") || options.name.includes("\\") || options.name.includes(".."))) {
    throw new Error("--name must be a file name, not a path");
  }
  return options;
}

export function main(argv = process.argv.slice(2), { log = (line) => process.stdout.write(`${line}\n`) } = {}) {
  const options = parseArguments(argv);
  if (options.help) { log(HELP); return 0; }

  const configured = (options.database ?? process.env.DATABASE_URL ?? "").trim();
  if (!configured) throw new Error("DATABASE_URL (or --database) must name the SQLite file to back up");
  if (configured === ":memory:") throw new Error("an in-memory database has no file to snapshot; this tool backs up a persistent database");
  const source = resolve(configured);
  if (!existsSync(source)) throw new Error("the database file does not exist yet; there is nothing to back up");

  const directory = options.to ? resolve(options.to) : resolve(dirname(source), "backups");
  const fileName = options.name ?? defaultBackupFileName();

  const database = new DatabaseSync(source, { readOnly: true });
  try {
    try { database.exec("PRAGMA busy_timeout = 5000"); } catch { /* a read-only handle may reject tuning; the snapshot does not depend on it */ }
    const result = createBackup(database, resolve(directory, fileName));
    // The reported name is the file's own basename only: an absolute path in a scheduled job's log is operator
    // information that does not need to travel, and a log line is the thing most likely to be pasted somewhere else.
    log(JSON.stringify({
      event: "database_backup_completed",
      file: basename(result.path),
      bytes: result.bytes,
      schemaVersion: result.schemaVersion,
      integrity: result.integrity,
      createdAt: result.createdAt,
    }));
    return 0;
  } finally {
    try { database.close(); } catch { /* nothing left to release */ }
  }
}

// Run only when invoked as a command, so the module stays importable by tests without side effects.
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try { process.exitCode = main(); }
  catch (error) {
    process.stderr.write(`backup failed: ${error instanceof Error ? error.message : "unknown error"}\n`);
    process.exitCode = 1;
  }
}
