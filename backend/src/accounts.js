/**
 * Account, one-time-token, and session rules. The HTTP router only translates the contracts; this module remains the
 * server authority for identities, verification state, password proof, and session lifecycle.
 */

import { ACCOUNT_STATUS } from "./db.js";
import { AccountApiError, ErrorCode } from "./errors.js";
import {
  canonicalizeEmail,
  digestsMatch,
  isWellFormedDisplayName,
  isWellFormedEmail,
  isWellFormedGuestIdentity,
  newAccountId,
  newSessionId,
  newToken,
  oneTimeTokenDigest,
  tokenDigest,
} from "./ids.js";
import { hashPassword, performDummyVerification, validatePassword, verifyPassword } from "./passwords.js";

const VERIFICATION_TABLE = "email_verification_tokens";
const RECOVERY_TABLE = "password_recovery_tokens";
const VERIFICATION_PURPOSE = "email-verification-v1";
const RECOVERY_PURPOSE = "password-recovery-v1";
const MAX_DEVICE_LABEL_LENGTH = 80;
const TOKEN_RETENTION_MILLIS = 30 * 24 * 60 * 60 * 1000;
const DUMMY_TOKEN_DIGEST = Buffer.alloc(32).toString("base64url");

function toPublicAccount(row) {
  return {
    userId: row.user_id,
    email: row.email,
    displayName: row.display_name,
    status: row.status,
    emailVerified: row.email_verified_at !== null,
    emailVerifiedAt: row.email_verified_at,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

function toPublicSession(row, tokens) {
  return {
    sessionId: row.session_id,
    issuedAt: row.issued_at,
    accessExpiresAt: row.access_expires_at,
    refreshExpiresAt: row.refresh_expires_at,
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
  };
}

function nowIso() { return new Date().toISOString(); }
function isoFromNow(seconds) { return new Date(Date.now() + seconds * 1000).toISOString(); }
function isPast(isoTimestamp) { return Date.parse(isoTimestamp) <= Date.now(); }

function requireString(body, field) {
  const value = body?.[field];
  if (typeof value !== "string") throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  return value;
}

function safeDeviceLabel(value) {
  if (value === undefined || value === null) return "CraftMind device";
  if (typeof value !== "string") throw new AccountApiError(ErrorCode.INVALID_DEVICE_LABEL);
  const label = value.trim();
  if (!label || label.length > MAX_DEVICE_LABEL_LENGTH || /[\u0000-\u001f\u007f]/.test(label)) {
    throw new AccountApiError(ErrorCode.INVALID_DEVICE_LABEL);
  }
  return label;
}

function runTransaction(database, operation) {
  database.exec("BEGIN IMMEDIATE");
  try {
    const result = operation();
    database.exec("COMMIT");
    return result;
  } catch (error) {
    database.exec("ROLLBACK");
    throw error;
  }
}

// ---------------------------------------------------------------------------------------------------- guest identity

export function recordGuestIdentity(database, guestIdentityId) {
  if (!isWellFormedGuestIdentity(guestIdentityId)) throw new AccountApiError(ErrorCode.INVALID_GUEST_IDENTITY);
  const timestamp = nowIso();
  const existing = database.prepare(
    "SELECT guest_identity_id, created_at, linked_user_id FROM guest_identities WHERE guest_identity_id = ?",
  ).get(guestIdentityId);
  if (existing) {
    database.prepare("UPDATE guest_identities SET last_seen_at = ? WHERE guest_identity_id = ?").run(timestamp, guestIdentityId);
    return { guestIdentityId: existing.guest_identity_id, createdAt: existing.created_at, linked: existing.linked_user_id !== null };
  }
  database.prepare(
    "INSERT INTO guest_identities (guest_identity_id, created_at, last_seen_at, linked_user_id, linked_at) VALUES (?, ?, ?, NULL, NULL)",
  ).run(guestIdentityId, timestamp, timestamp);
  return { guestIdentityId, createdAt: timestamp, linked: false };
}

function linkGuestIdentity(database, guestIdentityId, userId) {
  if (guestIdentityId === undefined || guestIdentityId === null) return false;
  if (!isWellFormedGuestIdentity(guestIdentityId)) throw new AccountApiError(ErrorCode.INVALID_GUEST_IDENTITY);
  const existing = database.prepare(
    "SELECT linked_user_id FROM guest_identities WHERE guest_identity_id = ?",
  ).get(guestIdentityId);
  if (existing?.linked_user_id !== null && existing?.linked_user_id !== undefined) {
    throw new AccountApiError(ErrorCode.GUEST_IDENTITY_ALREADY_LINKED);
  }
  const timestamp = nowIso();
  if (existing) {
    database.prepare(
      "UPDATE guest_identities SET linked_user_id = ?, linked_at = ?, last_seen_at = ? WHERE guest_identity_id = ?",
    ).run(userId, timestamp, timestamp, guestIdentityId);
  } else {
    database.prepare(
      "INSERT INTO guest_identities (guest_identity_id, created_at, last_seen_at, linked_user_id, linked_at) VALUES (?, ?, ?, ?, ?)",
    ).run(guestIdentityId, timestamp, timestamp, userId, timestamp);
  }
  return true;
}

// ----------------------------------------------------------------------------------------------------------- tokens

function issueOneTimeToken(database, configuration, { table, purpose, userId, lifetimeSeconds }) {
  const now = nowIso();
  // Only these closed internal table names are ever interpolated into SQL.
  if (![VERIFICATION_TABLE, RECOVERY_TABLE].includes(table)) throw new Error("unsupported one-time token table");
  database.prepare(`UPDATE ${table} SET consumed_at = ? WHERE user_id = ? AND consumed_at IS NULL`).run(now, userId);
  database.prepare(`DELETE FROM ${table} WHERE user_id = ? AND expires_at <= ?`).run(userId, now);
  const token = newToken();
  const expiresAt = isoFromNow(lifetimeSeconds);
  database.prepare(
    `INSERT INTO ${table} (token_digest, user_id, created_at, expires_at, consumed_at) VALUES (?, ?, ?, ?, NULL)`,
  ).run(oneTimeTokenDigest(configuration.authSecret, purpose, token), userId, now, expiresAt);
  return { email: null, token, expiresAt, userId };
}

function findOneTimeToken(database, configuration, table, purpose, token, invalidCode) {
  if (typeof token !== "string" || token.length < 32 || token.length > 128) throw new AccountApiError(invalidCode);
  const digest = oneTimeTokenDigest(configuration.authSecret, purpose, token);
  const row = database.prepare(`SELECT * FROM ${table} WHERE token_digest = ?`).get(digest);
  const matches = digestsMatch(row?.token_digest ?? DUMMY_TOKEN_DIGEST, digest);
  if (!row || !matches) throw new AccountApiError(invalidCode);
  return { row, digest };
}

// ------------------------------------------------------------------------------------------------------------- sessions

function issueSession(database, configuration, userId, guestIdentityId = null, deviceLabel = "CraftMind device") {
  const sessionId = newSessionId();
  const accessToken = newToken();
  const refreshToken = newToken();
  const issuedAt = nowIso();
  database.prepare(
    `INSERT INTO sessions
       (session_id, user_id, access_digest, refresh_digest, issued_at, access_expires_at, refresh_expires_at,
        revoked_at, guest_identity_id, last_used_at, device_label)
     VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)`,
  ).run(
    sessionId,
    userId,
    tokenDigest(configuration.authSecret, accessToken),
    tokenDigest(configuration.authSecret, refreshToken),
    issuedAt,
    isoFromNow(configuration.accessTokenTtlSeconds),
    isoFromNow(configuration.refreshTokenTtlSeconds),
    guestIdentityId,
    issuedAt,
    deviceLabel,
  );
  return { accessToken, refreshToken, sessionId };
}

function loadLiveSession(database, configuration, { accessToken, refreshToken }) {
  const column = accessToken !== undefined ? "access_digest" : "refresh_digest";
  const token = accessToken !== undefined ? accessToken : refreshToken;
  if (typeof token !== "string" || token.length === 0) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
  const digest = tokenDigest(configuration.authSecret, token);
  const row = database.prepare(`SELECT * FROM sessions WHERE ${column} = ?`).get(digest);
  if (!row || !digestsMatch(row[column], digest) || row.revoked_at !== null) throw new AccountApiError(ErrorCode.SESSION_INVALID);
  const expiresAt = accessToken !== undefined ? row.access_expires_at : row.refresh_expires_at;
  if (isPast(expiresAt)) throw new AccountApiError(ErrorCode.SESSION_EXPIRED);
  const user = database.prepare("SELECT * FROM users WHERE user_id = ?").get(row.user_id);
  if (!user) throw new AccountApiError(ErrorCode.SESSION_INVALID);
  if (user.status === ACCOUNT_STATUS.DELETED) throw new AccountApiError(ErrorCode.ACCOUNT_DELETED);
  if (user.status === ACCOUNT_STATUS.SUSPENDED) {
    revokeSession(database, row.session_id);
    throw new AccountApiError(ErrorCode.ACCOUNT_SUSPENDED);
  }
  if (user.email_verified_at === null) throw new AccountApiError(ErrorCode.EMAIL_NOT_VERIFIED);
  const lastUsedAt = nowIso();
  database.prepare("UPDATE sessions SET last_used_at = ? WHERE session_id = ? AND revoked_at IS NULL").run(lastUsedAt, row.session_id);
  row.last_used_at = lastUsedAt;
  return { row, user };
}

function revokeSession(database, sessionId) {
  database.prepare("UPDATE sessions SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL").run(nowIso(), sessionId);
}

function revokeAllSessionsForUser(database, userId, exceptSessionId = null) {
  const result = exceptSessionId === null
    ? database.prepare("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL").run(nowIso(), userId)
    : database.prepare("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND session_id <> ? AND revoked_at IS NULL")
      .run(nowIso(), userId, exceptSessionId);
  return Number(result.changes ?? 0);
}

/**
 * Bounded database hygiene. Expired session credentials are removed; consumed/expired one-time rows are retained for
 * 30 days so callers can receive stable USED/EXPIRED errors, then removed. The operation is idempotent and safe at
 * service start and on a periodic timer.
 */
export function cleanupExpiredAccountRecords(database, nowMillis = Date.now()) {
  const now = new Date(nowMillis).toISOString();
  const retentionCutoff = new Date(nowMillis - TOKEN_RETENTION_MILLIS).toISOString();
  const sessions = database.prepare(
    "DELETE FROM sessions WHERE refresh_expires_at <= ? OR (revoked_at IS NOT NULL AND revoked_at <= ?)",
  ).run(now, retentionCutoff);
  const verification = database.prepare(
    "DELETE FROM email_verification_tokens WHERE expires_at <= ?",
  ).run(retentionCutoff);
  const recovery = database.prepare(
    "DELETE FROM password_recovery_tokens WHERE expires_at <= ?",
  ).run(retentionCutoff);
  return {
    sessions: Number(sessions.changes ?? 0),
    verificationTokens: Number(verification.changes ?? 0),
    recoveryTokens: Number(recovery.changes ?? 0),
  };
}

// ------------------------------------------------------------------------------------------------------------ registration

/** Creates an unverified account and its first email-verification challenge in one write transaction. */
export async function registerAccount(database, configuration, body) {
  const email = requireString(body, "email").trim();
  const password = requireString(body, "password");
  const guestIdentityId = body.guestIdentityId ?? null;
  if (guestIdentityId !== null && !isWellFormedGuestIdentity(guestIdentityId)) throw new AccountApiError(ErrorCode.INVALID_GUEST_IDENTITY);
  if (!isWellFormedEmail(email)) throw new AccountApiError(ErrorCode.INVALID_EMAIL);
  if (!validatePassword(password).valid) throw new AccountApiError(ErrorCode.INVALID_PASSWORD);
  if (!isWellFormedDisplayName(body?.displayName)) throw new AccountApiError(ErrorCode.INVALID_DISPLAY_NAME);
  const canonicalEmail = canonicalizeEmail(email);
  if (database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(canonicalEmail)) {
    throw new AccountApiError(ErrorCode.ACCOUNT_ALREADY_EXISTS);
  }
  const passwordHash = await hashPassword(password);
  const userId = newAccountId();
  const timestamp = nowIso();
  const displayName = body.displayName.trim();
  const emailRecord = email.trim();
  let created;
  runTransaction(database, () => {
    if (database.prepare("SELECT user_id FROM users WHERE email_canonical = ?").get(canonicalEmail)) {
      throw new AccountApiError(ErrorCode.ACCOUNT_ALREADY_EXISTS);
    }
    database.prepare(
      `INSERT INTO users (user_id, email, email_canonical, display_name, password_hash, status, created_at, updated_at, email_verified_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)`,
    ).run(userId, emailRecord, canonicalEmail, displayName, passwordHash, ACCOUNT_STATUS.ACTIVE, timestamp, timestamp);
    const guestLinked = linkGuestIdentity(database, guestIdentityId, userId);
    const challenge = issueOneTimeToken(database, configuration, {
      table: VERIFICATION_TABLE,
      purpose: VERIFICATION_PURPOSE,
      userId,
      lifetimeSeconds: configuration.verificationTokenTtlSeconds,
    });
    challenge.email = emailRecord;
    created = { account: toPublicAccount(database.prepare("SELECT * FROM users WHERE user_id = ?").get(userId)), guestLinked, challenge };
  });
  return created;
}

export async function login(database, configuration, body) {
  const email = requireString(body, "email").trim();
  const password = requireString(body, "password");
  const guestIdentityId = body.guestIdentityId ?? null;
  const deviceLabel = safeDeviceLabel(body.deviceLabel);
  if (guestIdentityId !== null && !isWellFormedGuestIdentity(guestIdentityId)) throw new AccountApiError(ErrorCode.INVALID_GUEST_IDENTITY);
  if (!isWellFormedEmail(email)) throw new AccountApiError(ErrorCode.INVALID_EMAIL);
  if (password.length === 0) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const user = database.prepare("SELECT * FROM users WHERE email_canonical = ?").get(canonicalizeEmail(email));
  if (!user || user.status === ACCOUNT_STATUS.DELETED) {
    await performDummyVerification(password);
    throw new AccountApiError(ErrorCode.INVALID_CREDENTIALS);
  }
  if (!(await verifyPassword(password, user.password_hash))) throw new AccountApiError(ErrorCode.INVALID_CREDENTIALS);
  if (user.status === ACCOUNT_STATUS.SUSPENDED) throw new AccountApiError(ErrorCode.ACCOUNT_SUSPENDED);
  if (user.email_verified_at === null) throw new AccountApiError(ErrorCode.EMAIL_NOT_VERIFIED);

  let tokens;
  runTransaction(database, () => {
    // Recheck under the session-creation write lock: an administrator may suspend the account while scrypt verification
    // is pending. Either login commits first and suspension revokes its session, or suspension commits first and login
    // observes the new status; a suspended account must never receive a successful new session.
    const latestUser = database.prepare("SELECT * FROM users WHERE user_id = ?").get(user.user_id);
    if (!latestUser || latestUser.status === ACCOUNT_STATUS.DELETED) throw new AccountApiError(ErrorCode.INVALID_CREDENTIALS);
    if (latestUser.status === ACCOUNT_STATUS.SUSPENDED) throw new AccountApiError(ErrorCode.ACCOUNT_SUSPENDED);
    if (latestUser.password_hash !== user.password_hash) throw new AccountApiError(ErrorCode.INVALID_CREDENTIALS);
    if (latestUser.email_verified_at === null) throw new AccountApiError(ErrorCode.EMAIL_NOT_VERIFIED);
    try {
      linkGuestIdentity(database, guestIdentityId, latestUser.user_id);
    } catch (error) {
      if (!(error instanceof AccountApiError) || error.code !== ErrorCode.GUEST_IDENTITY_ALREADY_LINKED) throw error;
    }
    tokens = issueSession(database, configuration, latestUser.user_id, guestIdentityId, deviceLabel);
  });
  const sessionRow = database.prepare("SELECT * FROM sessions WHERE session_id = ?").get(tokens.sessionId);
  return { account: toPublicAccount(user), session: toPublicSession(sessionRow, tokens) };
}

// --------------------------------------------------------------------------------------------------- email verification

/** Returns a private delivery challenge or null; the HTTP layer never serializes the token. */
export function resendVerificationChallenge(database, configuration, emailValue) {
  const email = requireString({ email: emailValue }, "email").trim();
  if (!isWellFormedEmail(email)) throw new AccountApiError(ErrorCode.INVALID_EMAIL);
  const user = database.prepare("SELECT * FROM users WHERE email_canonical = ?").get(canonicalizeEmail(email));
  if (!user || user.status !== ACCOUNT_STATUS.ACTIVE || user.email_verified_at !== null) return null;
  let challenge;
  runTransaction(database, () => {
    challenge = issueOneTimeToken(database, configuration, {
      table: VERIFICATION_TABLE,
      purpose: VERIFICATION_PURPOSE,
      userId: user.user_id,
      lifetimeSeconds: configuration.verificationTokenTtlSeconds,
    });
    challenge.email = user.email;
  });
  return challenge;
}

export function verifyEmail(database, configuration, body) {
  const token = requireString(body, "token");
  const { row, digest } = findOneTimeToken(
    database,
    configuration,
    VERIFICATION_TABLE,
    VERIFICATION_PURPOSE,
    token,
    ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID,
  );
  if (row.consumed_at !== null) throw new AccountApiError(ErrorCode.EMAIL_VERIFICATION_TOKEN_USED);
  if (isPast(row.expires_at)) throw new AccountApiError(ErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED);
  let account;
  runTransaction(database, () => {
    const current = database.prepare("SELECT * FROM email_verification_tokens WHERE token_digest = ?").get(digest);
    if (!current || current.consumed_at !== null) throw new AccountApiError(ErrorCode.EMAIL_VERIFICATION_TOKEN_USED);
    if (isPast(current.expires_at)) throw new AccountApiError(ErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED);
    const user = database.prepare("SELECT * FROM users WHERE user_id = ?").get(current.user_id);
    if (!user || user.status !== ACCOUNT_STATUS.ACTIVE) throw new AccountApiError(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
    const verifiedAt = user.email_verified_at ?? nowIso();
    database.prepare("UPDATE users SET email_verified_at = ?, updated_at = ? WHERE user_id = ?")
      .run(verifiedAt, verifiedAt, user.user_id);
    database.prepare("UPDATE email_verification_tokens SET consumed_at = ? WHERE user_id = ? AND consumed_at IS NULL")
      .run(verifiedAt, user.user_id);
    account = toPublicAccount(database.prepare("SELECT * FROM users WHERE user_id = ?").get(user.user_id));
  });
  return { account };
}

// --------------------------------------------------------------------------------------------------- password recovery

/** Returns a private recovery challenge or null. The public response is identical whether an account exists or not. */
export function createPasswordRecoveryChallenge(database, configuration, emailValue) {
  const email = requireString({ email: emailValue }, "email").trim();
  if (!isWellFormedEmail(email)) throw new AccountApiError(ErrorCode.INVALID_EMAIL);
  const user = database.prepare("SELECT * FROM users WHERE email_canonical = ?").get(canonicalizeEmail(email));
  if (!user || user.status !== ACCOUNT_STATUS.ACTIVE || user.email_verified_at === null) return null;
  let challenge;
  runTransaction(database, () => {
    challenge = issueOneTimeToken(database, configuration, {
      table: RECOVERY_TABLE,
      purpose: RECOVERY_PURPOSE,
      userId: user.user_id,
      lifetimeSeconds: configuration.passwordResetTokenTtlSeconds,
    });
    challenge.email = user.email;
  });
  return challenge;
}

export async function confirmPasswordRecovery(database, configuration, body) {
  const token = requireString(body, "token");
  const newPassword = requireString(body, "newPassword");
  if (!validatePassword(newPassword).valid) throw new AccountApiError(ErrorCode.INVALID_PASSWORD);
  const { row, digest } = findOneTimeToken(
    database,
    configuration,
    RECOVERY_TABLE,
    RECOVERY_PURPOSE,
    token,
    ErrorCode.PASSWORD_RESET_TOKEN_INVALID,
  );
  if (row.consumed_at !== null) throw new AccountApiError(ErrorCode.PASSWORD_RESET_TOKEN_USED);
  if (isPast(row.expires_at)) throw new AccountApiError(ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED);
  const newHash = await hashPassword(newPassword);
  let result;
  runTransaction(database, () => {
    const current = database.prepare("SELECT * FROM password_recovery_tokens WHERE token_digest = ?").get(digest);
    if (!current || current.consumed_at !== null) throw new AccountApiError(ErrorCode.PASSWORD_RESET_TOKEN_USED);
    if (isPast(current.expires_at)) throw new AccountApiError(ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED);
    const user = database.prepare("SELECT * FROM users WHERE user_id = ?").get(current.user_id);
    if (!user || user.status !== ACCOUNT_STATUS.ACTIVE || user.email_verified_at === null) {
      throw new AccountApiError(ErrorCode.PASSWORD_RESET_TOKEN_INVALID);
    }
    const changedAt = nowIso();
    database.prepare("UPDATE users SET password_hash = ?, updated_at = ? WHERE user_id = ?")
      .run(newHash, changedAt, user.user_id);
    database.prepare("UPDATE password_recovery_tokens SET consumed_at = ? WHERE token_digest = ?")
      .run(changedAt, digest);
    const revokedSessions = revokeAllSessionsForUser(database, user.user_id);
    result = { reset: true, revokedSessions };
  });
  return result;
}

// -------------------------------------------------------------------------------------------------------- password change

export async function changePassword(database, configuration, accessToken, body) {
  const currentPassword = requireString(body, "currentPassword");
  const newPassword = requireString(body, "newPassword");
  if (!validatePassword(newPassword).valid) throw new AccountApiError(ErrorCode.INVALID_PASSWORD);
  const { row: session, user } = loadLiveSession(database, configuration, { accessToken });
  if (!(await verifyPassword(currentPassword, user.password_hash))) throw new AccountApiError(ErrorCode.CURRENT_PASSWORD_INVALID);
  const newHash = await hashPassword(newPassword);
  const expectedAccessDigest = tokenDigest(configuration.authSecret, accessToken);
  let revokedSessions;
  runTransaction(database, () => {
    const latestUser = database.prepare("SELECT * FROM users WHERE user_id = ?").get(user.user_id);
    if (!latestUser || latestUser.status !== ACCOUNT_STATUS.ACTIVE) throw new AccountApiError(ErrorCode.SESSION_INVALID);
    if (latestUser.password_hash !== user.password_hash) throw new AccountApiError(ErrorCode.CURRENT_PASSWORD_INVALID);
    const latestSession = database.prepare(
      "SELECT access_digest, access_expires_at, revoked_at FROM sessions WHERE session_id = ? AND user_id = ?",
    ).get(session.session_id, user.user_id);
    if (!latestSession || latestSession.revoked_at !== null || !digestsMatch(latestSession.access_digest, expectedAccessDigest)) {
      throw new AccountApiError(ErrorCode.SESSION_INVALID);
    }
    if (isPast(latestSession.access_expires_at)) throw new AccountApiError(ErrorCode.SESSION_EXPIRED);
    const changedAt = nowIso();
    database.prepare("UPDATE users SET password_hash = ?, updated_at = ? WHERE user_id = ?")
      .run(newHash, changedAt, user.user_id);
    revokedSessions = revokeAllSessionsForUser(database, user.user_id, session.session_id);
  });
  return { changed: true, currentSessionRetained: true, revokedOtherSessions: revokedSessions };
}

// -------------------------------------------------------------------------------------------------- session management

export function listSessions(database, configuration, accessToken) {
  const { row: current, user } = loadLiveSession(database, configuration, { accessToken });
  const now = nowIso();
  const rows = database.prepare(
    `SELECT session_id, issued_at, last_used_at, refresh_expires_at, device_label
       FROM sessions
      WHERE user_id = ? AND revoked_at IS NULL AND refresh_expires_at > ?
      ORDER BY issued_at DESC`,
  ).all(user.user_id, now);
  return {
    sessions: rows.map((session) => ({
      sessionId: session.session_id,
      createdAt: session.issued_at,
      lastUsedAt: session.last_used_at,
      expiresAt: session.refresh_expires_at,
      deviceLabel: session.device_label,
      isCurrent: session.session_id === current.session_id,
    })),
  };
}

export function revokeOneSession(database, configuration, accessToken, body) {
  const requestedId = requireString(body, "sessionId");
  const { row: current, user } = loadLiveSession(database, configuration, { accessToken });
  if (requestedId === current.session_id) throw new AccountApiError(ErrorCode.CURRENT_SESSION_REVOKE_NOT_ALLOWED);
  const target = database.prepare("SELECT session_id, revoked_at FROM sessions WHERE session_id = ? AND user_id = ?")
    .get(requestedId, user.user_id);
  if (!target) throw new AccountApiError(ErrorCode.SESSION_NOT_FOUND);
  if (target.revoked_at !== null) return { revoked: false, alreadyRevoked: true };
  revokeSession(database, target.session_id);
  return { revoked: true, alreadyRevoked: false };
}

export function revokeOtherSessions(database, configuration, accessToken) {
  const { row: current, user } = loadLiveSession(database, configuration, { accessToken });
  const revokedSessions = revokeAllSessionsForUser(database, user.user_id, current.session_id);
  return { revokedSessions, currentSessionRetained: true };
}

// ---------------------------------------------------------------------------------------------------------- existing operations

export function refreshSession(database, configuration, body) {
  const refreshToken = requireString(body, "refreshToken");
  let row;
  try {
    ({ row } = loadLiveSession(database, configuration, { refreshToken }));
  } catch (error) {
    if (error instanceof AccountApiError && error.code === ErrorCode.SESSION_INVALID) {
      throw new AccountApiError(ErrorCode.REFRESH_FAILED);
    }
    throw error;
  }
  const accessToken = newToken();
  const nextRefreshToken = newToken();
  const accessExpiresAt = isoFromNow(configuration.accessTokenTtlSeconds);
  const refreshExpiresAt = isoFromNow(configuration.refreshTokenTtlSeconds);
  const presentedRefreshDigest = tokenDigest(configuration.authSecret, refreshToken);
  const rotatedAt = nowIso();
  const result = database.prepare(
    `UPDATE sessions SET access_digest = ?, refresh_digest = ?, access_expires_at = ?, refresh_expires_at = ?, last_used_at = ?
     WHERE session_id = ? AND refresh_digest = ? AND revoked_at IS NULL AND refresh_expires_at > ?`,
  ).run(
    tokenDigest(configuration.authSecret, accessToken),
    tokenDigest(configuration.authSecret, nextRefreshToken),
    accessExpiresAt,
    refreshExpiresAt,
    rotatedAt,
    row.session_id,
    presentedRefreshDigest,
    rotatedAt,
  );
  if (Number(result.changes ?? 0) === 0) throw new AccountApiError(ErrorCode.REFRESH_FAILED);
  const updated = database.prepare("SELECT * FROM sessions WHERE session_id = ?").get(row.session_id);
  return { session: {
    sessionId: updated.session_id,
    issuedAt: updated.issued_at,
    accessExpiresAt: updated.access_expires_at,
    refreshExpiresAt: updated.refresh_expires_at,
    accessToken,
    refreshToken: nextRefreshToken,
  } };
}

export function logout(database, configuration, body) {
  const accessToken = typeof body?.accessToken === "string" ? body.accessToken : undefined;
  const refreshToken = typeof body?.refreshToken === "string" ? body.refreshToken : undefined;
  if (accessToken === undefined && refreshToken === undefined) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const digest = tokenDigest(configuration.authSecret, accessToken ?? refreshToken);
  const column = accessToken !== undefined ? "access_digest" : "refresh_digest";
  const row = database.prepare(`SELECT * FROM sessions WHERE ${column} = ?`).get(digest);
  if (!row) return { revoked: false, alreadyRevoked: false, revokedSessions: 0 };
  const alreadyRevoked = row.revoked_at !== null;
  revokeSession(database, row.session_id);
  return { revoked: true, alreadyRevoked, revokedSessions: alreadyRevoked ? 0 : 1 };
}

export function currentAccount(database, configuration, accessToken) {
  const { user, row } = loadLiveSession(database, configuration, { accessToken });
  return {
    account: toPublicAccount(user),
    session: {
      sessionId: row.session_id,
      issuedAt: row.issued_at,
      lastUsedAt: row.last_used_at,
      accessExpiresAt: row.access_expires_at,
      refreshExpiresAt: row.refresh_expires_at,
      deviceLabel: row.device_label,
    },
  };
}

export { issueSession, revokeAllSessionsForUser, toPublicAccount };
