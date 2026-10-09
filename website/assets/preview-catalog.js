/**
 * Preview catalog (Phase 21).
 *
 * There is no marketplace backend and no real listing inventory. To let the interface be designed and reviewed, this
 * module holds a small set of *sample* records that are rendered only when a reviewer explicitly asks for preview mode
 * (`?preview=1` on a marketplace or creator page). Every screen that renders one of them also renders a visible
 * "Preview data" banner, and every card carries a Preview badge.
 *
 * Rules this file exists to enforce:
 *   - nothing here is presented as purchasable inventory, real sales, real earnings, or real reviews;
 *   - prices are sample strings, never a real CraftMind price, and every purchase control stays unavailable;
 *   - `DEMO_LISTINGS`, `DEMO_ORDERS`, `DEMO_REVIEWS`, and `DEMO_ANALYTICS` are named "DEMO" in code for the same reason
 *     they are labelled "Preview" in the interface.
 */

const SAMPLE_CREATORS = Object.freeze([
  { handle: "sample-nordic", displayName: "Sample Studio · Nordic", initials: "SN" },
  { handle: "sample-terra", displayName: "Sample Studio · Terra", initials: "ST" },
  { handle: "sample-redstone", displayName: "Sample Studio · Redstone", initials: "SR" },
]);

/**
 * Sample creator records carry only the fields the interface renders: a display name and initials. No follower count,
 * joined date, rating, or listing total is stored, because none of those can be true for a sample record.
 */

/** Sample listings. `priceLabel` is illustrative only: no CraftMind price list exists in this phase. */
export const DEMO_LISTINGS = Object.freeze([
  {
    id: "demo-nordic-longhouse",
    title: "Sample build · Nordic longhouse",
    creator: "sample-nordic",
    category: "Structures",
    subcategory: "Housing",
    buildType: "ORIGINAL_DESIGN",
    difficulty: "INTERMEDIATE",
    summary: "A sample record used to design the marketplace card, detail, and preview layouts.",
    tags: ["timber", "roof", "interior"],
    edition: "java",
    minecraftVersion: "1.20.1",
    loader: "Fabric",
    loaderVersion: "0.16.10",
    priceLabel: "Sample price · not set",
    cover: "placeholder",
    screenshots: 3,
    updatedAt: null,
  },
  {
    id: "demo-terra-garden",
    title: "Sample build · Terraced garden",
    creator: "sample-terra",
    category: "Landscaping",
    subcategory: "Gardens",
    buildType: "ORIGINAL_DESIGN",
    difficulty: "BEGINNER",
    summary: "A sample record used to design the marketplace card, detail, and preview layouts.",
    tags: ["terrain", "water", "path"],
    edition: "java",
    minecraftVersion: "1.20.1",
    loader: "Fabric",
    loaderVersion: "0.16.10",
    priceLabel: "Sample price · not set",
    cover: "placeholder",
    screenshots: 2,
    updatedAt: null,
  },
  {
    id: "demo-redstone-hall",
    title: "Sample build · Redstone hall",
    creator: "sample-redstone",
    category: "Redstone",
    subcategory: "Automation",
    buildType: "ORIGINAL_DESIGN",
    difficulty: "ADVANCED",
    summary: "A sample record used to design the marketplace card, detail, and preview layouts.",
    tags: ["circuit", "doors", "lighting"],
    edition: "java",
    minecraftVersion: "1.20.1",
    loader: "Fabric",
    loaderVersion: "0.16.10",
    priceLabel: "Sample price · not set",
    cover: "placeholder",
    screenshots: 4,
    updatedAt: null,
  },
  {
    id: "demo-bedrock-notice",
    title: "Sample build · Bedrock compatibility example",
    creator: "sample-terra",
    category: "Structures",
    subcategory: "Decorations",
    buildType: "PORT",
    difficulty: "INTERMEDIATE",
    summary: "A sample record that shows how an unsupported edition is reported, using the real compatibility mirror.",
    tags: ["bedrock", "decor"],
    edition: "bedrock",
    minecraftVersion: "1.21.0",
    loader: "Bedrock Native",
    loaderVersion: null,
    priceLabel: "Sample price · not set",
    cover: "placeholder",
    screenshots: 1,
    updatedAt: null,
  },
]);

/** Sample orders for the creator order list and the buyer purchase list. No buyer identity, no payment reference. */
export const DEMO_ORDERS = Object.freeze([
  { id: "demo-order-1", listingId: "demo-nordic-longhouse", status: "PREVIEW_ONLY", placedAt: null, amountLabel: "Sample amount · not set", buyerLabel: "Sample buyer · hidden by design" },
  { id: "demo-order-2", listingId: "demo-terra-garden", status: "PREVIEW_ONLY", placedAt: null, amountLabel: "Sample amount · not set", buyerLabel: "Sample buyer · hidden by design" },
]);

/** Sample review rows. Rating values are null on purpose: no review data exists, so no stars are drawn. */
export const DEMO_REVIEWS = Object.freeze([
  { id: "demo-review-1", listingId: "demo-nordic-longhouse", rating: null, body: "Sample review row used to design the layout. It states no opinion and no rating.", authorLabel: "Sample reviewer", createdAt: null, responded: false },
  { id: "demo-review-2", listingId: "demo-redstone-hall", rating: null, body: "Sample review row used to design the layout. It states no opinion and no rating.", authorLabel: "Sample reviewer", createdAt: null, responded: false },
]);

/** Analytics placeholders. Every value is null so no chart or tile can render an invented number. */
export const DEMO_ANALYTICS = Object.freeze({
  views: null,
  saves: null,
  purchases: null,
  conversionRate: null,
  revenueLabel: null,
  topListings: [],
  trafficSources: [],
  note: "Analytics are not collected. This layout shows where real numbers will appear once an analytics service exists.",
});

/** Sample media slots for the media step: names and sizes only, with no fabricated upload result. */
export const DEMO_MEDIA = Object.freeze([
  { slot: "cover", label: "Cover image", accepted: "PNG, JPEG, or WebP", maximumBytes: 4_194_304, state: "NOT_UPLOADED" },
  { slot: "screenshots", label: "Screenshots", accepted: "PNG, JPEG, or WebP", maximumBytes: 4_194_304, maximumCount: 8, state: "NOT_UPLOADED" },
  { slot: "preview", label: "Optional preview video", accepted: "MP4 or WebM reference", maximumBytes: 25_165_824, state: "NOT_UPLOADED" },
]);

export const PREVIEW_CREATORS = SAMPLE_CREATORS;

/** Categories are UI scaffolding for the future catalog; they are not a claim about stored inventory. */
export const CATEGORIES = Object.freeze([
  "Structures", "Landscaping", "Interiors", "Redstone", "Farms", "Decorations", "Mini-games", "Whole worlds",
]);

export const BUILD_TYPES = Object.freeze(["ORIGINAL_DESIGN", "REFERENCE_RECREATION", "PORT", "UTILITY"]);
export const DIFFICULTIES = Object.freeze(["BEGINNER", "INTERMEDIATE", "ADVANCED", "EXPERT"]);

/** True when the current URL asked for preview mode. Preview mode never changes what a control claims to do. */
export function previewRequested(search = globalThis.location?.search ?? "") {
  return new URLSearchParams(search).get("preview") === "1";
}

export function creatorByHandle(handle) {
  return SAMPLE_CREATORS.find((candidate) => candidate.handle === handle) ?? null;
}

export function listingById(id) {
  return DEMO_LISTINGS.find((candidate) => candidate.id === id) ?? null;
}
