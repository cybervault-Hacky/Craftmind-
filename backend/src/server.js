/**
 * HTTP surface for the account service.
 *
 * The router is intentionally small: a fixed table of endpoints, a bounded JSON body reader, typed errors, and response
 * headers that keep account data out of caches. There is no plugin system, no third-party framework, and no
 * server-rendered page — this service is an API for the CraftMind app, not a website, and it is not a browser login
 * façade.
 *
 * Nothing here logs a request body. A failed request is answered with a typed code and a safe message; the underlying
 * exception is never echoed to the client.
 */

import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { AccountApiError, ErrorCode } from "./errors.js";
import {
  currentAccount,
  login,
  logout,
  recordGuestIdentity,
  refreshSession,
  registerAccount,
} from "./accounts.js";

const JSON_HEADERS = Object.freeze({
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
  Pragma: "no-cache",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "X-Frame-Options": "DENY",
});

function sendJson(response, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), "utf8");
  response.writeHead(status, { ...JSON_HEADERS, "Content-Length": String(body.byteLength) });
  response.end(body);
}

function sendError(response, error, requestId) {
  const typed = error instanceof AccountApiError ? error : new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  sendJson(response, typed.status, {
    error: { code: typed.code, message: typed.message, requestId },
  });
}

/** Reads a JSON body with a hard size cap, so an oversized request cannot exhaust memory. */
async function readJsonBody(request, maxBodyBytes) {
  const chunks = [];
  let total = 0;
  for await (const chunk of request) {
    total += chunk.length;
    if (total > maxBodyBytes) throw new AccountApiError(ErrorCode.REQUEST_TOO_LARGE);
    chunks.push(chunk);
  }
  if (total === 0) return {};
  const text = Buffer.concat(chunks).toString("utf8");
  try {
    const parsed = JSON.parse(text);
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new AccountApiError(ErrorCode.INVALID_REQUEST, "the request body must be a JSON object");
    }
    return parsed;
  } catch (error) {
    if (error instanceof AccountApiError) throw error;
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

/**
 * @param {{ database: import('node:sqlite').DatabaseSync, configuration: object, logger?: object }} options
 */
export function createAccountService({ database, configuration, logger = console }) {
  const routes = new Map([
    ["POST /auth/register", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      const result = await registerAccount(database, configuration, body);
      return { status: 201, payload: result };
    }],
    ["POST /auth/login", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      const result = await login(database, configuration, body);
      return { status: 200, payload: result };
    }],
    ["POST /auth/refresh", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      return { status: 200, payload: refreshSession(database, configuration, body) };
    }],
    ["POST /auth/logout", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      return { status: 200, payload: logout(database, configuration, body) };
    }],
    ["GET /auth/me", async (request) => {
      const token = bearerToken(request);
      if (token === undefined) throw new AccountApiError(ErrorCode.AUTHENTICATION_REQUIRED);
      return { status: 200, payload: currentAccount(database, configuration, token) };
    }],
    ["POST /auth/guest", async (request) => {
      const body = await readJsonBody(request, configuration.maxBodyBytes);
      if (typeof body.guestIdentityId !== "string") throw new AccountApiError(ErrorCode.INVALID_REQUEST);
      return { status: 201, payload: { guest: recordGuestIdentity(database, body.guestIdentityId) } };
    }],
    // A contract, not a feature: CraftMind has no email delivery yet, so password reset cannot be completed honestly.
    // The endpoint exists so a client can discover that fact in a typed way instead of guessing from a 404.
    ["POST /auth/password-reset", async () => {
      throw new AccountApiError(ErrorCode.PASSWORD_RESET_NOT_IMPLEMENTED);
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
    let status = 500;
    try {
      const url = new URL(request.url ?? "/", "http://localhost");
      const path = url.pathname.replace(/\/+$/, "") || "/";
      const handler = routes.get(`${request.method} ${path}`);
      if (!handler) {
        throw methodsByPath.has(path)
          ? new AccountApiError(ErrorCode.METHOD_NOT_ALLOWED)
          : new AccountApiError(ErrorCode.INVALID_REQUEST, "unknown endpoint");
      }
      const { status: handlerStatus, payload } = await handler(request);
      status = handlerStatus;
      sendJson(response, status, payload);
    } catch (error) {
      const typed = error instanceof AccountApiError ? error : new AccountApiError(ErrorCode.UNKNOWN_ERROR);
      status = typed.status;
      if (!(error instanceof AccountApiError)) {
        // The cause is recorded as a class name only: an exception message can contain a request body, and request
        // bodies here can contain passwords.
        logger.error?.(`account-service request failed: ${error?.name ?? "Error"}`);
      }
      sendError(response, typed, requestId);
    } finally {
      // Access log: method, path, status, duration. Never the body, never an email address, never a token.
      logger.info?.(`${request.method} ${request.url?.split("?")[0]} ${status} ${Date.now() - started}ms`);
    }
  });

  return server;
}
