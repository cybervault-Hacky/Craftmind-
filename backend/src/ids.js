/**
 * Identifier, token, and email handling.
 *
 * Two rules shape this file:
 *
 * 1. **Tokens are never stored.** User session tokens are 32 random bytes handed to the client once; the database
 *    keeps only `HMAC-SHA256(AUTH_SECRET, token)`. Developer sessions and one-time challenges use a separate
 *    purpose-prefixed HMAC namespace. A stolen database copy therefore cannot be replayed as a bearer credential,
 *    and a stolen `AUTH_SECRET` alone is not a token either.
 * 2. **Identifiers are opaque.** Account and session identifiers are random, not derived from an email address or an
 *    incrementing counter, so they leak nothing about the account and cannot be guessed.
 */

import { createHmac, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";

const TOKEN_BYTES = 32;
const GUEST_IDENTITY_PATTERN = /^[A-Za-z0-9_-]{22,64}$/;

export function newAccountId() {
  return `usr_${randomUUID()}`;
}

export function newSessionId() {
  return `ses_${randomUUID()}`;
}

export function newDeveloperId() {
  return `dvl_${randomUUID()}`;
}

export function newDeveloperSessionId() {
  return `dvs_${randomUUID()}`;
}

export function newAuditId() {
  return `aud_${randomUUID()}`;
}

export function newGrantId() {
  return `grt_${randomUUID()}`;
}

export function newSecurityEventId() {
  return `sev_${randomUUID()}`;
}

export function newSecurityIncidentId() {
  return `inc_${randomUUID()}`;
}

export function newSecurityActionId() {
  return `act_${randomUUID()}`;
}

export function newSecurityNotificationId() {
  return `ntf_${randomUUID()}`;
}

export function newSecurityProtectionId() {
  return `prt_${randomUUID()}`;
}

export function newMembershipId() {
  return `mbr_${randomUUID()}`;
}

export function newMembershipTransitionId() {
  return `mtr_${randomUUID()}`;
}

export function newCreditTransactionId() {
  return `crd_${randomUUID()}`;
}

export function newCreatorId() {
  return `crt_${randomUUID()}`;
}

export function newCreatorHistoryId() {
  return `cth_${randomUUID()}`;
}

export function newServerId() {
  return `srv_${randomUUID()}`;
}

export function newServerMemberId() {
  return `svm_${randomUUID()}`;
}

export function newListingId() {
  return `lst_${randomUUID()}`;
}

export function newJobId() {
  return `job_${randomUUID()}`;
}

export function newProposalId() {
  return `prp_${randomUUID()}`;
}

/**
 * Opaque, non-reversible correlation handle for a client source. The security system must correlate and throttle an
 * abusive origin without persisting IP addresses or device identifiers, so only this HMAC digest is ever stored.
 */
export function securitySourceDigest(authSecret, address) {
  return createHmac("sha256", authSecret)
    .update("craftmind:security-source-v1:", "utf8")
    .update(String(address ?? "unknown"), "utf8")
    .digest("hex")
    .slice(0, 40);
}

/**
 * Opaque account correlation handle used when a failed authentication names an account that does not (or may not)
 * exist. Security detection must be able to count repeated attempts against the same address without storing the
 * address itself, so only this keyed digest is ever written.
 */
export function securityAccountDigest(authSecret, canonicalEmail) {
  return `ref_${createHmac("sha256", authSecret)
    .update("craftmind:security-account-v1:", "utf8")
    .update(String(canonicalEmail ?? "unknown"), "utf8")
    .digest("hex")
    .slice(0, 40)}`;
}

/** Opaque incident handle shown to developers (for example `SEC-4F2C91A7`); never derived from account data. */
export function newIncidentReference() {
  return `SEC-${randomBytes(5).toString("hex").toUpperCase()}`;
}

export function newToken() {
  return randomBytes(TOKEN_BYTES).toString("base64url");
}

/** The digest stored for a token. Deterministic, so lookup is a single indexed comparison. */
export function tokenDigest(authSecret, token) {
  return createHmac("sha256", authSecret).update(token, "utf8").digest("base64url");
}

/** Dedicated, purpose-separated digest namespace for developer sessions and one-time confirmation challenges. */
export function developerTokenDigest(authSecret, purpose, token) {
  return createHmac("sha256", authSecret)
    .update(`craftmind:developer:${purpose}:`, "utf8")
    .update(token, "utf8")
    .digest("base64url");
}

/**
 * Idempotency handle for a credit operation.
 *
 * A client-supplied idempotency key is never stored as presented: the database keeps `HMAC(AUTH_SECRET, user ‖
 * operation ‖ key)`, exactly like session and one-time tokens. A stolen database copy therefore cannot be used to
 * replay a credit operation or to learn the key a client sent, and the same key from a different account or for a
 * different operation can never collide.
 */
export function creditOperationDigest(authSecret, { userId, operation, idempotencyKey }) {
  return `cred_${createHmac("sha256", authSecret)
    .update("craftmind:credit-operation-v1:", "utf8")
    .update(String(userId ?? ""), "utf8")
    .update("\u0000", "utf8")
    .update(String(operation ?? ""), "utf8")
    .update("\u0000", "utf8")
    .update(String(idempotencyKey ?? ""), "utf8")
    .digest("hex")}`;
}

/** Purpose-separated digest for email verification and password-recovery tokens. */
export function oneTimeTokenDigest(authSecret, purpose, token) {
  return createHmac("sha256", authSecret)
    .update(`craftmind:${purpose}:`, "utf8")
    .update(token, "utf8")
    .digest("base64url");
}

/** Compares two digests without leaking where they differ. */
export function digestsMatch(left, right) {
  const a = Buffer.from(String(left), "base64url");
  const b = Buffer.from(String(right), "base64url");
  if (a.length === 0 || a.length !== b.length) return false;
  return timingSafeEqual(a, b);
}

/** True when a client-supplied guest identity is in the accepted form (opaque, bounded, URL-safe). */
export function isWellFormedGuestIdentity(value) {
  return typeof value === "string" && GUEST_IDENTITY_PATTERN.test(value);
}

/** Normalises an email address for storage and uniqueness. Case and surrounding whitespace are not meaningful. */
export function canonicalizeEmail(email) {
  return String(email).trim().toLowerCase();
}

/**
 * A deliberately simple address shape check: one `@`, a non-empty local part, a dotted domain, no whitespace, and a
 * bounded length. CraftMind does not pretend this proves the mailbox exists; only a successfully consumed verification
 * challenge can mark an address verified. The local development sink sends no mail.
 */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/;

export function isWellFormedEmail(email) {
  if (typeof email !== "string") return false;
  const trimmed = email.trim();
  if (trimmed.length === 0 || trimmed.length > 254) return false;
  return EMAIL_PATTERN.test(trimmed);
}

export const DISPLAY_NAME_MINIMUM_LENGTH = 1;
export const DISPLAY_NAME_MAXIMUM_LENGTH = 80;

export function isWellFormedDisplayName(displayName) {
  if (typeof displayName !== "string") return false;
  const trimmed = displayName.trim();
  if (trimmed.length < DISPLAY_NAME_MINIMUM_LENGTH || trimmed.length > DISPLAY_NAME_MAXIMUM_LENGTH) return false;
  // Control characters would break rendering on any client, so they are rejected rather than silently stripped.
  return !/[\u0000-\u001f\u007f]/.test(trimmed);
}

export { TOKEN_BYTES, GUEST_IDENTITY_PATTERN };
