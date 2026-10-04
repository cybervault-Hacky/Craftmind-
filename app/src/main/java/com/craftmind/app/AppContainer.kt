package com.craftmind.app

import android.content.Context
import com.craftmind.app.data.settings.DataStoreThemePreferenceRepository
import com.craftmind.app.domain.settings.ThemePreferenceRepository

class AppContainer(context: Context) {
    val themePreferences: ThemePreferenceRepository = DataStoreThemePreferenceRepository(context)
}
