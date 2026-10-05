package com.craftmind.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.AiProviderSelection
import com.craftmind.app.domain.ai.AiProviderSelectionRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.aiProviderSelectionPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "craftmind_ai_selection",
)

/** Stores provider/model identifiers only. Provider API keys live exclusively in CredentialStore. */
class DataStoreAiProviderSelectionRepository(context: Context) : AiProviderSelectionRepository {
    private val dataStore = context.applicationContext.aiProviderSelectionPreferences

    override val selection: Flow<AiProviderSelection?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val providerId = preferences[PROVIDER_ID_KEY]?.takeIf(PROVIDER_ID_PATTERN::matches)
            val modelId = preferences[MODEL_ID_KEY]?.takeIf(MODEL_ID_PATTERN::matches)
            if (providerId != null && modelId != null) AiProviderSelection(AiProviderId(providerId), modelId)
            else null
        }

    override suspend fun select(selection: AiProviderSelection) {
        require(PROVIDER_ID_PATTERN.matches(selection.providerId.value))
        require(MODEL_ID_PATTERN.matches(selection.modelId))
        dataStore.edit { preferences ->
            preferences[PROVIDER_ID_KEY] = selection.providerId.value
            preferences[MODEL_ID_KEY] = selection.modelId
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(PROVIDER_ID_KEY)
            preferences.remove(MODEL_ID_KEY)
        }
    }

    private companion object {
        val PROVIDER_ID_KEY = stringPreferencesKey("provider_id")
        val MODEL_ID_KEY = stringPreferencesKey("model_id")
        val PROVIDER_ID_PATTERN = Regex("[a-z0-9_-]{1,64}")
        val MODEL_ID_PATTERN = Regex("[A-Za-z0-9._:/-]{1,200}")
    }
}
