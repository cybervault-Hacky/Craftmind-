/**
 * The website's data boundary (Phase 21).
 *
 * Phase 21 ships interface only. There is no marketplace backend, no creator backend, no membership, purchase,
 * entitlement, or payment service behind this site, so every adapter below is *registered but unconfigured* and every
 * method answers with the same typed `unavailable` result. Nothing here talks to a network: no `fetch`, no
 * `XMLHttpRequest`, no WebSocket, and no hardcoded production URL. A later phase supplies a real implementation through
 * `configure()`, and the pages that consume these adapters switch from honest unavailable states to real data without
 * any markup change.
 *
 * The one adapter that is fully specified is the account adapter, because the account service already exists in
 * `backend/`. It is still inactive until a deployer deliberately configures an origin, and it only ever calls the
 * endpoints that exist (see ACCOUNT_ENDPOINTS).
 */

import { STATE } from "./state.js";

/** Result statuses every adapter method returns. */
export const RESULT = Object.freeze({
  OK: "ok",
  EMPTY: "empty",
  UNAVAILABLE: "unavailable",
  UNAUTHORIZED: "unauthorized",
  ERROR: "error",
});

/** Why an adapter could not answer. These codes are stable and safe to render. */
export const REASON = Object.freeze({
  NOT_IMPLEMENTED: "NO_BACKEND_IMPLEMENTED",
  NOT_CONFIGURED: "NO_SERVICE_CONFIGURED",
  NOT_SIGNED_IN: "NO_SESSION",
  REJECTED: "SERVICE_REJECTED_REQUEST",
  UNREACHABLE: "SERVICE_UNREACHABLE",
});

const UNAVAILABLE_MESSAGES = Object.freeze({
  [REASON.NOT_IMPLEMENTED]: "The marketplace backend is not implemented yet. This interface is the Phase 21 foundation, and it will read real data once that service exists.",
  [REASON.NOT_CONFIGURED]: "This site has no account service configured, so no account data can be requested here.",
});

/** Maps an adapter code to the region state that should be rendered for it. */
export function stateKindFor(code) {
  if (code === STATE.UNAUTHORIZED) return STATE.UNAUTHORIZED;
  return STATE.UNAVAILABLE;
}

function unavailable(reason = REASON.NOT_IMPLEMENTED, extra = {}) {
  return Object.freeze({
    status: RESULT.UNAVAILABLE,
    reason,
    message: UNAVAILABLE_MESSAGES[reason] ?? "This capability is not available yet.",
    ...extra,
  });
}

function unconfigured(adapterName, methods, { reason = REASON.NOT_IMPLEMENTED, message } = {}) {
  const record = {
    configured: false,
    name: adapterName,
    /** The documented contract a future implementation must satisfy. Nothing calls it yet. */
    contract: methods,
  };
  for (const method of methods) {
    record[method] = async () => unavailable(reason, message ? { message } : {});
  }
  return Object.freeze(record);
}

/**
 * The account service contract that already exists in `backend/src/server.js`. Listed here so the future client cannot
 * drift from the implemented surface, and so this file documents exactly which calls the account pages may make.
 */
export const ACCOUNT_ENDPOINTS = Object.freeze([
  "POST /auth/login",
  "POST /auth/logout",
  "GET /auth/me",
  "POST /auth/verify-email",
  "POST /auth/resend-verification",
  "POST /auth/password-reset/request",
  "POST /auth/password-reset/confirm",
  "POST /auth/password/change",
  "GET /auth/sessions",
  "POST /auth/sessions/revoke",
  "POST /auth/sessions/revoke-all",
]);

/** The method names the configured account adapter exposes; also the contract reported by describeIntegrationBoundary. */
const ACCOUNT_METHODS = Object.freeze([
  "signIn", "signOut", "loadAccount", "loadSessions", "revokeSession", "revokeOtherSessions",
  "requestPasswordReset", "confirmPasswordReset", "changePassword", "resendVerification",
]);

async function requestJson(baseUrl, path, { method = "GET", body, token } = {}) {
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;
  let response;
  try {
    response = await fetch(new URL(path, baseUrl).toString(), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    return { status: RESULT.ERROR, reason: REASON.UNREACHABLE, message: "The configured account service could not be reached." };
  }
  let payload = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }
  if (response.status === 401 || response.status === 403) {
    return { status: RESULT.UNAUTHORIZED, reason: REASON.NOT_SIGNED_IN, message: "This action needs an active account session.", payload };
  }
  if (!response.ok) {
    return { status: RESULT.ERROR, reason: REASON.REJECTED, message: payload?.error?.message ?? "The account service rejected the request.", payload };
  }
  return { status: RESULT.OK, payload };
}

/**
 * Creates the account adapter. It stays inactive unless a deployer sets `CRAFTMIND_SITE_CONFIG.accountServiceOrigin`
 * before the site modules load. Even then it only calls the endpoints listed in `ACCOUNT_ENDPOINTS`, holds the access
 * token in memory for the current page only, and never writes to browser storage.
 */
export function createAccountAdapter({ origin = null } = {}) {
  const session = { accessToken: null, refreshToken: null, account: null };
  const base = {
    name: "account",
    configured: Boolean(origin),
    origin,
    contract: [...ACCOUNT_METHODS],
    get signedIn() { return Boolean(session.accessToken); },
    get account() { return session.account; },
    session,
  };
  if (!origin) {
    return Object.freeze({
      ...unconfigured("account", [...ACCOUNT_METHODS], { reason: REASON.NOT_CONFIGURED, message: UNAVAILABLE_MESSAGES[REASON.NOT_CONFIGURED] }),
      ...base,
      configured: false,
    });
  }
  return Object.freeze({
    ...base,
    async signIn({ email, password }) {
      const result = await requestJson(origin, "/auth/login", { method: "POST", body: { email, password } });
      if (result.status === RESULT.OK) {
        session.accessToken = result.payload?.session?.accessToken ?? null;
        session.refreshToken = result.payload?.session?.refreshToken ?? null;
        session.account = result.payload?.account ?? null;
      }
      return result;
    },
    async signOut() {
      const token = session.accessToken;
      session.accessToken = null;
      session.refreshToken = null;
      session.account = null;
      if (!token) return { status: RESULT.OK, payload: null };
      return requestJson(origin, "/auth/logout", { method: "POST", body: { accessToken: token }, token });
    },
    loadAccount() { return requestJson(origin, "/auth/me", { token: session.accessToken }); },
    loadSessions() { return requestJson(origin, "/auth/sessions", { token: session.accessToken }); },
    revokeSession({ sessionId }) { return requestJson(origin, "/auth/sessions/revoke", { method: "POST", body: { sessionId }, token: session.accessToken }); },
    revokeOtherSessions() { return requestJson(origin, "/auth/sessions/revoke-all", { method: "POST", body: {}, token: session.accessToken }); },
    requestPasswordReset({ email }) { return requestJson(origin, "/auth/password-reset/request", { method: "POST", body: { email } }); },
    confirmPasswordReset({ email, code, newPassword }) { return requestJson(origin, "/auth/password-reset/confirm", { method: "POST", body: { email, code, newPassword } }); },
    changePassword({ currentPassword, newPassword }) { return requestJson(origin, "/auth/password/change", { method: "POST", body: { currentPassword, newPassword }, token: session.accessToken }); },
    resendVerification({ email }) { return requestJson(origin, "/auth/resend-verification", { method: "POST", body: { email } }); },
  });
}

/**
 * Reads the optional deploy-time configuration object. It is intentionally absent in this repository: the site ships
 * unconfigured, so every non-account adapter is honestly unavailable and the account pages ask for an origin.
 */
function siteConfiguration() {
  const config = globalThis.CRAFTMIND_SITE_CONFIG;
  if (!config || typeof config !== "object") return {};
  const origin = typeof config.accountServiceOrigin === "string" ? config.accountServiceOrigin.trim() : "";
  // Only absolute HTTPS origins are accepted, mirroring the service's own CORS/HTTPS policy.
  return { accountServiceOrigin: /^https:\/\/[^\s]+$/.test(origin) ? origin : "" };
}

/** The integration boundary consumed by the page controllers. */
export function createAdapters() {
  const config = siteConfiguration();
  return Object.freeze({
    account: createAccountAdapter({ origin: config.accountServiceOrigin || null }),
    // Documented, unimplemented, and intentionally inert. Each list is the exact method set a later phase must provide.
    marketplace: unconfigured("marketplace", ["searchListings", "getListing", "listCategories", "listFeatured", "listSaved", "setSaved"]),
    creator: unconfigured("creator", ["listMyListings", "createDraft", "updateDraft", "publishListing", "unpublishListing", "duplicateListing", "deleteDraft", "listMedia", "uploadMedia"]),
    orders: unconfigured("orders", ["listSalesOrders", "listPurchases", "getOrder"]),
    reviews: unconfigured("reviews", ["listReviewsForListing", "listReviewsForCreator", "submitReview"]),
    analytics: unconfigured("analytics", ["listingViews", "listingSaves", "conversion", "revenue", "topListings", "trafficSources"]),
    membership: unconfigured("membership", ["listPlans", "getCurrentMembership", "startUpgrade", "startDowngrade", "cancelRenewal"]),
    entitlements: unconfigured("entitlements", ["listEntitlements", "checkAccess"]),
    payments: unconfigured("payments", ["listPayoutMethods", "listPayouts", "startCheckout", "openBillingPortal"]),
  });
}

/** The documented future API boundary, exposed for the site documentation and the integrity checker. */
export function describeIntegrationBoundary() {
  const adapters = createAdapters();
  const boundary = {};
  for (const [name, adapter] of Object.entries(adapters)) {
    boundary[name] = { configured: Boolean(adapter.configured), methods: [...(adapter.contract ?? [])] };
  }
  return Object.freeze({ boundary, accountEndpoints: ACCOUNT_ENDPOINTS });
}
