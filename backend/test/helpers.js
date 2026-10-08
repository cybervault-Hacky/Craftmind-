/** Deterministic local HTTP/SQLite test harness; email is captured by a non-delivering in-memory sink. */

import { loadConfiguration } from "../src/config.js";
import { openDatabase } from "../src/db.js";
import { DevelopmentEmailSink } from "../src/email-delivery.js";
import { createAccountService } from "../src/server.js";

export const TEST_SECRET = "test-secret-that-is-long-enough-for-configuration-validation";
const servicesByUrl = new Map();

export async function startService(overrides = {}, { emailDelivery = new DevelopmentEmailSink(), developerAiProvider = null, rateLimiter = undefined } = {}) {
  const configuration = loadConfiguration({
    NODE_ENV: "test",
    DATABASE_URL: ":memory:",
    AUTH_SECRET: TEST_SECRET,
    ACCESS_TOKEN_TTL_SECONDS: "3600",
    REFRESH_TOKEN_TTL_SECONDS: "2592000",
    MAX_BODY_BYTES: "16384",
    RATE_VERIFY_MAX: "1000",
    RATE_RESEND_MAX: "1000",
    RATE_REGISTER_MAX: "1000",
    RATE_LOGIN_MAX: "1000",
    RATE_RESET_REQUEST_MAX: "1000",
    RATE_RESET_CONFIRM_MAX: "1000",
    ...overrides,
  }, { allowInMemoryDatabase: true });
  const database = openDatabase(configuration.databaseUrl);
  const logs = [];
  const logger = {
    info: (message) => logs.push(`info ${message}`),
    warn: (message) => logs.push(`warn ${message}`),
    error: (message) => logs.push(`error ${message}`),
  };
  const server = createAccountService({ database, configuration, logger, emailDelivery, developerAiProvider, rateLimiter });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;
  const service = {
    baseUrl, database, configuration, logs, emailDelivery,
    async close() {
      servicesByUrl.delete(baseUrl);
      await new Promise((resolve) => server.close(resolve));
      database.close();
    },
  };
  servicesByUrl.set(baseUrl, service);
  return service;
}

export async function call(baseUrl, method, path, { body, headers = {}, raw } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: body === undefined && raw === undefined ? headers : { "Content-Type": "application/json", ...headers },
    body: raw !== undefined ? raw : body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let parsed = null;
  try { parsed = text.length > 0 ? JSON.parse(text) : null; }
  catch { parsed = { unparsed: text }; }
  return { status: response.status, body: parsed, headers: response.headers };
}

export const VALID_PASSWORD = "Correct Horse 7Battery";

export function registrationBody(overrides = {}) {
  return { email: "builder@example.com", password: VALID_PASSWORD, displayName: "Builder", ...overrides };
}

/** Calls the actual registration endpoint; it does not verify or sign in. */
export async function register(baseUrl, overrides = {}) {
  return call(baseUrl, "POST", "/auth/register", { body: registrationBody(overrides) });
}

/** Drives registration through the test-only mail sink and then logs in, for tests requiring an active session. */
export async function registerVerified(service, overrides = {}) {
  const input = registrationBody(overrides);
  const created = await register(service.baseUrl, input);
  if (created.status !== 201) return created;
  const email = input.email.trim();
  const message = service.emailDelivery.takeMessage("verification", email);
  if (!message) throw new Error("test email sink did not capture a verification message");
  const verified = await call(service.baseUrl, "POST", "/auth/verify-email", { body: { token: message.token } });
  if (verified.status !== 200) throw new Error("test verification did not complete");
  const signedIn = await loginCall(service.baseUrl, input);
  return {
    ...signedIn,
    status: 201,
    body: { ...signedIn.body, guestLinked: created.body.guestLinked, verificationRequired: true },
    registration: created,
  };
}

export async function loginCall(baseUrl, overrides = {}) {
  return call(baseUrl, "POST", "/auth/login", { body: registrationBody(overrides) });
}

export function guestIdentity(seed = "guest") {
  return `${seed}-identity-0123456789abcdef`.slice(0, 48).padEnd(24, "0");
}
