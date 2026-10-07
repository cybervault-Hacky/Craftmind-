package com.craftmind.app.domain.account

/**
 * Who owns a piece of CraftMind data (Phase 16).
 *
 * The classification exists because the next phases will add cloud features, and the dangerous moment is the one where
 * "we have an account now, so let us upload this" meets data the user never agreed to share. Naming ownership explicitly
 * makes that conversation a code review instead of a guess.
 */
enum class CraftMindDataOwnership {
    /** Device-local data. It may become account-scoped later, but only through an explicit, user-visible decision. */
    LOCAL_ONLY,

    /** Data that a future phase may synchronise with a CraftMind account. Nothing synchronises today. */
    ACCOUNT_SCOPED_FUTURE,

    /**
     * A secret that must never leave the device: not to a CraftMind account, not to a synchronisation service, not to
     * the Minecraft bridge. Keystore-backed, app-private, no-backup storage only.
     */
    SECRET_LOCAL_ONLY,

    /**
     * Minecraft runtime state: the bridge pairing, its pinned client identity, and what was executed against it.
     *
     * This data belongs to a runtime on this network, never to an account. A CraftMind account cannot acquire it by
     * signing in, and no future phase may synchronise it.
     */
    MINECRAFT_RUNTIME_LOCAL_ONLY,
}

/**
 * Every kind of user data CraftMind keeps, with its ownership classification (Phase 16 §6).
 *
 * [ownership] is what is true **today** and is enforced: the shipped app performs no cloud synchronisation at all, so no
 * domain is account-scoped today. [futureOwnership] records what the domain may become when a real account service and a
 * real synchronisation design exist — it is a plan, not behaviour, and the tests assert that no domain's *current*
 * classification is [CraftMindDataOwnership.ACCOUNT_SCOPED_FUTURE].
 *
 * [storedBy] names the production type that actually holds the data. A test resolves every name against the source tree,
 * so this registry cannot quietly drift away from the code.
 */
enum class CraftMindDataDomain(
    val ownership: CraftMindDataOwnership,
    val futureOwnership: CraftMindDataOwnership,
    val storedBy: String,
    val summary: String,
) {
    BUILD_HISTORY(
        ownership = CraftMindDataOwnership.LOCAL_ONLY,
        futureOwnership = CraftMindDataOwnership.ACCOUNT_SCOPED_FUTURE,
        storedBy = "AtomicLocalBuildRepository",
        summary = "Every accepted build plan on this device, with its request metadata.",
    ),
    BUILD_PLAN_VERSIONS(
        ownership = CraftMindDataOwnership.LOCAL_ONLY,
        futureOwnership = CraftMindDataOwnership.ACCOUNT_SCOPED_FUTURE,
        storedBy = "BuildHistoryPolicy",
        summary = "Saved plan versions and refinement lineage for a build.",
    ),
    APP_SETTINGS(
        ownership = CraftMindDataOwnership.LOCAL_ONLY,
        futureOwnership = CraftMindDataOwnership.ACCOUNT_SCOPED_FUTURE,
        storedBy = "DataStoreThemePreferenceRepository",
        summary = "Appearance and other non-secret preferences.",
    ),
    AI_PROVIDER_SELECTION(
        ownership = CraftMindDataOwnership.LOCAL_ONLY,
        futureOwnership = CraftMindDataOwnership.LOCAL_ONLY,
        storedBy = "DataStoreAiProviderSelectionRepository",
        summary = "Which provider and model this device is configured to use. Device-bound, because the key it depends " +
            "on is device-bound.",
    ),
    AI_PROVIDER_CREDENTIALS(
        ownership = CraftMindDataOwnership.SECRET_LOCAL_ONLY,
        futureOwnership = CraftMindDataOwnership.SECRET_LOCAL_ONLY,
        storedBy = "AndroidKeystoreCredentialStore",
        summary = "The user's own AI provider API keys. They are never uploaded, never attached to an account, and " +
            "never sent anywhere except the provider they belong to.",
    ),
    ACCOUNT_SESSION_CREDENTIALS(
        ownership = CraftMindDataOwnership.SECRET_LOCAL_ONLY,
        futureOwnership = CraftMindDataOwnership.SECRET_LOCAL_ONLY,
        storedBy = "AndroidKeystoreAccountSessionStore",
        summary = "The CraftMind account session secret. Stored separately from provider keys, in its own encrypted " +
            "namespace with its own Keystore key.",
    ),
    MINECRAFT_BRIDGE_PAIRING(
        ownership = CraftMindDataOwnership.MINECRAFT_RUNTIME_LOCAL_ONLY,
        futureOwnership = CraftMindDataOwnership.MINECRAFT_RUNTIME_LOCAL_ONLY,
        storedBy = "AndroidMinecraftBridgePairingRepository",
        summary = "The paired bridge and its P-256 client identity. A CraftMind account never gains control of a " +
            "Minecraft runtime.",
    ),
    BUILD_EXECUTION_HISTORY(
        ownership = CraftMindDataOwnership.MINECRAFT_RUNTIME_LOCAL_ONLY,
        futureOwnership = CraftMindDataOwnership.MINECRAFT_RUNTIME_LOCAL_ONLY,
        storedBy = "AtomicLocalBuildExecutionRepository",
        summary = "What was actually executed against a paired runtime.",
    ),
    ;

    /** True when this domain is account-scoped in the shipped app. No domain is, in Phase 16. */
    val isAccountScopedToday: Boolean get() = ownership == CraftMindDataOwnership.ACCOUNT_SCOPED_FUTURE
}

/** Domains that a future phase may synchronise — the only ones an account may ever claim data from. */
fun craftMindDomainsSyncableInFuture(): List<CraftMindDataDomain> =
    CraftMindDataDomain.entries.filter { it.futureOwnership == CraftMindDataOwnership.ACCOUNT_SCOPED_FUTURE }

/** Domains classified as secret. Everything here stays on the device, for the lifetime of the product. */
fun craftMindSecretDomains(): List<CraftMindDataDomain> =
    CraftMindDataDomain.entries.filter { it.ownership == CraftMindDataOwnership.SECRET_LOCAL_ONLY }
