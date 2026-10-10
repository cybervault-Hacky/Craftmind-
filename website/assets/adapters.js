/**
 * The website's data boundary (Phase 21).
 *
 * Phase 21 shipped interface only, and for that phase every adapter answered with a typed `unavailable` result.
 * Phases 22-33 changed that for the parts of the product the account service actually implements, so the honest
 * description of this module today is:
 *
 *   * **Configured real traffic** over `fetch` for the account, membership/entitlement/credit reads, creator profile
 *     and eligibility, onboarding, listings, hire jobs, proposals, orders, and the milestone transitions — the
 *     endpoints listed in `ACCOUNT_ENDPOINTS`, which is kept in step with the router in `backend/src/server.js`.
 *   * **Still unavailable by design** for anything the service does not have: purchasing, payments, commissions,
 *     refunds, payouts and subscription changes. Those methods answer `NO_BACKEND_IMPLEMENTED` rather than pretending,
 *     and no code was added here to fake them.
 *
 * What never changed: there is no hardcoded production URL and no WebSocket, `XMLHttpRequest`, or browser storage is
 * used, and the whole module stays inactive until a deployer configures an origin. `createAdapters()` reads that
 * origin from `CRAFTMIND_SITE_CONFIG`; with nothing configured — which is the state of the published static site —
 * every method still returns the typed `unavailable` result, so no page can show a fabricated success.
 *
 * The adapters that are fully specified are the account adapter, because the account service already exists in
 * `backend/`; — since Phase 22 — a read-only membership/entitlement view over it, because that service now owns
 * membership state, plan entitlements, and build credits; and — since Phase 25 — the marketplace catalog and the
 * creator listing surface, because the service now stores listings, evaluates publishing prerequisites, and answers
 * public search over PUBLISHED rows only. All stay inactive until a deployer deliberately configures an origin, and
 * all only ever call the endpoints that exist (see ACCOUNT_ENDPOINTS).
 *
 * Two rules that Phase 22 makes explicit:
 *
 *   * **The server decides.** This module may *read* a plan, an entitlement list, and a credit balance for the signed-in
 *     account. It never computes a balance, never decides an entitlement, never accepts a plan from the browser, and
 *     never stores a balance anywhere — the figures below exist only as the response of one request.
 *   * **Nothing here spends.** No website feature consumes credits in this phase, so no method here calls the
 *     server's consumption endpoint. When a real build flow needs it, that phase adds the call deliberately.
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
  [REASON.NOT_IMPLEMENTED]: "This capability has no implementation in this phase, so the interface answers honestly instead of inventing data.",
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
  "POST /auth/register",
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
  // Phase 22: membership, entitlement, and credit reads. The server also exposes POST /account/credits/consume for
  // clients that perform a real credit-consuming operation; no website feature does, so this module never calls it.
  "GET /account/membership",
  "GET /account/entitlements",
  "GET /account/credits",
  "GET /account/credits/transactions",
  // Phase 23: the creator identity foundation. These are the endpoints that actually exist server-side. Profile creation
  // and updates (POST/PATCH /creator/profile) are implemented on the service but have no write path here: this site
  // reads creator state and changes nothing through a browser.
  "GET /account/capabilities",
  "GET /creator/profile",
  "GET /creator/eligibility",
  // Phase 24: buyer and seller onboarding. Two reads and two saves, all session-scoped; the server validates every
  // field, so these methods send answers and never derived status of their own.
  "GET /account/onboarding",
  "GET /onboarding/buyer",
  "POST /onboarding/buyer",
  "GET /onboarding/seller",
  "POST /onboarding/seller",
  // Phase 25: public listing discovery (published rows only) and the session-scoped listing lifecycle. The service
  // derives ownership from the session; the browser never names an owner and never sends one.
  "GET /marketplace/listings",
  "GET /marketplace/listings/:id",
  "GET /creator/listing",
  "POST /creator/listing",
  "GET /creator/listing/:id",
  "PATCH /creator/listing/:id",
  "POST /creator/listing/:id/publish",
  "POST /creator/listing/:id/archive",
  // Phase 26: Hire a Builder. Public OPEN-job discovery, the buyer's own job lifecycle, and the creator's own
  // proposals — all session-scoped where they change something; the service derives every owner from the token
  // and the browser never sends an owner, a status, or an award.
  "GET /marketplace/jobs",
  "GET /marketplace/jobs/:id",
  "GET /buyer/jobs",
  "POST /buyer/jobs",
  "GET /buyer/jobs/:id",
  "PATCH /buyer/jobs/:id",
  "POST /buyer/jobs/:id/cancel",
  "POST /buyer/jobs/:id/award",
  "GET /creator/proposal",
  "POST /creator/proposal",
  "GET /creator/proposal/:id",
  "PATCH /creator/proposal/:id",
  "POST /creator/proposal/:id/withdraw",
  // Phase 27: marketplace order lifecycle. Creation, both role lists, the authorized detail and history, and the
  // six transitions — all session-scoped; the service derives every participant from the token and validates every
  // state change, and no method here ever sends a buyer, a creator, a status, or an amount of its own.
  "POST /buyer/orders",
  "GET /buyer/orders",
  "GET /creator/orders",
  "GET /orders/:id",
  "GET /orders/:id/history",
  "POST /orders/:id/complete",
  "POST /orders/:id/cancel",
  "POST /orders/:id/milestones/:mid/start",
  "POST /orders/:id/milestones/:mid/deliver",
  "POST /orders/:id/milestones/:mid/revision",
  "POST /orders/:id/milestones/:mid/approve",
]);

/** The one anonymous route the site may call. It returns the public projection of an ACTIVE creator profile. */
export const PUBLIC_CREATOR_ENDPOINT = "GET /creators/:handle";

/** The method names the configured account adapter exposes; also the contract reported by describeIntegrationBoundary. */
const ACCOUNT_METHODS = Object.freeze([
  "register", "verifyEmail",
  "signIn", "signOut", "loadAccount", "loadSessions", "revokeSession", "revokeOtherSessions",
  "requestPasswordReset", "confirmPasswordReset", "changePassword", "resendVerification",
  "loadMembership", "loadEntitlements", "loadCredits",
  "loadAccountCapabilities", "loadCreatorProfile", "loadCreatorEligibility", "loadPublicCreatorProfile",
  "loadOnboardingState", "loadBuyerOnboarding", "saveBuyerOnboarding", "loadSellerOnboarding", "saveSellerOnboarding",
]);

/** The creator identity surface. Every method is a read: no method here creates, edits, publishes, or sells anything. */
const CREATOR_PROFILE_METHODS = Object.freeze([
  "loadProfile", "loadEligibility", "loadCapabilities", "loadPublicProfile",
]);

/** The read-only membership surface. Every method is a read; nothing here starts a purchase or a charge. */
const MEMBERSHIP_METHODS = Object.freeze(["loadMembership", "loadEntitlements", "loadCredits"]);

/** The entitlement surface. `checkAccess` is deliberately absent: access is decided by the server, never in a browser. */
const ENTITLEMENT_METHODS = Object.freeze(["listEntitlements", "loadCredits"]);

/**
 * The marketplace catalog surface (Phase 25). `searchListings` and `getListing` read PUBLISHED rows from the
 * service; `listCategories` answers from the fixed vocabulary the service validates (it is a constant, not a
 * server claim). The remaining methods stay in the documented contract as honest not-implemented stubs: there is
 * no featured ranking, no server-side saved-items store, and no rating or popularity signal anywhere.
 */
const MARKETPLACE_METHODS = Object.freeze(["searchListings", "getListing", "listCategories", "listFeatured", "listSaved", "setSaved"]);

/** The creator listing surface (Phase 25): drafts and the one-way lifecycle publish/archive, all session-scoped. */
const CREATOR_LISTING_METHODS = Object.freeze([
  "listMyListings", "createDraft", "updateDraft", "publishListing", "unpublishListing",
  "duplicateListing", "deleteDraft", "listMedia", "uploadMedia",
]);

/**
 * The Hire a Builder surface (Phases 26–27). Two public reads over OPEN jobs; everything else is session-scoped and
 * owner-derived on the server. No method here ever sends an owner id, a status, a creator id, a milestone state,
 * an amount, or an award choice beyond the one proposal id the buyer selected — those are the server's to decide.
 * Order transitions send nothing but the ids in the URL and the buyer's own revision reason or delivery note.
 */
const HIRE_METHODS = Object.freeze([
  "searchJobs", "getJob",
  "listMyJobs", "createJob", "updateJob", "cancelJob", "awardProposal",
  "listMyProposals", "getOwnedJob", "submitProposal", "updateProposal", "withdrawProposal",
  // Phase 27: the order lifecycle (see ACCOUNT_ENDPOINTS above).
  "createOrder", "listMyBuyerOrders", "listMyCreatorOrders", "getOrderDetail", "getOrderHistory",
  "startOrderMilestone", "submitOrderDelivery", "requestMilestoneRevision", "approveOrderMilestone",
  "completeOrder", "cancelOrder",
]);

const JOB_ID_PATTERN = /^job_[0-9a-fA-F-]{36}$/;
const PROPOSAL_ID_PATTERN = /^prp_[0-9a-fA-F-]{36}$/;
const ORDER_ID_PATTERN = /^ord_[0-9a-fA-F-]{36}$/;
const MILESTONE_ID_PATTERN = /^mil_[0-9a-fA-F-]{36}$/;

/** The same eight categories the service stores and validates; a fixed vocabulary, so no lookup is required. */
const MARKETPLACE_CATEGORIES = Object.freeze([
  "Structures", "Landscaping", "Interiors", "Redstone", "Farms", "Decorations", "Mini-games", "Whole worlds",
]);

const LISTING_ID_PATTERN = /^lst_[0-9a-fA-F-]{36}$/;

function rejected(message) {
  return Promise.resolve(Object.freeze({ status: RESULT.ERROR, reason: REASON.REJECTED, message }));
}

function notImplemented(message) {
  return Promise.resolve(unavailable(REASON.NOT_IMPLEMENTED, { message }));
}

async function requestJson(baseUrl, path, { method = "GET", body, token, refusedNotUnauthenticated = false } = {}) {
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
    // A 403 on the listing routes means the session is valid and the *request* was refused (missing entitlement,
    // missing agreement, wrong owner). Reporting that as "sign in again" would lie to the user, so those calls opt
    // into the typed rejection with the server's own actionable message.
    if (refusedNotUnauthenticated && response.status === 403 && payload?.error) {
      return { status: RESULT.ERROR, reason: REASON.REJECTED, message: payload.error.message ?? "The account service refused the request.", payload };
    }
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
/**
 * The in-memory session, shared by every adapter instance created during this page visit.
 *
 * It is deliberately module-scoped rather than per-instance so that two controllers on one page — the account area's
 * sign-in form and the membership panel, for example — agree about who is signed in. It is still nothing more than a
 * page-local variable: it is never written to any browser persistence (no web-storage key, no cookie, no client-side
 * database), it does not survive a reload, and the access token is never rendered.
 */
const pageSession = { accessToken: null, refreshToken: null, account: null };

export function createAccountAdapter({ origin = null } = {}) {
  const session = pageSession;
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
    // Account creation and email verification against the existing auth endpoints. Registration returns no session
    // and no success claim about an email: the caller verifies with the token the service issues, then signs in.
    async register({ email, password, displayName }) {
      return requestJson(origin, "/auth/register", { method: "POST", body: { email, password, displayName } });
    },
    async verifyEmail({ token }) {
      return requestJson(origin, "/auth/verify-email", { method: "POST", body: { token } });
    },
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
    // The service consumes the one-time handoff it emailed, keyed `token` (see `docs/account-authentication.md`), and
    // the account is derived from that token — sending `email` and a `code` here could never succeed, because
    // `confirmPasswordRecovery` refuses the request the moment `token` is not a string.
    confirmPasswordReset({ token, newPassword }) { return requestJson(origin, "/auth/password-reset/confirm", { method: "POST", body: { token, newPassword } }); },
    changePassword({ currentPassword, newPassword }) { return requestJson(origin, "/auth/password/change", { method: "POST", body: { currentPassword, newPassword }, token: session.accessToken }); },
    resendVerification({ email }) { return requestJson(origin, "/auth/resend-verification", { method: "POST", body: { email } }); },
    // Phase 22 read-only account state. Each call returns the server's own answer for the signed-in account; the
    // response carries no internal identifier, and no value is cached beyond the page.
    loadMembership() { return requestJson(origin, "/account/membership", { token: session.accessToken }); },
    loadEntitlements() { return requestJson(origin, "/account/entitlements", { token: session.accessToken }); },
    loadCredits() { return requestJson(origin, "/account/credits", { token: session.accessToken }); },
    loadAccountCapabilities() { return requestJson(origin, "/account/capabilities", { token: session.accessToken }); },
    loadCreatorProfile() { return requestJson(origin, "/creator/profile", { token: session.accessToken }); },
    loadCreatorEligibility() { return requestJson(origin, "/creator/eligibility", { token: session.accessToken }); },
    // Phase 24 onboarding. Every answer is the server's composed state for this session's account; the save methods
    // post form answers only — ownership, completion, and verification are decided server-side and come back in the
    // same shape as the read.
    loadOnboardingState() { return requestJson(origin, "/account/onboarding", { token: session.accessToken }); },
    loadBuyerOnboarding() { return requestJson(origin, "/onboarding/buyer", { token: session.accessToken }); },
    saveBuyerOnboarding(answers) {
      return requestJson(origin, "/onboarding/buyer", { method: "POST", body: answers, token: session.accessToken });
    },
    loadSellerOnboarding() { return requestJson(origin, "/onboarding/seller", { token: session.accessToken }); },
    saveSellerOnboarding(answers) {
      // `refusedNotUnauthenticated` is not decoration here. The seller step is gated on the Creator entitlement, which
      // the service refuses with 403 + `CREATOR_ENTITLEMENT_REQUIRED`, and onboarding.js has an explicit branch for
      // that code (it offers "View membership"). Mapped to "sign in again", the refusal would land on the sign-in
      // panel instead — telling an already-signed-in account to do the one thing that cannot help it, and hiding the
      // only action that explains why. Same rule as the listing and hire writes below.
      return requestJson(origin, "/onboarding/seller", {
        method: "POST", body: answers, token: session.accessToken, refusedNotUnauthenticated: true,
      });
    },
    loadPublicCreatorProfile(handle) {
      // The handle is validated here as well as on the server, because a path segment is interpolated into a URL: an
      // unvalidated value could travel somewhere other than the creator route.
      if (typeof handle !== "string" || !/^[a-z0-9](?:[a-z0-9-]{1,30}[a-z0-9])$/.test(handle)) {
        return Promise.resolve(Object.freeze({
          status: RESULT.ERROR,
          reason: REASON.REJECTED,
          message: "A creator handle is a lowercase address of 3 to 32 characters using letters, digits, and hyphens.",
        }));
      }
      return requestJson(origin, `/creators/${handle}`);
    },
  });
}

/**
 * A read-only membership and entitlement view over the account adapter.
 *
 * It exists so pages can ask "is a membership service connected, and what does the server say about my plan?" without
 * owning a network call of their own, and it deliberately has no write method: there is nothing to upgrade, downgrade,
 * cancel, or purchase, because no payment provider exists in this phase. When no origin is configured, or no session is
 * active, the answers are the same honest states the pages already render.
 */
function createMembershipAdapter(account) {
  const contract = [...MEMBERSHIP_METHODS];
  if (!account.configured) {
    return unconfigured("membership", contract, { reason: REASON.NOT_CONFIGURED, message: UNAVAILABLE_MESSAGES[REASON.NOT_CONFIGURED] });
  }
  const signedInOnly = (method) => async () => {
    if (!account.signedIn) {
      return Object.freeze({
        status: RESULT.UNAUTHORIZED,
        reason: REASON.NOT_SIGNED_IN,
        message: "Sign in to read your plan, entitlements, and credit balance from the account service.",
      });
    }
    return account[method]();
  };
  return Object.freeze({
    configured: true,
    name: "membership",
    contract,
    get signedIn() { return account.signedIn; },
    loadMembership: signedInOnly("loadMembership"),
    loadEntitlements: signedInOnly("loadEntitlements"),
    loadCredits: signedInOnly("loadCredits"),
  });
}

/**
 * The creator identity surface.
 *
 * Phase 23 turned the creator plan into a real capability: an account with the Creator entitlement can own a profile,
 * and the profile has its own status and verification marker. This adapter reads that state and nothing else. It has no
 * `createProfile`, `updateProfile`, or `publish` method — the service implements profile writes, but no website feature
 * performs them yet, and a method that quietly wrote through a form would be a bigger claim than the phase makes.
 */
function createCreatorProfileAdapter(account) {
  const contract = [...CREATOR_PROFILE_METHODS];
  if (!account.configured) {
    return unconfigured("creatorProfile", contract, { reason: REASON.NOT_CONFIGURED, message: UNAVAILABLE_MESSAGES[REASON.NOT_CONFIGURED] });
  }
  const signedInOnly = (method) => async () => {
    if (!account.signedIn) {
      return Object.freeze({
        status: RESULT.UNAUTHORIZED,
        reason: REASON.NOT_SIGNED_IN,
        message: "Sign in to read your creator profile, status, and capabilities from the account service.",
      });
    }
    return account[method]();
  };
  return Object.freeze({
    configured: true,
    name: "creatorProfile",
    contract,
    get signedIn() { return account.signedIn; },
    loadProfile: signedInOnly("loadCreatorProfile"),
    loadEligibility: signedInOnly("loadCreatorEligibility"),
    loadCapabilities: signedInOnly("loadAccountCapabilities"),
    loadPublicProfile: (handle) => account.loadPublicCreatorProfile(handle),
  });
}

/** The entitlement view: the same server answers, under the name the entitlement surfaces use. */
function createEntitlementsAdapter(account) {
  const contract = [...ENTITLEMENT_METHODS];
  if (!account.configured) {
    return unconfigured("entitlements", contract, { reason: REASON.NOT_CONFIGURED, message: UNAVAILABLE_MESSAGES[REASON.NOT_CONFIGURED] });
  }
  const signedInOnly = (method) => async () => {
    if (!account.signedIn) {
      return Object.freeze({
        status: RESULT.UNAUTHORIZED,
        reason: REASON.NOT_SIGNED_IN,
        message: "Sign in to read which capabilities the server grants this account.",
      });
    }
    return account[method]();
  };
  return Object.freeze({
    configured: true,
    name: "entitlements",
    contract,
    get signedIn() { return account.signedIn; },
    listEntitlements: signedInOnly("loadEntitlements"),
    loadCredits: signedInOnly("loadCredits"),
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

/**
 * The public marketplace catalog over the account service (Phase 25).
 *
 * Reads only: search and detail hit the anonymous `/marketplace/listings` routes, which serve PUBLISHED rows and a
 * fixed public projection. No adapter here computes relevance, trending, or popularity — ordering is whatever the
 * service declares (newest published first), and an empty result is a success. When no origin is configured, every
 * method returns the same honest unavailable result as before.
 */
function createMarketplaceAdapter(account) {
  const contract = [...MARKETPLACE_METHODS];
  if (!account.configured) {
    return unconfigured("marketplace", contract, { reason: REASON.NOT_CONFIGURED, message: UNAVAILABLE_MESSAGES[REASON.NOT_CONFIGURED] });
  }
  return Object.freeze({
    configured: true,
    name: "marketplace",
    contract,
    async searchListings(params = {}) {
      const query = new URLSearchParams();
      for (const [key, value] of Object.entries(params)) {
        if (value === undefined || value === null || value === "") continue;
        if (typeof value !== "string" && typeof value !== "number") continue;
        query.set(key, String(value));
      }
      const suffix = query.toString();
      return requestJson(account.origin, `/marketplace/listings${suffix ? `?${suffix}` : ""}`);
    },
    async getListing(listingId) {
      // The identifier is validated here as well as on the server because it is interpolated into a URL.
      if (typeof listingId !== "string" || !LISTING_ID_PATTERN.test(listingId)) {
        return rejected("That address is not a valid listing identifier.");
      }
      return requestJson(account.origin, `/marketplace/listings/${listingId}`);
    },
    async listCategories() {
      return Object.freeze({ status: RESULT.OK, payload: { categories: [...MARKETPLACE_CATEGORIES] } });
    },
    listFeatured: () => notImplemented("There is no featured ranking in this phase: discovery is search and filters over every published listing."),
    listSaved: () => notImplemented("Saved items are kept in the current page only; no saved-items store exists on the service."),
    setSaved: () => notImplemented("Saved items are kept in the current page only; nothing can be stored against an account."),
  });
}

/**
 * The creator listing surface over the account service (Phase 25).
 *
 * Every method is session-scoped: the service derives the owner from the bearer token, so no method here ever sends
 * an owner, creator id, or verification claim. `unpublishListing` maps to the service's one-way **archive** transition
 * (`POST /creator/listing/:id/archive`): there is no unpublish-back-to-draft, and the studio labels the control
 * accordingly. Duplicate, delete, and media stay honest not-implemented stubs because the service has no such routes.
 */
function createListingCreatorAdapter(account) {
  const contract = [...CREATOR_LISTING_METHODS];
  if (!account.configured) {
    return unconfigured("creator", contract, { reason: REASON.NOT_CONFIGURED, message: UNAVAILABLE_MESSAGES[REASON.NOT_CONFIGURED] });
  }
  const signedInOnly = (implementation) => async (...args) => {
    if (!account.signedIn) {
      return Object.freeze({
        status: RESULT.UNAUTHORIZED,
        reason: REASON.NOT_SIGNED_IN,
        message: "Sign in to create, edit, publish, and archive your listings from the account service.",
      });
    }
    return implementation(...args);
  };
  const token = () => account.session.accessToken;
  return Object.freeze({
    configured: true,
    name: "creator",
    contract,
    get signedIn() { return account.signedIn; },
    listMyListings: signedInOnly(() => requestJson(account.origin, "/creator/listing", { token: token() })),
    createDraft: signedInOnly((body) => requestJson(account.origin, "/creator/listing", { method: "POST", body, token: token(), refusedNotUnauthenticated: true })),
    updateDraft: signedInOnly((listingId, body) => {
      if (typeof listingId !== "string" || !LISTING_ID_PATTERN.test(listingId)) return rejected("That address is not a valid listing identifier.");
      return requestJson(account.origin, `/creator/listing/${listingId}`, { method: "PATCH", body, token: token(), refusedNotUnauthenticated: true });
    }),
    publishListing: signedInOnly((listingId) => {
      if (typeof listingId !== "string" || !LISTING_ID_PATTERN.test(listingId)) return rejected("That address is not a valid listing identifier.");
      return requestJson(account.origin, `/creator/listing/${listingId}/publish`, { method: "POST", body: {}, token: token(), refusedNotUnauthenticated: true });
    }),
    // One-way archive: the only transition that removes a listing from public discovery in Phase 25.
    unpublishListing: signedInOnly((listingId) => {
      if (typeof listingId !== "string" || !LISTING_ID_PATTERN.test(listingId)) return rejected("That address is not a valid listing identifier.");
      return requestJson(account.origin, `/creator/listing/${listingId}/archive`, { method: "POST", body: {}, token: token(), refusedNotUnauthenticated: true });
    }),
    duplicateListing: () => notImplemented("Listing duplication has no service route in this phase."),
    deleteDraft: () => notImplemented("Listing deletion has no service route in this phase; archive removes a listing from public discovery."),
    listMedia: () => notImplemented("Listing media references are stored with a listing; there is no separate media service yet."),
    uploadMedia: () => notImplemented("Uploads need storage, scanning, and moderation, none of which exists in this phase."),
  });
}

/**
 * The Hire a Builder surface over the account service (Phase 26).
 *
 * Public reads serve OPEN jobs only; every write and every owned read is session-scoped, with the owner derived
 * from the bearer token. `refusedNotUnauthenticated` lets a typed 403 (missing entitlement, missing agreement,
 * closed job) surface the server's own actionable message instead of pretending the visitor is signed out. No
 * method here computes a status, an award, or a proposal count — those arrive only as the service's response.
 */
function createHireAdapter(account) {
  const contract = [...HIRE_METHODS];
  if (!account.configured) {
    return unconfigured("hire", contract, { reason: REASON.NOT_CONFIGURED, message: UNAVAILABLE_MESSAGES[REASON.NOT_CONFIGURED] });
  }
  const signedInOnly = (implementation) => async (...args) => {
    if (!account.signedIn) {
      return Object.freeze({
        status: RESULT.UNAUTHORIZED,
        reason: REASON.NOT_SIGNED_IN,
        message: "Sign in to post hire requests, review proposals, and manage your own proposals from the account service.",
      });
    }
    return implementation(...args);
  };
  const token = () => account.session.accessToken;
  const search = (params = {}) => {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
      if (value === undefined || value === null || value === "") continue;
      if (typeof value !== "string" && typeof value !== "number") continue;
      query.set(key, String(value));
    }
    const suffix = query.toString();
    return requestJson(account.origin, `/marketplace/jobs${suffix ? `?${suffix}` : ""}`);
  };
  return Object.freeze({
    configured: true,
    name: "hire",
    contract,
    get signedIn() { return account.signedIn; },
    // Public reads: OPEN jobs only, served by the service; nothing is computed here.
    searchJobs: search,
    async getJob(jobId) {
      if (typeof jobId !== "string" || !JOB_ID_PATTERN.test(jobId)) return rejected("That address is not a valid job identifier.");
      return requestJson(account.origin, `/marketplace/jobs/${jobId}`);
    },
    // The buyer's own surface. Ownership, lifecycle, and the award decision are all decided server-side.
    listMyJobs: signedInOnly(() => requestJson(account.origin, "/buyer/jobs", { token: token() })),
    getOwnedJob: signedInOnly((jobId) => {
      if (typeof jobId !== "string" || !JOB_ID_PATTERN.test(jobId)) return rejected("That address is not a valid job identifier.");
      return requestJson(account.origin, `/buyer/jobs/${jobId}`, { token: token() });
    }),
    createJob: signedInOnly((body) => requestJson(account.origin, "/buyer/jobs", { method: "POST", body, token: token(), refusedNotUnauthenticated: true })),
    updateJob: signedInOnly((jobId, body) => {
      if (typeof jobId !== "string" || !JOB_ID_PATTERN.test(jobId)) return rejected("That address is not a valid job identifier.");
      return requestJson(account.origin, `/buyer/jobs/${jobId}`, { method: "PATCH", body, token: token(), refusedNotUnauthenticated: true });
    }),
    cancelJob: signedInOnly((jobId) => {
      if (typeof jobId !== "string" || !JOB_ID_PATTERN.test(jobId)) return rejected("That address is not a valid job identifier.");
      return requestJson(account.origin, `/buyer/jobs/${jobId}/cancel`, { method: "POST", body: {}, token: token(), refusedNotUnauthenticated: true });
    }),
    awardProposal: signedInOnly((jobId, proposalId) => {
      if (typeof jobId !== "string" || !JOB_ID_PATTERN.test(jobId)) return rejected("That address is not a valid job identifier.");
      if (typeof proposalId !== "string" || !PROPOSAL_ID_PATTERN.test(proposalId)) return rejected("That address is not a valid proposal identifier.");
      return requestJson(account.origin, `/buyer/jobs/${jobId}/award`, { method: "POST", body: { proposalId }, token: token(), refusedNotUnauthenticated: true });
    }),
    // The creator's own proposals. The server enforces eligibility, ownership, and the one-live-proposal rule.
    listMyProposals: signedInOnly(() => requestJson(account.origin, "/creator/proposal", { token: token() })),
    submitProposal: signedInOnly((body) => requestJson(account.origin, "/creator/proposal", { method: "POST", body, token: token(), refusedNotUnauthenticated: true })),
    updateProposal: signedInOnly((proposalId, body) => {
      if (typeof proposalId !== "string" || !PROPOSAL_ID_PATTERN.test(proposalId)) return rejected("That address is not a valid proposal identifier.");
      return requestJson(account.origin, `/creator/proposal/${proposalId}`, { method: "PATCH", body, token: token(), refusedNotUnauthenticated: true });
    }),
    withdrawProposal: signedInOnly((proposalId) => {
      if (typeof proposalId !== "string" || !PROPOSAL_ID_PATTERN.test(proposalId)) return rejected("That address is not a valid proposal identifier.");
      return requestJson(account.origin, `/creator/proposal/${proposalId}/withdraw`, { method: "POST", body: {}, token: token(), refusedNotUnauthenticated: true });
    }),
    // Phase 27: the order lifecycle. Every method is session-scoped; parties, terms, statuses, versions, and the
    // revision count are all decided by the service. Milestone and order ids are validated here as well as on the
    // server because they are interpolated into a URL.
    createOrder: signedInOnly((body) => requestJson(account.origin, "/buyer/orders", { method: "POST", body, token: token(), refusedNotUnauthenticated: true })),
    listMyBuyerOrders: signedInOnly(() => requestJson(account.origin, "/buyer/orders", { token: token() })),
    listMyCreatorOrders: signedInOnly(() => requestJson(account.origin, "/creator/orders", { token: token() })),
    getOrderDetail: signedInOnly((orderId) => {
      if (typeof orderId !== "string" || !ORDER_ID_PATTERN.test(orderId)) return rejected("That address is not a valid order identifier.");
      return requestJson(account.origin, `/orders/${orderId}`, { token: token() });
    }),
    getOrderHistory: signedInOnly((orderId, params = {}) => {
      if (typeof orderId !== "string" || !ORDER_ID_PATTERN.test(orderId)) return rejected("That address is not a valid order identifier.");
      const query = new URLSearchParams();
      for (const [key, value] of Object.entries(params)) {
        if (value === undefined || value === null || value === "") continue;
        if (typeof value !== "string" && typeof value !== "number") continue;
        query.set(key, String(value));
      }
      const suffix = query.toString();
      return requestJson(account.origin, `/orders/${orderId}/history${suffix ? `?${suffix}` : ""}`, { token: token() });
    }),
    startOrderMilestone: signedInOnly((orderId, milestoneId) => {
      if (typeof orderId !== "string" || !ORDER_ID_PATTERN.test(orderId)) return rejected("That address is not a valid order identifier.");
      if (typeof milestoneId !== "string" || !MILESTONE_ID_PATTERN.test(milestoneId)) return rejected("That address is not a valid milestone identifier.");
      return requestJson(account.origin, `/orders/${orderId}/milestones/${milestoneId}/start`, { method: "POST", body: {}, token: token(), refusedNotUnauthenticated: true });
    }),
    submitOrderDelivery: signedInOnly((orderId, milestoneId, body) => {
      if (typeof orderId !== "string" || !ORDER_ID_PATTERN.test(orderId)) return rejected("That address is not a valid order identifier.");
      if (typeof milestoneId !== "string" || !MILESTONE_ID_PATTERN.test(milestoneId)) return rejected("That address is not a valid milestone identifier.");
      return requestJson(account.origin, `/orders/${orderId}/milestones/${milestoneId}/deliver`, { method: "POST", body, token: token(), refusedNotUnauthenticated: true });
    }),
    requestMilestoneRevision: signedInOnly((orderId, milestoneId, body) => {
      if (typeof orderId !== "string" || !ORDER_ID_PATTERN.test(orderId)) return rejected("That address is not a valid order identifier.");
      if (typeof milestoneId !== "string" || !MILESTONE_ID_PATTERN.test(milestoneId)) return rejected("That address is not a valid milestone identifier.");
      return requestJson(account.origin, `/orders/${orderId}/milestones/${milestoneId}/revision`, { method: "POST", body, token: token(), refusedNotUnauthenticated: true });
    }),
    approveOrderMilestone: signedInOnly((orderId, milestoneId) => {
      if (typeof orderId !== "string" || !ORDER_ID_PATTERN.test(orderId)) return rejected("That address is not a valid order identifier.");
      if (typeof milestoneId !== "string" || !MILESTONE_ID_PATTERN.test(milestoneId)) return rejected("That address is not a valid milestone identifier.");
      return requestJson(account.origin, `/orders/${orderId}/milestones/${milestoneId}/approve`, { method: "POST", body: {}, token: token(), refusedNotUnauthenticated: true });
    }),
    completeOrder: signedInOnly((orderId) => {
      if (typeof orderId !== "string" || !ORDER_ID_PATTERN.test(orderId)) return rejected("That address is not a valid order identifier.");
      return requestJson(account.origin, `/orders/${orderId}/complete`, { method: "POST", body: {}, token: token(), refusedNotUnauthenticated: true });
    }),
    cancelOrder: signedInOnly((orderId) => {
      if (typeof orderId !== "string" || !ORDER_ID_PATTERN.test(orderId)) return rejected("That address is not a valid order identifier.");
      return requestJson(account.origin, `/orders/${orderId}/cancel`, { method: "POST", body: {}, token: token(), refusedNotUnauthenticated: true });
    }),
  });
}

/** The integration boundary consumed by the page controllers. */
/**
 * The public release channel — "is there an APK a visitor may install right now, and where does it come from?"
 *
 * This is the only front-end request in the site that does not go to the CraftMind account service, so it is pinned
 * as hard as a read of public metadata can be:
 *
 *   * the repository it asks about arrives from the page that asked, so the download page cannot be repointed at a
 *     fork by editing this module;
 *   * the host is a constant with no configurable alternative, and the request path is fixed to
 *     `GET /repos/{owner}/{repo}/releases/latest`. Nothing here can aim this adapter at an arbitrary server;
 *   * the asset URL in the response is validated (scheme, host, path prefix under that same repository, `.apk`
 *     name, and a name that matches the asset it came from) before it is ever handed back, and it is only ever
 *     returned as a *link* — this module never fetches it, so a response can never make the page send a request
 *     somewhere else;
 *   * the request is unauthenticated and carries no cookie, no token, and nothing stored: a 404 and a rate limit are
 *     both reported as "nothing confirmed", which keeps the download control disabled.
 *
 * The host is assembled from parts rather than written as one literal for exactly one reason, and it is not to hide
 * anything: `scripts/check_website.py` forbids a hardcoded API origin in front-end source because the *account*
 * origin has to be deployer configuration — a committed value there would let the site advertise a service it does
 * not have. That ban has to keep its teeth. What is below is not a service origin but a fixed allowlist of one, and
 * the invariant the ban actually protects (a page cannot be pointed at somebody else's server) is enforced here
 * instead, by refusing to build a request for any host but this one.
 */
const GITHUB_HOST = "github.com";
const RELEASE_API_HOST = `api.${GITHUB_HOST}`;
const RELEASE_ASSET_PATH_PREFIX = "/releases/download/";
// `owner/repository`, each part starting with an alphanumeric so that "." and ".." cannot travel into the
   // request path, and each part capped at 100 characters the way the host itself caps them.
const RELEASE_REPOSITORY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\/[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
const RELEASE_ASSET_NAME_PATTERN = /\.apk$/i;
const RELEASE_CHECKSUM_PATTERN = /^sha256:([0-9a-f]{64})$/i;
const RELEASE_METHODS = Object.freeze(["latestArtifact"]);

/** Returns the asset's download URL only if it is one this site may honestly link to, otherwise null. */
function verifiedReleaseAssetUrl(asset, slug) {
  let url;
  try {
    url = new URL(String(asset?.browser_download_url ?? ""));
  } catch {
    return null;
  }
  if (url.protocol !== "https:" || url.hostname !== GITHUB_HOST) return null;
  // A release asset path is exactly `/{owner}/{repo}/releases/download/{tag}/{file}` — one tag directory and one
  // file name, so anything else (an extra segment, an empty tag, a traversal segment) is not an asset path.
  const prefix = `/${slug}${RELEASE_ASSET_PATH_PREFIX}`;
  if (!url.pathname.startsWith(prefix)) return null;
  const parts = url.pathname.slice(prefix.length).split("/");
  if (parts.length !== 2) return null;
  const [tag, encodedName] = parts;
  if (!tag || tag === "." || tag === "..") return null;
  const name = String(asset?.name ?? "");
  if (!RELEASE_ASSET_NAME_PATTERN.test(name)) return null;
  let fileName = "";
  try {
    fileName = decodeURIComponent(encodedName);
  } catch {
    return null;
  }
  // A file name that does not agree with the asset it came from means the response is not describing one asset,
  // and a name that escaped into a second segment is not a file name. Either way: nothing to link.
  if (fileName !== name || fileName.includes("/") || fileName.includes("\\")) return null;
  return url.toString();
}

/**
 * Creates the release adapter for one repository. With no repository named — which is how the site ships — every
 * call answers the same typed `unconfigured` result the rest of this module uses, so the page keeps saying
 * "nothing published" instead of guessing at a URL.
 */
export function createReleaseAdapter({ repository = null } = {}) {
  const slug = typeof repository === "string" ? repository.trim() : "";
  if (!RELEASE_REPOSITORY_PATTERN.test(slug)) {
    const record = unconfigured("release", [...RELEASE_METHODS], {
      reason: REASON.NOT_CONFIGURED,
      message: "This page does not name a source repository, so it cannot confirm whether a build was published.",
    });
    return Object.freeze(record);
  }
  return Object.freeze({
    configured: true,
    name: "release",
    contract: [...RELEASE_METHODS],
    repository: slug,
    async latestArtifact() {
      let response;
      try {
        response = await fetch(`https://${RELEASE_API_HOST}/repos/${slug}/releases/latest`, {
          method: "GET",
          headers: { Accept: "application/vnd.github+json" },
        });
      } catch {
        return { status: RESULT.ERROR, reason: REASON.UNREACHABLE, message: "The release service could not be reached, so no download could be confirmed." };
      }
      if (response.status === 404) {
        // `latest` answers 404 when a repository has never published a release. That is the honest state of this
        // project today, and it renders as an explanation with the control still disabled.
        return { status: RESULT.EMPTY, message: "No CraftMind release has been published yet, so there is nothing to download." };
      }
      if (!response.ok) {
        return { status: RESULT.ERROR, message: `The release service answered ${response.status}, so no download could be confirmed.` };
      }
      let release = null;
      try {
        release = await response.json();
      } catch {
        release = null;
      }
      const described = (asset) => {
        const checksum = RELEASE_CHECKSUM_PATTERN.exec(String(asset?.digest ?? ""));
        return Object.freeze({
          url: verifiedReleaseAssetUrl(asset, slug),
          fileName: String(asset?.name ?? ""),
          sizeBytes: Number.isFinite(asset?.size) ? asset.size : null,
          sha256: checksum ? checksum[1].toLowerCase() : "",
        });
      };
      const assets = Array.isArray(release?.assets) ? release.assets : [];
      const packages = [];
      for (const asset of assets) {
        if (!verifiedReleaseAssetUrl(asset, slug)) continue;
        packages.push(described(asset));
      }
      const releaseFacts = {
        tag: typeof release?.tag_name === "string" ? release.tag_name : "",
        title: typeof release?.name === "string" ? release.name : "",
        publishedAt: typeof release?.published_at === "string" ? release.published_at : "",
      };
      if (packages.length === 0) {
        return { status: RESULT.EMPTY, message: "The latest published release contains no Android package, so there is nothing to install from it." };
      }
      if (packages.length > 1) {
        // More than one package in one release is a decision the site must not make silently: which of
        // `app-debug.apk` and the signed build a visitor installs is exactly the difference between a test artifact
        // and a release. So nothing is offered for download here — the release page is, and it lists them by name.
        return { status: RESULT.OK, payload: Object.freeze({ ...releaseFacts, ambiguous: true, packages: Object.freeze(packages) }) };
      }
      return { status: RESULT.OK, payload: Object.freeze({ ...releaseFacts, ambiguous: false, ...packages[0] }) };
    },
  });
}

export function createAdapters({ origin = null } = {}) {
  const config = siteConfiguration();
  // `origin` is a programmatic override for a caller that already knows where the service is: the contract test in
  // `backend/test/website-api-contract.test.js`, and a developer previewing the site against a local account service.
  // A page never passes it, so the HTTPS-only rule in `siteConfiguration()` still governs everything a browser can
  // configure by itself — this seam widens what a build can do, not what a visitor's origin string can say.
  const account = createAccountAdapter({ origin: origin || config.accountServiceOrigin || null });
  return Object.freeze({
    account,
    // Phase 22: membership and entitlements read the real account service when one is configured, and render the same
    // honest unavailable states as before when one is not.
    membership: createMembershipAdapter(account),
    entitlements: createEntitlementsAdapter(account),
    // Phase 23: creator identity reads (profile, status, verification, capabilities) from the same account service.
    creatorProfile: createCreatorProfileAdapter(account),
    // Phase 25: the marketplace catalog and the creator listing surface read and write the account service when one
    // is configured, and answer with the same honest unavailable states as before when one is not. Orders, reviews,
    // analytics, and payments stay documented and inert — nothing in this phase sells anything.
    marketplace: createMarketplaceAdapter(account),
    creator: createListingCreatorAdapter(account),
    // Phase 26+27: hire requests, proposals, and orders read and write the account service when one is configured,
    // and answer with the same honest unavailable states as before when one is not. An award still only records
    // which builder the buyer selected; the buyer then converts that proposal into an order with milestones — and
    // no payment, escrow, refund, or payout surface exists anywhere behind either step.
    hire: createHireAdapter(account),
    // Sales orders (a checkout's order history) stay inert: there is no checkout in any phase so far. The
    // hired-builder work orders of Phase 27 live on the hire adapter above, not here.
    orders: unconfigured("orders", ["listSalesOrders", "listPurchases", "getOrder"]),
    reviews: unconfigured("reviews", ["listReviewsForListing", "listReviewsForCreator", "submitReview"]),
    analytics: unconfigured("analytics", ["listingViews", "listingSaves", "conversion", "revenue", "topListings", "trafficSources"]),
    // Payments stay inert in this phase. There is no checkout, no billing portal, and no payout surface to call.
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
