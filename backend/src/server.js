/**
 * Hardened JSON API for CraftMind account identity and a separately-authorized developer control plane. No request
 * bodies, email addresses, query strings, tokens, or exception messages are written to the service log.
 */

import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import {
  changePassword,
  cleanupExpiredAccountRecords,
  confirmPasswordRecovery,
  createPasswordRecoveryChallenge,
  currentAccount,
  listSessions,
  login,
  logout,
  recordGuestIdentity,
  refreshSession,
  registerAccount,
  resendVerificationChallenge,
  revokeOneSession,
  revokeOtherSessions,
  verifyEmail,
} from "./accounts.js";
import { createEmailDelivery } from "./email-delivery.js";
import { AccountApiError, ErrorCode } from "./errors.js";
import {
  consumeCreditsForSession,
  creditTransactionsForSession,
  creditsForSession,
  entitlementsForSession,
  membershipForSession,
} from "./account-membership.js";
import {
  accountCapabilitiesForSession,
  createCreatorProfileForSession,
  createServerForSession,
  creatorEligibilityForSession,
  creatorProfileForSession,
  listServersForSession,
  publicCreatorProfileForHandle,
  serverForSession,
  updateCreatorProfileForSession,
  updateServerForSession,
} from "./creator-server-api.js";
import {
  buyerOnboardingForSession,
  onboardingStateForSession,
  saveBuyerOnboardingForSession,
  saveSellerOnboardingForSession,
  sellerOnboardingForSession,
} from "./onboarding-api.js";
import {
  archiveListingForSession,
  createListingForSession,
  listingForSession,
  ownListingsForSession,
  publicListing,
  publishListingForSession,
  searchPublishedListings,
  updateListingForSession,
} from "./listing-api.js";
import {
  awardJobForSession,
  cancelJobForSession,
  createJobForSession,
  ownJobDetailForSession,
  ownJobsForSession,
  ownProposalDetailForSession,
  ownProposalsForSession,
  publicJob,
  searchOpenJobs,
  submitProposalForSession,
  updateJobForSession,
  updateProposalForSession,
  withdrawProposalForSession,
} from "./hire-api.js";

import {
  approveMilestoneForSession,
  buyerOrdersForSession,
  cancelOrderForSession,
  completeOrderForSession,
  createOrderForSession,
  creatorOrdersForSession,
  orderDetailForSession,
  orderHistoryForSession,
  requestRevisionForSession,
  startMilestoneForSession,
  submitDeliveryForSession,
} from "./order-api.js";
import {
  addBlockForSession,
  createReportForSession,
  myBlocksForSession,
  myReportsForSession,
  removeBlockForSession,
  reportsAboutMeForSession,
  trustSummaryForSession,
  withdrawReportForSession,
} from "./marketplace-trust-api.js";
import {
  addDisputeStatementForSession,
  disputeDetailForSession,
  listMyDisputesForSession,
  listOrderDisputesForSession,
  openDisputeForSession,
  setDisputePositionForSession,
  withdrawDisputeForSession,
} from "./order-dispute-api.js";
import { isWellFormedEmail } from "./ids.js";
import { InMemoryRateLimiter } from "./rate-limiter.js";
import { SecurityEngine } from "./security-engine.js";
import { countSecurityEvents } from "./security-events.js";
import {
  authenticateDeveloper,
  bootstrapDeveloper,
  cleanupExpiredDeveloperRecords,
  currentDeveloper,
  listDeveloperSessions,
  loginDeveloper,
  logoutDeveloper,
  refreshDeveloperSession,
  revokeDeveloperSession,
} from "./developer-auth.js";
import {
  cancelDeveloperAction,
  confirmDeveloperAction,
  invokeDeveloperTool,
  recordDeveloperAiAudit,
  recordDeveloperAiSecurityAudit,
  recordDeveloperToolRejection,
} from "./admin-tools.js";
import { DeveloperAiCoordinator } from "./developer-ai.js";
import { SCHEMA_VERSION } from "./db.js";

const NON_ENUMERATING_RESPONSE_FLOOR_MS = 150;
const DEVELOPER_DASHBOARD = readFileSync(new URL("../public/developer.html", import.meta.url), "utf8");
const DEVELOPER_SCRIPT = readFileSync(new URL("../public/developer.js", import.meta.url), "utf8");
const DEVELOPER_STYLES = readFileSync(new URL("../public/developer.css", import.meta.url), "utf8");

const SECURITY_HEADERS = Object.freeze({
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store, max-age=0",
  Pragma: "no-cache",
  Expires: "0",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "X-Frame-Options": "DENY",
  "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
});

function sendJson(response, status, payload, requestId, extraHeaders = {}) {
  const body = Buffer.from(JSON.stringify(payload), "utf8");
  response.writeHead(status, {
    ...SECURITY_HEADERS,
    "X-Request-Id": requestId,
    "Content-Length": String(body.byteLength),
    ...extraHeaders,
  });
  response.end(body);
}

function sendNoContent(response, requestId, extraHeaders = {}) {
  response.writeHead(204, { ...SECURITY_HEADERS, "X-Request-Id": requestId, ...extraHeaders });
  response.end();
}

function sendDeveloperAsset(response, status, body, contentType, requestId, extraHeaders = {}) {
  const bytes = Buffer.from(body, "utf8");
  response.writeHead(status, {
    ...SECURITY_HEADERS,
    "Content-Type": contentType,
    "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'",
    "Cache-Control": "no-store, max-age=0",
    "Content-Length": String(bytes.byteLength),
    "X-Request-Id": requestId,
    ...extraHeaders,
  });
  response.end(bytes);
}

function sendError(response, error, requestId, extraHeaders = {}) {
  const typed = error instanceof AccountApiError ? error : new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  const headers = { ...extraHeaders };
  if (typed.retryAfterSeconds) headers["Retry-After"] = String(typed.retryAfterSeconds);
  sendJson(response, typed.status, {
    error: {
      code: typed.code,
      message: typed.message,
      requestId,
      ...(typed.retryAfterSeconds ? { retryAfterSeconds: typed.retryAfterSeconds } : {}),
    },
  }, requestId, headers);
}

async function readJsonBody(request, maxBodyBytes) {
  const chunks = [];
  let total = 0;
  for await (const chunk of request) {
    total += chunk.length;
    if (total > maxBodyBytes) throw new AccountApiError(ErrorCode.REQUEST_TOO_LARGE);
    chunks.push(chunk);
  }
  if (total === 0) return {};
  const contentType = String(request.headers["content-type"] ?? "").split(";", 1)[0].trim().toLowerCase();
  if (contentType !== "application/json") throw new AccountApiError(ErrorCode.INVALID_CONTENT_TYPE);
  try {
    const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("not an object");
    return parsed;
  } catch {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
}

function bearerToken(request) {
  const header = request.headers.authorization;
  if (typeof header !== "string") return undefined;
  const match = /^Bearer ([A-Za-z0-9._~+/-]{16,512}=*)$/.exec(header.trim());
  if (!match) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
  return match[1];
}

function requireDeveloperActor(request, database, configuration) {
  let token;
  try { token = bearerToken(request); }
  catch { throw new AccountApiError(ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED); }
  if (token === undefined) throw new AccountApiError(ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED);
  const authenticated = authenticateDeveloper(database, configuration, token);
  return { ...authenticated.developer, session: authenticated.session };
}

function safeRoutePath(request) {
  try { return new URL(request.url ?? "/", "http://internal.invalid").pathname; }
  catch { return "/invalid-url"; }
}

function applyCors(request, configuration) {
  const origin = request.headers.origin;
  if (origin === undefined) return {};
  const expectedSameOrigin = configuration.production
    ? configuration.publicOrigin
    : `${request.socket.encrypted ? "https" : "http"}://${request.headers.host ?? ""}`;
  if (typeof origin === "string" && origin === expectedSameOrigin) return {};
  if (typeof origin !== "string" || !configuration.corsAllowedOrigins.includes(origin)) {
    throw new AccountApiError(ErrorCode.CORS_ORIGIN_NOT_ALLOWED);
  }
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Access-Control-Max-Age": "600",
    Vary: "Origin",
  };
}

function enforceHttps(request, configuration) {
  if (!configuration.production || request.socket.encrypted) return;
  // In production the process must only be reachable behind the configured TLS terminator. The deployment network
  // boundary must make direct HTTP access impossible; this header is accepted only in that explicitly-required mode.
  if (!configuration.trustProxyTls || request.headers["x-forwarded-proto"] !== "https") {
    throw new AccountApiError(ErrorCode.HTTPS_REQUIRED);
  }
}

function consumeLimit(rateLimiter, operation, request, subject, limits) {
  const clientAddress = request.socket.remoteAddress ?? "unknown";
  rateLimiter.consume(`${operation}:ip`, [clientAddress], limits);
  if (typeof subject === "string" && subject.trim()) {
    // Email-based operations also share an independent HMAC bucket across client IPs.
    rateLimiter.consume(`${operation}:subject`, [subject], limits);
  }
}

/**
 * Reads a query string strictly: an unparseable URL yields the typed invalid-request failure rather than an exception
 * from the URL parser, and callers validate the keys they accept. Query values never reach SQL as-is.
 */
function safeQuery(request) {
  try {
    const parsed = new URL(String(request.url ?? "/"), "http://account.invalid");
    if ([...parsed.searchParams.keys()].some((key) => key.length > 32)) {
      throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
    }
    return parsed.searchParams;
  } catch (error) {
    if (error instanceof AccountApiError) throw error;
    throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
  }
}

async function enforceNonEnumeratingResponseFloor(startedAt) {
  const remaining = startedAt + NON_ENUMERATING_RESPONSE_FLOOR_MS - Date.now();
  if (remaining > 0) await new Promise((resolve) => setTimeout(resolve, remaining));
}

function statusForEmailDelivery(result, emailDelivery) {
  if (result === "FAILED") return "UNAVAILABLE";
  return emailDelivery.mode === "DEVELOPMENT_SINK" ? "DEVELOPMENT_SINK" : "PROVIDER_ACCEPTED";
}

/**
 * Route → protection/event category. Security-sensitive routes are the only ones with autonomous protections.
 *
 * Phase 22 adds `/account/`: reading one's own membership and spending one's own credits are authenticated state
 * changes, so a source or session already under an active Phase 20 protection is throttled here too — the controls are
 * reused exactly as they are, not duplicated.
 */
const SECURITY_SENSITIVE_PREFIXES = ["/auth/", "/account/", "/developer/", "/creator/", "/servers/", "/onboarding/", "/buyer/"];

function isSecuritySensitiveRoute(pathKey) {
  return SECURITY_SENSITIVE_PREFIXES.some((prefix) => pathKey.startsWith(prefix));
}

function routeSecurityCategory(pathKey) {
  if (pathKey.startsWith("/developer/auth")) return "DEVELOPER_AUTH";
  // Confirmation attempts are their own abuse category, so a confirmation protection never throttles unrelated routes.
  if (pathKey === "/developer/tools/confirm" || pathKey === "/developer/tools/cancel") return "CONFIRMATION";
  if (pathKey.startsWith("/developer")) return "DEVELOPER_ADMIN";
  if (pathKey === "/auth/login" || pathKey === "/auth/register" || pathKey === "/auth/guest") return "USER_AUTH";
  // Credential-bearing session routes are the only ones a session protection may touch; account-recovery routes
  // (verification, password reset) stay in the user-auth category so a session protection never blocks recovery.
  if (pathKey === "/auth/refresh" || pathKey === "/auth/logout" || pathKey === "/auth/me") return "SESSION";
  if (pathKey.startsWith("/auth")) return "USER_AUTH";
  // A credit consumption is a credential-bearing state change: an active session protection applies to it as well.
  if (pathKey === "/account/credits/consume") return "SESSION";
  // Phase 23 creator and workspace state changes are credential-bearing too. Public creator reads are not in this
  // category at all: an anonymous read is throttled by the request limit and by the autonomous source protections, and
  // applying an *account* protection to an anonymous request would protect nothing.
  if (pathKey === "/creator/profile" || pathKey.startsWith("/servers/")) return "SESSION";
  // Phase 25 listing routes: reads and writes share each path, so the whole family gets session protection —
  // the same shape as /creator/profile. The public /marketplace/* reads stay ordinary request-category traffic.
  if (pathKey.startsWith("/creator/listing")) return "SESSION";
  // Phase 24 onboarding saves are credential-bearing state changes about the account itself; both verbs share the
  // path, so the category covers the read and the write together, exactly as it does for /creator/profile.
  if (pathKey === "/onboarding/buyer" || pathKey === "/onboarding/seller") return "SESSION";
  // Phase 26 hire routes: buyer job management (/buyer/jobs…) and creator proposals (/creator/proposal…) are
  // session-scoped reads and writes — the same shape as /creator/listing. The public /marketplace/jobs reads stay
  // ordinary request-category traffic.
  if (pathKey.startsWith("/buyer/jobs") || pathKey.startsWith("/creator/proposal")) return "SESSION";
  // Phase 27 order routes: reads and mutations under /orders/… are session-scoped (ownership is derived from the
  // token, never the body); the static /buyer/orders and /creator/orders paths join their hire siblings.
  if (pathKey.startsWith("/orders/") || pathKey === "/buyer/orders" || pathKey.startsWith("/creator/orders")) return "SESSION";
  // Phase 30 trust routes: reports, the block list, and the trust summary are session-scoped reads and writes over
  // the caller's own relationships, so they join the SESSION category. The public /marketplace/listings and
  // /marketplace/jobs reads are unaffected and stay ordinary request-category traffic. (Order disputes already live
  // under /orders/, which the line above routes here.)
  if (pathKey.startsWith("/marketplace/reports") || pathKey.startsWith("/marketplace/blocks")
    || pathKey.startsWith("/marketplace/disputes") || pathKey === "/marketplace/trust") return "SESSION";
  return "REQUEST";
}

function routeCategoryLabel(pathKey) {
  return pathKey.slice(1).replace(/\//g, "_").replace(/[^a-z0-9:_-]/gi, "").toLowerCase().slice(0, 64) || "root";
}

/** Maps a typed API failure to the normalized security signal it represents; unmapped codes produce nothing. */
function securitySignalForFailure(code, pathKey) {
  switch (code) {
    case ErrorCode.INVALID_CREDENTIALS:
      return { eventType: "AUTHENTICATION_FAILED", result: "FAILURE" };
    case ErrorCode.ACCOUNT_SUSPENDED:
    case ErrorCode.ACCOUNT_DELETED:
      return { eventType: "SUSPICIOUS_ACCOUNT_ACTIVITY", result: "DENIED" };
    case ErrorCode.DEVELOPER_INVALID_CREDENTIALS:
    case ErrorCode.DEVELOPER_BOOTSTRAP_INVALID:
    case ErrorCode.DEVELOPER_BOOTSTRAP_CONSUMED:
      return { eventType: "DEVELOPER_AUTHENTICATION_FAILED", result: "FAILURE" };
    case ErrorCode.SESSION_INVALID:
    case ErrorCode.AUTHENTICATION_REQUIRED:
    case ErrorCode.DEVELOPER_AUTHENTICATION_REQUIRED:
      return { eventType: "SESSION_INVALID_CREDENTIAL", result: "INVALID", sessionAware: true };
    case ErrorCode.SESSION_EXPIRED:
    case ErrorCode.DEVELOPER_SESSION_EXPIRED:
      return { eventType: "SESSION_EXPIRED_REUSE", result: "DENIED", sessionAware: true };
    case ErrorCode.REFRESH_FAILED:
    case ErrorCode.DEVELOPER_REFRESH_FAILED:
      return { eventType: "SESSION_REFRESH_FAILED", result: "FAILURE", sessionAware: true };
    case ErrorCode.RATE_LIMITED:
      return { eventType: "RATE_LIMIT_VIOLATION", result: "THROTTLED" };
    case ErrorCode.DEVELOPER_ACCESS_DENIED:
    case ErrorCode.SERVER_ACCESS_DENIED:
    case ErrorCode.OWNERSHIP_REQUIRED:
      return { eventType: "UNAUTHORIZED_ACCESS_ATTEMPT", result: "DENIED" };
    // A suspended profile or workspace refusing a protected operation is evidence of an account in a restricted state,
    // exactly like a suspended account refusing a session. It is recorded once per attempt; the detection thresholds
    // decide whether that is a pattern.
    case ErrorCode.CREATOR_PROFILE_SUSPENDED:
    case ErrorCode.SERVER_SUSPENDED:
      return { eventType: "SUSPICIOUS_ACCOUNT_ACTIVITY", result: "DENIED" };
    // Deliberately unmapped: CREATOR_ENTITLEMENT_REQUIRED, SERVER_ENTITLEMENT_REQUIRED, CREATOR_PROFILE_NOT_FOUND,
    // SERVER_NOT_FOUND, and CREATOR_HANDLE_UNAVAILABLE. Holding no plan, mistyping a handle, and racing for a taken
    // slug are ordinary outcomes of an ordinary client, not attack evidence. The first two are still audited
    // (ENTITLEMENT_DENIED) and the last three are rate-limited, so nothing is invisible — it is simply not escalated
    // into a security incident, because a security system that cries wolf is worse than one that stays quiet.
    case ErrorCode.DEVELOPER_TOOL_UNKNOWN:
    case ErrorCode.DEVELOPER_TOOL_INPUT_INVALID:
      return { eventType: "HIGH_IMPACT_ACTION_FAILED", result: "FAILURE" };
    case ErrorCode.DEVELOPER_CONFIRMATION_INVALID:
    case ErrorCode.DEVELOPER_CONFIRMATION_EXPIRED:
      return { eventType: "CONFIRMATION_ATTEMPT_INVALID", result: "INVALID" };
    case ErrorCode.INVALID_REQUEST:
    case ErrorCode.INVALID_CONTENT_TYPE:
    case ErrorCode.REQUEST_TOO_LARGE:
      return isSecuritySensitiveRoute(pathKey) ? { eventType: "MALFORMED_SECURITY_REQUEST", result: "INVALID" } : null;
    default:
      return null;
  }
}

/** @param {{ database: import('node:sqlite').DatabaseSync, configuration: object, logger?: object, emailDelivery?: object, rateLimiter?: object, developerAiProvider?: object, securityEngine?: object }} options */
export function createAccountService({ database, configuration, logger = console, emailDelivery, rateLimiter, developerAiProvider = null, securityEngine = null }) {
  const mail = emailDelivery ?? createEmailDelivery(configuration);
  const limits = rateLimiter ?? new InMemoryRateLimiter({ authSecret: configuration.authSecret });
  const developerAi = new DeveloperAiCoordinator({ provider: developerAiProvider });
  // The engine is local and self-contained: it never requires the AI provider, and an injected engine keeps tests and
  // alternative compositions explicit.
  const security = securityEngine ?? new SecurityEngine({ database, configuration, logger });
  cleanupExpiredAccountRecords(database);
  cleanupExpiredDeveloperRecords(database);
  const runSecurityMaintenance = () => {
    try {
      security.resolveQuietIncidents();
      security.cleanup();
    } catch (error) {
      logger.warn?.(JSON.stringify({ event: "security_maintenance_failed", errorType: error?.name ?? "Error" }));
    }
  };
  runSecurityMaintenance();
  const cleanupTimer = setInterval(() => {
    try {
      cleanupExpiredAccountRecords(database);
      cleanupExpiredDeveloperRecords(database);
    } catch (error) {
      logger.error?.(JSON.stringify({ event: "account_expiry_cleanup_failed", errorType: error?.name ?? "Error" }));
    }
    runSecurityMaintenance();
  }, 60 * 60 * 1000);
  cleanupTimer.unref?.();
  const sendMail = async (kind, challenge, requestId) => {
    if (!challenge) return "NOT_APPLICABLE";
    try {
      if (kind === "verification") await mail.sendVerification(challenge);
      else await mail.sendPasswordRecovery(challenge);
      return statusForEmailDelivery("OK", mail);
    } catch {
      // Recipient, body, token, and provider exception are deliberately excluded.
      logger.warn?.(JSON.stringify({ event: "email_delivery_failed", kind, requestId }));
      return statusForEmailDelivery("FAILED", mail);
    }
  };

  /**
   * Turns one rejected request into at most one normalized security signal. This is the single ingestion point for
   * request-observed behaviour; nothing secret (body, credentials, addresses, tokens) crosses into it.
   */
  const recordFailureSignal = (request, pathKey, error, { token, kind = "user", accountReference = null } = {}) => {
    try {
      // A rejection produced by an autonomous protection is not new evidence of abuse: recording it would let the
      // system's own defensive throttling escalate into fresh incidents (a feedback loop). The incident, the
      // protection, and the audit record for it already exist.
      if (error?.securityProtectionId) return;
      const code = error instanceof AccountApiError ? error.code : null;
      if (!code) return;
      const signal = securitySignalForFailure(code, pathKey);
      if (!signal) return;
      let sessionReference = null;
      let resolvedAccount = accountReference;
      if (signal.sessionAware && typeof token === "string") {
        const classification = security.classifySessionFailure({ kind, token });
        if (classification.state === "REVOKED") signal.eventType = "SESSION_REVOKED_REUSE";
        else if (classification.state === "EXPIRED") signal.eventType = "SESSION_EXPIRED_REUSE";
        sessionReference = classification.sessionReference ?? null;
        resolvedAccount = classification.accountReference ?? resolvedAccount;
      }
      security.recordSignal({
        eventType: signal.eventType,
        result: signal.result,
        accountReference: resolvedAccount,
        sessionReference,
        routeCategory: routeCategoryLabel(pathKey),
        sourceReference: security.sourceDigestFor(request),
      });
    } catch (failure) {
      logger.warn?.(JSON.stringify({ event: "security_signal_failed", errorType: failure?.name ?? "Error" }));
    }
  };

  /**
   * Applies active autonomous protections to an incoming security-sensitive request. A hard rejection throws the same
   * typed rate-limit error the existing limiter uses; a tightened throttle consumes from that same limiter, so the
   * stricter bound always governs and no privileged path is granted.
   */
  const enforceRequestProtections = (request, pathKey, { accountReference = null, sessionReference = null, token = null, kind = "user" } = {}) => {
    const category = routeSecurityCategory(pathKey);
    const sourceReference = security.sourceDigestFor(request);
    security.noteRequest({ sourceReference, accountReference, routeCategory: routeCategoryLabel(pathKey) });
    let resolvedAccount = accountReference;
    let resolvedSession = sessionReference;
    if (token && !resolvedSession && !resolvedAccount) {
      const classification = security.classifySessionFailure({ kind, token });
      resolvedSession = classification.sessionReference ?? null;
      resolvedAccount = classification.accountReference ?? null;
    }
    security.enforceProtection({ sourceReference, accountReference: resolvedAccount, sessionReference: resolvedSession, category, rateLimiter: limits });
  };

  /** Records a successful sign-in, and flags the "success after repeated failures" pattern the detection layer watches. */
  const recordAuthenticationSuccess = (request, accountReference) => {
    if (!accountReference) return;
    try {
      const since = new Date(Date.now() - 600_000).toISOString();
      const recentFailures = countSecurityEvents(database, { since, eventType: "AUTHENTICATION_FAILED", accountReference });
      security.recordSignal({
        eventType: recentFailures >= 3 ? "AUTHENTICATION_SUCCEEDED_AFTER_FAILURES" : "AUTHENTICATION_SUCCEEDED",
        result: "SUCCESS",
        accountReference,
        routeCategory: "auth_login",
        sourceReference: security.sourceDigestFor(request),
        metadata: recentFailures >= 3 ? { recentFailures } : {},
      });
    } catch (error) {
      logger.warn?.(JSON.stringify({ event: "security_success_signal_failed", errorType: error?.name ?? "Error" }));
    }
  };

  const routes = new Map([
    ["POST /auth/register", async (request, context) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "register", request, "", configuration.rateLimit.register);
      const created = await registerAccount(database, configuration, body);
      const deliveryStatus = await sendMail("verification", created.challenge, context.requestId);
      return {
        status: 201,
        payload: {
          account: created.account,
          verificationRequired: true,
          deliveryStatus,
          guestLinked: created.guestLinked,
        },
      };
    }],
    ["POST /auth/login", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "login", request, body.email, configuration.rateLimit.login);
      // An account-level protection throttles sign-in attempts for one account without ever locking the owner out.
      const accountReference = typeof body.email === "string" && isWellFormedEmail(body.email)
        ? security.accountReferenceForEmail(body.email)
        : null;
      enforceRequestProtections(request, "/auth/login", { accountReference });
      let payload;
      try {
        payload = await login(database, configuration, body);
      } catch (error) {
        if (error instanceof AccountApiError) {
          // Richer signal than the request-level catch can produce: the attempted account is known here.
          recordFailureSignal(request, "/auth/login", error, { accountReference });
          error.securityRecorded = true;
        }
        throw error;
      }
      recordAuthenticationSuccess(request, accountReference);
      return { status: 200, payload };
    }],
    ["POST /auth/refresh", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "session", request, "", configuration.rateLimit.session);
      return { status: 200, payload: refreshSession(database, configuration, body) };
    }],
    ["POST /auth/logout", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "session", request, "", configuration.rateLimit.session);
      return { status: 200, payload: logout(database, configuration, body) };
    }],
    ["GET /auth/me", async (request) => {
      consumeLimit(limits, "session", request, "", configuration.rateLimit.session);
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      return { status: 200, payload: currentAccount(database, configuration, token) };
    }],
    ["POST /auth/guest", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "guest", request, "", configuration.rateLimit.register);
      return { status: 201, payload: { guest: recordGuestIdentity(database, body.guestIdentityId) } };
    }],
    ["POST /auth/verify-email", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "verify-email", request, "", configuration.rateLimit.verification);
      return { status: 200, payload: verifyEmail(database, configuration, body) };
    }],
    ["POST /auth/resend-verification", async (request, context) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "resend-verification", request, body.email, configuration.rateLimit.resend);
      const startedAt = Date.now();
      const challenge = resendVerificationChallenge(database, configuration, body.email);
      if (challenge) void sendMail("verification", challenge, context.requestId).catch(() => {});
      await enforceNonEnumeratingResponseFloor(startedAt);
      return {
        status: 202,
        payload: {
          accepted: true,
          deliveryMode: configuration.emailDeliveryMode,
          message: "If verification is needed, the service accepted the request for processing. This response does not confirm that an email was delivered.",
        },
      };
    }],
    ["POST /auth/password-reset/request", async (request, context) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "password-reset-request", request, body.email, configuration.rateLimit.resetRequest);
      const startedAt = Date.now();
      const challenge = createPasswordRecoveryChallenge(database, configuration, body.email);
      if (challenge) void sendMail("password-recovery", challenge, context.requestId).catch(() => {});
      await enforceNonEnumeratingResponseFloor(startedAt);
      return {
        status: 202,
        payload: {
          accepted: true,
          deliveryMode: configuration.emailDeliveryMode,
          message: "If the account exists, the service accepted the recovery request for processing. This response neither confirms the account nor confirms email delivery.",
        },
      };
    }],
    ["POST /auth/password-reset/confirm", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "password-reset-confirm", request, "", configuration.rateLimit.resetConfirm);
      return { status: 200, payload: await confirmPasswordRecovery(database, configuration, body) };
    }],
    // Backwards-compatible request route: the old 501 contract now enters the real, enumeration-safe recovery flow.
    ["POST /auth/password-reset", async (request, context) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "password-reset-request", request, body.email, configuration.rateLimit.resetRequest);
      const startedAt = Date.now();
      const challenge = createPasswordRecoveryChallenge(database, configuration, body.email);
      if (challenge) void sendMail("password-recovery", challenge, context.requestId).catch(() => {});
      await enforceNonEnumeratingResponseFloor(startedAt);
      return { status: 202, payload: { accepted: true, deliveryMode: configuration.emailDeliveryMode, message: "If the account exists, the service accepted the recovery request for processing. This response does not confirm account status or email delivery." } };
    }],
    ["POST /auth/password/change", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "session", request, "", configuration.rateLimit.session);
      return { status: 200, payload: await changePassword(database, configuration, token, body) };
    }],
    ["GET /auth/sessions", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "session", request, "", configuration.rateLimit.session);
      return { status: 200, payload: listSessions(database, configuration, token) };
    }],
    ["POST /auth/sessions/revoke", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "session", request, "", configuration.rateLimit.session);
      return { status: 200, payload: revokeOneSession(database, configuration, token, body) };
    }],
    ["POST /auth/sessions/revoke-all", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "session", request, "", configuration.rateLimit.session);
      return { status: 200, payload: revokeOtherSessions(database, configuration, token) };
    }],
    // Phase 22 account surface. Every route identifies the caller from the bearer session alone; none accepts an
    // account id, a plan, a status, or a balance from the request.
    ["GET /account/membership", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "credits", request, "", configuration.rateLimit.credits);
      return { status: 200, payload: membershipForSession(database, configuration, token) };
    }],
    ["GET /account/entitlements", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "credits", request, "", configuration.rateLimit.credits);
      return { status: 200, payload: entitlementsForSession(database, configuration, token) };
    }],
    ["GET /account/credits", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "credits", request, "", configuration.rateLimit.credits);
      return { status: 200, payload: creditsForSession(database, configuration, token) };
    }],
    ["GET /account/credits/transactions", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "credits", request, "", configuration.rateLimit.credits);
      const query = safeQuery(request);
      // Strict, bounded paging: an unknown key, a non-numeric limit, or a limit outside 1..100 is refused rather than
      // silently interpreted.
      for (const key of query.keys()) {
        if (!["limit", "type"].includes(key)) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
      }
      const rawLimit = query.get("limit");
      if (rawLimit !== null && !/^[0-9]{1,3}$/.test(rawLimit)) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
      const limit = rawLimit === null ? 25 : Number(rawLimit);
      if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new AccountApiError(ErrorCode.CREDIT_OPERATION_INVALID);
      const type = query.get("type");
      return { status: 200, payload: creditTransactionsForSession(database, configuration, token, { limit, type }) };
    }],
    ["POST /account/credits/consume", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "credit-consume", request, "", configuration.rateLimit.creditConsume);
      const headerKey = typeof request.headers["idempotency-key"] === "string" ? request.headers["idempotency-key"].trim() : null;
      return { status: 200, payload: consumeCreditsForSession(database, configuration, token, body, headerKey) };
    }],
    // Phase 23 creator surface. The account always comes from the bearer session; the body carries content only. A
    // public creator read is the one anonymous route: it shows the public projection of an ACTIVE profile, and every
    // other case — unknown, suspended, or disabled — answers with the same not-found failure.
    ["GET /account/capabilities", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "credits", request, "", configuration.rateLimit.credits);
      return { status: 200, payload: accountCapabilitiesForSession(database, configuration, token) };
    }],
    ["GET /creator/profile", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "creator-read", request, "", configuration.rateLimit.creatorProfileRead);
      return { status: 200, payload: creatorProfileForSession(database, configuration, token) };
    }],
    ["GET /creator/eligibility", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "creator-read", request, "", configuration.rateLimit.creatorProfileRead);
      return { status: 200, payload: creatorEligibilityForSession(database, configuration, token) };
    }],
    ["POST /creator/profile", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "creator-write", request, "", configuration.rateLimit.creatorProfileWrite);
      return { status: 201, payload: createCreatorProfileForSession(database, configuration, token, body) };
    }],
    ["PATCH /creator/profile", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "creator-write", request, "", configuration.rateLimit.creatorProfileWrite);
      return { status: 200, payload: updateCreatorProfileForSession(database, configuration, token, body) };
    }],
    ["GET /servers", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "server-read", request, "", configuration.rateLimit.serverRead);
      return { status: 200, payload: listServersForSession(database, configuration, token) };
    }],
    ["POST /servers", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "server-write", request, "", configuration.rateLimit.serverWrite);
      return { status: 201, payload: createServerForSession(database, configuration, token, body) };
    }],
    // Phase 24 onboarding surface. The account always comes from the bearer session; bodies carry content only.
    // Reads are ordinary authenticated state; saves consume the write bucket and answer with the same composed shape
    // as the read, so the website renders server truth after every write.
    ["GET /account/onboarding", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "onboarding-read", request, "", configuration.rateLimit.onboardingRead);
      return { status: 200, payload: onboardingStateForSession(database, configuration, token) };
    }],
    ["GET /onboarding/buyer", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "onboarding-read", request, "", configuration.rateLimit.onboardingRead);
      return { status: 200, payload: buyerOnboardingForSession(database, configuration, token) };
    }],
    ["POST /onboarding/buyer", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "onboarding-write", request, "", configuration.rateLimit.onboardingWrite);
      return { status: 200, payload: saveBuyerOnboardingForSession(database, configuration, token, body) };
    }],
    ["GET /onboarding/seller", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "onboarding-read", request, "", configuration.rateLimit.onboardingRead);
      return { status: 200, payload: sellerOnboardingForSession(database, configuration, token) };
    }],
    ["POST /onboarding/seller", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "onboarding-write", request, "", configuration.rateLimit.onboardingWrite);
      return { status: 200, payload: saveSellerOnboardingForSession(database, configuration, token, body) };
    }],
    // Phase 25 marketplace listings. Creators manage their own rows under /creator/listing (session-derived
    // ownership only); the public discovers PUBLISHED rows under /marketplace/listings.
    ["POST /creator/listing", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "listing-write", request, "", configuration.rateLimit.creatorProfileWrite);
      return { status: 201, payload: createListingForSession(database, configuration, token, body) };
    }],
    ["GET /creator/listing", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "listing-read", request, "", configuration.rateLimit.creatorProfileRead);
      return { status: 200, payload: ownListingsForSession(database, configuration, token) };
    }],
    ["GET /marketplace/listings", async (request) => {
      consumeLimit(limits, "listing-search", request, "", configuration.rateLimit.creatorProfileRead);
      const searchParams = new URL(request.url ?? "/", "http://listing.local").searchParams;
      return { status: 200, payload: searchPublishedListings(database, searchParams) };
    }],
    // Phase 26 Hire a Builder. Buyers manage their own job requests under /buyer/jobs (session-derived ownership
    // only); creators manage proposals under /creator/proposal; the public discovers OPEN jobs under
    // /marketplace/jobs. Job creation and proposal submission each spend their own dedicated rate budget.
    ["POST /buyer/jobs", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "job-write", request, "", configuration.rateLimit.jobWrite);
      return { status: 201, payload: createJobForSession(database, configuration, token, body) };
    }],
    ["GET /buyer/jobs", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "hire-read", request, "", configuration.rateLimit.creatorProfileRead);
      return { status: 200, payload: ownJobsForSession(database, configuration, token) };
    }],
    ["GET /marketplace/jobs", async (request) => {
      consumeLimit(limits, "job-search", request, "", configuration.rateLimit.creatorProfileRead);
      const searchParams = new URL(request.url ?? "/", "http://hire.local").searchParams;
      return { status: 200, payload: searchOpenJobs(database, searchParams) };
    }],
    ["GET /creator/proposal", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "hire-read", request, "", configuration.rateLimit.creatorProfileRead);
      return { status: 200, payload: ownProposalsForSession(database, configuration, token) };
    }],
    ["POST /creator/proposal", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "proposal-submit", request, "", configuration.rateLimit.proposalWrite);
      return { status: 201, payload: submitProposalForSession(database, configuration, token, body) };
    }],
    // Phase 27 order lifecycle. Creation and the buyer/creator views are static paths; every order action below is
    // a parameterized route whose id is validated inside the handler. Order creation, milestone transitions,
    // delivery submissions, and revision requests each spend a dedicated rate budget.
    ["POST /buyer/orders", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "order-create", request, "", configuration.rateLimit.orderCreate);
      return { status: 201, payload: createOrderForSession(database, configuration, token, body) };
    }],
    ["GET /buyer/orders", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "order-read", request, "", configuration.rateLimit.creatorProfileRead);
      const searchParams = new URL(request.url ?? "/", "http://orders.local").searchParams;
      return { status: 200, payload: buyerOrdersForSession(database, configuration, token, searchParams) };
    }],
    ["GET /creator/orders", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "order-read", request, "", configuration.rateLimit.creatorProfileRead);
      const searchParams = new URL(request.url ?? "/", "http://orders.local").searchParams;
      return { status: 200, payload: creatorOrdersForSession(database, configuration, token, searchParams) };
    }],
    // Phase 30 marketplace trust: reports and the avoid/block list. Every route identifies the caller from the bearer
    // session alone and derives every relationship server-side; none accepts a reporter, a target, a blocked account
    // (on create), a subject status, or a money field. Writes spend the dedicated trust budget; reads the ordinary one.
    ["POST /marketplace/reports", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "trust-write", request, "", configuration.rateLimit.trustWrite);
      return { status: 201, payload: createReportForSession(database, configuration, token, body) };
    }],
    ["GET /marketplace/reports", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "trust-read", request, "", configuration.rateLimit.creatorProfileRead);
      const searchParams = new URL(request.url ?? "/", "http://trust.local").searchParams;
      return { status: 200, payload: myReportsForSession(database, configuration, token, searchParams) };
    }],
    ["GET /marketplace/reports/about-me", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "trust-read", request, "", configuration.rateLimit.creatorProfileRead);
      const searchParams = new URL(request.url ?? "/", "http://trust.local").searchParams;
      return { status: 200, payload: reportsAboutMeForSession(database, configuration, token, searchParams) };
    }],
    ["GET /marketplace/trust", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "trust-read", request, "", configuration.rateLimit.creatorProfileRead);
      return { status: 200, payload: trustSummaryForSession(database, configuration, token) };
    }],
    ["POST /marketplace/blocks", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "trust-write", request, "", configuration.rateLimit.trustWrite);
      return { status: 200, payload: addBlockForSession(database, configuration, token, body) };
    }],
    ["POST /marketplace/blocks/remove", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "trust-write", request, "", configuration.rateLimit.trustWrite);
      return { status: 200, payload: removeBlockForSession(database, configuration, token, body) };
    }],
    ["GET /marketplace/blocks", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "trust-read", request, "", configuration.rateLimit.creatorProfileRead);
      return { status: 200, payload: myBlocksForSession(database, configuration, token) };
    }],
    ["GET /marketplace/disputes", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      consumeLimit(limits, "dispute-read", request, "", configuration.rateLimit.creatorProfileRead);
      const searchParams = new URL(request.url ?? "/", "http://trust.local").searchParams;
      return { status: 200, payload: listMyDisputesForSession(database, configuration, token, searchParams) };
    }],
    ["GET /developer", async () => ({ status: 200, asset: { body: DEVELOPER_DASHBOARD, contentType: "text/html; charset=utf-8" } })],
    ["GET /developer/developer.js", async () => ({ status: 200, asset: { body: DEVELOPER_SCRIPT, contentType: "text/javascript; charset=utf-8" } })],
    ["GET /developer/developer.css", async () => ({ status: 200, asset: { body: DEVELOPER_STYLES, contentType: "text/css; charset=utf-8" } })],
    ["POST /developer/auth/bootstrap", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "developer-bootstrap", request, "", configuration.rateLimit.developerBootstrap);
      return { status: 201, payload: await bootstrapDeveloper(database, configuration, body) };
    }],
    ["POST /developer/auth/login", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "developer-login", request, body.email, configuration.rateLimit.developerLogin);
      const accountReference = typeof body.email === "string" && isWellFormedEmail(body.email)
        ? security.accountReferenceForEmail(body.email, { kind: "developer" })
        : null;
      enforceRequestProtections(request, "/developer/auth/login", { accountReference });
      try {
        return { status: 200, payload: await loginDeveloper(database, configuration, body) };
      } catch (error) {
        if (error instanceof AccountApiError) {
          recordFailureSignal(request, "/developer/auth/login", error, { accountReference });
          error.securityRecorded = true;
        }
        throw error;
      }
    }],
    ["POST /developer/auth/refresh", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      consumeLimit(limits, "developer-session", request, "", configuration.rateLimit.developerSession);
      return { status: 200, payload: refreshDeveloperSession(database, configuration, body) };
    }],
    ["GET /developer/auth/me", async (request) => {
      consumeLimit(limits, "developer-session", request, "", configuration.rateLimit.developerSession);
      requireDeveloperActor(request, database, configuration);
      const token = bearerToken(request);
      return { status: 200, payload: currentDeveloper(database, configuration, token) };
    }],
    ["POST /developer/auth/logout", async (request) => {
      consumeLimit(limits, "developer-session", request, "", configuration.rateLimit.developerSession);
      requireDeveloperActor(request, database, configuration);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      if (Object.keys(body).length !== 0) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
      const token = bearerToken(request);
      return { status: 200, payload: logoutDeveloper(database, configuration, token) };
    }],
    ["GET /developer/auth/sessions", async (request) => {
      consumeLimit(limits, "developer-session", request, "", configuration.rateLimit.developerSession);
      requireDeveloperActor(request, database, configuration);
      const token = bearerToken(request);
      return { status: 200, payload: listDeveloperSessions(database, configuration, token) };
    }],
    ["POST /developer/auth/sessions/revoke", async (request) => {
      consumeLimit(limits, "developer-session", request, "", configuration.rateLimit.developerSession);
      requireDeveloperActor(request, database, configuration);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      if (Object.keys(body).length !== 1 || typeof body.sessionId !== "string") throw new AccountApiError(ErrorCode.INVALID_REQUEST);
      const token = bearerToken(request);
      return { status: 200, payload: revokeDeveloperSession(database, configuration, token, body) };
    }],
    ["POST /developer/tools/invoke", async (request) => {
      consumeLimit(limits, "developer-admin", request, "", configuration.rateLimit.developerAdmin);
      const actor = requireDeveloperActor(request, database, configuration);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      if (Object.keys(body).length !== 2 || typeof body.tool !== "string" || !Object.hasOwn(body, "arguments")) {
        recordDeveloperToolRejection(database, actor);
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      const result = invokeDeveloperTool(database, configuration, actor, body.tool, body.arguments, {
        configuration, schemaVersion: SCHEMA_VERSION, developerAiProvider, securityEngine: security,
      });
      return { status: 200, payload: { tool: body.tool, result } };
    }],
    ["POST /developer/tools/confirm", async (request) => {
      consumeLimit(limits, "developer-admin", request, "", configuration.rateLimit.developerAdmin);
      const actor = requireDeveloperActor(request, database, configuration);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      return { status: 200, payload: { result: confirmDeveloperAction(database, configuration, actor, body) } };
    }],
    ["POST /developer/tools/cancel", async (request) => {
      consumeLimit(limits, "developer-admin", request, "", configuration.rateLimit.developerAdmin);
      const actor = requireDeveloperActor(request, database, configuration);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      return { status: 200, payload: { result: cancelDeveloperAction(database, configuration, actor, body) } };
    }],
    ["GET /developer/ai/status", async (request) => {
      consumeLimit(limits, "developer-ai", request, "", configuration.rateLimit.developerAi);
      requireDeveloperActor(request, database, configuration);
      return { status: 200, payload: { available: developerAi.isAvailable } };
    }],
    ["POST /developer/ai/turn", async (request) => {
      consumeLimit(limits, "developer-ai", request, "", configuration.rateLimit.developerAi);
      const actor = requireDeveloperActor(request, database, configuration);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      if (Object.keys(body).length !== 1 || typeof body.prompt !== "string") {
        recordDeveloperAiAudit(database, actor, "FAILURE", { resultCode: ErrorCode.DEVELOPER_TOOL_INPUT_INVALID });
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      return {
        status: 200,
        payload: await developerAi.runTurn({
          database, configuration, actor, prompt: body.prompt,
          context: { configuration, schemaVersion: SCHEMA_VERSION, developerAiProvider, securityEngine: security },
        }),
      };
    }],
    ["POST /developer/ai/security-summary", async (request) => {
      consumeLimit(limits, "developer-ai", request, "", configuration.rateLimit.developerAi);
      const actor = requireDeveloperActor(request, database, configuration);
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      if (Object.keys(body).some((key) => !["incidentId", "prompt"].includes(key))) {
        recordDeveloperAiSecurityAudit(database, actor, "FAILURE", { resultCode: ErrorCode.DEVELOPER_TOOL_INPUT_INVALID });
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
      // Message-only AI assistance over a sanitized brief. Detection and immediate protection have already run, and
      // continue to run without this provider.
      return {
        status: 200,
        payload: await developerAi.runSecurityAnalysis({
          database, actor, prompt: body.prompt ?? "", incidentId: body.incidentId ?? null,
          context: { configuration, schemaVersion: SCHEMA_VERSION, developerAiProvider, securityEngine: security },
        }),
      };
    }],
    ["GET /health", async () => ({ status: 200, payload: { status: "ok", service: "craftmind-auth", schemaVersion: SCHEMA_VERSION } })],
  ]);

  /**
   * Parameterized routes.
   *
   * The router is an exact-match map, which is what makes it auditable — but two resources are addressed by their own
   * public slug. Rather than loosen every route into a pattern, the three slug-addressed handlers live here and are
   * consulted only when the exact map misses. Two consequences are deliberate:
   *
   *   * the **route pattern** is what reaches the request log, never the slug, so a log line cannot become a list of
   *     every handle and workspace a caller probed;
   *   * the slug is decoded only by handler code that validates it, never by the router.
   */
  const dynamicRoutes = [
    {
      method: "GET",
      name: "/creators/:handle",
      pattern: /^\/creators\/([^/]{1,64})$/,
      handler: (request, _context, handle) => {
        consumeLimit(limits, "creator-public", request, "", configuration.rateLimit.creatorProfileRead);
        return { status: 200, payload: publicCreatorProfileForHandle(database, decodeURIComponent(handle)) };
      },
    },
    {
      method: "GET",
      name: "/servers/:slug",
      pattern: /^\/servers\/([^/]{1,64})$/,
      handler: (request, _context, slug) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "server-read", request, "", configuration.rateLimit.serverRead);
        return { status: 200, payload: serverForSession(database, configuration, token, decodeURIComponent(slug)) };
      },
    },
    {
      method: "PATCH",
      name: "/servers/:slug",
      pattern: /^\/servers\/([^/]{1,64})$/,
      handler: async (request, _context, slug) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "server-write", request, "", configuration.rateLimit.serverWrite);
        return {
          status: 200,
          payload: updateServerForSession(database, configuration, token, decodeURIComponent(slug), body),
        };
      },
    },
    // Phase 25 listing routes. The listing id is validated inside the handlers (shape + ownership); the router only
    // ever sees the route pattern in the request log, never a probeable id list.
    {
      method: "GET",
      name: "/creator/listing/:id",
      pattern: /^\/creator\/listing\/([^/]{1,64})$/,
      handler: (request, _context, listingId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "listing-read", request, "", configuration.rateLimit.creatorProfileRead);
        return { status: 200, payload: listingForSession(database, configuration, token, decodeURIComponent(listingId)) };
      },
    },
    {
      method: "PATCH",
      name: "/creator/listing/:id",
      pattern: /^\/creator\/listing\/([^/]{1,64})$/,
      handler: async (request, _context, listingId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "listing-write", request, "", configuration.rateLimit.creatorProfileWrite);
        return {
          status: 200,
          payload: updateListingForSession(database, configuration, token, decodeURIComponent(listingId), body),
        };
      },
    },
    {
      method: "POST",
      name: "/creator/listing/:id/publish",
      pattern: /^\/creator\/listing\/([^/]{1,64})\/publish$/,
      handler: (request, _context, listingId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "listing-write", request, "", configuration.rateLimit.creatorProfileWrite);
        return { status: 200, payload: publishListingForSession(database, configuration, token, decodeURIComponent(listingId)) };
      },
    },
    {
      method: "POST",
      name: "/creator/listing/:id/archive",
      pattern: /^\/creator\/listing\/([^/]{1,64})\/archive$/,
      handler: (request, _context, listingId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "listing-write", request, "", configuration.rateLimit.creatorProfileWrite);
        return { status: 200, payload: archiveListingForSession(database, configuration, token, decodeURIComponent(listingId)) };
      },
    },
    {
      method: "GET",
      name: "/marketplace/listings/:id",
      pattern: /^\/marketplace\/listings\/([^/]{1,64})$/,
      handler: (request, _context, listingId) => {
        consumeLimit(limits, "listing-search", request, "", configuration.rateLimit.creatorProfileRead);
        return { status: 200, payload: publicListing(database, decodeURIComponent(listingId)) };
      },
    },
    // Phase 26 hire routes. Job and proposal ids are validated inside the handlers (shape + ownership); the
    // router only ever sees the route pattern in the request log, never a probeable id list.
    {
      method: "GET",
      name: "/buyer/jobs/:id",
      pattern: /^\/buyer\/jobs\/([^/]{1,64})$/,
      handler: (request, _context, jobId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "hire-read", request, "", configuration.rateLimit.creatorProfileRead);
        return { status: 200, payload: ownJobDetailForSession(database, configuration, token, decodeURIComponent(jobId)) };
      },
    },
    {
      method: "PATCH",
      name: "/buyer/jobs/:id",
      pattern: /^\/buyer\/jobs\/([^/]{1,64})$/,
      handler: async (request, _context, jobId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "job-write", request, "", configuration.rateLimit.jobWrite);
        return {
          status: 200,
          payload: updateJobForSession(database, configuration, token, decodeURIComponent(jobId), body),
        };
      },
    },
    {
      method: "POST",
      name: "/buyer/jobs/:id/cancel",
      pattern: /^\/buyer\/jobs\/([^/]{1,64})\/cancel$/,
      handler: (request, _context, jobId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "job-write", request, "", configuration.rateLimit.jobWrite);
        return { status: 200, payload: cancelJobForSession(database, configuration, token, decodeURIComponent(jobId)) };
      },
    },
    {
      method: "POST",
      name: "/buyer/jobs/:id/award",
      pattern: /^\/buyer\/jobs\/([^/]{1,64})\/award$/,
      handler: async (request, _context, jobId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "job-write", request, "", configuration.rateLimit.jobWrite);
        return {
          status: 200,
          payload: awardJobForSession(database, configuration, token, decodeURIComponent(jobId), body),
        };
      },
    },
    {
      method: "GET",
      name: "/marketplace/jobs/:id",
      pattern: /^\/marketplace\/jobs\/([^/]{1,64})$/,
      handler: (request, _context, jobId) => {
        consumeLimit(limits, "job-search", request, "", configuration.rateLimit.creatorProfileRead);
        return { status: 200, payload: publicJob(database, decodeURIComponent(jobId)) };
      },
    },
    {
      method: "GET",
      name: "/creator/proposal/:id",
      pattern: /^\/creator\/proposal\/([^/]{1,64})$/,
      handler: (request, _context, proposalId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "hire-read", request, "", configuration.rateLimit.creatorProfileRead);
        return { status: 200, payload: ownProposalDetailForSession(database, configuration, token, decodeURIComponent(proposalId)) };
      },
    },
    {
      method: "PATCH",
      name: "/creator/proposal/:id",
      pattern: /^\/creator\/proposal\/([^/]{1,64})$/,
      handler: async (request, _context, proposalId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "proposal-write", request, "", configuration.rateLimit.proposalWrite);
        return {
          status: 200,
          payload: updateProposalForSession(database, configuration, token, decodeURIComponent(proposalId), body),
        };
      },
    },
    {
      method: "POST",
      name: "/creator/proposal/:id/withdraw",
      pattern: /^\/creator\/proposal\/([^/]{1,64})\/withdraw$/,
      handler: (request, _context, proposalId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "proposal-write", request, "", configuration.rateLimit.proposalWrite);
        return { status: 200, payload: withdrawProposalForSession(database, configuration, token, decodeURIComponent(proposalId)) };
      },
    },
    // Phase 27 order routes. Order and milestone ids are validated inside the handlers (shape + participant
    // ownership → uniform 404); the router only ever sees the route pattern in the request log.
    {
      method: "GET",
      name: "/orders/:id",
      pattern: /^\/orders\/([^/]{1,64})$/,
      handler: (request, _context, orderId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "order-read", request, "", configuration.rateLimit.creatorProfileRead);
        return { status: 200, payload: orderDetailForSession(database, configuration, token, decodeURIComponent(orderId)) };
      },
    },
    {
      method: "GET",
      name: "/orders/:id/history",
      pattern: /^\/orders\/([^/]{1,64})\/history$/,
      handler: (request, _context, orderId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "order-read", request, "", configuration.rateLimit.creatorProfileRead);
        const searchParams = new URL(request.url ?? "/", "http://orders.local").searchParams;
        return { status: 200, payload: orderHistoryForSession(database, configuration, token, decodeURIComponent(orderId), searchParams) };
      },
    },
    {
      method: "POST",
      name: "/orders/:id/complete",
      pattern: /^\/orders\/([^/]{1,64})\/complete$/,
      handler: async (request, _context, orderId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "order-write", request, "", configuration.rateLimit.orderWrite);
        return { status: 200, payload: completeOrderForSession(database, configuration, token, decodeURIComponent(orderId), body) };
      },
    },
    {
      method: "POST",
      name: "/orders/:id/cancel",
      pattern: /^\/orders\/([^/]{1,64})\/cancel$/,
      handler: async (request, _context, orderId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "order-write", request, "", configuration.rateLimit.orderWrite);
        return { status: 200, payload: cancelOrderForSession(database, configuration, token, decodeURIComponent(orderId), body) };
      },
    },
    {
      method: "POST",
      name: "/orders/:id/milestones/:mid/start",
      pattern: /^\/orders\/([^/]{1,64})\/milestones\/([^/]{1,64})\/start$/,
      handler: async (request, _context, orderId, milestoneId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "milestone-write", request, "", configuration.rateLimit.orderWrite);
        return { status: 200, payload: startMilestoneForSession(database, configuration, token, decodeURIComponent(orderId), decodeURIComponent(milestoneId), body) };
      },
    },
    {
      method: "POST",
      name: "/orders/:id/milestones/:mid/deliver",
      pattern: /^\/orders\/([^/]{1,64})\/milestones\/([^/]{1,64})\/deliver$/,
      handler: async (request, _context, orderId, milestoneId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "delivery-write", request, "", configuration.rateLimit.deliveryWrite);
        return { status: 201, payload: submitDeliveryForSession(database, configuration, token, decodeURIComponent(orderId), decodeURIComponent(milestoneId), body) };
      },
    },
    {
      method: "POST",
      name: "/orders/:id/milestones/:mid/revision",
      pattern: /^\/orders\/([^/]{1,64})\/milestones\/([^/]{1,64})\/revision$/,
      handler: async (request, _context, orderId, milestoneId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "revision-write", request, "", configuration.rateLimit.revisionWrite);
        return { status: 200, payload: requestRevisionForSession(database, configuration, token, decodeURIComponent(orderId), decodeURIComponent(milestoneId), body) };
      },
    },
    {
      method: "POST",
      name: "/orders/:id/milestones/:mid/approve",
      pattern: /^\/orders\/([^/]{1,64})\/milestones\/([^/]{1,64})\/approve$/,
      handler: async (request, _context, orderId, milestoneId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "milestone-write", request, "", configuration.rateLimit.orderWrite);
        return { status: 200, payload: approveMilestoneForSession(database, configuration, token, decodeURIComponent(orderId), decodeURIComponent(milestoneId), body) };
      },
    },
    // Phase 30 trust and dispute routes. Report and dispute ids are validated inside the handlers (shape + a
    // relationship the caller actually holds); the router only ever sees the route pattern in the request log, never a
    // probeable id, and every actor is the session account. No path here reads or writes money.
    {
      method: "POST",
      name: "/marketplace/reports/:id/withdraw",
      pattern: /^\/marketplace\/reports\/([^/]{1,64})\/withdraw$/,
      handler: (request, _context, reportId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "trust-write", request, "", configuration.rateLimit.trustWrite);
        return { status: 200, payload: withdrawReportForSession(database, configuration, token, decodeURIComponent(reportId)) };
      },
    },
    {
      method: "POST",
      name: "/orders/:id/disputes",
      pattern: /^\/orders\/([^/]{1,64})\/disputes$/,
      handler: async (request, _context, orderId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "dispute-write", request, "", configuration.rateLimit.disputeWrite);
        return { status: 201, payload: openDisputeForSession(database, configuration, token, decodeURIComponent(orderId), body) };
      },
    },
    {
      method: "GET",
      name: "/orders/:id/disputes",
      pattern: /^\/orders\/([^/]{1,64})\/disputes$/,
      handler: (request, _context, orderId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "dispute-read", request, "", configuration.rateLimit.creatorProfileRead);
        return { status: 200, payload: listOrderDisputesForSession(database, configuration, token, decodeURIComponent(orderId)) };
      },
    },
    {
      method: "GET",
      name: "/orders/:id/disputes/:did",
      pattern: /^\/orders\/([^/]{1,64})\/disputes\/([^/]{1,64})$/,
      handler: (request, _context, orderId, disputeId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "dispute-read", request, "", configuration.rateLimit.creatorProfileRead);
        return { status: 200, payload: disputeDetailForSession(database, configuration, token, decodeURIComponent(orderId), decodeURIComponent(disputeId)) };
      },
    },
    {
      method: "POST",
      name: "/orders/:id/disputes/:did/statements",
      pattern: /^\/orders\/([^/]{1,64})\/disputes\/([^/]{1,64})\/statements$/,
      handler: async (request, _context, orderId, disputeId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "dispute-write", request, "", configuration.rateLimit.disputeWrite);
        return { status: 201, payload: addDisputeStatementForSession(database, configuration, token, decodeURIComponent(orderId), decodeURIComponent(disputeId), body) };
      },
    },
    {
      method: "POST",
      name: "/orders/:id/disputes/:did/position",
      pattern: /^\/orders\/([^/]{1,64})\/disputes\/([^/]{1,64})\/position$/,
      handler: async (request, _context, orderId, disputeId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        const body = await readJsonBody(request, configuration.maxBodyBytes);
        consumeLimit(limits, "dispute-write", request, "", configuration.rateLimit.disputeWrite);
        return { status: 200, payload: setDisputePositionForSession(database, configuration, token, decodeURIComponent(orderId), decodeURIComponent(disputeId), body) };
      },
    },
    {
      method: "POST",
      name: "/orders/:id/disputes/:did/withdraw",
      pattern: /^\/orders\/([^/]{1,64})\/disputes\/([^/]{1,64})\/withdraw$/,
      handler: (request, _context, orderId, disputeId) => {
        const token = bearerToken(request);
        if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
        consumeLimit(limits, "dispute-write", request, "", configuration.rateLimit.disputeWrite);
        return { status: 200, payload: withdrawDisputeForSession(database, configuration, token, decodeURIComponent(orderId), decodeURIComponent(disputeId)) };
      },
    },
  ];
  /**
   * Method-aware matching, and deliberately so: a pattern match without a method match must not run a handler at all.
   * `PATCH /servers/:slug` is an update; answering it with the read handler would ignore the request body, report
   * success, and return unchanged state — the most dangerous kind of wrong answer, because the caller believes a write
   * happened. When the pattern matches and the method does not, the caller raises `METHOD_NOT_ALLOWED` instead.
   */
  const matchDynamicRoute = (method, pathKey) => {
    for (const route of dynamicRoutes) {
      if (route.method !== method) continue;
      const match = route.pattern.exec(pathKey);
      if (match) return { route, params: match.slice(1) };
    }
    return null;
  };
  /** The pattern that owns a path, for logging and for method-mismatch decisions only. Never the slug itself. */
  const dynamicPatternFor = (pathKey) => dynamicRoutes.find((route) => route.pattern.test(pathKey))?.name ?? null;
  const dynamicPathMatches = (pathKey) => dynamicRoutes.some((route) => route.pattern.test(pathKey));
  const dynamicMethodAllowed = (pathKey, method) =>
    dynamicRoutes.some((route) => route.method === method && route.pattern.test(pathKey));

  const methodsByPath = new Map();
  for (const key of routes.keys()) {
    const [method, path] = key.split(" ");
    if (!methodsByPath.has(path)) methodsByPath.set(path, new Set());
    methodsByPath.get(path).add(method);
  }

  const server = createServer(async (request, response) => {
    const requestId = randomUUID();
    const started = Date.now();
    const path = safeRoutePath(request);
    let logRoute = "/unmatched";
    let status = 500;
    let corsHeaders = {};
    try {
      corsHeaders = {
        ...applyCors(request, configuration),
        ...(configuration.production ? { "Strict-Transport-Security": "max-age=31536000; includeSubDomains" } : {}),
      };
      enforceHttps(request, configuration);
      const pathKey = path.replace(/\/+$/, "") || "/";
      if (routes.has(`${request.method} ${pathKey}`) || methodsByPath.has(pathKey)) logRoute = pathKey;
      else if (dynamicPathMatches(pathKey)) {
        // The pattern is logged, the slug is not: a request log must not become a directory of creator handles, and a
        // mismatched method must not be logged as though the request had reached a handler.
        logRoute = dynamicPatternFor(pathKey) ?? "/unmatched";
      }
      if (isSecuritySensitiveRoute(pathKey) && request.method !== "OPTIONS") {
        // Autonomous protections for the request source, then (when a bearer credential is presented) for the account
        // or session it resolves to. This is the enforcement half of the response policy; it never grants anything.
        let presentedToken = null;
        try { presentedToken = bearerToken(request) ?? null; } catch { presentedToken = null; }
        enforceRequestProtections(request, pathKey, {
          token: presentedToken,
          kind: pathKey.startsWith("/developer") ? "developer" : "user",
        });
      }
      if (request.method === "OPTIONS") {
        const requestedMethod = String(request.headers["access-control-request-method"] ?? "");
        if (!methodsByPath.get(pathKey)?.has(requestedMethod) && !dynamicMethodAllowed(pathKey, requestedMethod)) {
          throw new AccountApiError(ErrorCode.METHOD_NOT_ALLOWED);
        }
        status = 204;
        sendNoContent(response, requestId, corsHeaders);
        return;
      }
      let handler = routes.get(`${request.method} ${pathKey}`);
      if (!handler) {
        const matched = matchDynamicRoute(request.method, pathKey);
        if (matched) {
          const { route, params } = matched;
          handler = (dynamicRequest, context) => route.handler(dynamicRequest, context, ...params);
        }
      }
      if (!handler) {
        throw methodsByPath.has(pathKey) || dynamicPathMatches(pathKey)
          ? new AccountApiError(ErrorCode.METHOD_NOT_ALLOWED)
          : new AccountApiError(ErrorCode.INVALID_REQUEST, "Unknown account endpoint.");
      }
      const { status: handlerStatus, payload, asset } = await handler(request, { requestId });
      status = handlerStatus;
      if (asset) sendDeveloperAsset(response, status, asset.body, asset.contentType, requestId, corsHeaders);
      else sendJson(response, status, payload, requestId, corsHeaders);
    } catch (error) {
      const typed = error instanceof AccountApiError ? error : new AccountApiError(ErrorCode.UNKNOWN_ERROR);
      status = typed.status;
      // The single security ingestion point for rejected requests. Account/session references are resolved internally
      // and stored only as opaque identifiers. A route that already recorded a richer signal (it knows the attempted
      // account) marks the error so the same failure is never counted twice.
      try {
        if (error instanceof AccountApiError && error.securityRecorded !== true) {
          let presentedToken = null;
          try { presentedToken = bearerToken(request) ?? null; } catch { presentedToken = null; }
          recordFailureSignal(request, path.replace(/\/+$/, "") || "/", error, {
            token: presentedToken,
            kind: path.startsWith("/developer") ? "developer" : "user",
          });
        }
      } catch (signalError) {
        logger.warn?.(JSON.stringify({ event: "security_signal_failed", errorType: signalError?.name ?? "Error" }));
      }
      if (!(error instanceof AccountApiError)) {
        // The class name is safe; the message may contain a body or provider response and is discarded.
        logger.error?.(JSON.stringify({ event: "account_request_internal_failure", errorType: error?.name ?? "Error", requestId }));
      }
      sendError(response, typed, requestId, corsHeaders);
    } finally {
      const logEntry = {
        event: "account_http_request",
        method: request.method,
        route: logRoute,
        status,
        durationMs: Date.now() - started,
        requestId,
      };
      logger.info?.(JSON.stringify(logEntry));
    }
  });

  server.once("close", () => clearInterval(cleanupTimer));
  return server;
}
