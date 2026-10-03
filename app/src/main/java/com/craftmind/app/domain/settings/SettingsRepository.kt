package com.craftmind.app.domain.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val appearance: Flow<AppearanceMode>
    suspend fun setAppearance(mode: AppearanceMode)
}
