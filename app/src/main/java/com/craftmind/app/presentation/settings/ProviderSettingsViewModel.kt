package com.craftmind.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.craftmind.app.data.ai.AiBuildEngine
import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiErrorMapper
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.AiProviderSelection
import com.craftmind.app.domain.security.ProviderCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProviderSettingsViewModel(
    private val engine: AiBuildEngine,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProviderSettingsState())
    val state: StateFlow<ProviderSettingsState> = mutableState.asStateFlow()
    private var actionJob: Job? = null

    init {
        actionJob = viewModelScope.launch {
            val providers = engine.providers()
            val selection = engine.currentSelection()
            val activeId = selection?.providerId?.takeIf { id -> providers.any { it.id == id } }
                ?: providers.firstOrNull()?.id
            val hasCredential = activeId?.let { id -> runCatching { engine.hasCredential(id) }.getOrDefault(false) } ?: false
            mutableState.value = ProviderSettingsState(
                providers = providers,
                activeProviderId = activeId,
                savedCredentialExists = hasCredential,
                selectedModelId = selection?.takeIf { it.providerId == activeId }?.modelId,
            )
        }
    }

    fun dispatch(event: ProviderSettingsEvent) {
        when (event) {
            is ProviderSettingsEvent.SelectProvider -> selectProvider(event.providerId)
            is ProviderSettingsEvent.KeyDraftChanged -> mutableState.update { current ->
                if (current.isSavingCredential) current else current.copy(keyDraft = event.value.take(MAX_KEY_DRAFT_LENGTH), message = null)
            }
            ProviderSettingsEvent.SaveCredential -> saveCredential()
            ProviderSettingsEvent.RemoveCredential -> removeCredential()
            ProviderSettingsEvent.TestConnection -> testConnection()
            is ProviderSettingsEvent.SelectModel -> selectModel(event.modelId)
            ProviderSettingsEvent.DismissMessage -> mutableState.update { it.copy(message = null) }
        }
    }

    private fun selectProvider(providerId: AiProviderId) {
        val current = mutableState.value
        if (current.providers.none { it.id == providerId }) return
        actionJob?.cancel()
        mutableState.value = current.copy(
            activeProviderId = providerId,
            savedCredentialExists = false,
            keyDraft = "",
            models = emptyList(),
            selectedModelId = null,
            connection = ProviderConnectionState.Unverified,
            message = null,
            isSavingCredential = false,
            isSavingSelection = false,
        )
        actionJob = viewModelScope.launch {
            val hasCredential = runCatching { engine.hasCredential(providerId) }.getOrDefault(false)
            val selection = engine.currentSelection()
            mutableState.update { state ->
                if (state.activeProviderId != providerId) state else state.copy(
                    savedCredentialExists = hasCredential,
                    selectedModelId = selection?.takeIf { it.providerId == providerId }?.modelId,
                )
            }
        }
    }

    private fun saveCredential() {
        val state = mutableState.value
        val providerId = state.activeProviderId ?: run {
            mutableState.update { it.copy(message = "No supported provider is available.") }
            return
        }
        val draft = state.keyDraft
        if (draft.isBlank()) {
            mutableState.update { it.copy(message = "Enter the provider's API key first.") }
            return
        }
        if (draft.length > MAX_KEY_BYTES) {
            mutableState.update { it.copy(message = "The API key is too long to store safely.") }
            return
        }
        if (draft.any { it.code !in MIN_VISIBLE_ASCII..MAX_VISIBLE_ASCII }) {
            mutableState.update { it.copy(message = "The API key contains whitespace or unsupported characters. Paste it exactly as issued.") }
            return
        }

        actionJob?.cancel()
        mutableState.update { it.copy(isSavingCredential = true, message = null) }
        actionJob = viewModelScope.launch {
            val characters = draft.toCharArray()
            val credential = ProviderCredential.fromCharacters(characters)
            characters.fill('\u0000')
            try {
                engine.saveCredential(providerId, credential)
                mutableState.update { current ->
                    if (current.activeProviderId != providerId) current else current.copy(
                        savedCredentialExists = true,
                        keyDraft = "",
                        models = emptyList(),
                        connection = ProviderConnectionState.Unverified,
                        isSavingCredential = false,
                        message = "API key saved encrypted on this device. Test the connection before choosing a model.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { current ->
                    if (current.activeProviderId != providerId) current else current.copy(
                        isSavingCredential = false,
                        message = messageFor(AiErrorMapper.fromThrowable(error).code),
                    )
                }
            } finally {
                credential.close()
            }
        }
    }

    private fun removeCredential() {
        val providerId = mutableState.value.activeProviderId ?: return
        actionJob?.cancel()
        mutableState.update { it.copy(isSavingCredential = true, message = null) }
        actionJob = viewModelScope.launch {
            try {
                engine.removeCredential(providerId)
                mutableState.update { current ->
                    if (current.activeProviderId != providerId) current else current.copy(
                        savedCredentialExists = false,
                        keyDraft = "",
                        models = emptyList(),
                        selectedModelId = null,
                        connection = ProviderConnectionState.Unverified,
                        isSavingCredential = false,
                        isSavingSelection = false,
                        message = "Saved provider key and model selection removed.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { current ->
                    if (current.activeProviderId != providerId) current else current.copy(
                        isSavingCredential = false,
                        message = messageFor(AiErrorMapper.fromThrowable(error).code),
                    )
                }
            }
        }
    }

    private fun testConnection() {
        val providerId = mutableState.value.activeProviderId ?: return
        actionJob?.cancel()
        mutableState.update {
            it.copy(connection = ProviderConnectionState.Testing, models = emptyList(), message = null)
        }
        actionJob = viewModelScope.launch {
            try {
                val models = engine.verifyProvider(providerId)
                val selection = engine.currentSelection()
                mutableState.update { current ->
                    if (current.activeProviderId != providerId) current else current.copy(
                        models = models,
                        selectedModelId = current.selectedModelId?.takeIf { modelId -> models.any { it.id == modelId } }
                            ?: selection?.takeIf { it.providerId == providerId }?.modelId?.takeIf { modelId -> models.any { it.id == modelId } },
                        connection = ProviderConnectionState.Verified(models.size),
                        message = "Verified directly with ${current.providers.firstOrNull { it.id == providerId }?.displayName ?: "provider"}; ${models.size} compatible model(s) found.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { current ->
                    if (current.activeProviderId != providerId) current else current.copy(
                        models = emptyList(),
                        connection = ProviderConnectionState.Failed(AiErrorMapper.fromThrowable(error).code),
                        message = messageFor(AiErrorMapper.fromThrowable(error).code),
                    )
                }
            }
        }
    }

    private fun selectModel(modelId: String) {
        val state = mutableState.value
        val providerId = state.activeProviderId ?: return
        if (state.connection !is ProviderConnectionState.Verified || state.models.none { it.id == modelId }) return
        actionJob?.cancel()
        mutableState.update { it.copy(isSavingSelection = true, message = null) }
        actionJob = viewModelScope.launch {
            try {
                engine.saveSelection(AiProviderSelection(providerId, modelId))
                mutableState.update { current ->
                    if (current.activeProviderId != providerId) current else current.copy(
                        selectedModelId = modelId,
                        isSavingSelection = false,
                        message = "Model selection saved. Generation will recheck provider availability.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val code = AiErrorMapper.fromThrowable(error).code
                mutableState.update { current ->
                    if (current.activeProviderId != providerId) current else current.copy(
                        models = emptyList(),
                        connection = ProviderConnectionState.Failed(code),
                        isSavingSelection = false,
                        message = messageFor(code),
                    )
                }
            }
        }
    }

    private fun messageFor(code: AiErrorCode): String = when (code) {
        AiErrorCode.INVALID_API_KEY -> "The provider rejected this API key. Check it with the provider and try again."
        AiErrorCode.RATE_LIMITED -> "The provider is rate limiting requests. Wait a while before retrying."
        AiErrorCode.NETWORK_TIMEOUT -> "The provider request timed out. Check your connection and retry."
        AiErrorCode.NETWORK_UNAVAILABLE -> "Could not reach the provider. Check your internet connection."
        AiErrorCode.PROVIDER_UNAVAILABLE -> "The provider is temporarily unavailable. Try again later."
        AiErrorCode.MODEL_UNAVAILABLE -> "That model is no longer available. Test the connection and choose another."
        AiErrorCode.NO_MODELS_AVAILABLE -> "The provider returned no compatible text-generation models."
        AiErrorCode.MISSING_CREDENTIAL -> "Save a provider API key before testing the connection."
        AiErrorCode.CREDENTIAL_STORAGE_FAILURE -> "The encrypted key could not be accessed. Check device security and try again."
        AiErrorCode.UNSUPPORTED_CAPABILITY -> "The selected provider or model does not support structured plan generation."
        AiErrorCode.VISION_UNSUPPORTED -> "The selected model does not support image analysis; no model fallback was used."
        AiErrorCode.INVALID_BUILD_REQUEST -> "The build request is invalid. Add a description or supported image."
        AiErrorCode.IMAGE_UNREADABLE -> "The image could not be opened locally. Choose it again."
        AiErrorCode.IMAGE_CONTENT_INVALID -> "The selected file is not a supported readable image."
        AiErrorCode.IMAGE_TOO_LARGE -> "The image exceeds CraftMind's size limit. Choose a smaller file."
        AiErrorCode.IMAGE_DIMENSIONS_UNSUPPORTED -> "The image dimensions exceed CraftMind's safe decoding limit."
        AiErrorCode.IMAGE_MIME_MISMATCH -> "The image contents do not match its declared format. Choose it again."
        AiErrorCode.INVALID_AI_RESPONSE, AiErrorCode.UNSUPPORTED_SCHEMA_VERSION, AiErrorCode.INVALID_BUILD_PLAN,
        AiErrorCode.INVALID_BUILD_EDIT, AiErrorCode.NO_CHANGES_PROPOSED ->
            "The provider returned data that CraftMind could not safely validate."
        AiErrorCode.REFINEMENT_CONTEXT_TOO_LARGE -> "This plan is too large for safe refinement with the selected model."
        AiErrorCode.BUILD_VERSION_CONFLICT -> "The saved build version changed. Reopen its current version."
        AiErrorCode.BUILD_HISTORY_FAILURE -> "The local build version could not be saved."
        AiErrorCode.BUILD_HISTORY_LIMIT_REACHED -> "Local build history reached its limit. Existing versions were kept."
        AiErrorCode.BUILD_TOO_LARGE, AiErrorCode.RESPONSE_TOO_LARGE -> "The provider response exceeded CraftMind's safety limits."
        AiErrorCode.NO_PROVIDER_SELECTED -> "Choose a supported AI provider."
        AiErrorCode.NO_MODEL_SELECTED -> "Choose a verified model before generating a plan."
        AiErrorCode.CANCELLED -> "The provider request was cancelled."
        AiErrorCode.UNKNOWN_PROVIDER_ERROR -> "The provider request failed. No secret or raw response was displayed."
    }

    private companion object {
        const val MAX_KEY_DRAFT_LENGTH = 8 * 1024
        const val MAX_KEY_BYTES = 8 * 1024
        const val MIN_VISIBLE_ASCII = 0x21
        const val MAX_VISIBLE_ASCII = 0x7e
    }
}
