/**
 * Test harness for the account service.
 *
 * Each test gets its own in-memory database and its own listening socket on an ephemeral port, so tests cannot leak
 * state into one another. The logger is captured rather than printed, which lets the security tests assert that no
 * credential ever reaches a log line.
 */

import { loadConfiguration } from "../src/config.js";
import { openDatabase } from "../src/db.js";
import { createAccountService } from "../src/server.js";

export const TEST_SECRET = "test-secret-that-is-long-enough-for-configuration-validation";

export async function startService(overrides = {}) {
  const configuration = loadConfiguration(
    {
      DATABASE_URL: ":memory:",
      AUTH_SECRET: TEST_SECRET,
      ACCESS_TOKEN_TTL_SECONDS: "3600",
      REFRESH_TOKEN_TTL_SECONDS: "2592000",
      MAX_BODY_BYTES: "16384",
      ...overrides,
    },
    { allowInMemoryDatabase: true },
  );
  const database = openDatabase(configuration.databaseUrl);
  const logs = [];
  const logger = {
    info: (message) => logs.push(`info ${message}`),
    error: (message) => logs.push(`error ${message}`),
  };
  const server = createAccountService({ database, configuration, logger });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    database,
    configuration,
    logs,
    async close() {
      await new Promise((resolve) => server.close(resolve));
      database.close();
    },
  };
}

/** Performs a request and returns status plus parsed JSON, without throwing on error statuses. */
export async function call(baseUrl, method, path, { body, headers = {}, raw } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: body === undefined && raw === undefined ? headers : { "Content-Type": "application/json", ...headers },
    body: raw !== undefined ? raw : body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let parsed = null;
  try {
    parsed = text.length > 0 ? JSON.parse(text) : null;
  } catch {
    parsed = { unparsed: text };
  }
  return { status: response.status, body: parsed, headers: response.headers };
}

export const VALID_PASSWORD = "Correct Horse 7Battery";

export function registrationBody(overrides = {}) {
  return { email: "builder@example.com", password: VALID_PASSWORD, displayName: "Builder", ...overrides };
}

export async function register(baseUrl, overrides = {}) {
  return call(baseUrl, "POST", "/auth/register", { body: registrationBody(overrides) });
}

export async function loginCall(baseUrl, overrides = {}) {
  return call(baseUrl, "POST", "/auth/login", { body: registrationBody(overrides) });
}

export function guestIdentity(seed = "guest") {
  // Same shape the app produces: opaque, URL-safe, 22–64 characters.
  return `${seed}-identity-0123456789abcdef`.slice(0, 48).padEnd(24, "0");
}
