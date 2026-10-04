package com.craftmind.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.craftmind.app.domain.settings.ThemeMode
import com.craftmind.app.domain.settings.ThemePreferenceRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.craftMindPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "craftmind_preferences",
)

/** Persists only the non-sensitive appearance choice. No request or credential data is stored. */
class DataStoreThemePreferenceRepository(context: Context) : ThemePreferenceRepository {
    private val dataStore = context.applicationContext.craftMindPreferences

    override val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> ThemeMode.fromStorageValue(preferences[THEME_MODE_KEY]) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode.storageValue
        }
    }

    private companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    }
}
