package com.craftmind.app.domain.ai

import kotlinx.coroutines.flow.Flow

data class AiProviderSelection(
    val providerId: AiProviderId,
    val modelId: String,
)

/** Selection contains identifiers only. Provider credentials are held by CredentialStore. */
interface AiProviderSelectionRepository {
    val selection: Flow<AiProviderSelection?>
    suspend fun select(selection: AiProviderSelection)
    suspend fun clear()
}
