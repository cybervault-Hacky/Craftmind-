/**
 * Configuration for the CraftMind account service.
 *
 * Every value comes from the environment. There are no built-in credentials, no default secret, and no fallback that
 * would let the service start insecurely: a missing `AUTH_SECRET` is a startup error, not a warning.
 */

const MINIMUM_SECRET_LENGTH = 32;

export class ConfigurationError extends Error {
  constructor(message) {
    super(message);
    this.name = "ConfigurationError";
  }
}

function requirePositiveInteger(environment, name, fallback) {
  const raw = environment[name];
  if (raw === undefined || raw === "") return fallback;
  const value = Number.parseInt(raw, 10);
  if (!Number.isFinite(value) || value <= 0) {
    throw new ConfigurationError(`${name} must be a positive integer`);
  }
  return value;
}

/**
 * Loads and validates configuration.
 *
 * @param {NodeJS.ProcessEnv} environment
 * @param {{ allowInMemoryDatabase?: boolean }} options
 */
export function loadConfiguration(environment = process.env, options = {}) {
  const databaseUrl = (environment.DATABASE_URL ?? "").trim();
  if (databaseUrl === "") {
    throw new ConfigurationError("DATABASE_URL is required (path to the SQLite database file)");
  }
  if (databaseUrl === ":memory:" && options.allowInMemoryDatabase !== true) {
    throw new ConfigurationError("DATABASE_URL must point to a file; in-memory databases are for tests only");
  }

  const authSecret = environment.AUTH_SECRET ?? "";
  if (authSecret.length < MINIMUM_SECRET_LENGTH) {
    throw new ConfigurationError(
      `AUTH_SECRET must be set and at least ${MINIMUM_SECRET_LENGTH} characters long`,
    );
  }

  return Object.freeze({
    databaseUrl,
    authSecret,
    host: (environment.HOST ?? "127.0.0.1").trim(),
    port: Number.parseInt(environment.PORT ?? "8787", 10),
    accessTokenTtlSeconds: requirePositiveInteger(environment, "ACCESS_TOKEN_TTL_SECONDS", 3600),
    refreshTokenTtlSeconds: requirePositiveInteger(environment, "REFRESH_TOKEN_TTL_SECONDS", 2592000),
    maxBodyBytes: requirePositiveInteger(environment, "MAX_BODY_BYTES", 16384),
  });
}

export { MINIMUM_SECRET_LENGTH };
