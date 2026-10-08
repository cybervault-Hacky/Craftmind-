/**
 * Hardened JSON API for CraftMind account identity. No request bodies, email addresses, query strings, tokens, or
 * exception messages are written to the service log.
 */

import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
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
import { InMemoryRateLimiter } from "./rate-limiter.js";

const NON_ENUMERATING_RESPONSE_FLOOR_MS = 150;

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

function safeRoutePath(request) {
  try { return new URL(request.url ?? "/", "http://internal.invalid").pathname; }
  catch { return "/invalid-url"; }
}

function applyCors(request, configuration) {
  const origin = request.headers.origin;
  if (origin === undefined) return {};
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

async function enforceNonEnumeratingResponseFloor(startedAt) {
  const remaining = startedAt + NON_ENUMERATING_RESPONSE_FLOOR_MS - Date.now();
  if (remaining > 0) await new Promise((resolve) => setTimeout(resolve, remaining));
}

function statusForEmailDelivery(result, emailDelivery) {
  if (result === "FAILED") return "UNAVAILABLE";
  return emailDelivery.mode === "DEVELOPMENT_SINK" ? "DEVELOPMENT_SINK" : "PROVIDER_ACCEPTED";
}

/** @param {{ database: import('node:sqlite').DatabaseSync, configuration: object, logger?: object, emailDelivery?: object, rateLimiter?: object }} options */
export function createAccountService({ database, configuration, logger = console, emailDelivery, rateLimiter }) {
  const mail = emailDelivery ?? createEmailDelivery(configuration);
  const limits = rateLimiter ?? new InMemoryRateLimiter({ authSecret: configuration.authSecret });
  cleanupExpiredAccountRecords(database);
  const cleanupTimer = setInterval(() => {
    try {
      cleanupExpiredAccountRecords(database);
    } catch (error) {
      logger.error?.(JSON.stringify({ event: "account_expiry_cleanup_failed", errorType: error?.name ?? "Error" }));
    }
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
      return { status: 200, payload: await login(database, configuration, body) };
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
    ["GET /health", async () => ({ status: 200, payload: { status: "ok", service: "craftmind-auth" } })],
  ]);

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
      if (request.method === "OPTIONS") {
        const requestedMethod = String(request.headers["access-control-request-method"] ?? "");
        if (!methodsByPath.get(pathKey)?.has(requestedMethod)) throw new AccountApiError(ErrorCode.METHOD_NOT_ALLOWED);
        status = 204;
        sendNoContent(response, requestId, corsHeaders);
        return;
      }
      const handler = routes.get(`${request.method} ${pathKey}`);
      if (!handler) {
        throw methodsByPath.has(pathKey)
          ? new AccountApiError(ErrorCode.METHOD_NOT_ALLOWED)
          : new AccountApiError(ErrorCode.INVALID_REQUEST, "Unknown account endpoint.");
      }
      const { status: handlerStatus, payload } = await handler(request, { requestId });
      status = handlerStatus;
      sendJson(response, status, payload, requestId, corsHeaders);
    } catch (error) {
      const typed = error instanceof AccountApiError ? error : new AccountApiError(ErrorCode.UNKNOWN_ERROR);
      status = typed.status;
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
