package com.craftmind.app.presentation.navigation

enum class MainDestination(
    val route: String,
    val label: String,
) {
    HOME("home", "Home"),
    BUILDS("builds", "Builds"),
    SETTINGS("settings", "Settings");

    companion object {
        fun fromRoute(route: String?): MainDestination =
            entries.firstOrNull { it.route == route } ?: HOME
    }
}
