/**
 * Typed, stable error codes for the account API.
 *
 * The Android client maps these codes to its own typed failures, so the strings are part of the public contract and
 * must not change casually. Messages are safe for a client to display: they never contain a token, a password, a hash,
 * a digest, a SQL fragment, or a stack trace.
 */

export const ErrorCode = Object.freeze({
  // Request shape
  MALFORMED_REQUEST: "MALFORMED_REQUEST",
  REQUEST_TOO_LARGE: "REQUEST_TOO_LARGE",
  METHOD_NOT_ALLOWED: "METHOD_NOT_ALLOWED",

  // Validation
  INVALID_EMAIL: "INVALID_EMAIL",
  INVALID_DISPLAY_NAME: "INVALID_DISPLAY_NAME",
  INVALID_PASSWORD: "INVALID_PASSWORD",

  // Account lifecycle
  ACCOUNT_ALREADY_EXISTS: "ACCOUNT_ALREADY_EXISTS",
  ACCOUNT_NOT_FOUND: "ACCOUNT_NOT_FOUND",
  INVALID_CREDENTIALS: "INVALID_CREDENTIALS",
  ACCOUNT_SUSPENDED: "ACCOUNT_SUSPENDED",

  // Sessions
  SESSION_EXPIRED: "SESSION_EXPIRED",
  SESSION_NOT_FOUND: "SESSION_NOT_FOUND",
  AUTHENTICATION_REQUIRED: "AUTHENTICATION_REQUIRED",

  // Guest identity
  INVALID_GUEST_IDENTITY: "INVALID_GUEST_IDENTITY",
  GUEST_IDENTITY_ALREADY_LINKED: "GUEST_IDENTITY_ALREADY_LINKED",

  // Deliberately not implemented, and said so rather than faked
  PASSWORD_RESET_NOT_IMPLEMENTED: "PASSWORD_RESET_NOT_IMPLEMENTED",

  // Service
  BACKEND_UNAVAILABLE: "BACKEND_UNAVAILABLE",
  UNKNOWN_ERROR: "UNKNOWN_ERROR",
});

/** HTTP status per code. */
const STATUS_BY_CODE = Object.freeze({
  [ErrorCode.MALFORMED_REQUEST]: 400,
  [ErrorCode.REQUEST_TOO_LARGE]: 413,
  [ErrorCode.METHOD_NOT_ALLOWED]: 405,
  [ErrorCode.INVALID_EMAIL]: 400,
  [ErrorCode.INVALID_DISPLAY_NAME]: 400,
  [ErrorCode.INVALID_PASSWORD]: 400,
  [ErrorCode.ACCOUNT_ALREADY_EXISTS]: 409,
  [ErrorCode.ACCOUNT_NOT_FOUND]: 404,
  [ErrorCode.INVALID_CREDENTIALS]: 401,
  [ErrorCode.ACCOUNT_SUSPENDED]: 403,
  [ErrorCode.SESSION_EXPIRED]: 401,
  [ErrorCode.SESSION_NOT_FOUND]: 401,
  [ErrorCode.AUTHENTICATION_REQUIRED]: 401,
  [ErrorCode.INVALID_GUEST_IDENTITY]: 400,
  [ErrorCode.GUEST_IDENTITY_ALREADY_LINKED]: 409,
  [ErrorCode.PASSWORD_RESET_NOT_IMPLEMENTED]: 501,
  [ErrorCode.BACKEND_UNAVAILABLE]: 503,
  [ErrorCode.UNKNOWN_ERROR]: 500,
});

/** A failure that is safe to return to a client. Never carries credentials or internal detail. */
export class AccountApiError extends Error {
  constructor(code, message, { status } = {}) {
    super(message ?? defaultMessage(code));
    this.name = "AccountApiError";
    this.code = code;
    this.status = status ?? STATUS_BY_CODE[code] ?? 400;
  }
}

function defaultMessage(code) {
  switch (code) {
    case ErrorCode.MALFORMED_REQUEST:
      return "The request body is not valid JSON matching the contract.";
    case ErrorCode.REQUEST_TOO_LARGE:
      return "The request body is larger than this service accepts.";
    case ErrorCode.METHOD_NOT_ALLOWED:
      return "That method is not supported for this endpoint.";
    case ErrorCode.INVALID_EMAIL:
      return "That email address is not in a usable form.";
    case ErrorCode.INVALID_DISPLAY_NAME:
      return "That display name is not usable.";
    case ErrorCode.INVALID_PASSWORD:
      return "That password does not meet CraftMind's password requirements.";
    case ErrorCode.ACCOUNT_ALREADY_EXISTS:
      return "An account already exists for that email address.";
    case ErrorCode.ACCOUNT_NOT_FOUND:
      return "No account matches that request.";
    case ErrorCode.INVALID_CREDENTIALS:
      return "The email address and password combination was not accepted.";
    case ErrorCode.ACCOUNT_SUSPENDED:
      return "This account is suspended and cannot sign in.";
    case ErrorCode.SESSION_EXPIRED:
      return "The session has expired. Sign in again.";
    case ErrorCode.SESSION_NOT_FOUND:
      return "The session is no longer valid. Sign in again.";
    case ErrorCode.AUTHENTICATION_REQUIRED:
      return "This endpoint requires a valid session.";
    case ErrorCode.INVALID_GUEST_IDENTITY:
      return "That guest identity is not in the expected form.";
    case ErrorCode.GUEST_IDENTITY_ALREADY_LINKED:
      return "That guest identity is already linked to an account.";
    case ErrorCode.PASSWORD_RESET_NOT_IMPLEMENTED:
      return "Password reset is not implemented by this service yet.";
    case ErrorCode.BACKEND_UNAVAILABLE:
      return "The account service cannot serve this request right now.";
    default:
      return "The account service could not complete this request.";
  }
}

export { STATUS_BY_CODE };
