/**
 * Shared text, slug, and reference validation (Phase 23).
 *
 * Creator profiles and server workspaces accept the same *kind* of input — a short display name, a bounded description,
 * a slug, an optional `https` reference — so the rules live in one place instead of being re-written per resource. That
 * matters for security: a second copy of a validator is a second chance to forget the control-character rule.
 *
 * The rules are deliberately conservative and explainable:
 *
 *   * **No HTML.** Angle brackets are refused rather than escaped, so a stored value can never be markup even if a
 *     future renderer forgets to escape it.
 *   * **No control characters**, with one exception: a description may contain newlines. Unicode line separators and
 *     bidirectional overrides are refused — legitimate typography is not worth a display-name spoofing primitive.
 *   * **No silent mangling.** A slug with characters outside the accepted alphabet is refused, not stripped: a creator
 *     must never end up owning a handle they did not ask for.
 *   * **References are `https` URLs**, with a host and no embedded credentials. `data:`, `javascript:`, `file:`, and
 *     protocol-relative values cannot pass a scheme check that only accepts `https:`.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { HANDLE_RULES, isReservedHandle, normalizeHandle } from "./creator-catalog.js";

export const FORBIDDEN_TEXT = /[<>]/;
export const CONTROL_CHARACTERS = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/;
export const DECEPTIVE_CHARACTERS = /[\u2028\u2029\u202a-\u202e\u2066-\u2069]/;

export function normalizeWhitespace(value, { allowNewlines = false } = {}) {
  const collapsed = allowNewlines
    ? value.replace(/\r\n?/g, "\n").replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n")
    : value.replace(/\s+/g, " ");
  return collapsed.trim();
}

export function isSafeText(value, { allowNewlines = false } = {}) {
  const pattern = allowNewlines ? /\u2028|\u2029|[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/ : CONTROL_CHARACTERS;
  return !FORBIDDEN_TEXT.test(value) && !pattern.test(value) && !DECEPTIVE_CHARACTERS.test(value);
}

/**
 * The deceptive-character check runs against the **raw input**, before whitespace normalization. Folding first would
 * quietly satisfy the rule by deleting the character, which would make the stored value safe but the *rule* untrue —
 * and a rule that is only true because something else already happened is a rule nobody can rely on.
 */
function assertNoDeceptiveCharacters(value) {
  if (DECEPTIVE_CHARACTERS.test(value)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
}

/**
 * Validates one bounded text field, returning the normalized value. `allowEmpty` distinguishes an optional field
 * (`""` is meaningful) from a required one.
 */
export function validateBoundedText(value, { minimum, maximum, allowNewlines = false, allowEmpty = false } = {}) {
  if (typeof value !== "string") throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  assertNoDeceptiveCharacters(value);
  const text = normalizeWhitespace(value, { allowNewlines });
  if (text.length > 0 && text.length < minimum) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  if (text.length === 0 && !allowEmpty && minimum > 0) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  if (text.length > maximum) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  if (text.length > 0 && !isSafeText(text, { allowNewlines })) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  return text;
}

/** An optional `https` reference: bounded, host-bearing, credential-free, and free of quotes or whitespace. */
export function validateSafeReference(value, { maximum }) {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value !== "string") throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  assertNoDeceptiveCharacters(value);
  const reference = value.trim();
  if (reference.length === 0 || reference.length > maximum) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  if (CONTROL_CHARACTERS.test(reference) || /[<>"'\s]/.test(reference)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  let parsed;
  try {
    parsed = new URL(reference);
  } catch {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  return parsed.toString();
}

/**
 * Normalizes a slug/handle and throws the resource's own typed refusals: a reserved value gets `reservedCode` (a
 * distinguishable, non-secret fact about the *request*), and everything else is an invalid-request failure.
 */
export function validateSlugInput(value, { reservedCode, alsoReserved = () => false } = {}) {
  const normalized = normalizeHandle(value);
  if (normalized.ok) {
    if (alsoReserved(normalized.handle)) throw new AccountApiError(reservedCode);
    return normalized.handle;
  }
  if (normalized.code === "RESERVED") throw new AccountApiError(reservedCode);
  throw new AccountApiError(ErrorCode.INVALID_REQUEST);
}

export { HANDLE_RULES, isReservedHandle, normalizeHandle };
