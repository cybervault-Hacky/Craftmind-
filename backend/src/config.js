/**
 * Environment-only service configuration. Production requires an HTTPS front door and a real email provider; the
 * in-memory development sink is never selected as a production fallback.
 */

import { isWellFormedEmail } from "./ids.js";

const MINIMUM_SECRET_LENGTH = 32;
const MINIMUM_PROVIDER_TOKEN_LENGTH = 24;
const MINIMUM_BOOTSTRAP_SECRET_LENGTH = 43;
const MINIMUM_DEVELOPER_AI_KEY_LENGTH = 24;

export class ConfigurationError extends Error {
  constructor(message) {
    super(message);
    this.name = "ConfigurationError";
  }
}

function positiveInteger(environment, name, fallback, maximum = Number.MAX_SAFE_INTEGER) {
  const raw = environment[name];
  if (raw === undefined || raw === "") return fallback;
  if (!/^[0-9]+$/.test(raw)) throw new ConfigurationError(`${name} must be a positive integer`);
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0 || value > maximum) {
    throw new ConfigurationError(`${name} must be a positive integer within its supported range`);
  }
  return value;
}

function httpsUrl(environment, name, required = false) {
  const raw = (environment[name] ?? "").trim();
  if (!raw) {
    if (required) throw new ConfigurationError(`${name} must be an absolute HTTPS URL`);
    return null;
  }
  let parsed;
  try {
    parsed = new URL(raw);
  } catch {
    throw new ConfigurationError(`${name} must be an absolute HTTPS URL`);
  }
  if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.hash) {
    throw new ConfigurationError(`${name} must be an absolute HTTPS URL without credentials or a fragment`);
  }
  return parsed.toString().replace(/\/$/, "");
}

function isCanonicalBootstrapSecret(value) {
  if (typeof value !== "string" || value.length < MINIMUM_BOOTSTRAP_SECRET_LENGTH || value.length > 256 ||
      !/^[A-Za-z0-9_-]+$/.test(value)) return false;
  const decoded = Buffer.from(value, "base64url");
  return decoded.length >= 32 && decoded.length <= 192 && decoded.toString("base64url") === value;
}

function originList(environment) {
  const raw = (environment.CORS_ALLOWED_ORIGINS ?? "").trim();
  if (!raw) return Object.freeze([]);
  const origins = raw.split(",").map((entry) => entry.trim()).filter(Boolean);
  const normalized = origins.map((origin) => {
    let parsed;
    try {
      parsed = new URL(origin);
    } catch {
      throw new ConfigurationError("CORS_ALLOWED_ORIGINS must contain absolute HTTPS origins");
    }
    if (parsed.protocol !== "https:" || parsed.origin !== origin || parsed.username || parsed.password || parsed.pathname !== "/" || parsed.search || parsed.hash) {
      throw new ConfigurationError("CORS_ALLOWED_ORIGINS must contain exact HTTPS origins, not wildcards or paths");
    }
    return parsed.origin;
  });
  return Object.freeze([...new Set(normalized)]);
}

/** @param {NodeJS.ProcessEnv} environment */
export function loadConfiguration(environment = process.env, options = {}) {
  const nodeEnvironment = (environment.NODE_ENV ?? "development").trim().toLowerCase();
  const production = nodeEnvironment === "production";
  const databaseUrl = (environment.DATABASE_URL ?? "").trim();
  if (!databaseUrl) throw new ConfigurationError("DATABASE_URL is required (path to a persistent SQLite database file)");
  if (databaseUrl === ":memory:" && (production || options.allowInMemoryDatabase !== true)) {
    throw new ConfigurationError("DATABASE_URL must point to a file; in-memory databases are for tests only");
  }

  const authSecret = environment.AUTH_SECRET ?? "";
  if (authSecret.length < MINIMUM_SECRET_LENGTH) {
    throw new ConfigurationError(`AUTH_SECRET must be set and at least ${MINIMUM_SECRET_LENGTH} characters long`);
  }

  const emailProvider = (environment.EMAIL_PROVIDER ?? (production ? "" : "memory")).trim().toLowerCase();
  if (production && emailProvider !== "webhook") {
    throw new ConfigurationError("production requires EMAIL_PROVIDER=webhook; the development sink cannot deliver production email");
  }
  if (!production && !["memory", "webhook"].includes(emailProvider)) {
    throw new ConfigurationError("EMAIL_PROVIDER must be memory or webhook outside production");
  }
  if (emailProvider === "memory" && production) {
    throw new ConfigurationError("the in-memory email sink is development/test-only");
  }

  const emailWebhookUrl = emailProvider === "webhook" ? httpsUrl(environment, "EMAIL_WEBHOOK_URL", true) : null;
  const emailWebhookToken = (environment.EMAIL_WEBHOOK_TOKEN ?? "").trim();
  const emailFrom = (environment.EMAIL_FROM ?? "").trim();
  if (emailProvider === "webhook") {
    if (emailWebhookToken.length < MINIMUM_PROVIDER_TOKEN_LENGTH) {
      throw new ConfigurationError(`EMAIL_WEBHOOK_TOKEN must be set and at least ${MINIMUM_PROVIDER_TOKEN_LENGTH} characters long`);
    }
    if (!/^[^\s@]+@[^\s@.]+(?:\.[^\s@.]+)+$/.test(emailFrom) || emailFrom.length > 254) {
      throw new ConfigurationError("EMAIL_FROM must be a valid sender email address");
    }
  }

  const publicOrigin = production ? httpsUrl(environment, "PUBLIC_ORIGIN", true) : httpsUrl(environment, "PUBLIC_ORIGIN", false);
  const trustProxyTls = (environment.TRUST_PROXY_TLS ?? "").trim().toLowerCase() === "true";
  if (production && !trustProxyTls) {
    throw new ConfigurationError("production requires TRUST_PROXY_TLS=true behind a trusted HTTPS terminator");
  }

  const corsAllowedOrigins = originList(environment);
  if (production && (environment.CORS_ALLOWED_ORIGINS ?? "").includes("*")) {
    throw new ConfigurationError("production CORS does not allow wildcard origins");
  }

  const developerBootstrapEmailRaw = (environment.CRAFTMIND_DEV_BOOTSTRAP_EMAIL ?? "").trim();
  const developerBootstrapSecret = (environment.CRAFTMIND_DEV_BOOTSTRAP_SECRET ?? "").trim();
  if (Boolean(developerBootstrapEmailRaw) !== Boolean(developerBootstrapSecret)) {
    throw new ConfigurationError("CRAFTMIND_DEV_BOOTSTRAP_EMAIL and CRAFTMIND_DEV_BOOTSTRAP_SECRET must be configured together");
  }
  if (developerBootstrapEmailRaw && !isWellFormedEmail(developerBootstrapEmailRaw)) {
    throw new ConfigurationError("CRAFTMIND_DEV_BOOTSTRAP_EMAIL must be a valid email address");
  }
  if (developerBootstrapSecret && !isCanonicalBootstrapSecret(developerBootstrapSecret)) {
    throw new ConfigurationError("CRAFTMIND_DEV_BOOTSTRAP_SECRET must be a high-entropy base64url value of at least 43 characters");
  }

  const developerAiProvider = (environment.CRAFTMIND_DEVELOPER_AI_PROVIDER ?? "").trim();
  const developerAiModel = (environment.CRAFTMIND_DEVELOPER_AI_MODEL ?? "").trim();
  const developerAiApiKey = environment.CRAFTMIND_DEVELOPER_AI_API_KEY ?? "";
  const developerAiValuesPresent = [developerAiProvider, developerAiModel, developerAiApiKey].filter(Boolean).length;
  if (developerAiValuesPresent !== 0 && developerAiValuesPresent !== 3) {
    throw new ConfigurationError("CRAFTMIND_DEVELOPER_AI_PROVIDER, CRAFTMIND_DEVELOPER_AI_MODEL, and CRAFTMIND_DEVELOPER_AI_API_KEY must be configured together");
  }
  if (developerAiProvider && !/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(developerAiProvider)) {
    throw new ConfigurationError("CRAFTMIND_DEVELOPER_AI_PROVIDER has an invalid identifier");
  }
  if (developerAiModel.length > 128 || /[\u0000-\u001f\u007f]/.test(developerAiModel)) {
    throw new ConfigurationError("CRAFTMIND_DEVELOPER_AI_MODEL is not usable");
  }
  if (developerAiApiKey && (developerAiApiKey.length < MINIMUM_DEVELOPER_AI_KEY_LENGTH || developerAiApiKey.length > 4096)) {
    throw new ConfigurationError("CRAFTMIND_DEVELOPER_AI_API_KEY is outside its supported length range");
  }

  const configuration = {
    databaseUrl,
    authSecret,
    nodeEnvironment,
    production,
    host: (environment.HOST ?? "127.0.0.1").trim(),
    port: positiveInteger(environment, "PORT", 8787, 65535),
    accessTokenTtlSeconds: positiveInteger(environment, "ACCESS_TOKEN_TTL_SECONDS", 3600, 31_536_000),
    refreshTokenTtlSeconds: positiveInteger(environment, "REFRESH_TOKEN_TTL_SECONDS", 2_592_000, 31_536_000),
    verificationTokenTtlSeconds: positiveInteger(environment, "EMAIL_VERIFICATION_TTL_SECONDS", 86_400, 604_800),
    passwordResetTokenTtlSeconds: positiveInteger(environment, "PASSWORD_RESET_TTL_SECONDS", 3600, 86_400),
    maxBodyBytes: positiveInteger(environment, "MAX_BODY_BYTES", 16_384, 1_048_576),
    emailProvider,
    emailWebhookUrl,
    emailWebhookToken: emailProvider === "webhook" ? emailWebhookToken : null,
    emailFrom: emailProvider === "webhook" ? emailFrom : null,
    emailDeliveryMode: emailProvider === "memory" ? "DEVELOPMENT_SINK" : "PROVIDER_CONFIGURED",
    developerBootstrapEmail: developerBootstrapEmailRaw ? developerBootstrapEmailRaw.toLowerCase() : null,
    developerBootstrapSecret: developerBootstrapSecret || null,
    developerAccessTokenTtlSeconds: positiveInteger(environment, "DEVELOPER_ACCESS_TOKEN_TTL_SECONDS", 900, 3600),
    developerRefreshTokenTtlSeconds: positiveInteger(environment, "DEVELOPER_REFRESH_TOKEN_TTL_SECONDS", 604_800, 2_592_000),
    developerConfirmationTtlSeconds: positiveInteger(environment, "DEVELOPER_CONFIRMATION_TTL_SECONDS", 300, 900),
    developerAi: Object.freeze({
      provider: developerAiProvider || null,
      model: developerAiModel || null,
      apiKey: developerAiApiKey || null,
      configured: developerAiValuesPresent === 3,
    }),
    publicOrigin,
    trustProxyTls,
    corsAllowedOrigins,
    rateLimit: Object.freeze({
      login: Object.freeze({ maximum: positiveInteger(environment, "RATE_LOGIN_MAX", 10, 10_000), windowMs: positiveInteger(environment, "RATE_LOGIN_WINDOW_MS", 900_000, 86_400_000) }),
      register: Object.freeze({ maximum: positiveInteger(environment, "RATE_REGISTER_MAX", 60, 10_000), windowMs: positiveInteger(environment, "RATE_REGISTER_WINDOW_MS", 3_600_000, 86_400_000) }),
      verification: Object.freeze({ maximum: positiveInteger(environment, "RATE_VERIFY_MAX", 10, 10_000), windowMs: positiveInteger(environment, "RATE_VERIFY_WINDOW_MS", 900_000, 86_400_000) }),
      resend: Object.freeze({ maximum: positiveInteger(environment, "RATE_RESEND_MAX", 3, 10_000), windowMs: positiveInteger(environment, "RATE_RESEND_WINDOW_MS", 3_600_000, 86_400_000) }),
      resetRequest: Object.freeze({ maximum: positiveInteger(environment, "RATE_RESET_REQUEST_MAX", 5, 10_000), windowMs: positiveInteger(environment, "RATE_RESET_REQUEST_WINDOW_MS", 3_600_000, 86_400_000) }),
      resetConfirm: Object.freeze({ maximum: positiveInteger(environment, "RATE_RESET_CONFIRM_MAX", 10, 10_000), windowMs: positiveInteger(environment, "RATE_RESET_CONFIRM_WINDOW_MS", 900_000, 86_400_000) }),
      session: Object.freeze({ maximum: positiveInteger(environment, "RATE_SESSION_MAX", 60, 10_000), windowMs: positiveInteger(environment, "RATE_SESSION_WINDOW_MS", 900_000, 86_400_000) }),
      developerBootstrap: Object.freeze({ maximum: positiveInteger(environment, "RATE_DEV_BOOTSTRAP_MAX", 5, 100), windowMs: positiveInteger(environment, "RATE_DEV_BOOTSTRAP_WINDOW_MS", 900_000, 86_400_000) }),
      developerLogin: Object.freeze({ maximum: positiveInteger(environment, "RATE_DEV_LOGIN_MAX", 5, 10_000), windowMs: positiveInteger(environment, "RATE_DEV_LOGIN_WINDOW_MS", 900_000, 86_400_000) }),
      developerSession: Object.freeze({ maximum: positiveInteger(environment, "RATE_DEV_SESSION_MAX", 30, 10_000), windowMs: positiveInteger(environment, "RATE_DEV_SESSION_WINDOW_MS", 900_000, 86_400_000) }),
      developerAdmin: Object.freeze({ maximum: positiveInteger(environment, "RATE_DEV_ADMIN_MAX", 120, 10_000), windowMs: positiveInteger(environment, "RATE_DEV_ADMIN_WINDOW_MS", 900_000, 86_400_000) }),
      developerAi: Object.freeze({ maximum: positiveInteger(environment, "RATE_DEV_AI_MAX", 10, 10_000), windowMs: positiveInteger(environment, "RATE_DEV_AI_WINDOW_MS", 60_000, 86_400_000) }),
    }),
  };
  return Object.freeze(configuration);
}

export { MINIMUM_SECRET_LENGTH, MINIMUM_PROVIDER_TOKEN_LENGTH };
