package com.craftmind.app.presentation.settings

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiProviderDefinition
import com.craftmind.app.domain.ai.AiProviderId

sealed interface ProviderConnectionState {
    data object Unverified : ProviderConnectionState
    data object Testing : ProviderConnectionState
    data class Verified(val compatibleModelCount: Int) : ProviderConnectionState
    data class Failed(val code: AiErrorCode) : ProviderConnectionState
}

data class ProviderSettingsState(
    val providers: List<AiProviderDefinition> = emptyList(),
    val activeProviderId: AiProviderId? = null,
    val savedCredentialExists: Boolean = false,
    val keyDraft: String = "",
    val models: List<AiModel> = emptyList(),
    val selectedModelId: String? = null,
    val connection: ProviderConnectionState = ProviderConnectionState.Unverified,
    val message: String? = null,
    val isSavingCredential: Boolean = false,
    val isSavingSelection: Boolean = false,
)

sealed interface ProviderSettingsEvent {
    data class SelectProvider(val providerId: AiProviderId) : ProviderSettingsEvent
    data class KeyDraftChanged(val value: String) : ProviderSettingsEvent
    data object SaveCredential : ProviderSettingsEvent
    data object RemoveCredential : ProviderSettingsEvent
    data object TestConnection : ProviderSettingsEvent
    data class SelectModel(val modelId: String) : ProviderSettingsEvent
    data object DismissMessage : ProviderSettingsEvent
}
