/**
 * Stable, typed errors for the account API. Messages contain no request data, credentials, SQL, or stack traces.
 */

export const ErrorCode = Object.freeze({
  // Request and transport
  INVALID_REQUEST: "INVALID_REQUEST",
  INVALID_CONTENT_TYPE: "INVALID_CONTENT_TYPE",
  REQUEST_TOO_LARGE: "REQUEST_TOO_LARGE",
  METHOD_NOT_ALLOWED: "METHOD_NOT_ALLOWED",
  CORS_ORIGIN_NOT_ALLOWED: "CORS_ORIGIN_NOT_ALLOWED",
  HTTPS_REQUIRED: "HTTPS_REQUIRED",
  RATE_LIMITED: "RATE_LIMITED",

  // Validation
  INVALID_EMAIL: "INVALID_EMAIL",
  INVALID_DISPLAY_NAME: "INVALID_DISPLAY_NAME",
  INVALID_PASSWORD: "INVALID_PASSWORD",
  INVALID_DEVICE_LABEL: "INVALID_DEVICE_LABEL",

  // Account lifecycle
  ACCOUNT_ALREADY_EXISTS: "ACCOUNT_ALREADY_EXISTS",
  ACCOUNT_NOT_FOUND: "ACCOUNT_NOT_FOUND",
  INVALID_CREDENTIALS: "INVALID_CREDENTIALS",
  ACCOUNT_SUSPENDED: "ACCOUNT_SUSPENDED",
  ACCOUNT_DELETED: "ACCOUNT_DELETED",
  EMAIL_NOT_VERIFIED: "EMAIL_NOT_VERIFIED",

  // Verification/recovery
  EMAIL_VERIFICATION_TOKEN_INVALID: "EMAIL_VERIFICATION_TOKEN_INVALID",
  EMAIL_VERIFICATION_TOKEN_EXPIRED: "EMAIL_VERIFICATION_TOKEN_EXPIRED",
  EMAIL_VERIFICATION_TOKEN_USED: "EMAIL_VERIFICATION_TOKEN_USED",
  PASSWORD_RESET_TOKEN_INVALID: "PASSWORD_RESET_TOKEN_INVALID",
  PASSWORD_RESET_TOKEN_EXPIRED: "PASSWORD_RESET_TOKEN_EXPIRED",
  PASSWORD_RESET_TOKEN_USED: "PASSWORD_RESET_TOKEN_USED",
  CURRENT_PASSWORD_INVALID: "CURRENT_PASSWORD_INVALID",
  EMAIL_DELIVERY_UNAVAILABLE: "EMAIL_DELIVERY_UNAVAILABLE",

  // Sessions
  SESSION_EXPIRED: "SESSION_EXPIRED",
  SESSION_INVALID: "SESSION_INVALID",
  REFRESH_FAILED: "REFRESH_FAILED",
  AUTHENTICATION_REQUIRED: "AUTHENTICATION_REQUIRED",
  SESSION_NOT_FOUND: "SESSION_NOT_FOUND",
  CURRENT_SESSION_REVOKE_NOT_ALLOWED: "CURRENT_SESSION_REVOKE_NOT_ALLOWED",

  // Guest identity
  INVALID_GUEST_IDENTITY: "INVALID_GUEST_IDENTITY",
  GUEST_IDENTITY_ALREADY_LINKED: "GUEST_IDENTITY_ALREADY_LINKED",

  // Legacy wire value retained so older service/client combinations can still interpret the pre-Phase-18 response.
  PASSWORD_RESET_NOT_IMPLEMENTED: "PASSWORD_RESET_NOT_IMPLEMENTED",

  // Service
  NETWORK_ERROR: "NETWORK_ERROR",
  BACKEND_UNAVAILABLE: "BACKEND_UNAVAILABLE",
  UNKNOWN_ERROR: "UNKNOWN_ERROR",
});

const STATUS_BY_CODE = Object.freeze({
  [ErrorCode.INVALID_REQUEST]: 400,
  [ErrorCode.INVALID_CONTENT_TYPE]: 415,
  [ErrorCode.REQUEST_TOO_LARGE]: 413,
  [ErrorCode.METHOD_NOT_ALLOWED]: 405,
  [ErrorCode.CORS_ORIGIN_NOT_ALLOWED]: 403,
  [ErrorCode.HTTPS_REQUIRED]: 400,
  [ErrorCode.RATE_LIMITED]: 429,
  [ErrorCode.INVALID_EMAIL]: 400,
  [ErrorCode.INVALID_DISPLAY_NAME]: 400,
  [ErrorCode.INVALID_PASSWORD]: 400,
  [ErrorCode.INVALID_DEVICE_LABEL]: 400,
  [ErrorCode.ACCOUNT_ALREADY_EXISTS]: 409,
  [ErrorCode.ACCOUNT_NOT_FOUND]: 404,
  [ErrorCode.INVALID_CREDENTIALS]: 401,
  [ErrorCode.ACCOUNT_SUSPENDED]: 403,
  [ErrorCode.ACCOUNT_DELETED]: 403,
  [ErrorCode.EMAIL_NOT_VERIFIED]: 403,
  [ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID]: 400,
  [ErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED]: 410,
  [ErrorCode.EMAIL_VERIFICATION_TOKEN_USED]: 409,
  [ErrorCode.PASSWORD_RESET_TOKEN_INVALID]: 400,
  [ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED]: 410,
  [ErrorCode.PASSWORD_RESET_TOKEN_USED]: 409,
  [ErrorCode.CURRENT_PASSWORD_INVALID]: 401,
  [ErrorCode.EMAIL_DELIVERY_UNAVAILABLE]: 503,
  [ErrorCode.SESSION_EXPIRED]: 401,
  [ErrorCode.SESSION_INVALID]: 401,
  [ErrorCode.REFRESH_FAILED]: 401,
  [ErrorCode.AUTHENTICATION_REQUIRED]: 401,
  [ErrorCode.SESSION_NOT_FOUND]: 404,
  [ErrorCode.CURRENT_SESSION_REVOKE_NOT_ALLOWED]: 409,
  [ErrorCode.INVALID_GUEST_IDENTITY]: 400,
  [ErrorCode.GUEST_IDENTITY_ALREADY_LINKED]: 409,
  [ErrorCode.PASSWORD_RESET_NOT_IMPLEMENTED]: 501,
  [ErrorCode.NETWORK_ERROR]: 503,
  [ErrorCode.BACKEND_UNAVAILABLE]: 503,
  [ErrorCode.UNKNOWN_ERROR]: 500,
});

const SAFE_MESSAGES = Object.freeze({
  [ErrorCode.INVALID_REQUEST]: "The request body is not valid JSON matching the contract.",
  [ErrorCode.INVALID_CONTENT_TYPE]: "This endpoint accepts JSON requests only.",
  [ErrorCode.REQUEST_TOO_LARGE]: "The request body is larger than this service accepts.",
  [ErrorCode.METHOD_NOT_ALLOWED]: "That method is not supported for this endpoint.",
  [ErrorCode.CORS_ORIGIN_NOT_ALLOWED]: "This origin is not allowed to call the account service.",
  [ErrorCode.HTTPS_REQUIRED]: "The account service requires a secure transport.",
  [ErrorCode.RATE_LIMITED]: "Too many attempts. Wait before trying again.",
  [ErrorCode.INVALID_EMAIL]: "That email address is not in a usable form.",
  [ErrorCode.INVALID_DISPLAY_NAME]: "That display name is not usable.",
  [ErrorCode.INVALID_PASSWORD]: "That password does not meet CraftMind's password requirements.",
  [ErrorCode.INVALID_DEVICE_LABEL]: "That device label is not usable.",
  [ErrorCode.ACCOUNT_ALREADY_EXISTS]: "An account already exists for that email address.",
  [ErrorCode.ACCOUNT_NOT_FOUND]: "No account matches that request.",
  [ErrorCode.INVALID_CREDENTIALS]: "The email address and password combination was not accepted.",
  [ErrorCode.ACCOUNT_SUSPENDED]: "This account is suspended and cannot sign in.",
  [ErrorCode.ACCOUNT_DELETED]: "This account is no longer available.",
  [ErrorCode.EMAIL_NOT_VERIFIED]: "Verify this email address before signing in.",
  [ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID]: "That verification code is not valid.",
  [ErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED]: "That verification code has expired. Request a new one.",
  [ErrorCode.EMAIL_VERIFICATION_TOKEN_USED]: "That verification code has already been used.",
  [ErrorCode.PASSWORD_RESET_TOKEN_INVALID]: "That recovery code is not valid.",
  [ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED]: "That recovery code has expired. Request a new one.",
  [ErrorCode.PASSWORD_RESET_TOKEN_USED]: "That recovery code has already been used.",
  [ErrorCode.CURRENT_PASSWORD_INVALID]: "The current password was not accepted.",
  [ErrorCode.EMAIL_DELIVERY_UNAVAILABLE]: "Email delivery is temporarily unavailable.",
  [ErrorCode.SESSION_EXPIRED]: "The session has expired. Sign in again.",
  [ErrorCode.SESSION_INVALID]: "The session is no longer valid. Sign in again.",
  [ErrorCode.REFRESH_FAILED]: "The session could not be refreshed. Sign in again.",
  [ErrorCode.AUTHENTICATION_REQUIRED]: "This endpoint requires a valid session.",
  [ErrorCode.SESSION_NOT_FOUND]: "That session is not available.",
  [ErrorCode.CURRENT_SESSION_REVOKE_NOT_ALLOWED]: "Use sign out to end the current session.",
  [ErrorCode.INVALID_GUEST_IDENTITY]: "That guest identity is not in the expected form.",
  [ErrorCode.GUEST_IDENTITY_ALREADY_LINKED]: "That guest identity is already linked to an account.",
  [ErrorCode.PASSWORD_RESET_NOT_IMPLEMENTED]: "Password reset is not implemented by this service yet.",
  [ErrorCode.NETWORK_ERROR]: "The account service could not complete the network request.",
  [ErrorCode.BACKEND_UNAVAILABLE]: "The account service cannot serve this request right now.",
  [ErrorCode.UNKNOWN_ERROR]: "The account service could not complete this request.",
});

export class AccountApiError extends Error {
  constructor(code, message = SAFE_MESSAGES[code] ?? SAFE_MESSAGES[ErrorCode.UNKNOWN_ERROR], options = {}) {
    super(message);
    this.name = "AccountApiError";
    this.code = code;
    this.status = options.status ?? STATUS_BY_CODE[code] ?? 400;
    this.retryAfterSeconds = options.retryAfterSeconds;
  }
}

export { STATUS_BY_CODE, SAFE_MESSAGES };
