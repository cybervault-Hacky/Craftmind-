package com.craftmind.app.domain.settings

import kotlinx.coroutines.flow.Flow

interface ThemePreferenceRepository {
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
}
