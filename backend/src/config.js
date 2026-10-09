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

function positiveInteger(environment, name, fallback, maximum = Number.MAX_SAFE_INTEGER, allowZero = false) {
  const raw = environment[name];
  if (raw === undefined || raw === "") return fallback;
  if (!/^[0-9]+$/.test(raw)) throw new ConfigurationError(`${name} must be a positive integer`);
  const value = Number(raw);
  const floor = allowZero ? 0 : 1;
  if (!Number.isSafeInteger(value) || value < floor || value > maximum) {
    throw new ConfigurationError(
      `${name} must be ${allowZero ? "a non-negative" : "a positive"} integer within its supported range`,
    );
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

/**
 * One detection rule's centralized thresholds. Every automated decision reads its numbers from here, so no rule
 * contains a magic number of its own. Ordering is validated: medium < high < critical.
 */
function detectionThresholds(environment, key, { medium, high, critical, windowMs, maximumWindowMs = 3_600_000 }) {
  const prefix = `SECURITY_${key}`;
  const resolved = {
    windowMs: positiveInteger(environment, `${prefix}_WINDOW_MS`, windowMs, maximumWindowMs),
    medium: positiveInteger(environment, `${prefix}_MEDIUM_MAX`, medium, 1_000_000),
    high: positiveInteger(environment, `${prefix}_HIGH_MAX`, high, 1_000_000),
    critical: positiveInteger(environment, `${prefix}_CRITICAL_MAX`, critical, 1_000_000),
  };
  if (!(resolved.medium < resolved.high && resolved.high < resolved.critical)) {
    throw new ConfigurationError(`${prefix} thresholds must satisfy medium < high < critical`);
  }
  return Object.freeze(resolved);
}

function securityConfiguration(environment) {
  return Object.freeze({
    thresholds: Object.freeze({
      bruteForce: detectionThresholds(environment, "BRUTE_FORCE", { windowMs: 120_000, medium: 5, high: 12, critical: 25 }),
      developerAuthFailures: detectionThresholds(environment, "DEV_AUTH", { windowMs: 120_000, medium: 3, high: 6, critical: 12 }),
      // Stale or revoked credentials are routinely retried by clients after a logout, an app restart, or an expired
      // access token, so this rule watches *sustained* reuse rather than a handful of ordinary retries.
      sessionAbuse: detectionThresholds(environment, "SESSION_ABUSE", { windowMs: 300_000, medium: 8, high: 15, critical: 25 }),
      rateLimitAbuse: detectionThresholds(environment, "RATE_LIMIT_ABUSE", { windowMs: 600_000, medium: 3, high: 8, critical: 15 }),
      unauthorizedAccess: detectionThresholds(environment, "UNAUTHORIZED_ACCESS", { windowMs: 600_000, medium: 5, high: 10, critical: 20 }),
      confirmationAbuse: detectionThresholds(environment, "CONFIRMATION_ABUSE", { windowMs: 600_000, medium: 3, high: 6, critical: 12 }),
      malformedRequests: detectionThresholds(environment, "MALFORMED_REQUESTS", { windowMs: 300_000, medium: 5, high: 10, critical: 20 }),
      requestBurst: detectionThresholds(environment, "REQUEST_BURST", { windowMs: 60_000, medium: 120, high: 240, critical: 400 }),
    }),
    response: Object.freeze({
      // Automated protections are temporary by construction. No automated action is allowed to become permanent.
      defaultProtectionSeconds: positiveInteger(environment, "SECURITY_DEFAULT_PROTECTION_SECONDS", 900, 86_400),
      maximumProtectionSeconds: positiveInteger(environment, "SECURITY_MAX_PROTECTION_SECONDS", 3_600, 86_400),
      throttleMaximum: positiveInteger(environment, "SECURITY_THROTTLE_MAX", 5, 600),
      throttleWindowMs: positiveInteger(environment, "SECURITY_THROTTLE_WINDOW_MS", 300_000, 3_600_000),
      burstCooldownMs: positiveInteger(environment, "SECURITY_BURST_COOLDOWN_MS", 60_000, 3_600_000),
      notificationCooldownMs: positiveInteger(environment, "SECURITY_NOTIFICATION_COOLDOWN_MS", 300_000, 86_400_000),
      // A hard rejection is only ever issued against an abusive request source, never against an account or session.
      deniableScopes: Object.freeze(["SOURCE"]),
    }),
    retention: Object.freeze({
      eventsSeconds: positiveInteger(environment, "SECURITY_EVENT_RETENTION_SECONDS", 2_592_000, 31_536_000),
      notificationsSeconds: positiveInteger(environment, "SECURITY_NOTIFICATION_RETENTION_SECONDS", 7_776_000, 31_536_000),
      resolvedIncidentsSeconds: positiveInteger(environment, "SECURITY_INCIDENT_RETENTION_SECONDS", 15_552_000, 63_072_000),
      releasedProtectionSeconds: positiveInteger(environment, "SECURITY_PROTECTION_RETENTION_SECONDS", 604_800, 7_776_000),
    }),
    bounds: Object.freeze({
      metadataCharacters: 2048,
      reasonCharacters: 200,
      reasonsPerIncident: 6,
      eventDeduplicationMs: 1000,
      incidentDeduplicationMs: positiveInteger(environment, "SECURITY_INCIDENT_DEDUP_MS", 900_000, 86_400_000),
      maximumEventsPerQuery: 200,
      maximumIncidentsPerQuery: 100,
    }),
  });
}

/**
 * Phase 22 membership, entitlement, and credit configuration.
 *
 * Every number the entitlement engine and the credit ledger enforce lives here, so no route handler contains a limit of
 * its own. Plan credit allowances are internal (promotional) allocations — they are not prices, they are not advertised
 * in the interface, and they can only be applied by an authorized backend tool or a plan grant.
 */
function membershipConfiguration(environment) {
  const catalog = {
    freeDailyGenerations: positiveInteger(environment, "MEMBERSHIP_FREE_DAILY_GENERATIONS", 10, 1_000_000),
    freePromotionalCredits: positiveInteger(environment, "MEMBERSHIP_FREE_PROMOTIONAL_CREDITS", 0, 1_000_000, true),
    proPromotionalCredits: positiveInteger(environment, "MEMBERSHIP_PRO_PROMOTIONAL_CREDITS", 200, 1_000_000),
    creatorPromotionalCredits: positiveInteger(environment, "MEMBERSHIP_CREATOR_PROMOTIONAL_CREDITS", 500, 1_000_000),
    serverPromotionalCredits: positiveInteger(environment, "MEMBERSHIP_SERVER_PROMOTIONAL_CREDITS", 1_000, 1_000_000),
    creditExpiryDays: positiveInteger(environment, "MEMBERSHIP_CREDIT_EXPIRY_DAYS", 90, 3_650),
    maximumGrantCredits: positiveInteger(environment, "MEMBERSHIP_MAX_GRANT_CREDITS", 10_000, 1_000_000),
    maximumConsumeCredits: positiveInteger(environment, "MEMBERSHIP_MAX_CONSUME_CREDITS", 100, 1_000_000),
    maximumPlanGrantDays: positiveInteger(environment, "MEMBERSHIP_MAX_PLAN_GRANT_DAYS", 365, 3_650),
    maximumTransactionPageSize: positiveInteger(environment, "MEMBERSHIP_MAX_TRANSACTION_PAGE", 100, 1_000),
    reconciliationBatch: positiveInteger(environment, "MEMBERSHIP_RECONCILIATION_BATCH", 50, 10_000),
  };
  if (catalog.freePromotionalCredits > catalog.maximumGrantCredits ||
      catalog.proPromotionalCredits > catalog.maximumGrantCredits ||
      catalog.creatorPromotionalCredits > catalog.maximumGrantCredits ||
      catalog.serverPromotionalCredits > catalog.maximumGrantCredits) {
    throw new ConfigurationError("a plan's promotional credit allocation cannot exceed MEMBERSHIP_MAX_GRANT_CREDITS");
  }
  return Object.freeze(catalog);
}

/**
 * Phase 23 creator and server configuration.
 *
 * There is exactly one number here, and it is an **operational bound**: how many workspaces one account may create. It
 * is not a plan entitlement, it is not a price, and it is not advertised as a tier — the API returns it alongside a
 * marker saying it is configuration. Everything else the phase needs to bound (profile field lengths, slug rules,
 * capability definitions) is a product invariant and lives in `creator-catalog.js` / `server-catalog.js`, where a test
 * can assert it rather than an operator silently changing it.
 */
function creatorAndServerConfiguration(environment) {
  return Object.freeze({
    serverWorkspaces: Object.freeze({
      maximumPerAccount: positiveInteger(environment, "SERVER_MAX_WORKSPACES_PER_ACCOUNT", 3, 100),
    }),
  });
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
    membership: membershipConfiguration(environment),
    ...creatorAndServerConfiguration(environment),
    security: securityConfiguration(environment),
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
      // Phase 22: reading entitlement state is cheap; consuming credits is a state change and is limited far more tightly.
      credits: Object.freeze({ maximum: positiveInteger(environment, "RATE_CREDITS_MAX", 120, 10_000), windowMs: positiveInteger(environment, "RATE_CREDITS_WINDOW_MS", 900_000, 86_400_000) }),
      creditConsume: Object.freeze({ maximum: positiveInteger(environment, "RATE_CREDIT_CONSUME_MAX", 30, 10_000), windowMs: positiveInteger(environment, "RATE_CREDIT_CONSUME_WINDOW_MS", 900_000, 86_400_000) }),
      // Phase 23: reading creator/server state is cheap; creating or changing it is a bounded state change. Public
      // creator reads are limited too, because an anonymous endpoint is the one most worth scraping.
      creatorProfileWrite: Object.freeze({ maximum: positiveInteger(environment, "RATE_CREATOR_WRITE_MAX", 30, 10_000), windowMs: positiveInteger(environment, "RATE_CREATOR_WRITE_WINDOW_MS", 900_000, 86_400_000) }),
      creatorProfileRead: Object.freeze({ maximum: positiveInteger(environment, "RATE_CREATOR_READ_MAX", 120, 10_000), windowMs: positiveInteger(environment, "RATE_CREATOR_READ_WINDOW_MS", 900_000, 86_400_000) }),
      serverWrite: Object.freeze({ maximum: positiveInteger(environment, "RATE_SERVER_WRITE_MAX", 30, 10_000), windowMs: positiveInteger(environment, "RATE_SERVER_WRITE_WINDOW_MS", 900_000, 86_400_000) }),
      serverRead: Object.freeze({ maximum: positiveInteger(environment, "RATE_SERVER_READ_MAX", 120, 10_000), windowMs: positiveInteger(environment, "RATE_SERVER_READ_WINDOW_MS", 900_000, 86_400_000) }),
      // Phase 24: saving onboarding answers is a bounded, credential-bearing state change; reading one's own onboarding
      // state is an ordinary authenticated read. The buckets are separate so a form re-render can never spend the
      // budget that protects the write.
      onboardingWrite: Object.freeze({ maximum: positiveInteger(environment, "RATE_ONBOARDING_WRITE_MAX", 30, 10_000), windowMs: positiveInteger(environment, "RATE_ONBOARDING_WRITE_WINDOW_MS", 900_000, 86_400_000) }),
      onboardingRead: Object.freeze({ maximum: positiveInteger(environment, "RATE_ONBOARDING_READ_MAX", 120, 10_000), windowMs: positiveInteger(environment, "RATE_ONBOARDING_READ_WINDOW_MS", 900_000, 86_400_000) }),
      // Phase 26: posting and managing hire requests, and submitting or amending proposals, are bounded state
      // changes with dedicated budgets — a busy buyer cannot spend a creator's proposal budget and vice versa.
      jobWrite: Object.freeze({ maximum: positiveInteger(environment, "RATE_JOB_WRITE_MAX", 30, 10_000), windowMs: positiveInteger(environment, "RATE_JOB_WRITE_WINDOW_MS", 900_000, 86_400_000) }),
      proposalWrite: Object.freeze({ maximum: positiveInteger(environment, "RATE_PROPOSAL_WRITE_MAX", 30, 10_000), windowMs: positiveInteger(environment, "RATE_PROPOSAL_WRITE_WINDOW_MS", 900_000, 86_400_000) }),
    }),
  };
  return Object.freeze(configuration);
}

export { MINIMUM_SECRET_LENGTH, MINIMUM_PROVIDER_TOKEN_LENGTH };
