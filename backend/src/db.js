/**
 * Structured persistence for accounts, sessions, and guest identities.
 *
 * Storage is SQLite through Node's built-in `node:sqlite` driver: a real relational store with constraints,
 * transactions, and indexes — not a flat JSON file. Migrations are versioned and applied inside a transaction, so
 * starting the same database twice is a no-op and an interrupted start cannot half-apply a version.
 *
 * Only the tables this phase needs exist: users, sessions, guest_identities. There is no table for subscriptions,
 * payments, credits, gifts, bans, marketplace, admin, or developer tooling, and none may be added by this phase.
 */

import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";

export const ACCOUNT_STATUS = Object.freeze({
  ACTIVE: "ACTIVE",
  SUSPENDED: "SUSPENDED",
  DELETED: "DELETED",
});

const MIGRATIONS = [
  {
    version: 1,
    statements: [
      `CREATE TABLE IF NOT EXISTS users (
         user_id TEXT PRIMARY KEY,
         email TEXT NOT NULL,
         email_canonical TEXT NOT NULL UNIQUE,
         display_name TEXT NOT NULL,
         password_hash TEXT NOT NULL,
         status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL
       )`,
      `CREATE TABLE IF NOT EXISTS sessions (
         session_id TEXT PRIMARY KEY,
         user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         access_digest TEXT NOT NULL UNIQUE,
         refresh_digest TEXT NOT NULL UNIQUE,
         issued_at TEXT NOT NULL,
         access_expires_at TEXT NOT NULL,
         refresh_expires_at TEXT NOT NULL,
         revoked_at TEXT,
         guest_identity_id TEXT REFERENCES guest_identities(guest_identity_id)
       )`,
      `CREATE INDEX IF NOT EXISTS sessions_by_user ON sessions(user_id)`,
      `CREATE INDEX IF NOT EXISTS sessions_by_refresh_digest ON sessions(refresh_digest)`,
      `CREATE TABLE IF NOT EXISTS guest_identities (
         guest_identity_id TEXT PRIMARY KEY,
         created_at TEXT NOT NULL,
         last_seen_at TEXT NOT NULL,
         linked_user_id TEXT REFERENCES users(user_id),
         linked_at TEXT
       )`,
      `CREATE INDEX IF NOT EXISTS guest_identities_by_linked_user ON guest_identities(linked_user_id)`,
    ],
  },
];

/**
 * Opens the database and brings it to the current schema version.
 *
 * @param {string} databaseUrl path to the SQLite file (`:memory:` is accepted for tests)
 */
export function openDatabase(databaseUrl) {
  const path = databaseUrl === ":memory:" ? ":memory:" : resolve(databaseUrl);
  if (path !== ":memory:") {
    mkdirSync(dirname(path), { recursive: true });
  }
  const database = new DatabaseSync(path);
  database.exec("PRAGMA journal_mode = WAL");
  database.exec("PRAGMA foreign_keys = ON");
  database.exec("PRAGMA busy_timeout = 5000");
  database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
  migrate(database);
  return database;
}

function migrate(database) {
  const applied = new Set(
    database.prepare("SELECT version FROM schema_migrations").all().map((row) => Number(row.version)),
  );
  for (const migration of MIGRATIONS) {
    if (applied.has(migration.version)) continue;
    database.exec("BEGIN IMMEDIATE");
    try {
      for (const statement of migration.statements) database.exec(statement);
      database
        .prepare("INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)")
        .run(migration.version, new Date().toISOString());
      database.exec("COMMIT");
    } catch (error) {
      database.exec("ROLLBACK");
      throw error;
    }
  }
}

export const SCHEMA_VERSION = MIGRATIONS[MIGRATIONS.length - 1].version;
