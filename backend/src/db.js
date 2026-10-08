/** SQLite persistence and transactional migrations for account identity and the separately-authorized developer control plane. */

import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";

export const ACCOUNT_STATUS = Object.freeze({ ACTIVE: "ACTIVE", SUSPENDED: "SUSPENDED", DELETED: "DELETED" });

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
  {
    version: 2,
    statements: [
      `ALTER TABLE users ADD COLUMN email_verified_at TEXT`,
      `ALTER TABLE sessions ADD COLUMN last_used_at TEXT NOT NULL DEFAULT ''`,
      `ALTER TABLE sessions ADD COLUMN device_label TEXT NOT NULL DEFAULT 'Unknown device'`,
      `UPDATE sessions SET last_used_at = issued_at WHERE last_used_at = ''`,
      `CREATE TABLE email_verification_tokens (
         token_digest TEXT PRIMARY KEY,
         user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         created_at TEXT NOT NULL,
         expires_at TEXT NOT NULL,
         consumed_at TEXT
       )`,
      `CREATE INDEX email_verification_tokens_by_user ON email_verification_tokens(user_id, consumed_at)`,
      `CREATE INDEX email_verification_tokens_by_expiry ON email_verification_tokens(expires_at)`,
      `CREATE TABLE password_recovery_tokens (
         token_digest TEXT PRIMARY KEY,
         user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         created_at TEXT NOT NULL,
         expires_at TEXT NOT NULL,
         consumed_at TEXT
       )`,
      `CREATE INDEX password_recovery_tokens_by_user ON password_recovery_tokens(user_id, consumed_at)`,
      `CREATE INDEX password_recovery_tokens_by_expiry ON password_recovery_tokens(expires_at)`,
      `CREATE INDEX sessions_by_refresh_expiry ON sessions(refresh_expires_at)`,
      `CREATE INDEX sessions_by_revocation ON sessions(revoked_at)`,
    ],
  },
  {
    version: 3,
    statements: [
      `CREATE TABLE developer_accounts (
         developer_id TEXT PRIMARY KEY,
         email TEXT NOT NULL,
         email_canonical TEXT NOT NULL UNIQUE,
         password_hash TEXT NOT NULL,
         role TEXT NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER')),
         status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL,
         last_login_at TEXT
       )`,
      `CREATE TABLE developer_sessions (
         session_id TEXT PRIMARY KEY,
         developer_id TEXT NOT NULL REFERENCES developer_accounts(developer_id) ON DELETE CASCADE,
         access_digest TEXT NOT NULL UNIQUE,
         refresh_digest TEXT NOT NULL UNIQUE,
         issued_at TEXT NOT NULL,
         access_expires_at TEXT NOT NULL,
         refresh_expires_at TEXT NOT NULL,
         last_used_at TEXT NOT NULL,
         device_label TEXT NOT NULL DEFAULT 'Developer dashboard',
         revoked_at TEXT
       )`,
      `CREATE INDEX developer_sessions_by_developer ON developer_sessions(developer_id, issued_at)`,
      `CREATE INDEX developer_sessions_by_refresh_expiry ON developer_sessions(refresh_expires_at)`,
      `CREATE TABLE developer_bootstrap_state (
         singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
         consumed_at TEXT NOT NULL,
         developer_id TEXT NOT NULL UNIQUE REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT
       )`,
      `CREATE TABLE developer_access_grants (
         grant_id TEXT PRIMARY KEY,
         user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         entitlement_key TEXT NOT NULL CHECK (entitlement_key IN ('BETA_ACCESS', 'PREVIEW_ACCESS', 'PROMOTIONAL_ACCESS')),
         granted_by TEXT NOT NULL REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         created_at TEXT NOT NULL,
         expires_at TEXT NOT NULL,
         revoked_at TEXT,
         revoked_by TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT
       )`,
      `CREATE INDEX developer_grants_by_user ON developer_access_grants(user_id, created_at DESC)`,
      `CREATE INDEX developer_grants_by_entitlement ON developer_access_grants(user_id, entitlement_key, expires_at)`,
      `CREATE TABLE admin_audit_log (
         audit_id TEXT PRIMARY KEY,
         actor_developer_id TEXT NOT NULL REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'overview', 'inspect_user', 'list_user_sessions', 'revoke_user_sessions', 'suspend_user', 'restore_user',
           'list_entitlements', 'grant_entitlement', 'revoke_entitlement', 'list_audit_log',
           'configuration_status', 'unknown_tool', 'action_confirmation', 'developer_ai_turn', 'developer_session_revoke'
         )),
         target_user_id TEXT,
         occurred_at TEXT NOT NULL,
         outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED', 'PREPARED', 'CANCELLED')),
         metadata_json TEXT NOT NULL CHECK (length(metadata_json) <= 4096)
       )`,
      `CREATE INDEX admin_audit_by_time ON admin_audit_log(occurred_at DESC, audit_id)`,
      `CREATE INDEX admin_audit_by_actor ON admin_audit_log(actor_developer_id, occurred_at DESC)`,
      `CREATE INDEX admin_audit_by_target ON admin_audit_log(target_user_id, occurred_at DESC)`,
      `CREATE TRIGGER admin_audit_log_no_update BEFORE UPDATE ON admin_audit_log
         BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END`,
      `CREATE TRIGGER admin_audit_log_no_delete BEFORE DELETE ON admin_audit_log
         BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END`,
      `CREATE TRIGGER admin_audit_log_no_replacement BEFORE INSERT ON admin_audit_log
         WHEN EXISTS (SELECT 1 FROM admin_audit_log WHERE audit_id = NEW.audit_id)
         BEGIN SELECT RAISE(ABORT, 'audit records cannot be replaced'); END`,
      `CREATE TABLE developer_action_confirmations (
         confirmation_digest TEXT PRIMARY KEY,
         developer_id TEXT NOT NULL REFERENCES developer_accounts(developer_id) ON DELETE CASCADE,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'revoke_user_sessions', 'suspend_user', 'restore_user', 'grant_entitlement', 'revoke_entitlement'
         )),
         target_user_id TEXT,
         arguments_json TEXT NOT NULL CHECK (length(arguments_json) <= 4096),
         created_at TEXT NOT NULL,
         expires_at TEXT NOT NULL,
         consumed_at TEXT
       )`,
      `CREATE INDEX developer_confirmations_by_expiry ON developer_action_confirmations(expires_at)`,
    ],
  },
];

/** @param {string} databaseUrl path to the SQLite file (`:memory:` is accepted only by tests) */
export function openDatabase(databaseUrl) {
  const path = databaseUrl === ":memory:" ? ":memory:" : resolve(databaseUrl);
  if (path !== ":memory:") mkdirSync(dirname(path), { recursive: true });
  const database = new DatabaseSync(path);
  database.exec("PRAGMA journal_mode = WAL");
  database.exec("PRAGMA foreign_keys = ON");
  database.exec("PRAGMA recursive_triggers = ON");
  database.exec("PRAGMA busy_timeout = 5000");
  database.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
  migrate(database);
  return database;
}

function migrate(database) {
  const applied = new Set(database.prepare("SELECT version FROM schema_migrations").all().map((row) => Number(row.version)));
  for (const migration of MIGRATIONS) {
    if (applied.has(migration.version)) continue;
    database.exec("BEGIN IMMEDIATE");
    try {
      for (const statement of migration.statements) database.exec(statement);
      database.prepare("INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)").run(migration.version, new Date().toISOString());
      database.exec("COMMIT");
    } catch (error) {
      database.exec("ROLLBACK");
      throw error;
    }
  }
}

export const SCHEMA_VERSION = MIGRATIONS.at(-1).version;
