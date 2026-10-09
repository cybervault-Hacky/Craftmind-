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
      // The confirmation challenge records the tool's action type, so its CHECK list is widened in the same migration.
      // Its rows are pending proposals only: while one is being copied it cannot be consumed, and the copy happens
      // inside this migration's own transaction, so no challenge is ever in flight across the rebuild.
      `CREATE TABLE developer_action_confirmations_v5 (
         confirmation_digest TEXT PRIMARY KEY,
         developer_id TEXT NOT NULL REFERENCES developer_accounts(developer_id) ON DELETE CASCADE,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'revoke_user_sessions', 'suspend_user', 'restore_user', 'grant_entitlement', 'revoke_entitlement',
           'grant_membership', 'grant_credits', 'reverse_credit_grant'
         )),
         target_user_id TEXT,
         arguments_json TEXT NOT NULL CHECK (length(arguments_json) <= 4096),
         created_at TEXT NOT NULL,
         expires_at TEXT NOT NULL,
         consumed_at TEXT
       )`,
      `INSERT INTO developer_action_confirmations_v5
         (confirmation_digest, developer_id, action_type, target_user_id, arguments_json, created_at, expires_at, consumed_at)
       SELECT confirmation_digest, developer_id, action_type, target_user_id, arguments_json, created_at, expires_at, consumed_at
         FROM developer_action_confirmations`,
      `DROP TABLE developer_action_confirmations`,
      `ALTER TABLE developer_action_confirmations_v5 RENAME TO developer_action_confirmations`,
      `CREATE INDEX developer_confirmations_by_developer ON developer_action_confirmations(developer_id, created_at DESC)`,
      `CREATE INDEX developer_confirmations_by_expiry ON developer_action_confirmations(expires_at)`,
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
  {
    // Phase 22 is additive: membership state, an append-only credit ledger, and an idempotency key table. Two
    // extensions are made to the *existing* structures rather than adding competing ones:
    //
    //   * `admin_audit_log` is rebuilt with the Phase 22 tool and domain action types, and with a `SYSTEM` actor
    //     category for engine-initiated domain events (a baseline assignment, an expiration, a consumption). Every
    //     existing row, its actor, and its incident reference are copied across; the append-only triggers are recreated.
    //     There is still exactly one audit log.
    //   * Nothing is dropped: `users`, `sessions`, `guest_identities`, the developer tables, the Phase 20 security
    //     tables, and every Phase 21 website artefact are untouched.
    //
    // The credit ledger is append-only and immutable (enforced by triggers). Balances are always derived from it, so
    // `users` never carries a mutable credit column and no code path can assign a balance directly.
    version: 5,
    statements: [
      `CREATE TABLE membership_accounts (
         membership_id TEXT PRIMARY KEY,
         user_id TEXT NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
         plan TEXT NOT NULL CHECK (plan IN ('FREE', 'PRO', 'CREATOR', 'SERVER')),
         status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED', 'PENDING', 'UNAVAILABLE')),
         source TEXT NOT NULL CHECK (source IN ('DEFAULT_BASELINE', 'DEVELOPER_GRANT', 'BASELINE_FALLBACK')),
         starts_at TEXT NOT NULL,
         ends_at TEXT,
         granted_by TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL,
         CHECK (status <> 'ACTIVE' OR plan = 'FREE' OR ends_at IS NOT NULL),
         CHECK (ends_at IS NULL OR ends_at > starts_at)
       )`,
      `CREATE INDEX membership_accounts_by_plan ON membership_accounts(plan, status)`,
      `CREATE INDEX membership_accounts_by_expiry ON membership_accounts(ends_at) WHERE ends_at IS NOT NULL`,
      `CREATE TABLE membership_transitions (
         transition_id TEXT PRIMARY KEY,
         user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         from_plan TEXT NOT NULL,
         to_plan TEXT NOT NULL,
         from_status TEXT NOT NULL,
         to_status TEXT NOT NULL,
         reason TEXT NOT NULL CHECK (length(reason) <= 200),
         source TEXT NOT NULL CHECK (source IN ('DEFAULT_BASELINE', 'DEVELOPER_GRANT', 'BASELINE_FALLBACK')),
         actor_kind TEXT NOT NULL CHECK (actor_kind IN ('DEVELOPER', 'SYSTEM_SECURITY', 'AI', 'SYSTEM')),
         actor_developer_id TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         occurred_at TEXT NOT NULL,
         CHECK (actor_kind <> 'SYSTEM' OR actor_developer_id IS NULL),
         CHECK (actor_kind = 'SYSTEM' OR actor_developer_id IS NOT NULL)
       )`,
      `CREATE INDEX membership_transitions_by_user ON membership_transitions(user_id, occurred_at DESC, transition_id)`,
      `CREATE TRIGGER membership_transitions_no_update BEFORE UPDATE ON membership_transitions
         BEGIN SELECT RAISE(ABORT, 'membership history is append-only'); END`,
      `CREATE TRIGGER membership_transitions_no_delete BEFORE DELETE ON membership_transitions
         BEGIN SELECT RAISE(ABORT, 'membership history is append-only'); END`,
      `CREATE TABLE credit_ledger (
         transaction_id TEXT PRIMARY KEY,
         user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         type TEXT NOT NULL CHECK (type IN ('GRANT', 'CONSUME', 'EXPIRE', 'ADJUSTMENT', 'REVERSAL')),
         amount INTEGER NOT NULL CHECK (amount <> 0 AND amount >= -1000000000 AND amount <= 1000000000),
         reason TEXT NOT NULL CHECK (length(reason) <= 200),
         source TEXT NOT NULL CHECK (source IN (
           'DEVELOPER_GRANT', 'PLAN_ALLOCATION', 'BUILD_CONSUMPTION', 'EXPIRATION', 'REVERSAL'
         )),
         reference_id TEXT,
         actor_kind TEXT NOT NULL CHECK (actor_kind IN ('DEVELOPER', 'SYSTEM_SECURITY', 'AI', 'SYSTEM')),
         actor_developer_id TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         created_at TEXT NOT NULL,
         expires_at TEXT,
         CHECK (actor_kind <> 'SYSTEM' OR actor_developer_id IS NULL),
         CHECK (actor_kind = 'SYSTEM' OR actor_developer_id IS NOT NULL),
         CHECK (actor_kind <> 'AI' OR type <> 'GRANT'),
         CHECK (type IN ('GRANT', 'ADJUSTMENT') OR expires_at IS NULL),
         CHECK (type <> 'GRANT' OR amount > 0),
         CHECK (type NOT IN ('CONSUME', 'EXPIRE', 'REVERSAL') OR amount < 0),
         CHECK (type <> 'REVERSAL' OR reference_id IS NOT NULL)
       )`,
      `CREATE INDEX credit_ledger_by_user ON credit_ledger(user_id, created_at DESC, transaction_id)`,
      `CREATE INDEX credit_ledger_by_expiry ON credit_ledger(expires_at) WHERE expires_at IS NOT NULL`,
      `CREATE INDEX credit_ledger_by_reference ON credit_ledger(reference_id) WHERE reference_id IS NOT NULL`,
      `CREATE TRIGGER credit_ledger_no_update BEFORE UPDATE ON credit_ledger
         BEGIN SELECT RAISE(ABORT, 'the credit ledger is append-only'); END`,
      `CREATE TRIGGER credit_ledger_no_delete BEFORE DELETE ON credit_ledger
         BEGIN SELECT RAISE(ABORT, 'the credit ledger is append-only'); END`,
      `CREATE TRIGGER credit_ledger_no_replacement BEFORE INSERT ON credit_ledger
         WHEN EXISTS (SELECT 1 FROM credit_ledger WHERE transaction_id = NEW.transaction_id)
         BEGIN SELECT RAISE(ABORT, 'credit transactions cannot be replaced'); END`,
      `CREATE TABLE credit_operation_keys (
         operation_digest TEXT PRIMARY KEY,
         user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         operation TEXT NOT NULL CHECK (operation IN ('GRANT', 'CONSUME', 'EXPIRE', 'ADJUSTMENT', 'REVERSAL')),
         request_digest TEXT NOT NULL,
         transaction_id TEXT REFERENCES credit_ledger(transaction_id) ON DELETE RESTRICT,
         created_at TEXT NOT NULL
       )`,
      `CREATE INDEX credit_operation_keys_by_user ON credit_operation_keys(user_id, created_at DESC)`,
      `CREATE TABLE admin_audit_log_v5 (
         audit_id TEXT PRIMARY KEY,
         actor_kind TEXT NOT NULL CHECK (actor_kind IN ('DEVELOPER', 'SYSTEM_SECURITY', 'AI', 'SYSTEM')),
         actor_developer_id TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'overview', 'inspect_user', 'list_user_sessions', 'revoke_user_sessions', 'suspend_user', 'restore_user',
           'list_entitlements', 'grant_entitlement', 'revoke_entitlement', 'list_audit_log',
           'configuration_status', 'unknown_tool', 'action_confirmation', 'developer_ai_turn', 'developer_session_revoke',
           'security_overview', 'list_security_incidents', 'get_security_incident', 'list_security_events',
           'list_security_actions', 'list_security_notifications', 'developer_ai_security_summary',
           'SECURITY_INCIDENT_CREATED', 'SECURITY_RATE_LIMIT_APPLIED', 'SECURITY_REQUEST_REJECTED',
           'SECURITY_SESSION_REVOKED', 'SECURITY_ACCOUNT_PROTECTED', 'SECURITY_ALERT_CREATED',
           'SECURITY_PROTECTION_RELEASED', 'SECURITY_INCIDENT_RESOLVED',
           'inspect_membership', 'grant_membership', 'grant_credits', 'reverse_credit_grant', 'list_credit_transactions',
           'MEMBERSHIP_BASELINE_ASSIGNED', 'MEMBERSHIP_GRANTED', 'MEMBERSHIP_EXPIRED',
           'CREDIT_GRANTED', 'CREDIT_CONSUMED', 'CREDIT_EXPIRED', 'CREDIT_REVERSED',
           'CREDIT_OPERATION_REJECTED', 'ENTITLEMENT_DENIED'
         )),
         target_user_id TEXT,
         incident_id TEXT,
         occurred_at TEXT NOT NULL,
         outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED', 'PREPARED', 'CANCELLED')),
         metadata_json TEXT NOT NULL CHECK (length(metadata_json) <= 4096),
         CHECK (actor_kind <> 'SYSTEM_SECURITY' OR actor_developer_id IS NULL),
         CHECK (actor_kind <> 'SYSTEM' OR actor_developer_id IS NULL)
       )`,
      `INSERT INTO admin_audit_log_v5
         (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       SELECT audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json
         FROM admin_audit_log`,
      `DROP TRIGGER admin_audit_log_no_update`,
      `DROP TRIGGER admin_audit_log_no_delete`,
      `DROP TRIGGER admin_audit_log_no_replacement`,
      `DROP TABLE admin_audit_log`,
      `ALTER TABLE admin_audit_log_v5 RENAME TO admin_audit_log`,
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
  {
    // Phase 23 is additive: creator identity and server workspaces, each with the ownership rules and the append-only
    // history the marketplace phase will consume. Two existing structures are widened rather than duplicated:
    //
    //   * `admin_audit_log` is rebuilt with the Phase 23 tool names and domain events. Every existing row, its actor,
    //     its target, and its incident reference are copied across, and all three append-only triggers are recreated.
    //     There is still exactly one audit log.
    //   * `developer_action_confirmations` is rebuilt so the new high-impact tools can be confirmed. Its rows are
    //     pending proposals only, and the rebuild happens inside this migration's own transaction.
    //
    // Nothing is dropped. `users`, `sessions`, `guest_identities`, the developer tables, the Phase 20 security tables,
    // the Phase 22 membership and credit tables, and every Phase 21 website artefact are untouched.
    //
    // Ownership is enforced by the database, not by convention: a workspace has exactly one owner row, that row cannot
    // be updated or deleted, and a second owner row cannot be inserted. There is no ownership transfer in this phase,
    // so there is no code path — and no SQL path — that could quietly reassign a workspace.
    version: 6,
    statements: [
      `CREATE TABLE creator_profiles (
         creator_id TEXT PRIMARY KEY,
         user_id TEXT NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
         handle TEXT NOT NULL UNIQUE CHECK (length(handle) BETWEEN 3 AND 32),
         display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 40),
         bio TEXT NOT NULL DEFAULT '' CHECK (length(bio) <= 600),
         category TEXT CHECK (category IS NULL OR category IN ('Structures', 'Landscaping', 'Interiors', 'Redstone')),
         avatar_reference TEXT CHECK (avatar_reference IS NULL OR length(avatar_reference) <= 300),
         status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'PENDING', 'SUSPENDED', 'DISABLED')),
         verification_status TEXT NOT NULL CHECK (verification_status IN ('UNVERIFIED', 'PENDING', 'VERIFIED', 'REVOKED')),
         status_changed_at TEXT NOT NULL,
         verified_at TEXT,
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL,
         CHECK (verification_status <> 'VERIFIED' OR verified_at IS NOT NULL)
       )`,
      `CREATE INDEX creator_profiles_by_status ON creator_profiles(status, created_at DESC)`,
      `CREATE INDEX creator_profiles_by_verification ON creator_profiles(verification_status, created_at DESC)`,
      `CREATE TABLE creator_status_history (
         history_id TEXT PRIMARY KEY,
         creator_id TEXT NOT NULL REFERENCES creator_profiles(creator_id) ON DELETE CASCADE,
         user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         change_type TEXT NOT NULL CHECK (change_type IN ('STATUS', 'VERIFICATION')),
         from_value TEXT,
         to_value TEXT NOT NULL,
         reason TEXT NOT NULL CHECK (length(reason) BETWEEN 3 AND 200),
         actor_kind TEXT NOT NULL CHECK (actor_kind IN ('DEVELOPER', 'SYSTEM_SECURITY', 'AI', 'SYSTEM')),
         actor_developer_id TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         occurred_at TEXT NOT NULL,
         CHECK (actor_kind <> 'SYSTEM_SECURITY' OR actor_developer_id IS NULL),
         CHECK (actor_kind <> 'SYSTEM' OR actor_developer_id IS NULL)
       )`,
      `CREATE INDEX creator_status_history_by_creator ON creator_status_history(creator_id, occurred_at DESC, history_id)`,
      `CREATE INDEX creator_status_history_by_user ON creator_status_history(user_id, occurred_at DESC)`,
      `CREATE TRIGGER creator_status_history_no_update BEFORE UPDATE ON creator_status_history
         BEGIN SELECT RAISE(ABORT, 'creator history is append-only'); END`,
      `CREATE TRIGGER creator_status_history_no_delete BEFORE DELETE ON creator_status_history
         BEGIN SELECT RAISE(ABORT, 'creator history is append-only'); END`,
      `CREATE TABLE server_workspaces (
         server_id TEXT PRIMARY KEY,
         owner_user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         slug TEXT NOT NULL UNIQUE CHECK (length(slug) BETWEEN 3 AND 32),
         display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 60),
         description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 600),
         status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL
       )`,
      `CREATE INDEX server_workspaces_by_owner ON server_workspaces(owner_user_id, created_at DESC)`,
      `CREATE INDEX server_workspaces_by_status ON server_workspaces(status, created_at DESC)`,
      `CREATE TABLE server_members (
         member_id TEXT PRIMARY KEY,
         server_id TEXT NOT NULL REFERENCES server_workspaces(server_id) ON DELETE CASCADE,
         user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         role TEXT NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
         created_at TEXT NOT NULL,
         UNIQUE (server_id, user_id)
       )`,
      `CREATE INDEX server_members_by_user ON server_members(user_id, created_at DESC)`,
      `CREATE INDEX server_members_by_server ON server_members(server_id, role)`,
      `CREATE TRIGGER server_members_owner_immutable_update BEFORE UPDATE ON server_members
         WHEN OLD.role = 'OWNER'
         BEGIN SELECT RAISE(ABORT, 'workspace ownership cannot be changed'); END`,
      `CREATE TRIGGER server_members_owner_immutable_delete BEFORE DELETE ON server_members
         WHEN OLD.role = 'OWNER'
         BEGIN SELECT RAISE(ABORT, 'workspace ownership cannot be removed'); END`,
      `CREATE TRIGGER server_members_single_owner BEFORE INSERT ON server_members
         WHEN NEW.role = 'OWNER'
          AND EXISTS (SELECT 1 FROM server_members WHERE server_id = NEW.server_id AND role = 'OWNER')
         BEGIN SELECT RAISE(ABORT, 'a workspace has exactly one owner'); END`,
      `CREATE TABLE admin_audit_log_v6 (
         audit_id TEXT PRIMARY KEY,
         actor_kind TEXT NOT NULL CHECK (actor_kind IN ('DEVELOPER', 'SYSTEM_SECURITY', 'AI', 'SYSTEM')),
         actor_developer_id TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'overview', 'inspect_user', 'list_user_sessions', 'revoke_user_sessions', 'suspend_user', 'restore_user',
           'list_entitlements', 'grant_entitlement', 'revoke_entitlement', 'list_audit_log',
           'configuration_status', 'unknown_tool', 'action_confirmation', 'developer_ai_turn', 'developer_session_revoke',
           'security_overview', 'list_security_incidents', 'get_security_incident', 'list_security_events',
           'list_security_actions', 'list_security_notifications', 'developer_ai_security_summary',
           'SECURITY_INCIDENT_CREATED', 'SECURITY_RATE_LIMIT_APPLIED', 'SECURITY_REQUEST_REJECTED',
           'SECURITY_SESSION_REVOKED', 'SECURITY_ACCOUNT_PROTECTED', 'SECURITY_ALERT_CREATED',
           'SECURITY_PROTECTION_RELEASED', 'SECURITY_INCIDENT_RESOLVED',
           'inspect_membership', 'grant_membership', 'grant_credits', 'reverse_credit_grant', 'list_credit_transactions',
           'MEMBERSHIP_BASELINE_ASSIGNED', 'MEMBERSHIP_GRANTED', 'MEMBERSHIP_EXPIRED',
           'CREDIT_GRANTED', 'CREDIT_CONSUMED', 'CREDIT_EXPIRED', 'CREDIT_REVERSED',
           'CREDIT_OPERATION_REJECTED', 'ENTITLEMENT_DENIED',
           'inspect_creator', 'list_creator_profiles', 'verify_creator', 'revoke_creator_verification',
           'suspend_creator', 'restore_creator',
           'inspect_server', 'list_server_workspaces', 'suspend_server', 'restore_server', 'archive_server',
           'CREATOR_PROFILE_CREATED', 'CREATOR_PROFILE_UPDATED', 'CREATOR_STATUS_CHANGED',
           'CREATOR_VERIFICATION_CHANGED', 'CREATOR_ACCESS_DENIED',
           'SERVER_CREATED', 'SERVER_UPDATED', 'SERVER_STATUS_CHANGED', 'SERVER_ACCESS_DENIED'
         )),
         target_user_id TEXT,
         incident_id TEXT,
         occurred_at TEXT NOT NULL,
         outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED', 'PREPARED', 'CANCELLED')),
         metadata_json TEXT NOT NULL CHECK (length(metadata_json) <= 4096),
         CHECK (actor_kind <> 'SYSTEM_SECURITY' OR actor_developer_id IS NULL),
         CHECK (actor_kind <> 'SYSTEM' OR actor_developer_id IS NULL)
       )`,
      `INSERT INTO admin_audit_log_v6
         (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       SELECT audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json
         FROM admin_audit_log`,
      `DROP TRIGGER admin_audit_log_no_update`,
      `DROP TRIGGER admin_audit_log_no_delete`,
      `DROP TRIGGER admin_audit_log_no_replacement`,
      `DROP TABLE admin_audit_log`,
      `ALTER TABLE admin_audit_log_v6 RENAME TO admin_audit_log`,
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
      `CREATE TABLE developer_action_confirmations_v6 (
         confirmation_digest TEXT PRIMARY KEY,
         developer_id TEXT NOT NULL REFERENCES developer_accounts(developer_id) ON DELETE CASCADE,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'revoke_user_sessions', 'suspend_user', 'restore_user', 'grant_entitlement', 'revoke_entitlement',
           'grant_membership', 'grant_credits', 'reverse_credit_grant',
           'verify_creator', 'revoke_creator_verification', 'suspend_creator', 'restore_creator',
           'suspend_server', 'restore_server', 'archive_server'
         )),
         target_user_id TEXT,
         arguments_json TEXT NOT NULL CHECK (length(arguments_json) <= 4096),
         created_at TEXT NOT NULL,
         expires_at TEXT NOT NULL,
         consumed_at TEXT
       )`,
      `INSERT INTO developer_action_confirmations_v6
         (confirmation_digest, developer_id, action_type, target_user_id, arguments_json, created_at, expires_at, consumed_at)
       SELECT confirmation_digest, developer_id, action_type, target_user_id, arguments_json, created_at, expires_at, consumed_at
         FROM developer_action_confirmations`,
      `DROP TABLE developer_action_confirmations`,
      `ALTER TABLE developer_action_confirmations_v6 RENAME TO developer_action_confirmations`,
      `CREATE INDEX developer_confirmations_by_developer ON developer_action_confirmations(developer_id, created_at DESC)`,
      `CREATE INDEX developer_confirmations_by_expiry ON developer_action_confirmations(expires_at)`,
    ],
  },
  {
    // Phase 24: buyer and seller onboarding records.
    //
    // Nothing is dropped or rewritten. `buyer_onboarding` and `seller_onboarding` are new 1:1 records keyed by the
    // account — deliberately NOT new identities: the creator handle, display name, bio, avatar, and category stay in
    // `creator_profiles`, and email verification stays in `users.email_verified_at`, so onboarding cannot become a
    // second source of truth for either. A seller row stores only what Phase 23 had no place for: the referral answer,
    // the compatibility claims, and the creator-agreement receipt.
    //
    // The audit log is rebuilt to declare two new action types (`BUYER_ONBOARDING_SAVED`, `SELLER_ONBOARDING_SAVED`) in
    // its CHECK constraint, following the exact pattern of v6: create the shadow table, copy every row, replace, then
    // restore the indexes and append-only triggers. Every v6 action type stays listed, so history remains writable-then
    // immutable and no existing record becomes invalid.
    version: 7,
    statements: [
      `CREATE TABLE buyer_onboarding (
         user_id TEXT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
         full_name TEXT NOT NULL CHECK (length(full_name) BETWEEN 2 AND 120),
         referral_source TEXT NOT NULL CHECK (referral_source IN (
           'YOUTUBE', 'INSTAGRAM', 'GOOGLE', 'REDDIT', 'DISCORD', 'FRIEND_REFERRAL', 'MINECRAFT_COMMUNITY', 'OTHER'
         )),
         referral_detail TEXT NOT NULL DEFAULT '' CHECK (length(referral_detail) <= 160),
         completed_at TEXT NOT NULL,
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL
       )`,
      `CREATE TABLE seller_onboarding (
         user_id TEXT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
         referral_source TEXT NOT NULL CHECK (referral_source IN (
           'YOUTUBE', 'INSTAGRAM', 'GOOGLE', 'REDDIT', 'DISCORD', 'FRIEND_REFERRAL', 'MINECRAFT_COMMUNITY', 'OTHER'
         )),
         referral_detail TEXT NOT NULL DEFAULT '' CHECK (length(referral_detail) <= 160),
         editions TEXT NOT NULL CHECK (length(editions) BETWEEN 2 AND 400),
         minecraft_versions TEXT NOT NULL CHECK (length(minecraft_versions) BETWEEN 2 AND 400),
         loaders TEXT NOT NULL CHECK (length(loaders) BETWEEN 2 AND 400),
         agreement_version TEXT NOT NULL CHECK (length(agreement_version) BETWEEN 3 AND 64),
         agreed_at TEXT NOT NULL,
         completed_at TEXT NOT NULL,
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL
       )`,
      `CREATE TABLE admin_audit_log_v7 (
         audit_id TEXT PRIMARY KEY,
         actor_kind TEXT NOT NULL CHECK (actor_kind IN ('DEVELOPER', 'SYSTEM_SECURITY', 'AI', 'SYSTEM')),
         actor_developer_id TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'overview', 'inspect_user', 'list_user_sessions', 'revoke_user_sessions', 'suspend_user', 'restore_user',
           'list_entitlements', 'grant_entitlement', 'revoke_entitlement', 'list_audit_log',
           'configuration_status', 'unknown_tool', 'action_confirmation', 'developer_ai_turn', 'developer_session_revoke',
           'security_overview', 'list_security_incidents', 'get_security_incident', 'list_security_events',
           'list_security_actions', 'list_security_notifications', 'developer_ai_security_summary',
           'SECURITY_INCIDENT_CREATED', 'SECURITY_RATE_LIMIT_APPLIED', 'SECURITY_REQUEST_REJECTED',
           'SECURITY_SESSION_REVOKED', 'SECURITY_ACCOUNT_PROTECTED', 'SECURITY_ALERT_CREATED',
           'SECURITY_PROTECTION_RELEASED', 'SECURITY_INCIDENT_RESOLVED',
           'inspect_membership', 'grant_membership', 'grant_credits', 'reverse_credit_grant', 'list_credit_transactions',
           'MEMBERSHIP_BASELINE_ASSIGNED', 'MEMBERSHIP_GRANTED', 'MEMBERSHIP_EXPIRED',
           'CREDIT_GRANTED', 'CREDIT_CONSUMED', 'CREDIT_EXPIRED', 'CREDIT_REVERSED',
           'CREDIT_OPERATION_REJECTED', 'ENTITLEMENT_DENIED',
           'inspect_creator', 'list_creator_profiles', 'verify_creator', 'revoke_creator_verification',
           'suspend_creator', 'restore_creator',
           'inspect_server', 'list_server_workspaces', 'suspend_server', 'restore_server', 'archive_server',
           'CREATOR_PROFILE_CREATED', 'CREATOR_PROFILE_UPDATED', 'CREATOR_STATUS_CHANGED',
           'CREATOR_VERIFICATION_CHANGED', 'CREATOR_ACCESS_DENIED',
           'SERVER_CREATED', 'SERVER_UPDATED', 'SERVER_STATUS_CHANGED', 'SERVER_ACCESS_DENIED',
           'BUYER_ONBOARDING_SAVED', 'SELLER_ONBOARDING_SAVED'
         )),
         target_user_id TEXT,
         incident_id TEXT,
         occurred_at TEXT NOT NULL,
         outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED', 'PREPARED', 'CANCELLED')),
         metadata_json TEXT NOT NULL CHECK (length(metadata_json) <= 4096),
         CHECK (actor_kind <> 'SYSTEM_SECURITY' OR actor_developer_id IS NULL),
         CHECK (actor_kind <> 'SYSTEM' OR actor_developer_id IS NULL)
       )`,
      `INSERT INTO admin_audit_log_v7
         (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       SELECT audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json
         FROM admin_audit_log`,
      `DROP TRIGGER admin_audit_log_no_update`,
      `DROP TRIGGER admin_audit_log_no_delete`,
      `DROP TRIGGER admin_audit_log_no_replacement`,
      `DROP TABLE admin_audit_log`,
      `ALTER TABLE admin_audit_log_v7 RENAME TO admin_audit_log`,
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
  {
    // Phase 25: marketplace listings.
    //
    // One table, keyed to the creator profile that Phase 23 already owns — ownership is never a column a client can
    // set, and there is no second identity: the listing points at `creator_profiles`, which points at `users`.
    // Everything is additive; no existing table, trigger, or audit row is rewritten. The CHECK constraints are a
    // backstop for what the listing services already enforce: bounded text, the fixed category/edition/status
    // vocabularies, and the invariant that a published listing must carry a publication timestamp.
    //
    // Deliberately absent columns: price, sales, reviews, ratings, downloads, moderation decisions, and any
    // verification marker — none of those mechanisms exist in this phase, and inventing the columns would invite
    // fabricating the data.
    version: 8,
    statements: [
      `CREATE TABLE marketplace_listings (
         listing_id TEXT PRIMARY KEY,
         creator_id TEXT NOT NULL REFERENCES creator_profiles(creator_id) ON DELETE CASCADE,
         title TEXT NOT NULL CHECK (length(title) BETWEEN 3 AND 120),
         description TEXT NOT NULL CHECK (length(description) BETWEEN 10 AND 5000),
         category TEXT NOT NULL CHECK (category IN (
           'Structures', 'Landscaping', 'Interiors', 'Redstone', 'Farms', 'Decorations', 'Mini-games', 'Whole worlds'
         )),
         subcategory TEXT NOT NULL DEFAULT '' CHECK (length(subcategory) <= 40),
         edition TEXT NOT NULL CHECK (edition IN ('java', 'bedrock', 'legacy')),
         minecraft_versions TEXT NOT NULL CHECK (length(minecraft_versions) BETWEEN 2 AND 400),
         loaders TEXT NOT NULL CHECK (length(loaders) <= 400),
         tags TEXT NOT NULL CHECK (length(tags) <= 420),
         image_references TEXT NOT NULL CHECK (length(image_references) <= 1400),
         status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
         published_at TEXT,
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL,
         CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
       )`,
      `CREATE INDEX marketplace_listings_by_creator ON marketplace_listings(creator_id, updated_at DESC, listing_id)`,
      `CREATE INDEX marketplace_listings_by_published ON marketplace_listings(published_at DESC, listing_id) WHERE status = 'PUBLISHED'`,
      `CREATE INDEX marketplace_listings_by_category ON marketplace_listings(category, status, published_at DESC)`,
      // The audit log is rebuilt once more to declare the four Phase 25 listing action types in its CHECK
      // constraint — same shadow-table pattern as v6 and v7: copy every row, replace, restore the append-only
      // triggers. No existing record is dropped or rewritten, and unknown action types stay refused.
      `CREATE TABLE admin_audit_log_v8 (
         audit_id TEXT PRIMARY KEY,
         actor_kind TEXT NOT NULL CHECK (actor_kind IN ('DEVELOPER', 'SYSTEM_SECURITY', 'AI', 'SYSTEM')),
         actor_developer_id TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'overview', 'inspect_user', 'list_user_sessions', 'revoke_user_sessions', 'suspend_user', 'restore_user',
           'list_entitlements', 'grant_entitlement', 'revoke_entitlement', 'list_audit_log',
           'configuration_status', 'unknown_tool', 'action_confirmation', 'developer_ai_turn', 'developer_session_revoke',
           'security_overview', 'list_security_incidents', 'get_security_incident', 'list_security_events',
           'list_security_actions', 'list_security_notifications', 'developer_ai_security_summary',
           'SECURITY_INCIDENT_CREATED', 'SECURITY_RATE_LIMIT_APPLIED', 'SECURITY_REQUEST_REJECTED',
           'SECURITY_SESSION_REVOKED', 'SECURITY_ACCOUNT_PROTECTED', 'SECURITY_ALERT_CREATED',
           'SECURITY_PROTECTION_RELEASED', 'SECURITY_INCIDENT_RESOLVED',
           'inspect_membership', 'grant_membership', 'grant_credits', 'reverse_credit_grant', 'list_credit_transactions',
           'MEMBERSHIP_BASELINE_ASSIGNED', 'MEMBERSHIP_GRANTED', 'MEMBERSHIP_EXPIRED',
           'CREDIT_GRANTED', 'CREDIT_CONSUMED', 'CREDIT_EXPIRED', 'CREDIT_REVERSED',
           'CREDIT_OPERATION_REJECTED', 'ENTITLEMENT_DENIED',
           'inspect_creator', 'list_creator_profiles', 'verify_creator', 'revoke_creator_verification',
           'suspend_creator', 'restore_creator',
           'inspect_server', 'list_server_workspaces', 'suspend_server', 'restore_server', 'archive_server',
           'CREATOR_PROFILE_CREATED', 'CREATOR_PROFILE_UPDATED', 'CREATOR_STATUS_CHANGED',
           'CREATOR_VERIFICATION_CHANGED', 'CREATOR_ACCESS_DENIED',
           'SERVER_CREATED', 'SERVER_UPDATED', 'SERVER_STATUS_CHANGED', 'SERVER_ACCESS_DENIED',
           'BUYER_ONBOARDING_SAVED', 'SELLER_ONBOARDING_SAVED',
           'LISTING_CREATED', 'LISTING_UPDATED', 'LISTING_PUBLISHED', 'LISTING_ARCHIVED'
         )),
         target_user_id TEXT,
         incident_id TEXT,
         occurred_at TEXT NOT NULL,
         outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED', 'PREPARED', 'CANCELLED')),
         metadata_json TEXT NOT NULL CHECK (length(metadata_json) <= 4096),
         CHECK (actor_kind <> 'SYSTEM_SECURITY' OR actor_developer_id IS NULL),
         CHECK (actor_kind <> 'SYSTEM' OR actor_developer_id IS NULL)
       )`,
      `INSERT INTO admin_audit_log_v8
         (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       SELECT audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json
         FROM admin_audit_log`,
      `DROP TRIGGER admin_audit_log_no_update`,
      `DROP TRIGGER admin_audit_log_no_delete`,
      `DROP TRIGGER admin_audit_log_no_replacement`,
      `DROP TABLE admin_audit_log`,
      `ALTER TABLE admin_audit_log_v8 RENAME TO admin_audit_log`,
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
      `CREATE INDEX marketplace_listings_by_edition ON marketplace_listings(edition, status, published_at DESC)`,
    ],
  },
  {
    // Phase 26 — Hire a Builder. Two additive tables: buyer job requests and the proposals creators submit on
    // them. Jobs carry no PII beyond the owning account reference; proposals are hidden from everyone except their
    // creator and the buyer who owns the job. The partial unique index is the database-level guarantee that one
    // creator can never hold two live proposals on the same job, even if two requests race the application layer.
    // The audit log is rebuilt once more (shadow-table pattern from v6/v7/v8) so its CHECK constraint accepts the
    // ten Phase 26 hire action types; no existing record is dropped or rewritten.
    version: 9,
    statements: [
      `CREATE TABLE buyer_jobs (
         job_id TEXT PRIMARY KEY,
         buyer_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         title TEXT NOT NULL CHECK (length(title) BETWEEN 3 AND 120),
         description TEXT NOT NULL CHECK (length(description) BETWEEN 10 AND 5000),
         edition TEXT NOT NULL CHECK (edition IN ('java', 'bedrock', 'legacy')),
         minecraft_version TEXT NOT NULL CHECK (length(minecraft_version) BETWEEN 2 AND 32),
         loaders TEXT NOT NULL CHECK (length(loaders) <= 400),
         image_references TEXT NOT NULL CHECK (length(image_references) <= 1400),
         budget_min INTEGER CHECK (budget_min IS NULL OR budget_min >= 0),
         budget_max INTEGER CHECK (budget_max IS NULL OR budget_max >= 0),
         budget_currency TEXT CHECK (budget_currency IS NULL OR budget_currency IN ('INR', 'USD', 'EUR', 'GBP')),
         deadline TEXT,
         scope TEXT NOT NULL CHECK (length(scope) BETWEEN 5 AND 1000),
         status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'AWARDED', 'CANCELLED')),
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL,
         awarded_at TEXT,
         -- A budget is a validated triple or absent entirely; an award always records when it happened.
         CHECK (
           (budget_min IS NULL AND budget_max IS NULL AND budget_currency IS NULL)
           OR (budget_min IS NOT NULL AND budget_max IS NOT NULL AND budget_currency IS NOT NULL AND budget_min <= budget_max)
         ),
         CHECK (status <> 'AWARDED' OR awarded_at IS NOT NULL)
       )`,
      `CREATE TABLE job_proposals (
         proposal_id TEXT PRIMARY KEY,
         job_id TEXT NOT NULL REFERENCES buyer_jobs(job_id) ON DELETE CASCADE,
         user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         creator_id TEXT NOT NULL REFERENCES creator_profiles(creator_id) ON DELETE CASCADE,
         message TEXT NOT NULL CHECK (length(message) BETWEEN 10 AND 2000),
         scope TEXT NOT NULL CHECK (length(scope) BETWEEN 5 AND 2000),
         budget_min INTEGER CHECK (budget_min IS NULL OR budget_min >= 0),
         budget_max INTEGER CHECK (budget_max IS NULL OR budget_max >= 0),
         budget_currency TEXT CHECK (budget_currency IS NULL OR budget_currency IN ('INR', 'USD', 'EUR', 'GBP')),
         delivery_estimate_days INTEGER CHECK (delivery_estimate_days IS NULL OR delivery_estimate_days BETWEEN 1 AND 365),
         status TEXT NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED', 'WITHDRAWN', 'SELECTED', 'NOT_SELECTED')),
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL,
         CHECK (
           (budget_min IS NULL AND budget_max IS NULL AND budget_currency IS NULL)
           OR (budget_min IS NOT NULL AND budget_max IS NOT NULL AND budget_currency IS NOT NULL AND budget_min <= budget_max)
         )
       )`,
      `CREATE INDEX buyer_jobs_by_buyer ON buyer_jobs(buyer_id, updated_at DESC, job_id)`,
      `CREATE INDEX buyer_jobs_by_open ON buyer_jobs(created_at DESC, job_id) WHERE status = 'OPEN'`,
      `CREATE INDEX buyer_jobs_by_edition ON buyer_jobs(edition, status, created_at DESC)`,
      // One live proposal per creator per job, enforced by the database itself: a second SUBMITTED row for the
      // same (user, job) pair fails the insert even if the application check were somehow bypassed.
      `CREATE UNIQUE INDEX job_proposals_active_per_creator ON job_proposals(user_id, job_id) WHERE status = 'SUBMITTED'`,
      `CREATE INDEX job_proposals_by_job ON job_proposals(job_id, created_at, proposal_id)`,
      `CREATE INDEX job_proposals_by_creator ON job_proposals(user_id, updated_at DESC, proposal_id)`,
      `CREATE TABLE admin_audit_log_v9 (
         audit_id TEXT PRIMARY KEY,
         actor_kind TEXT NOT NULL CHECK (actor_kind IN ('DEVELOPER', 'SYSTEM_SECURITY', 'AI', 'SYSTEM')),
         actor_developer_id TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'overview', 'inspect_user', 'list_user_sessions', 'revoke_user_sessions', 'suspend_user', 'restore_user',
           'list_entitlements', 'grant_entitlement', 'revoke_entitlement', 'list_audit_log',
           'configuration_status', 'unknown_tool', 'action_confirmation', 'developer_ai_turn', 'developer_session_revoke',
           'security_overview', 'list_security_incidents', 'get_security_incident', 'list_security_events',
           'list_security_actions', 'list_security_notifications', 'developer_ai_security_summary',
           'SECURITY_INCIDENT_CREATED', 'SECURITY_RATE_LIMIT_APPLIED', 'SECURITY_REQUEST_REJECTED',
           'SECURITY_SESSION_REVOKED', 'SECURITY_ACCOUNT_PROTECTED', 'SECURITY_ALERT_CREATED',
           'SECURITY_PROTECTION_RELEASED', 'SECURITY_INCIDENT_RESOLVED',
           'inspect_membership', 'grant_membership', 'grant_credits', 'reverse_credit_grant', 'list_credit_transactions',
           'MEMBERSHIP_BASELINE_ASSIGNED', 'MEMBERSHIP_GRANTED', 'MEMBERSHIP_EXPIRED',
           'CREDIT_GRANTED', 'CREDIT_CONSUMED', 'CREDIT_EXPIRED', 'CREDIT_REVERSED',
           'CREDIT_OPERATION_REJECTED', 'ENTITLEMENT_DENIED',
           'inspect_creator', 'list_creator_profiles', 'verify_creator', 'revoke_creator_verification',
           'suspend_creator', 'restore_creator',
           'inspect_server', 'list_server_workspaces', 'suspend_server', 'restore_server', 'archive_server',
           'CREATOR_PROFILE_CREATED', 'CREATOR_PROFILE_UPDATED', 'CREATOR_STATUS_CHANGED',
           'CREATOR_VERIFICATION_CHANGED', 'CREATOR_ACCESS_DENIED',
           'SERVER_CREATED', 'SERVER_UPDATED', 'SERVER_STATUS_CHANGED', 'SERVER_ACCESS_DENIED',
           'BUYER_ONBOARDING_SAVED', 'SELLER_ONBOARDING_SAVED',
           'LISTING_CREATED', 'LISTING_UPDATED', 'LISTING_PUBLISHED', 'LISTING_ARCHIVED',
           'JOB_CREATED', 'JOB_UPDATED', 'JOB_CANCELLED', 'JOB_AWARDED', 'JOB_ACCESS_DENIED',
           'PROPOSAL_SUBMITTED', 'PROPOSAL_UPDATED', 'PROPOSAL_WITHDRAWN', 'PROPOSAL_SELECTED', 'PROPOSAL_ACCESS_DENIED'
         )),
         target_user_id TEXT,
         incident_id TEXT,
         occurred_at TEXT NOT NULL,
         outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED', 'PREPARED', 'CANCELLED')),
         metadata_json TEXT NOT NULL CHECK (length(metadata_json) <= 4096),
         CHECK (actor_kind <> 'SYSTEM_SECURITY' OR actor_developer_id IS NULL),
         CHECK (actor_kind <> 'SYSTEM' OR actor_developer_id IS NULL)
       )`,
      `INSERT INTO admin_audit_log_v9
         (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       SELECT audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json
         FROM admin_audit_log`,
      `DROP TRIGGER admin_audit_log_no_update`,
      `DROP TRIGGER admin_audit_log_no_delete`,
      `DROP TRIGGER admin_audit_log_no_replacement`,
      `DROP TABLE admin_audit_log`,
      `ALTER TABLE admin_audit_log_v9 RENAME TO admin_audit_log`,
      `CREATE INDEX admin_audit_by_time ON admin_audit_log(occurred_at DESC, audit_id)`,
      `CREATE INDEX admin_audit_by_actor ON admin_audit_log(actor_developer_id, occurred_at DESC, audit_id)`,
      `CREATE INDEX admin_audit_by_target ON admin_audit_log(target_user_id, occurred_at DESC, audit_id)`,
      `CREATE INDEX admin_audit_by_incident ON admin_audit_log(incident_id, occurred_at DESC, audit_id)`,
      `CREATE TRIGGER admin_audit_log_no_update BEFORE UPDATE ON admin_audit_log
         BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END`,
      `CREATE TRIGGER admin_audit_log_no_delete BEFORE DELETE ON admin_audit_log
         BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END`,
      `CREATE TRIGGER admin_audit_log_no_replacement BEFORE INSERT ON admin_audit_log
         WHEN EXISTS (SELECT 1 FROM admin_audit_log WHERE audit_id = NEW.audit_id)
         BEGIN SELECT RAISE(ABORT, 'audit records cannot be replaced'); END`,
    ],
  },
  {
    // Phase 27 — marketplace order lifecycle. An order converts exactly one SELECTED proposal (on an AWARDED
    // job) into a formal work agreement: the agreed terms are snapshotted at creation so later job/proposal
    // edits can never rewrite them. Milestones carry the state machine, deliveries are immutable versions
    // (a new submission never erases an earlier one), and revision requests are first-class rows so the
    // finite revision policy is counted from trusted records rather than client claims. Amount totals are
    // enforced by triggers, not only by application checks. The audit log is rebuilt once more (shadow-table
    // pattern from v6–v9) so its CHECK constraint accepts the ten Phase 27 order action types.
    version: 10,
    statements: [
      `CREATE TABLE orders (
         order_id TEXT PRIMARY KEY,
         job_id TEXT NOT NULL REFERENCES buyer_jobs(job_id) ON DELETE CASCADE,
         proposal_id TEXT NOT NULL REFERENCES job_proposals(proposal_id) ON DELETE CASCADE,
         buyer_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         creator_user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         job_title TEXT NOT NULL CHECK (length(job_title) BETWEEN 3 AND 120),
         scope TEXT NOT NULL CHECK (length(scope) BETWEEN 5 AND 2000),
         edition TEXT NOT NULL CHECK (edition IN ('java', 'bedrock', 'legacy')),
         minecraft_version TEXT NOT NULL CHECK (length(minecraft_version) BETWEEN 2 AND 32),
         loaders TEXT NOT NULL CHECK (length(loaders) <= 400),
         agreed_budget_min INTEGER CHECK (agreed_budget_min IS NULL OR agreed_budget_min >= 0),
         agreed_budget_max INTEGER CHECK (agreed_budget_max IS NULL OR agreed_budget_max >= 0),
         agreed_currency TEXT CHECK (agreed_currency IS NULL OR agreed_currency IN ('INR', 'USD', 'EUR', 'GBP')),
         agreed_deadline TEXT,
         agreed_delivery_estimate_days INTEGER CHECK (agreed_delivery_estimate_days IS NULL OR agreed_delivery_estimate_days BETWEEN 1 AND 365),
         revision_limit INTEGER NOT NULL CHECK (revision_limit BETWEEN 0 AND 10),
         status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL,
         completed_at TEXT,
         cancelled_at TEXT,
         -- The agreed budget is a triple or absent entirely, exactly like the proposal it was snapshotted from.
         CHECK (
           (agreed_budget_min IS NULL AND agreed_budget_max IS NULL AND agreed_currency IS NULL)
           OR (agreed_budget_min IS NOT NULL AND agreed_budget_max IS NOT NULL AND agreed_currency IS NOT NULL)
         ),
         CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL),
         CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL)
       )`,
      `CREATE TABLE order_milestones (
         milestone_id TEXT PRIMARY KEY,
         order_id TEXT NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
         position INTEGER NOT NULL CHECK (position BETWEEN 1 AND 50),
         title TEXT NOT NULL CHECK (length(title) BETWEEN 3 AND 120),
         description TEXT NOT NULL CHECK (length(description) BETWEEN 5 AND 2000),
         acceptance_criteria TEXT NOT NULL CHECK (length(acceptance_criteria) BETWEEN 3 AND 1000),
         amount INTEGER CHECK (amount IS NULL OR amount >= 0),
         status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SUBMITTED', 'REVISION_REQUESTED', 'APPROVED')),
         created_at TEXT NOT NULL,
         updated_at TEXT NOT NULL,
         started_at TEXT,
         submitted_at TEXT,
         approved_at TEXT,
         CHECK (status <> 'IN_PROGRESS' OR started_at IS NOT NULL),
         CHECK (status <> 'REVISION_REQUESTED' OR submitted_at IS NOT NULL),
         CHECK (status <> 'SUBMITTED' OR submitted_at IS NOT NULL),
         CHECK (status <> 'APPROVED' OR approved_at IS NOT NULL)
       )`,
      `CREATE TABLE milestone_deliveries (
         delivery_id TEXT PRIMARY KEY,
         milestone_id TEXT NOT NULL REFERENCES order_milestones(milestone_id) ON DELETE CASCADE,
         order_id TEXT NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
         version INTEGER NOT NULL CHECK (version >= 1),
         note TEXT NOT NULL CHECK (length(note) BETWEEN 1 AND 2000),
         evidence_references TEXT NOT NULL CHECK (length(evidence_references) <= 1400),
         compatibility_note TEXT NOT NULL DEFAULT '' CHECK (length(compatibility_note) <= 500),
         submitted_by TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         submitted_at TEXT NOT NULL
       )`,
      `CREATE TABLE milestone_revision_requests (
         revision_id TEXT PRIMARY KEY,
         milestone_id TEXT NOT NULL REFERENCES order_milestones(milestone_id) ON DELETE CASCADE,
         order_id TEXT NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
         kind TEXT NOT NULL CHECK (kind IN ('REVISION', 'SCOPE_CHANGE')),
         reason TEXT NOT NULL CHECK (length(reason) BETWEEN 5 AND 1000),
         requested_by TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
         created_at TEXT NOT NULL
       )`,
      `CREATE INDEX orders_by_buyer ON orders(buyer_id, updated_at DESC, order_id)`,
      `CREATE INDEX orders_by_creator ON orders(creator_user_id, updated_at DESC, order_id)`,
      `CREATE INDEX orders_by_job ON orders(job_id)`,
      // The database itself refuses a second order from the same awarded proposal, even under a race.
      `CREATE UNIQUE INDEX orders_by_proposal ON orders(proposal_id)`,
      `CREATE UNIQUE INDEX milestone_position_per_order ON order_milestones(order_id, position)`,
      `CREATE UNIQUE INDEX milestone_delivery_versions ON milestone_deliveries(milestone_id, version)`,
      `CREATE INDEX milestone_deliveries_by_order ON milestone_deliveries(order_id, submitted_at DESC, delivery_id)`,
      `CREATE INDEX revision_requests_by_milestone ON milestone_revision_requests(milestone_id, created_at, revision_id)`,
      // Amount totals are guarded in the database as well as in the application: milestone amounts exist only
      // against a snapshotted agreed budget, and their sum can never exceed it.
      `CREATE TRIGGER milestone_amount_requires_budget BEFORE INSERT ON order_milestones
         WHEN NEW.amount IS NOT NULL
           AND (SELECT agreed_budget_max FROM orders WHERE order_id = NEW.order_id) IS NULL
         BEGIN SELECT RAISE(ABORT, 'milestone amounts require an agreed budget on the order'); END`,
      `CREATE TRIGGER milestone_amount_within_budget BEFORE INSERT ON order_milestones
         WHEN NEW.amount IS NOT NULL
           AND (SELECT COALESCE(SUM(amount), 0) FROM order_milestones WHERE order_id = NEW.order_id) + NEW.amount
             > (SELECT agreed_budget_max FROM orders WHERE order_id = NEW.order_id)
         BEGIN SELECT RAISE(ABORT, 'milestone amounts exceed the agreed order budget'); END`,
      // The same guards cover direct edits: a row can never gain an amount the application would have refused.
      `CREATE TRIGGER milestone_amount_requires_budget_update BEFORE UPDATE ON order_milestones
         WHEN NEW.amount IS NOT NULL
           AND (SELECT agreed_budget_max FROM orders WHERE order_id = NEW.order_id) IS NULL
         BEGIN SELECT RAISE(ABORT, 'milestone amounts require an agreed budget on the order'); END`,
      `CREATE TRIGGER milestone_amount_within_budget_update BEFORE UPDATE ON order_milestones
         WHEN NEW.amount IS NOT NULL
           AND (SELECT COALESCE(SUM(amount), 0) FROM order_milestones WHERE order_id = NEW.order_id)
               - COALESCE((SELECT amount FROM order_milestones WHERE milestone_id = OLD.milestone_id), 0) + NEW.amount
             > (SELECT agreed_budget_max FROM orders WHERE order_id = NEW.order_id)
         BEGIN SELECT RAISE(ABORT, 'milestone amounts exceed the agreed order budget'); END`,
      `CREATE TABLE admin_audit_log_v10 (
         audit_id TEXT PRIMARY KEY,
         actor_kind TEXT NOT NULL CHECK (actor_kind IN ('DEVELOPER', 'SYSTEM_SECURITY', 'AI', 'SYSTEM')),
         actor_developer_id TEXT REFERENCES developer_accounts(developer_id) ON DELETE RESTRICT,
         action_type TEXT NOT NULL CHECK (action_type IN (
           'overview', 'inspect_user', 'list_user_sessions', 'revoke_user_sessions', 'suspend_user', 'restore_user',
           'list_entitlements', 'grant_entitlement', 'revoke_entitlement', 'list_audit_log',
           'configuration_status', 'unknown_tool', 'action_confirmation', 'developer_ai_turn', 'developer_session_revoke',
           'security_overview', 'list_security_incidents', 'get_security_incident', 'list_security_events',
           'list_security_actions', 'list_security_notifications', 'developer_ai_security_summary',
           'SECURITY_INCIDENT_CREATED', 'SECURITY_RATE_LIMIT_APPLIED', 'SECURITY_REQUEST_REJECTED',
           'SECURITY_SESSION_REVOKED', 'SECURITY_ACCOUNT_PROTECTED', 'SECURITY_ALERT_CREATED',
           'SECURITY_PROTECTION_RELEASED', 'SECURITY_INCIDENT_RESOLVED',
           'inspect_membership', 'grant_membership', 'grant_credits', 'reverse_credit_grant', 'list_credit_transactions',
           'MEMBERSHIP_BASELINE_ASSIGNED', 'MEMBERSHIP_GRANTED', 'MEMBERSHIP_EXPIRED',
           'CREDIT_GRANTED', 'CREDIT_CONSUMED', 'CREDIT_EXPIRED', 'CREDIT_REVERSED',
           'CREDIT_OPERATION_REJECTED', 'ENTITLEMENT_DENIED',
           'inspect_creator', 'list_creator_profiles', 'verify_creator', 'revoke_creator_verification',
           'suspend_creator', 'restore_creator',
           'inspect_server', 'list_server_workspaces', 'suspend_server', 'restore_server', 'archive_server',
           'CREATOR_PROFILE_CREATED', 'CREATOR_PROFILE_UPDATED', 'CREATOR_STATUS_CHANGED',
           'CREATOR_VERIFICATION_CHANGED', 'CREATOR_ACCESS_DENIED',
           'SERVER_CREATED', 'SERVER_UPDATED', 'SERVER_STATUS_CHANGED', 'SERVER_ACCESS_DENIED',
           'BUYER_ONBOARDING_SAVED', 'SELLER_ONBOARDING_SAVED',
           'LISTING_CREATED', 'LISTING_UPDATED', 'LISTING_PUBLISHED', 'LISTING_ARCHIVED',
           'JOB_CREATED', 'JOB_UPDATED', 'JOB_CANCELLED', 'JOB_AWARDED', 'JOB_ACCESS_DENIED',
           'PROPOSAL_SUBMITTED', 'PROPOSAL_UPDATED', 'PROPOSAL_WITHDRAWN', 'PROPOSAL_SELECTED', 'PROPOSAL_ACCESS_DENIED',
           'ORDER_CREATED', 'ORDER_COMPLETED', 'ORDER_CANCELLED', 'ORDER_ACCESS_DENIED',
           'MILESTONE_STARTED', 'DELIVERY_SUBMITTED', 'DELIVERY_REVISED', 'REVISION_REQUESTED',
           'SCOPE_CHANGE_REQUESTED', 'MILESTONE_APPROVED'
         )),
         target_user_id TEXT,
         incident_id TEXT,
         occurred_at TEXT NOT NULL,
         outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED', 'PREPARED', 'CANCELLED')),
         metadata_json TEXT NOT NULL CHECK (length(metadata_json) <= 4096),
         CHECK (actor_kind <> 'SYSTEM_SECURITY' OR actor_developer_id IS NULL),
         CHECK (actor_kind <> 'SYSTEM' OR actor_developer_id IS NULL)
       )`,
      `INSERT INTO admin_audit_log_v10
         (audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json)
       SELECT audit_id, actor_kind, actor_developer_id, action_type, target_user_id, incident_id, occurred_at, outcome, metadata_json
         FROM admin_audit_log`,
      `DROP TRIGGER admin_audit_log_no_update`,
      `DROP TRIGGER admin_audit_log_no_delete`,
      `DROP TRIGGER admin_audit_log_no_replacement`,
      `DROP TABLE admin_audit_log`,
      `ALTER TABLE admin_audit_log_v10 RENAME TO admin_audit_log`,
      `CREATE INDEX admin_audit_by_time ON admin_audit_log(occurred_at DESC, audit_id)`,
      `CREATE INDEX admin_audit_by_actor ON admin_audit_log(actor_developer_id, occurred_at DESC, audit_id)`,
      `CREATE INDEX admin_audit_by_target ON admin_audit_log(target_user_id, occurred_at DESC, audit_id)`,
      `CREATE INDEX admin_audit_by_incident ON admin_audit_log(incident_id, occurred_at DESC, audit_id)`,
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
  /** The subset of Phase 22 tool names that a mutating action may be confirmed for. */
  CONFIRMABLE_MEMBERSHIP_TOOLS: Object.freeze(["grant_membership", "grant_credits", "reverse_credit_grant"]),
  MEMBERSHIP_AND_CREDIT_TOOLS: Object.freeze([
    "inspect_membership", "grant_membership", "grant_credits", "reverse_credit_grant", "list_credit_transactions",
  ]),
  MEMBERSHIP_AND_CREDIT_EVENTS: Object.freeze([
    "MEMBERSHIP_BASELINE_ASSIGNED", "MEMBERSHIP_GRANTED", "MEMBERSHIP_EXPIRED",
    "CREDIT_GRANTED", "CREDIT_CONSUMED", "CREDIT_EXPIRED", "CREDIT_REVERSED",
    "CREDIT_OPERATION_REJECTED", "ENTITLEMENT_DENIED",
  ]),
  CREATOR_TOOLS: Object.freeze([
    "inspect_creator", "list_creator_profiles", "verify_creator", "revoke_creator_verification",
    "suspend_creator", "restore_creator",
  ]),
  SERVER_TOOLS: Object.freeze([
    "inspect_server", "list_server_workspaces", "suspend_server", "restore_server", "archive_server",
  ]),
  /** The subset of Phase 23 tool names that a mutating action may be confirmed for. */
  CONFIRMABLE_CREATOR_SERVER_TOOLS: Object.freeze([
    "verify_creator", "revoke_creator_verification", "suspend_creator", "restore_creator",
    "suspend_server", "restore_server", "archive_server",
  ]),
  CREATOR_AND_SERVER_EVENTS: Object.freeze([
    "CREATOR_PROFILE_CREATED", "CREATOR_PROFILE_UPDATED", "CREATOR_STATUS_CHANGED",
    "CREATOR_VERIFICATION_CHANGED", "CREATOR_ACCESS_DENIED",
    "SERVER_CREATED", "SERVER_UPDATED", "SERVER_STATUS_CHANGED", "SERVER_ACCESS_DENIED",
  ]),
  ONBOARDING_EVENTS: Object.freeze(["BUYER_ONBOARDING_SAVED", "SELLER_ONBOARDING_SAVED"]),
  MARKETPLACE_LISTING_EVENTS: Object.freeze([
    "LISTING_CREATED", "LISTING_UPDATED", "LISTING_PUBLISHED", "LISTING_ARCHIVED",
  ]),
  // Phase 26 Hire a Builder. Records name ids and state only — never a proposal's message, scope, budget, or any
  // personal detail — and denials ride the same append-only path so they survive a rolled-back transaction.
  HIRE_EVENTS: Object.freeze([
    "JOB_CREATED", "JOB_UPDATED", "JOB_CANCELLED", "JOB_AWARDED", "JOB_ACCESS_DENIED",
    "PROPOSAL_SUBMITTED", "PROPOSAL_UPDATED", "PROPOSAL_WITHDRAWN", "PROPOSAL_SELECTED", "PROPOSAL_ACCESS_DENIED",
  ]),
  // Phase 27 marketplace orders. Same discipline as every earlier group: ids, transition vocabulary, counts, and
  // reason categories only — never delivery contents, external URLs, proposal text, or personal details.
  ORDER_EVENTS: Object.freeze([
    "ORDER_CREATED", "ORDER_COMPLETED", "ORDER_CANCELLED", "ORDER_ACCESS_DENIED",
    "MILESTONE_STARTED", "DELIVERY_SUBMITTED", "DELIVERY_REVISED", "REVISION_REQUESTED",
    "SCOPE_CHANGE_REQUESTED", "MILESTONE_APPROVED",
  ]),
  AUTOMATED_SECURITY_ACTIONS: Object.freeze([
    "SECURITY_INCIDENT_CREATED", "SECURITY_RATE_LIMIT_APPLIED", "SECURITY_REQUEST_REJECTED",
    "SECURITY_SESSION_REVOKED", "SECURITY_ACCOUNT_PROTECTED", "SECURITY_ALERT_CREATED",
    "SECURITY_PROTECTION_RELEASED", "SECURITY_INCIDENT_RESOLVED",
  ]),
});

/**
 * Every action type the audit log accepts, derived from the groups above so a new action type cannot be added in one
 * place only. `appendAuditRecord` checks membership before writing, so an unregistered action fails closed.
 */
export const REGISTERED_AUDIT_ACTION_TYPES = Object.freeze(
  new Set(Object.values(AUDIT_ACTION_TYPES).flatMap((group) => [...group])),
);

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
