/**
 * The membership plan catalog and the typed entitlement registry (Phase 22).
 *
 * This file is deliberately pure: it contains no database access and no request handling. It answers exactly two
 * questions for the rest of the engine:
 *
 *   1. What is a plan, and what does it grant?
 *   2. What does an entitlement key mean?
 *
 * Three product rules shape the catalog, and all three are enforced here rather than in a route handler:
 *
 *   * **No plan is purchasable in this phase.** `purchasable` is `false` for every plan, and there is no price field at
 *     all. A plan's availability describes whether it can be *advertised as available today*, not whether it can be
 *     bought. FREE is available today; PRO, CREATOR, and SERVER exist as definitions only.
 *   * **A plan can be granted without being purchasable.** `grantable` is what an authorized developer tool checks, so
 *     an internal evaluation grant never implies a working checkout.
 *   * **Every limit is configuration, not a literal.** Daily generation allowances and promotional credit allocations
 *     are read from `configuration.membership`, so no route, tool, or screen hardcodes a number.
 *
 * Plans are cumulative tiers: SERVER includes everything CREATOR includes, which includes PRO, which includes FREE. The
 * invariant is validated at module load, so an edit to one list cannot silently strip a lower tier's entitlement.
 */

/** Stable plan identifiers. These are the only values the database accepts. */
export const MEMBERSHIP_PLAN = Object.freeze({
  FREE: "FREE",
  PRO: "PRO",
  CREATOR: "CREATOR",
  SERVER: "SERVER",
});

/** Membership lifecycle states. There is no billing-derived state (no PAST_DUE, no TRIALING) because nothing bills. */
export const MEMBERSHIP_STATUS = Object.freeze({
  ACTIVE: "ACTIVE",
  EXPIRED: "EXPIRED",
  CANCELLED: "CANCELLED",
  PENDING: "PENDING",
  UNAVAILABLE: "UNAVAILABLE",
});

/** Where a membership state came from. Payment is not an option, and will not be until a payment phase exists. */
export const MEMBERSHIP_SOURCE = Object.freeze({
  DEFAULT_BASELINE: "DEFAULT_BASELINE",
  DEVELOPER_GRANT: "DEVELOPER_GRANT",
  BASELINE_FALLBACK: "BASELINE_FALLBACK",
});

/** Product availability: whether the plan may be presented as something a user has or can obtain today. */
export const PLAN_AVAILABILITY = Object.freeze({
  AVAILABLE_TODAY: "AVAILABLE_TODAY",
  UNAVAILABLE: "UNAVAILABLE",
});

/** How a membership entry is described in the interface and in audit records. */
export const MEMBERSHIP_STATE_ORDER = Object.freeze([
  MEMBERSHIP_STATUS.ACTIVE,
  MEMBERSHIP_STATUS.PENDING,
  MEMBERSHIP_STATUS.EXPIRED,
  MEMBERSHIP_STATUS.CANCELLED,
  MEMBERSHIP_STATUS.UNAVAILABLE,
]);

/**
 * Typed entitlement identifiers. Every protected capability asks for one of these keys instead of comparing plan
 * strings, so a plan can be re-tiered later without touching a single feature check.
 */
export const ENTITLEMENT = Object.freeze({
  BUILD_GENERATION: "BUILD_GENERATION",
  LOCAL_BUILD_HISTORY: "LOCAL_BUILD_HISTORY",
  VISUAL_REFERENCE: "VISUAL_REFERENCE",
  ADVANCED_AI: "ADVANCED_AI",
  HIGHER_GENERATION_LIMITS: "HIGHER_GENERATION_LIMITS",
  CREATOR_TOOLS: "CREATOR_TOOLS",
  CREATOR_ANALYTICS: "CREATOR_ANALYTICS",
  SERVER_TOOLS: "SERVER_TOOLS",
  TEAM_WORKSPACE: "TEAM_WORKSPACE",
});

/**
 * The registry that gives every key a stable public meaning. The order is the order the interface lists them in, which
 * is why it is frozen: an accidental reorder would silently change a screenshot, a test, and a support conversation.
 */
export const ENTITLEMENT_REGISTRY = Object.freeze([
  Object.freeze({
    key: ENTITLEMENT.BUILD_GENERATION,
    label: "Build generation",
    description: "Generate a validated BuildPlan from a description, with your own AI provider key.",
    plan: MEMBERSHIP_PLAN.FREE,
  }),
  Object.freeze({
    key: ENTITLEMENT.LOCAL_BUILD_HISTORY,
    label: "Local build history",
    description: "Keep every accepted plan on your own device.",
    plan: MEMBERSHIP_PLAN.FREE,
  }),
  Object.freeze({
    key: ENTITLEMENT.VISUAL_REFERENCE,
    label: "One visual reference per request",
    description: "Attach one optional image or reference URL to a generation request.",
    plan: MEMBERSHIP_PLAN.FREE,
  }),
  Object.freeze({
    key: ENTITLEMENT.HIGHER_GENERATION_LIMITS,
    label: "Higher generation allowance",
    description: "A larger daily generation allowance than the free baseline.",
    plan: MEMBERSHIP_PLAN.PRO,
  }),
  Object.freeze({
    key: ENTITLEMENT.ADVANCED_AI,
    label: "Advanced AI building tools",
    description: "Advanced planning and refinement tooling beyond the free baseline.",
    plan: MEMBERSHIP_PLAN.PRO,
  }),
  Object.freeze({
    key: ENTITLEMENT.CREATOR_TOOLS,
    label: "Creator tools",
    description: "Creator studio surfaces for preparing and reviewing listings.",
    plan: MEMBERSHIP_PLAN.CREATOR,
  }),
  Object.freeze({
    key: ENTITLEMENT.CREATOR_ANALYTICS,
    label: "Creator analytics",
    description: "Analytics views for a creator's own listings, once a real analytics service exists.",
    plan: MEMBERSHIP_PLAN.CREATOR,
  }),
  Object.freeze({
    key: ENTITLEMENT.SERVER_TOOLS,
    label: "Server tools",
    description: "Server-oriented planning and build workflows.",
    plan: MEMBERSHIP_PLAN.SERVER,
  }),
  Object.freeze({
    key: ENTITLEMENT.TEAM_WORKSPACE,
    label: "Team workspace",
    description: "Shared workspace capabilities for more than one operator.",
    plan: MEMBERSHIP_PLAN.SERVER,
  }),
]);

const ENTITLEMENT_KEYS = Object.freeze(ENTITLEMENT_REGISTRY.map((entry) => entry.key));
const ENTITLEMENT_BY_KEY = new Map(ENTITLEMENT_REGISTRY.map((entry) => [entry.key, entry]));

/** True when a key is in the registry. An unknown key is a programming error, never an accidental grant. */
export function isRegisteredEntitlement(key) {
  return ENTITLEMENT_BY_KEY.has(key);
}

/** The registry entry for a key, or `null`. */
export function entitlementDefinition(key) {
  return ENTITLEMENT_BY_KEY.get(key) ?? null;
}

/** Every registered key, in registry order. */
export function entitlementKeys() {
  return [...ENTITLEMENT_KEYS];
}

const FREE_ENTITLEMENTS = Object.freeze([
  ENTITLEMENT.BUILD_GENERATION,
  ENTITLEMENT.LOCAL_BUILD_HISTORY,
  ENTITLEMENT.VISUAL_REFERENCE,
]);

const PRO_ENTITLEMENTS = Object.freeze([
  ...FREE_ENTITLEMENTS,
  ENTITLEMENT.HIGHER_GENERATION_LIMITS,
  ENTITLEMENT.ADVANCED_AI,
]);

const CREATOR_ENTITLEMENTS = Object.freeze([
  ...PRO_ENTITLEMENTS,
  ENTITLEMENT.CREATOR_TOOLS,
  ENTITLEMENT.CREATOR_ANALYTICS,
]);

const SERVER_ENTITLEMENTS = Object.freeze([
  ...CREATOR_ENTITLEMENTS,
  ENTITLEMENT.SERVER_TOOLS,
  ENTITLEMENT.TEAM_WORKSPACE,
]);

const PLAN_ENTITLEMENTS = Object.freeze({
  [MEMBERSHIP_PLAN.FREE]: FREE_ENTITLEMENTS,
  [MEMBERSHIP_PLAN.PRO]: PRO_ENTITLEMENTS,
  [MEMBERSHIP_PLAN.CREATOR]: CREATOR_ENTITLEMENTS,
  [MEMBERSHIP_PLAN.SERVER]: SERVER_ENTITLEMENTS,
});

/**
 * Builds the resolved catalog from configuration. Nothing in the returned object is a price: `creditPolicy` describes
 * an internal promotional allocation that only an authorized backend tool or a plan grant can apply.
 *
 * @param {{ membership?: object }} [configuration]
 */
export function planCatalog(configuration = {}) {
  const limits = configuration.membership ?? {};
  const freeGenerations = limits.freeDailyGenerations ?? 10;
  const expiryDays = limits.creditExpiryDays ?? 90;
  const grantDays = limits.maximumPlanGrantDays ?? 365;
  const plans = [
    {
      id: MEMBERSHIP_PLAN.FREE,
      name: "Free",
      summary: "The CraftMind experience that exists today: bring your own AI key, plan locally, review every build.",
      availability: PLAN_AVAILABILITY.AVAILABLE_TODAY,
      availabilityLabel: "Available today — no charge",
      priceState: "No price applies",
      purchasable: false,
      grantable: false,
      generationAllowance: Object.freeze({
        perDay: freeGenerations,
        enforced: false,
        note: "Recorded for the future AI gateway. No allowance is enforced by this phase, and no request is blocked by it.",
      }),
      creditPolicy: Object.freeze({
        promotionalCredits: limits.freePromotionalCredits ?? 0,
        expiresInDays: expiryDays,
        note: "The free plan grants no credits by default. A developer tool may grant promotional credits explicitly.",
      }),
      entitlements: PLAN_ENTITLEMENTS[MEMBERSHIP_PLAN.FREE],
    },
    {
      id: MEMBERSHIP_PLAN.PRO,
      name: "Pro",
      summary: "For builders who want more room: higher generation limits and advanced AI building tools.",
      availability: PLAN_AVAILABILITY.UNAVAILABLE,
      availabilityLabel: "Planned — not purchasable",
      priceState: "Not priced yet",
      purchasable: false,
      grantable: true,
      generationAllowance: Object.freeze({
        perDay: freeGenerations * 10,
        enforced: false,
        note: "Documented configuration only. Enforcement arrives with the AI gateway phase.",
      }),
      creditPolicy: Object.freeze({
        promotionalCredits: limits.proPromotionalCredits ?? 200,
        expiresInDays: expiryDays,
        note: "An internal promotional allocation applied only when an authorized tool grants this plan.",
      }),
      entitlements: PLAN_ENTITLEMENTS[MEMBERSHIP_PLAN.PRO],
    },
    {
      id: MEMBERSHIP_PLAN.CREATOR,
      name: "Creator",
      summary: "For builders who publish: creator tools, marketplace listings, and analytics.",
      availability: PLAN_AVAILABILITY.UNAVAILABLE,
      availabilityLabel: "Planned — not purchasable",
      priceState: "Not priced yet",
      purchasable: false,
      grantable: true,
      generationAllowance: Object.freeze({
        perDay: freeGenerations * 25,
        enforced: false,
        note: "Documented configuration only. Enforcement arrives with the AI gateway phase.",
      }),
      creditPolicy: Object.freeze({
        promotionalCredits: limits.creatorPromotionalCredits ?? 500,
        expiresInDays: expiryDays,
        note: "An internal promotional allocation applied only when an authorized tool grants this plan.",
      }),
      entitlements: PLAN_ENTITLEMENTS[MEMBERSHIP_PLAN.CREATOR],
    },
    {
      id: MEMBERSHIP_PLAN.SERVER,
      name: "Server",
      summary: "For servers and teams: server-oriented features and shared workspace capabilities.",
      availability: PLAN_AVAILABILITY.UNAVAILABLE,
      availabilityLabel: "Planned — not purchasable",
      priceState: "Not priced yet",
      purchasable: false,
      grantable: true,
      generationAllowance: Object.freeze({
        perDay: freeGenerations * 50,
        enforced: false,
        note: "Documented configuration only. Enforcement arrives with the AI gateway phase.",
      }),
      creditPolicy: Object.freeze({
        promotionalCredits: limits.serverPromotionalCredits ?? 1_000,
        expiresInDays: expiryDays,
        note: "An internal promotional allocation applied only when an authorized tool grants this plan.",
      }),
      entitlements: PLAN_ENTITLEMENTS[MEMBERSHIP_PLAN.SERVER],
    },
  ];
  if (grantDays <= 0) throw new RangeError("membership: maximumPlanGrantDays must be positive");
  return Object.freeze({
    plans: Object.freeze(plans.map((plan) => Object.freeze(plan))),
    byId: Object.freeze(Object.fromEntries(plans.map((plan) => [plan.id, plan]))),
    maximumPlanGrantDays: grantDays,
    creditExpiryDays: expiryDays,
  });
}

/** The plans a developer tool may grant: everything that is not the implicit free baseline. */
export function grantablePlans(catalog = planCatalog()) {
  return catalog.plans.filter((plan) => plan.grantable).map((plan) => plan.id);
}

/** Resolves a plan id, or `null`. Callers decide whether an unknown id is a rejection or a fallback. */
export function planDefinition(planId, catalog = planCatalog()) {
  return catalog.byId[planId] ?? null;
}

/** A plan's entitlement keys, or an empty list for an unknown plan. */
export function planEntitlements(planId) {
  return [...(PLAN_ENTITLEMENTS[planId] ?? [])];
}

// ---------------------------------------------------------------------------------------------- load-time invariants

/**
 * The tier invariant is checked when the module loads, so a partially edited catalog cannot grant less than a lower
 * tier. `Object.freeze` above prevents mutation after this point.
 */
function assertCumulativeTiers() {
  const order = [MEMBERSHIP_PLAN.FREE, MEMBERSHIP_PLAN.PRO, MEMBERSHIP_PLAN.CREATOR, MEMBERSHIP_PLAN.SERVER];
  for (let index = 1; index < order.length; index += 1) {
    const lower = new Set(PLAN_ENTITLEMENTS[order[index - 1]]);
    const missing = [...lower].filter((key) => !PLAN_ENTITLEMENTS[order[index]].includes(key));
    if (missing.length > 0) {
      throw new Error(`membership plan ${order[index]} is missing lower-tier entitlements: ${missing.join(", ")}`);
    }
  }
  for (const plan of [MEMBERSHIP_PLAN.FREE, MEMBERSHIP_PLAN.PRO, MEMBERSHIP_PLAN.CREATOR, MEMBERSHIP_PLAN.SERVER]) {
    const unknown = PLAN_ENTITLEMENTS[plan].filter((key) => !isRegisteredEntitlement(key));
    if (unknown.length > 0) throw new Error(`membership plan ${plan} references unregistered entitlements: ${unknown.join(", ")}`);
  }
  if (FREE_ENTITLEMENTS.length === 0) throw new Error("the free baseline must grant at least one entitlement");
}

assertCumulativeTiers();
