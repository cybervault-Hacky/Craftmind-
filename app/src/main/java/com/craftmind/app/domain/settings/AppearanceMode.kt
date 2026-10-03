package com.craftmind.app.domain.settings

enum class AppearanceMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorage(value: String?): AppearanceMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

fun AppearanceMode.resolveDarkTheme(systemIsDark: Boolean): Boolean = when (this) {
    AppearanceMode.SYSTEM -> systemIsDark
    AppearanceMode.LIGHT -> false
    AppearanceMode.DARK -> true
}
