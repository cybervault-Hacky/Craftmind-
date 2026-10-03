package com.craftmind.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.craftmind.app.domain.ai.ProviderConfiguration
import com.craftmind.app.domain.model.BuildLimits
import com.craftmind.app.domain.ai.ProviderConfigurationRepository
import com.craftmind.app.domain.ai.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.providerConfigurationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "craftmind_provider_configuration",
)

/** Stores provider/model identifiers only; credential material has a separate encrypted store. */
class DataStoreProviderConfigurationRepository(context: Context) : ProviderConfigurationRepository {
    private val dataStore = context.applicationContext.providerConfigurationDataStore

    override val configuration: Flow<ProviderConfiguration> = dataStore.data.map { preferences ->
        ProviderConfiguration(
            providerId = preferences[PROVIDER_ID]?.takeIf(String::isNotBlank)?.let(::ProviderId),
            modelId = preferences[MODEL_ID].orEmpty(),
        )
    }

    override suspend fun save(configuration: ProviderConfiguration) {
        val normalizedModelId = configuration.modelId.trim()
        require(normalizedModelId.length <= BuildLimits.MAX_MODEL_ID_CHARACTERS) { "Model identifier is too long." }
        require(
            configuration.providerId == null ||
                configuration.providerId.value.length <= BuildLimits.MAX_PROVIDER_ID_CHARACTERS &&
                configuration.providerId.value.matches(
                    Regex("[a-z][a-z0-9_-]{0,${BuildLimits.MAX_PROVIDER_ID_CHARACTERS - 1}}"),
                ),
        ) {
            "Provider identifier is invalid."
        }
        dataStore.edit { preferences ->
            if (configuration.providerId == null) preferences.remove(PROVIDER_ID)
            else preferences[PROVIDER_ID] = configuration.providerId.value
            preferences[MODEL_ID] = normalizedModelId
        }
    }

    private companion object {
        val PROVIDER_ID = stringPreferencesKey("provider_id")
        val MODEL_ID = stringPreferencesKey("model_id")
    }
}
