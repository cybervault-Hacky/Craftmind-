package com.craftmind.app.domain.ai

import kotlinx.coroutines.flow.Flow

data class ProviderConfiguration(
    val providerId: ProviderId? = null,
    val modelId: String = "",
)

interface ProviderConfigurationRepository {
    val configuration: Flow<ProviderConfiguration>
    suspend fun save(configuration: ProviderConfiguration)
}
