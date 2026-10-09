/**
 * Minecraft compatibility vocabulary for the server (Phase 24).
 *
 * Seller onboarding asks which editions, versions, and loaders a creator supports, so the *server* has to be able to
 * say no to invented values — a browser-side check alone would make the stored answer only as honest as the form that
 * produced it. This module is the backend counterpart of the website mirror in `website/assets/compatibility.js`, and
 * both mirror the identifiers CraftMind ships in the Android domain model:
 *
 *   app/src/main/java/com/craftmind/app/domain/minecraft/compatibility/MinecraftCompatibilityModels.kt
 *   app/src/main/java/com/craftmind/app/domain/minecraft/compatibility/MinecraftRuntimeProfileRegistry.kt
 *
 * `backend/test/onboarding.test.js` imports *both* modules and fails if they drift, so there is one vocabulary with two
 * readers, not two vocabularies. Nothing here decides buildability or compatibility status: that verdict belongs to the
 * registered-profile registry, and onboarding only records what a creator says they support.
 */

/** Edition wire values, exactly as the Android domain model serializes them. `unknown` is a system state, not a claim. */
export const EDITION_VALUES = Object.freeze(["java", "bedrock", "legacy"]);

/** Loader wire values per edition, mirrored from `MinecraftLoader.edition`. */
export const LOADERS_BY_EDITION = Object.freeze({
  java: Object.freeze(["Fabric", "Forge", "NeoForge", "Vanilla"]),
  bedrock: Object.freeze(["Bedrock Native"]),
  legacy: Object.freeze([]),
});

/** Loader values across all editions, derived so the two lists can never disagree. */
export const LOADER_VALUES = Object.freeze(
  [...new Set(Object.values(LOADERS_BY_EDITION).flat())],
);

/**
 * A syntactically valid Minecraft version (`1.20.1`, `1.21`, …). There is no canonical multi-version list anywhere in
 * this repository — only the single registered production profile — so a free but strictly shaped answer is the honest
 * rule: it rejects prose like `latest` without pretending the product has certified versions it has not.
 */
export const MINECRAFT_VERSION_PATTERN = /^\d{1,2}\.\d{1,2}(?:\.\d{1,2})?$/;

/** List bounds. An onboarding answer is a claim about support, not a build plan, so the bounds stay small and explicit. */
export const COMPATIBILITY_LIMITS = Object.freeze({
  maximumEditions: 3,
  maximumLoaders: 4,
  maximumVersions: 12,
  maximumVersionLength: 12,
});

export function isKnownEdition(value) {
  return typeof value === "string" && EDITION_VALUES.includes(value);
}

export function isKnownLoader(value) {
  return typeof value === "string" && LOADER_VALUES.includes(value);
}

/** True when `loader` belongs to at least one of the selected editions — the same rule the website form applies. */
export function loaderBelongsToEditions(loader, editions) {
  return editions.some((edition) => (LOADERS_BY_EDITION[edition] ?? []).includes(loader));
}

export function isWellFormedMinecraftVersion(value) {
  return typeof value === "string" && value.length <= COMPATIBILITY_LIMITS.maximumVersionLength &&
    MINECRAFT_VERSION_PATTERN.test(value);
}
