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
  {
    // Phase 20 is additive: every Phase 17–19 table, row, and identifier is preserved. The append-only audit log gains
    // a system/developer actor category and an incident reference, so automated security actions are auditable in the
    // same log as developer actions instead of a competing one.
    version: 4,
    statements: [
      `CREATE TABLE security_incidents (
         incident_id TEXT PRIMARY KEY,
         reference TEXT NOT NULL UNIQUE,
         severity TEXT NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
         status TEXT NOT NULL CHECK (status IN ('OPEN', 'CONTAINED', 'INVESTIGATING', 'RESOLVED')),
         threat_category TEXT NOT NULL CHECK (threat_category IN (
           'BRUTE_FORCE', 'SESSION_ABUSE', 'RATE_LIMIT_ABUSE', 'UNAUTHORIZED_ACCESS', 'CONFIRMATION_ABUSE', 'REQUEST_ABUSE'
         )),
         subject_kind TEXT NOT NULL CHECK (subject_kind IN ('ACCOUNT', 'SESSION', 'SOURCE', 'DEVELOPER', 'ROUTE')),
         subject_reference TEXT NOT NULL,
         risk_score INTEGER NOT NULL CHECK (risk_score >= 0 AND risk_score <= 100),
         detection_reasons_json TEXT NOT NULL CHECK (length(detection_reasons_json) <= 2048),
         dedup_key TEXT NOT NULL,
         event_count INTEGER NOT NULL DEFAULT 0 CHECK (event_count >= 0),
         correlation_id TEXT NOT NULL,
         detected_at TEXT NOT NULL,
         last_activity_at TEXT NOT NULL,
         contained_at TEXT,
         resolved_at TEXT,
         resolution TEXT,
         created_at TEXT NOT NULL
       )`,
      `CREATE UNIQUE INDEX security_incidents_active_dedup ON security_incidents(dedup_key) WHERE status <> 'RESOLVED'`,
      `CREATE INDEX security_incidents_by_time ON security_incidents(detected_at DESC, incident_id)`,
      `CREATE INDEX security_incidents_by_status ON security_incidents(status, last_activity_at DESC)`,
      `CREATE TABLE security_events (
         event_id TEXT PRIMARY KEY,
         event_type TEXT NOT NULL CHECK (event_type IN (
           'AUTHENTICATION_FAILED', 'AUTHENTICATION_SUCCEEDED', 'AUTHENTICATION_SUCCEEDED_AFTER_FAILURES',
           'SESSION_REFRESH_FAILED', 'SESSION_INVALID_CREDENTIAL', 'SESSION_REVOKED_REUSE', 'SESSION_EXPIRED_REUSE',
           'RATE_LIMIT_VIOLATION', 'UNAUTHORIZED_ACCESS_ATTEMPT', 'MALFORMED_SECURITY_REQUEST',
           'SUSPICIOUS_ACCOUNT_ACTIVITY', 'DEVELOPER_AUTHENTICATION_FAILED', 'HIGH_IMPACT_ACTION_FAILED',
           'CONFIRMATION_ATTEMPT_INVALID', 'REQUEST_BURST'
         )),
         severity TEXT NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
         source_category TEXT NOT NULL CHECK (source_category IN (
           'USER_AUTH', 'DEVELOPER_AUTH', 'SESSION', 'RATE_LIMIT', 'AUTHORIZATION', 'REQUEST', 'CONFIRMATION', 'ACCOUNT'
         )),
         account_reference TEXT,
         session_reference TEXT,
         route_category TEXT,
         result TEXT NOT NULL CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED', 'THROTTLED', 'INVALID', 'BLOCKED')),
         signal_count INTEGER NOT NULL DEFAULT 1 CHECK (signal_count >= 1),
         source_reference TEXT,
         correlation_id TEXT NOT NULL,
         incident_id TEXT,
         metadata_json TEXT NOT NULL CHECK (length(metadata_json) <= 2048),
         occurred_at TEXT NOT NULL,
         recorded_at TEXT NOT NULL
       )`,
      `CREATE INDEX security_events_by_time ON security_events(occurred_at DESC, event_id)`,
      `CREATE INDEX security_events_by_type ON security_events(event_type, occurred_at DESC)`,
      `CREATE INDEX security_events_by_account ON security_events(account_reference, occurred_at DESC)`,
      `CREATE INDEX security_events_by_source ON security_events(source_reference, occurred_at DESC)`,
      `CREATE INDEX security_events_by_incident ON security_events(incident_id, occurred_at DESC)`,
      `CREATE TABLE security_actions (
         action_id TEXT PRIMARY KEY,
         incident_id TEXT NOT NULL REFERENCES security_incidents(incident_id) ON DELETE RESTRICT,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'SECURITY_INCIDENT_CREATED', 'SECURITY_RATE_LIMIT_APPLIED', 'SECURITY_REQUEST_REJECTED',
           'SECURITY_SESSION_REVOKED', 'SECURITY_ACCOUNT_PROTECTED', 'SECURITY_ALERT_CREATED',
           'SECURITY_PROTECTION_RELEASED', 'SECURITY_INCIDENT_RESOLVED'
         )),
         tool_name TEXT NOT NULL CHECK (tool_name IN (
           'security.createIncident', 'security.applyRateLimit', 'security.rejectAbusiveRequest', 'security.revokeSession',
           'security.protectAccount', 'security.notifyDeveloper', 'security.releaseProtection', 'security.resolveIncident'
         )),
         scope TEXT NOT NULL CHECK (scope IN ('SOURCE', 'ACCOUNT', 'SESSION', 'INCIDENT', 'DEVELOPER')),
         subject_reference TEXT,
         authorized_by TEXT NOT NULL CHECK (authorized_by = 'SECURITY_POLICY'),
         policy_id TEXT NOT NULL,
         arguments_json TEXT NOT NULL CHECK (length(arguments_json) <= 1024),
         result TEXT NOT NULL CHECK (result IN ('APPLIED', 'SKIPPED', 'FAILED')),
         result_code TEXT,
         reversible INTEGER NOT NULL CHECK (reversible IN (0, 1)),
         expires_at TEXT,
         released_at TEXT,
         occurred_at TEXT NOT NULL
       )`,
      `CREATE INDEX security_actions_by_incident ON security_actions(incident_id, occurred_at DESC)`,
      `CREATE INDEX security_actions_by_time ON security_actions(occurred_at DESC, action_id)`,
      `CREATE INDEX security_actions_by_type ON security_actions(action_type, occurred_at DESC)`,
      `CREATE TABLE security_notifications (
         notification_id TEXT PRIMARY KEY,
         incident_id TEXT REFERENCES security_incidents(incident_id) ON DELETE SET NULL,
         priority TEXT NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
         title TEXT NOT NULL CHECK (length(title) <= 120),
         body TEXT NOT NULL CHECK (length(body) <= 512),
         threat_category TEXT NOT NULL,
         status TEXT NOT NULL CHECK (status IN ('UNREAD', 'READ', 'ACKNOWLEDGED')),
         created_at TEXT NOT NULL
       )`,
      `CREATE INDEX security_notifications_by_time ON security_notifications(created_at DESC, notification_id)`,
      `CREATE TABLE security_rate_limit_state (
         protection_id TEXT PRIMARY KEY,
         incident_id TEXT REFERENCES security_incidents(incident_id) ON DELETE SET NULL,
         scope TEXT NOT NULL CHECK (scope IN ('SOURCE', 'ACCOUNT', 'SESSION')),
         subject_reference TEXT NOT NULL,
         category TEXT NOT NULL CHECK (category IN (
           'USER_AUTH', 'DEVELOPER_AUTH', 'SESSION', 'DEVELOPER_ADMIN', 'CONFIRMATION', 'SECURITY_EVENT', 'INCIDENT', 'ANY'
         )),
         mode TEXT NOT NULL CHECK (mode IN ('THROTTLE', 'DENY')),
         maximum INTEGER NOT NULL CHECK (maximum >= 1 AND maximum <= 600),
         window_ms INTEGER NOT NULL CHECK (window_ms >= 1000 AND window_ms <= 3600000),
         reason TEXT NOT NULL CHECK (length(reason) <= 200),
         policy_id TEXT NOT NULL,
         applied_at TEXT NOT NULL,
         expires_at TEXT NOT NULL,
         released_at TEXT,
         release_reason TEXT,
         CHECK (mode <> 'DENY' OR scope = 'SOURCE')
       )`,
      `CREATE INDEX security_rate_limit_state_active ON security_rate_limit_state(subject_reference, category, expires_at)`,
      `CREATE INDEX security_rate_limit_state_by_expiry ON security_rate_limit_state(expires_at)`,
      `CREATE TABLE admin_audit_log_v4 (
         audit_id TEXT PRIMARY KEY,
         actor_kind TEXT NOT NULL CHECK (actor_kind IN ('DEVELOPER', 'SYSTEM_SECURITY', 'AI')),
         actor_developer_id TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'overview', 'inspect_user', 'list_user_sessions', 'revoke_user_sessions', 'suspend_user', 'restore_user',
           'list_entitlements', 'grant_entitlement', 'revoke_entitlement', 'list_audit_log',
           'configuration_status', 'unknown_tool', 'action_confirmation', 'developer_ai_turn', 'developer_session_revoke',
           'security_overview', 'list_security_incidents', 'get_security_incident', 'list_security_events',
           'list_security_actions', 'list_security_notifications', 'developer_ai_security_summary',
           'SECURITY_INCIDENT_CREATED', 'SECURITY_RATE_LIMIT_APPLIED', 'SECURITY_REQUEST_REJECTED',
           'SECURITY_SESSION_REVOKED', 'SECURITY_ACCOUNT_PROTECTED', 'SECURITY_ALERT_CREATED',
           'SECURITY_PROTECTION_RELEASED', 'SECURITY_INCIDENT_RESOLVED'
         )),
         target_user_id TEXT,
         incident_id TEXT,
         occurred_at TEXT NOT NULL,
         outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED', 'PREPARED', 'CANCELLED')),
         metadata_json TEXT NOT NULL CHECK (length(metadata_json) <= 4096),
         CHECK (actor_kind <> 'SYSTEM_SECURITY' OR actor_developer_id IS NULL)
       )`,
      `INSERT INTO admin_audit_log_v4
         (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       SELECT audit_id, 'DEVELOPER', actor_developer_id, action_type, target_user_id, NULL, occurred_at, outcome, metadata_json
         FROM admin_audit_log`,
      `DROP TRIGGER admin_audit_log_no_update`,
      `DROP TRIGGER admin_audit_log_no_delete`,
      `DROP TRIGGER admin_audit_log_no_replacement`,
      `DROP TABLE admin_audit_log`,
      `ALTER TABLE admin_audit_log_v4 RENAME TO admin_audit_log`,
      `CREATE INDEX admin_audit_by_time ON admin_audit_log(occurred_at DESC, audit_id)`,
      `CREATE INDEX admin_audit_by_actor ON admin_audit_log(actor_developer_id, occurred_at DESC)`,
      `CREATE INDEX admin_audit_by_target ON admin_audit_log(target_user_id, occurred_at DESC)`,
      `CREATE INDEX admin_audit_by_incident ON admin_audit_log(incident_id, occurred_at DESC)`,
      `CREATE TRIGGER admin_audit_log_no_update BEFORE UPDATE ON admin_audit_log
         BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END`,
      `CREATE TRIGGER admin_audit_log_no_delete BEFORE DELETE ON admin_audit_log
         BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END`,
      `CREATE TRIGGER admin_audit_log_no_replacement BEFORE INSERT ON admin_audit_log
         WHEN EXISTS (SELECT 1 FROM admin_audit_log WHERE audit_id = NEW.audit_id)
         BEGIN SELECT RAISE(ABORT, 'audit records cannot be replaced'); END`,
    ],
  },
];

export const AUDIT_ACTION_TYPES = Object.freeze({
  DEVELOPER_TOOLS: Object.freeze([
    "overview", "inspect_user", "list_user_sessions", "revoke_user_sessions", "suspend_user", "restore_user",
    "list_entitlements", "grant_entitlement", "revoke_entitlement", "list_audit_log",
    "configuration_status", "unknown_tool", "action_confirmation", "developer_ai_turn", "developer_session_revoke",
  ]),
  SECURITY_CENTER_TOOLS: Object.freeze([
    "security_overview", "list_security_incidents", "get_security_incident", "list_security_events",
    "list_security_actions", "list_security_notifications", "developer_ai_security_summary",
  ]),
  AUTOMATED_SECURITY_ACTIONS: Object.freeze([
    "SECURITY_INCIDENT_CREATED", "SECURITY_RATE_LIMIT_APPLIED", "SECURITY_REQUEST_REJECTED",
    "SECURITY_SESSION_REVOKED", "SECURITY_ACCOUNT_PROTECTED", "SECURITY_ALERT_CREATED",
    "SECURITY_PROTECTION_RELEASED", "SECURITY_INCIDENT_RESOLVED",
  ]),
});

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

function migrate(database, { upTo = SCHEMA_VERSION } = {}) {
  const applied = new Set(database.prepare("SELECT version FROM schema_migrations").all().map((row) => Number(row.version)));
  for (const migration of MIGRATIONS) {
    if (migration.version > upTo) break;
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

/**
 * Test-only migration control: applies the schema up to (but not including) a later version so backward-compatible
 * upgrades can be proven against a real pre-Phase-20 database file.
 */
export function migrateToVersion(database, version) {
  migrate(database, { upTo: version });
}

export const FIRST_SCHEMA_VERSION = MIGRATIONS[0].version;
export const SCHEMA_VERSION = MIGRATIONS.at(-1).version;
