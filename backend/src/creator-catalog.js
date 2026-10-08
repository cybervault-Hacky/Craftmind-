/**
 * The creator vocabulary (Phase 23): statuses, verification states, capabilities, limits, and handle rules.
 *
 * Everything here is a definition, not a behaviour: the profile store, the entitlement engine, and the developer tools
 * all read these tables instead of inventing their own values. Three boundaries are deliberate:
 *
 *   * **Status is not membership.** Membership answers "does this account hold the Creator plan?" — a Phase 22 fact.
 *     Creator status answers "is this creator profile currently allowed to operate?" A suspended profile and a live
 *     Creator membership can coexist, and authorization consults both.
 *   * **A capability is not a service.** A capability key says what an account *may* do; the service behind it may not
 *     exist yet. Every capability therefore carries an explicit state (`AVAILABLE` or `FUTURE`), and a `FUTURE`
 *     capability is never reported as usable. Publishing, analytics, reviews, and team seats are `FUTURE` here.
 *   * **A limit is not a policy we invented.** Limits that a real product decision has not been made about are
 *     recorded as `NOT_IMPLEMENTED` with the reason, never as a silent number that starts refusing work.
 */

/** Profile lifecycle, independent of membership. */
export const CREATOR_STATUS = Object.freeze({
  ACTIVE: "ACTIVE",
  PENDING: "PENDING",
  SUSPENDED: "SUSPENDED",
  DISABLED: "DISABLED",
});

export const CREATOR_STATUS_REGISTRY = Object.freeze([
  Object.freeze({
    status: CREATOR_STATUS.ACTIVE,
    label: "Active",
    description: "The profile exists and is allowed to operate.",
    publicReadable: true,
    assignableByTool: true,
  }),
  Object.freeze({
    status: CREATOR_STATUS.PENDING,
    label: "Pending review",
    description: "Defined for a future review workflow. No code path assigns it in this phase, and a pending profile is not publicly readable.",
    publicReadable: false,
    assignableByTool: false,
  }),
  Object.freeze({
    status: CREATOR_STATUS.SUSPENDED,
    label: "Suspended",
    description: "A developer suspended the profile. Protected operations are refused and the profile is not publicly readable; the record is preserved.",
    publicReadable: false,
    assignableByTool: true,
  }),
  Object.freeze({
    status: CREATOR_STATUS.DISABLED,
    label: "Disabled",
    description: "The profile is permanently closed to operation while its history is retained.",
    publicReadable: false,
    assignableByTool: true,
  }),
]);

const CREATOR_STATUS_BY_ID = new Map(CREATOR_STATUS_REGISTRY.map((entry) => [entry.status, entry]));

export function creatorStatusDefinition(status) {
  return CREATOR_STATUS_BY_ID.get(status) ?? null;
}

export function isCreatorStatus(value) {
  return CREATOR_STATUS_BY_ID.has(value);
}

export function isPubliclyReadableCreatorStatus(status) {
  return CREATOR_STATUS_BY_ID.get(status)?.publicReadable === true;
}

/**
 * Creator verification: an internal CraftMind state, **not** identity or payment verification. There is no KYC here —
 * no government identity, no document upload, no biometric check, and no payment instrument. Only a developer tool
 * moves this state, and every move is audited.
 */
export const CREATOR_VERIFICATION = Object.freeze({
  UNVERIFIED: "UNVERIFIED",
  PENDING: "PENDING",
  VERIFIED: "VERIFIED",
  REVOKED: "REVOKED",
});

export const CREATOR_VERIFICATION_REGISTRY = Object.freeze([
  Object.freeze({
    state: CREATOR_VERIFICATION.UNVERIFIED,
    label: "Not verified",
    description: "The default: the profile exists and has been through no verification review.",
  }),
  Object.freeze({
    state: CREATOR_VERIFICATION.PENDING,
    label: "Verification pending",
    description: "A review has been requested. The state itself grants nothing; it is a developer-visible marker.",
  }),
  Object.freeze({
    state: CREATOR_VERIFICATION.VERIFIED,
    label: "Verified",
    description: "A developer recorded an internal verification. This is not identity, payment, or government verification.",
  }),
  Object.freeze({
    state: CREATOR_VERIFICATION.REVOKED,
    label: "Verification revoked",
    description: "A previously recorded verification was withdrawn. The profile keeps operating; the marker is removed.",
  }),
]);

const CREATOR_VERIFICATION_BY_STATE = new Map(CREATOR_VERIFICATION_REGISTRY.map((entry) => [entry.state, entry]));

export function creatorVerificationDefinition(state) {
  return CREATOR_VERIFICATION_BY_STATE.get(state) ?? null;
}

export function isCreatorVerification(value) {
  return CREATOR_VERIFICATION_BY_STATE.has(value);
}

/** States a developer tool may set. `UNVERIFIED` is the creation default, not a tool action. */
export const ASSIGNABLE_CREATOR_VERIFICATION = Object.freeze([
  CREATOR_VERIFICATION.PENDING,
  CREATOR_VERIFICATION.VERIFIED,
  CREATOR_VERIFICATION.REVOKED,
]);

/**
 * Typed creator capabilities.
 *
 * `CREATOR_PROFILE`, `CREATOR_DASHBOARD`, and `CREATOR_MANAGE_OWN_CONTENT` describe systems that exist in this phase
 * (profile storage, the eligibility read, and ownership-checked profile updates). Everything else is `FUTURE`: the
 * capability is named so Phase 24 can consume it by key, and it is reported as unavailable with the reason, so no
 * interface can claim publishing, analytics, or reviews as if they worked.
 */
export const CREATOR_CAPABILITY = Object.freeze({
  CREATOR_PROFILE: "CREATOR_PROFILE",
  CREATOR_DASHBOARD: "CREATOR_DASHBOARD",
  CREATOR_MANAGE_OWN_CONTENT: "CREATOR_MANAGE_OWN_CONTENT",
  CREATOR_ANALYTICS: "CREATOR_ANALYTICS",
  CREATOR_REVIEWS: "CREATOR_REVIEWS",
  CREATOR_PUBLISH: "CREATOR_PUBLISH",
});

export const CREATOR_CAPABILITY_REGISTRY = Object.freeze([
  Object.freeze({
    key: CREATOR_CAPABILITY.CREATOR_PROFILE,
    label: "Creator profile",
    state: "AVAILABLE",
    description: "Read and update the creator profile owned by this account.",
  }),
  Object.freeze({
    key: CREATOR_CAPABILITY.CREATOR_DASHBOARD,
    label: "Creator dashboard",
    state: "AVAILABLE",
    description: "Read the account's creator state, status, and verification marker.",
  }),
  Object.freeze({
    key: CREATOR_CAPABILITY.CREATOR_MANAGE_OWN_CONTENT,
    label: "Manage own content",
    state: "AVAILABLE",
    description: "Ownership-checked management of resources this account owns. The content itself arrives with the marketplace phase.",
  }),
  Object.freeze({
    key: CREATOR_CAPABILITY.CREATOR_ANALYTICS,
    label: "Creator analytics",
    state: "FUTURE",
    description: "Analytics over listings and sales.",
    unavailableReason: "No creator analytics service exists yet: nothing is collected, so there is nothing to report.",
  }),
  Object.freeze({
    key: CREATOR_CAPABILITY.CREATOR_REVIEWS,
    label: "Creator reviews",
    state: "FUTURE",
    description: "Buyer reviews of a creator's listings.",
    unavailableReason: "Reviews belong to the marketplace phase; no review can be written or read yet.",
  }),
  Object.freeze({
    key: CREATOR_CAPABILITY.CREATOR_PUBLISH,
    label: "Publish content",
    state: "FUTURE",
    description: "Publish a listing to the marketplace.",
    unavailableReason: "Marketplace publishing is not implemented. Nothing can be published, and no request pretends otherwise.",
  }),
]);

const CREATOR_CAPABILITY_BY_KEY = new Map(CREATOR_CAPABILITY_REGISTRY.map((entry) => [entry.key, entry]));

export function creatorCapabilityDefinition(key) {
  return CREATOR_CAPABILITY_BY_KEY.get(key) ?? null;
}

export function isCreatorCapability(key) {
  return CREATOR_CAPABILITY_BY_KEY.has(key);
}

/**
 * Creator limits, centralized. `profilesPerAccount` is a structural rule (one profile per account, one account per
 * profile) rather than a commercial one. The rest are explicitly not implemented: naming a number for owned content,
 * content size, or analytics would be inventing a product policy that this phase has not decided.
 */
export const CREATOR_LIMITS = Object.freeze({
  profilesPerAccount: Object.freeze({
    state: "ENFORCED",
    value: 1,
    reason: "A creator profile is one identity per account: the database enforces UNIQUE(user_id).",
  }),
  ownedContent: Object.freeze({
    state: "NOT_IMPLEMENTED",
    reason: "No marketplace content exists yet, so no content limit is enforced or advertised.",
  }),
  contentSize: Object.freeze({
    state: "NOT_IMPLEMENTED",
    reason: "Build artefacts keep the existing BuildPlan limits; no separate creator content quota exists.",
  }),
  analytics: Object.freeze({
    state: "NOT_IMPLEMENTED",
    reason: "No analytics service exists, so there is no analytics limit to enforce.",
  }),
});

/** Field bounds for profile payloads. They mirror the lengths the public profile page documents. */
export const CREATOR_FIELD_LIMITS = Object.freeze({
  displayName: Object.freeze({ minimum: 1, maximum: 40 }),
  bio: Object.freeze({ minimum: 0, maximum: 600 }),
  category: Object.freeze({ values: Object.freeze(["Structures", "Landscaping", "Interiors", "Redstone"]) }),
  avatarReference: Object.freeze({ maximum: 300 }),
});

export const CREATOR_CATEGORIES = CREATOR_FIELD_LIMITS.category.values;

/**
 * Paths a creator handle must never shadow. They are the site's own sections and the service's own routes; a handle
 * that collided with one would make `/creators/<handle>` indistinguishable from a real page, so the handle is refused
 * rather than allowed to create a confusing alias.
 */
export const RESERVED_HANDLES = Object.freeze([
  "admin", "administrator", "api", "account", "accounts", "auth", "build", "builds", "checkout", "creator", "creators",
  "developer", "developers", "download", "faq", "features", "health", "how-it-works", "login", "logout", "marketplace",
  "membership", "memberships", "moderator", "privacy", "profile", "root", "security", "server", "servers", "settings",
  "signin", "signout", "signup", "staff", "static", "support", "system", "terms", "user", "users", "www",
]);

const RESERVED_HANDLE_SET = new Set(RESERVED_HANDLES);
/**
 * The same words with their separators removed, so the reserved check cannot be walked around with punctuation:
 * `market_place`, `market-place`, and `marketplace` are one word to a visitor, and all three must resolve to the same
 * refusal. Collapsing both sides is what makes that true — `how-it-works` stays reserved while `howitworks` is caught
 * as the same path.
 */
const RESERVED_HANDLE_COLLAPSED = new Set(RESERVED_HANDLES.map((word) => word.toLowerCase().replace(/[^a-z0-9]+/g, "")));

export const HANDLE_RULES = Object.freeze({
  minimumLength: 3,
  maximumLength: 32,
  /** Lowercase letters, digits, and single interior hyphens. */
  pattern: /^[a-z0-9]+(?:-[a-z0-9]+)*$/,
});

export function isReservedHandle(handle) {
  const normalized = String(handle ?? "").toLowerCase();
  if (RESERVED_HANDLE_SET.has(normalized)) return true;
  return RESERVED_HANDLE_COLLAPSED.has(normalized.replace(/[^a-z0-9]+/g, ""));
}

/**
 * Normalizes a client-supplied handle deterministically, or explains why it cannot be used.
 *
 * Normalization is deliberately conservative: whitespace and underscores become hyphens, the value is lowercased, and
 * repeated or edge hyphens are collapsed. Anything outside `[a-z0-9-_ ]` is *refused* rather than silently stripped —
 * "café-studio" must not quietly become "caf-studio", because the creator would then own a handle they never asked for.
 *
 * Returns `{ ok: true, handle }` or `{ ok: false, code }` where `code` is one of `INVALID`, `RESERVED`, `TOO_SHORT`,
 * `TOO_LONG`.
 */
export function normalizeHandle(input) {
  if (typeof input !== "string") return { ok: false, code: "INVALID" };
  const collapsed = input.trim().toLowerCase().replace(/[\s_]+/g, "-").replace(/-+/g, "-").replace(/^-|-$/g, "");
  if (collapsed.length === 0 || !/^[a-z0-9-]+$/.test(collapsed)) return { ok: false, code: "INVALID" };
  if (collapsed.length < HANDLE_RULES.minimumLength) return { ok: false, code: "TOO_SHORT" };
  if (collapsed.length > HANDLE_RULES.maximumLength) return { ok: false, code: "TOO_LONG" };
  if (!HANDLE_RULES.pattern.test(collapsed)) return { ok: false, code: "INVALID" };
  if (isReservedHandle(collapsed)) return { ok: false, code: "RESERVED" };
  return { ok: true, handle: collapsed };
}

/** Public-safe creator profile projection. Nothing here is derived from the private account record. */
export function toPublicCreatorProfile(row) {
  if (!row) return null;
  return Object.freeze({
    handle: row.handle,
    displayName: row.display_name,
    bio: row.bio ?? "",
    category: row.category ?? null,
    avatarReference: row.avatar_reference ?? null,
    verification: row.verification_status,
    verified: row.verification_status === CREATOR_VERIFICATION.VERIFIED,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  });
}

/** The owner's own view: the public projection plus the private-but-own state. Never includes account or session data. */
export function toOwnerCreatorProfile(row) {
  if (!row) return null;
  const definition = creatorStatusDefinition(row.status);
  return Object.freeze({
    ...toPublicCreatorProfile(row),
    status: row.status,
    statusLabel: definition?.label ?? row.status,
    verification: row.verification_status,
    verificationLabel: creatorVerificationDefinition(row.verification_status)?.label ?? row.verification_status,
    statusChangedAt: row.status_changed_at,
    verifiedAt: row.verified_at ?? null,
  });
}
