package com.craftmind.app.presentation.navigation

/**
 * The top-level navigation model (Phase 15 §4).
 *
 * CraftMind has exactly four destinations. The information architecture is deliberately small: everything a user
 * needs on the way from an idea to a build in their world is one tap away, and nothing that is not a daily
 * destination (About, privacy, licences) is promoted into navigation — those live inside Settings.
 *
 * The order is part of the contract and is asserted by `MainDestinationTest`: [HOME] first because it is where work
 * starts, then [BUILDS] (the library), then [MINECRAFT] (the runtime connection), then [SETTINGS]. Later phases add
 * screens *inside* these destinations rather than adding destinations, unless a phase brief explicitly says otherwise.
 */
enum class MainDestination(
    /** Stable route key; also the value restored across process death. */
    val route: String,
    /** Short navigation label. */
    val label: String,
    /** One sentence stating what the screen is for; rendered as the screen subtitle. */
    val purpose: String,
) {
    HOME(
        route = "home",
        label = "Home",
        purpose = "Describe a build, add one optional reference, and generate a validated plan.",
    ),
    BUILDS(
        route = "builds",
        label = "Builds",
        purpose = "Every plan you have accepted on this device, with its saved versions.",
    ),
    MINECRAFT(
        route = "minecraft",
        label = "Minecraft",
        purpose = "Pair a bridge, see the detected runtime, and find out whether building is available.",
    ),
    SETTINGS(
        route = "settings",
        label = "Settings",
        purpose = "Appearance, AI providers, Minecraft, data, and about CraftMind.",
    );

    companion object {
        /** The primary destination: where the app opens and where Back returns. */
        val PRIMARY: MainDestination = HOME

        /** Resolves a route, falling back to [PRIMARY] for unknown or missing values. */
        fun fromRoute(route: String?): MainDestination =
            entries.firstOrNull { it.route == route } ?: PRIMARY
    }
}
