/**
 * Minecraft compatibility vocabulary for the website (Phase 21).
 *
 * This is a read-only mirror of the identifiers CraftMind already ships. It is deliberately *not* a second version
 * model: the editions, loaders, certification ladder, support statuses, and the single registered production profile
 * below are the same values declared in
 *
 *   app/src/main/java/com/craftmind/app/domain/minecraft/compatibility/MinecraftCompatibilityModels.kt
 *   app/src/main/java/com/craftmind/app/domain/minecraft/compatibility/MinecraftRuntimeProfileRegistry.kt
 *   app/src/main/java/com/craftmind/app/domain/buildplan/BuildPlanValidator.kt
 *
 * `scripts/check_website.py` re-reads those Kotlin sources and fails if this mirror drifts from them, so the creator
 * form cannot offer an edition, loader, or version that the product does not actually know about.
 */

/** Edition wire values, exactly as the Android domain model serializes them. */
export const EDITION = Object.freeze({
  JAVA: "java",
  BEDROCK: "bedrock",
  LEGACY: "legacy",
  UNKNOWN: "unknown",
});

export const EDITIONS = Object.freeze([
  { wire: EDITION.JAVA, label: "Java Edition", status: "SUPPORTED", note: "One certified production target: Java 1.20.1 with Fabric." },
  { wire: EDITION.BEDROCK, label: "Bedrock Edition", status: "EXPERIMENTAL", note: "Recognized for compatibility reporting only. No Bedrock bridge exists and no Bedrock runtime is certified, so building is refused." },
  { wire: EDITION.LEGACY, label: "Legacy Edition", status: "EXPERIMENTAL", note: "Declared contract only. The registry keeps it EXPERIMENTAL with certification NOT_PERFORMED." },
]);

/** Loader wire values, exactly as the Android domain model serializes them. */
export const LOADER = Object.freeze({
  FABRIC: "Fabric",
  FORGE: "Forge",
  NEOFORGE: "NeoForge",
  VANILLA: "Vanilla",
  BEDROCK_NATIVE: "Bedrock Native",
  UNKNOWN: "unknown",
});

/** Loader families, mirrored from `MinecraftLoader.edition`. */
export const LOADERS_BY_EDITION = Object.freeze({
  [EDITION.JAVA]: [LOADER.FABRIC, LOADER.FORGE, LOADER.NEOFORGE, LOADER.VANILLA],
  [EDITION.BEDROCK]: [LOADER.BEDROCK_NATIVE],
  [EDITION.LEGACY]: [],
  [EDITION.UNKNOWN]: [],
});

/** Support statuses, mirrored from `MinecraftCompatibilityStatus`. */
export const SUPPORT_STATUS = Object.freeze(["SUPPORTED", "EXPERIMENTAL", "UNSUPPORTED", "UNKNOWN"]);

/** Certification ladder, mirrored from `MinecraftRuntimeCertification`, strongest last. */
export const CERTIFICATION = Object.freeze([
  "NOT_PERFORMED", "STATIC_ONLY", "UNIT_TESTED", "BRIDGE_TESTED", "SIMULATED_E2E_VERIFIED", "RUNTIME_TESTED", "CERTIFIED",
]);

/**
 * The registered production profile(s). `MinecraftRuntimeProfileRegistry` currently declares exactly one Java profile;
 * Bedrock and legacy runtimes are declared contracts that can never report SUPPORTED.
 */
export const REGISTERED_PROFILES = Object.freeze([
  Object.freeze({
    adapterId: "java-fabric-1.20.1",
    edition: EDITION.JAVA,
    minecraftVersion: "1.20.1",
    loader: LOADER.FABRIC,
    loaderVersion: "0.16.10",
    requiredFabricApiVersion: "0.92.2+1.20.1",
    javaRuntimeMajor: 17,
    bridgeVersion: "1.2.0",
    bridgeProtocolVersion: 2,
    buildPlanSchemaVersion: 2,
    supportStatus: "SUPPORTED",
    certification: "CERTIFIED",
    releaseChannel: "RELEASE",
  }),
]);

/** BuildPlan limits, mirrored from `BuildPlanValidator` / `BuildPlanLimits`. */
export const BUILD_PLAN_LIMITS = Object.freeze({
  schemaVersion: 2,
  maximumOperations: 4096,
  maximumWidth: 96,
  maximumHeight: 64,
  maximumDepth: 96,
});

/** Release channels a creator may declare for a listing's target. */
export const RELEASE_CHANNELS = Object.freeze(["RELEASE", "SNAPSHOT", "BETA", "ALPHA", "LEGACY", "UNKNOWN"]);

/** True when this edition/loader pair is part of the declared domain model. */
export function loaderBelongsToEdition(loader, edition) {
  return (LOADERS_BY_EDITION[edition] ?? []).includes(loader);
}

/**
 * The compatibility verdict stated to a creator or buyer, derived from the mirrored registry rather than invented.
 * Mirrors the app's posture: only a registered, certified profile is buildable; everything else is reported honestly.
 */
export function describeCompatibility({ edition, minecraftVersion, loader, loaderVersion }) {
  const profile = REGISTERED_PROFILES.find(
    (candidate) => candidate.edition === edition &&
      candidate.minecraftVersion === minecraftVersion &&
      candidate.loader === loader &&
      (!loaderVersion || candidate.loaderVersion === loaderVersion),
  );
  if (profile) {
    return Object.freeze({
      status: profile.supportStatus,
      buildable: true,
      certification: profile.certification,
      summary: `${profile.minecraftVersion} · ${profile.loader} ${profile.loaderVersion} · Fabric API ${profile.requiredFabricApiVersion} · Java ${profile.javaRuntimeMajor}`,
    });
  }
  const editionInfo = EDITIONS.find((candidate) => candidate.wire === edition);
  if (!editionInfo) {
    return Object.freeze({ status: "UNKNOWN", buildable: false, certification: "NOT_PERFORMED", summary: "Edition not recognized by CraftMind." });
  }
  return Object.freeze({
    status: editionInfo.status === "SUPPORTED" ? "UNSUPPORTED" : editionInfo.status,
    buildable: false,
    certification: "NOT_PERFORMED",
    summary: edition === EDITION.JAVA
      ? "This Java version, loader, or loader version is not registered in CraftMind, so it cannot build."
      : editionInfo.note,
  });
}

/** Options for the creator compatibility step, generated from the mirror so the form cannot offer unregistered values. */
export function compatibilityOptions() {
  return Object.freeze({
    editions: EDITIONS,
    loadersByEdition: LOADERS_BY_EDITION,
    releaseChannels: RELEASE_CHANNELS,
    registeredProfiles: REGISTERED_PROFILES,
    limits: BUILD_PLAN_LIMITS,
  });
}
