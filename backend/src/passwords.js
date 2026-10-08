/**
 * Password hashing and verification.
 *
 * Passwords are hashed with scrypt (a memory-hard KDF from Node's own crypto module) using a per-account random salt.
 * The plaintext password exists only for the duration of the request that carried it: it is never written to the
 * database, never logged, never placed in an error, and never returned in a response.
 *
 * The stored format is `scrypt$N$r$p$salt$hash`, so the parameters travel with the hash and can be raised later
 * without invalidating existing accounts.
 */

import { randomBytes, scrypt, timingSafeEqual } from "node:crypto";

const PARAMETERS = Object.freeze({ N: 16384, r: 8, p: 1, keyLength: 64, saltBytes: 16 });

export const PASSWORD_MINIMUM_LENGTH = 10;
export const PASSWORD_MAXIMUM_LENGTH = 256;

/**
 * CraftMind's password rule, stated once so the client and the service agree.
 *
 * Deliberately conservative: a length floor, a ceiling that keeps hashing bounded, and a requirement for more than one
 * character class. No composition maze, no expiry, no "must contain a symbol from this list" theatre.
 */
export function validatePassword(password) {
  if (typeof password !== "string") return { valid: false, reason: "INVALID_PASSWORD" };
  if (password.length < PASSWORD_MINIMUM_LENGTH) return { valid: false, reason: "INVALID_PASSWORD" };
  if (password.length > PASSWORD_MAXIMUM_LENGTH) return { valid: false, reason: "INVALID_PASSWORD" };
  if (password.trim().length === 0) return { valid: false, reason: "INVALID_PASSWORD" };
  const classes = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^A-Za-z0-9]/].filter((pattern) => pattern.test(password)).length;
  if (classes < 2) return { valid: false, reason: "INVALID_PASSWORD" };
  return { valid: true };
}

function derive(password, salt, parameters) {
  return new Promise((resolve, reject) => {
    scrypt(
      password,
      salt,
      parameters.keyLength,
      { N: parameters.N, r: parameters.r, p: parameters.p, maxmem: 128 * parameters.N * parameters.r * 2 },
      (error, derived) => (error ? reject(error) : resolve(derived)),
    );
  });
}

/** Hashes a validated password. The caller must have validated it first. */
export async function hashPassword(password) {
  const salt = randomBytes(PARAMETERS.saltBytes);
  const derived = await derive(password, salt, PARAMETERS);
  return [
    "scrypt",
    PARAMETERS.N,
    PARAMETERS.r,
    PARAMETERS.p,
    salt.toString("base64url"),
    derived.toString("base64url"),
  ].join("$");
}

/**
 * Verifies a password against a stored hash, in constant time with respect to the hash bytes.
 *
 * Returns false for a malformed stored hash rather than throwing, so a corrupted row cannot turn into a 500 that
 * distinguishes itself from a wrong password.
 */
export async function verifyPassword(password, storedHash) {
  if (typeof password !== "string" || typeof storedHash !== "string") return false;
  const parts = storedHash.split("$");
  if (parts.length !== 6 || parts[0] !== "scrypt") return false;
  const [, rawN, rawR, rawP, saltPart, hashPart] = parts;
  const parameters = {
    N: Number.parseInt(rawN, 10),
    r: Number.parseInt(rawR, 10),
    p: Number.parseInt(rawP, 10),
    keyLength: PARAMETERS.keyLength,
  };
  if (!Number.isFinite(parameters.N) || !Number.isFinite(parameters.r) || !Number.isFinite(parameters.p)) return false;
  let expected;
  let salt;
  try {
    expected = Buffer.from(hashPart, "base64url");
    salt = Buffer.from(saltPart, "base64url");
  } catch {
    return false;
  }
  if (expected.length === 0 || salt.length === 0) return false;
  let derived;
  try {
    derived = await derive(password, salt, parameters);
  } catch {
    return false;
  }
  if (derived.length !== expected.length) return false;
  return timingSafeEqual(derived, expected);
}

/**
 * A verification that always fails, used when no account matches the submitted email address.
 *
 * It performs the same work as a real verification so that "unknown email" and "wrong password" take comparable time
 * and cannot be told apart by timing.
 */
export async function performDummyVerification(password) {
  const salt = Buffer.alloc(PARAMETERS.saltBytes, 7);
  await derive(typeof password === "string" ? password : "", salt, PARAMETERS);
  return false;
}

export { PARAMETERS };
