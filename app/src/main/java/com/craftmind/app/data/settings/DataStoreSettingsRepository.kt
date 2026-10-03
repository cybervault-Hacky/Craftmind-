package com.craftmind.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.craftmind.app.domain.settings.AppearanceMode
import com.craftmind.app.domain.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.craftMindSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "craftmind_settings",
)

class DataStoreSettingsRepository(context: Context) : SettingsRepository {
    private val dataStore = context.applicationContext.craftMindSettingsDataStore

    override val appearance: Flow<AppearanceMode> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> AppearanceMode.fromStorage(preferences[APPEARANCE_KEY]) }

    override suspend fun setAppearance(mode: AppearanceMode) {
        dataStore.edit { preferences ->
            preferences[APPEARANCE_KEY] = mode.storageValue
        }
    }

    private companion object {
        val APPEARANCE_KEY = stringPreferencesKey("appearance")
    }
}
