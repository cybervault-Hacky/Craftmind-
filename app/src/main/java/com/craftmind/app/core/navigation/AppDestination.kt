package com.craftmind.app.core.navigation

enum class AppDestination(val route: String) {
    HOME("home"),
    BUILDS("builds"),
    SETTINGS("settings");

    companion object {
        fun fromRoute(route: String?): AppDestination? =
            entries.firstOrNull { destination ->
                route == destination.route || route?.startsWith("${destination.route}/") == true
            }
    }
}
