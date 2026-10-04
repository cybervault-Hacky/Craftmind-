package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiFailure
import com.craftmind.app.domain.ai.AiBuildGenerator
import com.craftmind.app.domain.ai.AiGenerationResponse
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiProviderAdapter
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.AiProviderRegistry
import com.craftmind.app.domain.ai.AiProviderSelection
import com.craftmind.app.domain.ai.AiProviderSelectionRepository
import com.craftmind.app.domain.ai.StructuredOutputMode
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.security.CredentialStore
import com.craftmind.app.domain.security.CredentialStoreException
import com.craftmind.app.domain.security.ProviderCredential
import kotlinx.coroutines.flow.first

/** Provider-independent generation and configuration boundary used by presentation view models. */
class AiBuildEngine(
    private val registry: AiProviderRegistry,
    private val credentialStore: CredentialStore,
    private val selections: AiProviderSelectionRepository,
    private val parser: BuildPlanParser,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : AiBuildGenerator {
    fun providers() = registry.providers()

    suspend fun hasCredential(providerId: AiProviderId): Boolean {
        return try {
            val credential = credentialStore.read(providerId) ?: return false
            credential.close()
            true
        } catch (error: CredentialStoreException) {
            throw credentialFailure(error)
        }
    }

    suspend fun saveCredential(providerId: AiProviderId, credential: ProviderCredential) {
        requireAdapter(providerId)
        try {
            credentialStore.write(providerId, credential)
        } catch (error: CredentialStoreException) {
            throw credentialFailure(error)
        }
    }

    suspend fun removeCredential(providerId: AiProviderId) {
        requireAdapter(providerId)
        try {
            credentialStore.remove(providerId)
            val selection = selections.selection.first()
            if (selection?.providerId == providerId) selections.clear()
        } catch (error: CredentialStoreException) {
            throw credentialFailure(error)
        }
    }

    /** A live authenticated model-list call is the only action that verifies provider connectivity. */
    suspend fun verifyProvider(providerId: AiProviderId): List<AiModel> = withCredential(providerId) { adapter, credential ->
        if (!adapter.definition.capabilities.textGeneration ||
            adapter.definition.capabilities.structuredOutput != StructuredOutputMode.JSON_MIME_TYPE
        ) {
            throw providerFailure(AiErrorCode.UNSUPPORTED_CAPABILITY)
        }
        val models = adapter.listModels(credential)
        if (models.any { it.providerId != providerId }) throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        models.filter { model ->
            model.capabilities.textGeneration &&
                model.capabilities.structuredOutput == StructuredOutputMode.JSON_MIME_TYPE
        }.also { compatible ->
            if (compatible.isEmpty()) throw providerFailure(AiErrorCode.NO_MODELS_AVAILABLE)
        }
    }

    suspend fun saveSelection(selection: AiProviderSelection) {
        requireAdapter(selection.providerId)
        if (selection.modelId.isBlank()) throw providerFailure(AiErrorCode.NO_MODEL_SELECTED)
        val available = verifyProvider(selection.providerId)
        if (available.none { it.id == selection.modelId }) throw providerFailure(AiErrorCode.MODEL_UNAVAILABLE)
        try {
            selections.select(selection)
        } catch (_: IllegalArgumentException) {
            throw providerFailure(AiErrorCode.NO_MODEL_SELECTED)
        }
    }

    suspend fun currentSelection(): AiProviderSelection? = selections.selection.first()

    override suspend fun generate(request: BuildRequest): AiGenerationResponse {
        val selection = selections.selection.first() ?: throw providerFailure(
            if (registry.providers().isEmpty()) AiErrorCode.NO_PROVIDER_SELECTED else AiErrorCode.NO_MODEL_SELECTED,
        )
        if (selection.providerId.value.isBlank()) throw providerFailure(AiErrorCode.NO_PROVIDER_SELECTED)
        if (selection.modelId.isBlank()) throw providerFailure(AiErrorCode.NO_MODEL_SELECTED)

        return withCredential(selection.providerId) { adapter, credential ->
            if (!adapter.definition.capabilities.textGeneration ||
                adapter.definition.capabilities.structuredOutput != StructuredOutputMode.JSON_MIME_TYPE
            ) {
                throw providerFailure(AiErrorCode.UNSUPPORTED_CAPABILITY)
            }
            val models = adapter.listModels(credential)
            val model = models.firstOrNull { it.id == selection.modelId }
                ?: throw providerFailure(AiErrorCode.MODEL_UNAVAILABLE)
            if (model.providerId != selection.providerId || !model.capabilities.textGeneration) {
                throw providerFailure(AiErrorCode.UNSUPPORTED_CAPABILITY)
            }
            if (model.capabilities.structuredOutput != StructuredOutputMode.JSON_MIME_TYPE) {
                throw providerFailure(AiErrorCode.UNSUPPORTED_CAPABILITY)
            }

            val providerResponse = adapter.generateContent(
                request = BuildPlanGenerationPrompt.forRequest(request, model),
                credential = credential,
            )
            val plan = parser.parse(
                content = providerResponse.content,
                request = request,
                providerId = selection.providerId.value,
                modelId = model.id,
                generatedAtEpochMillis = nowEpochMillis(),
            )
            AiGenerationResponse(plan = plan, usage = providerResponse.usage)
        }
    }

    private suspend fun <T> withCredential(
        providerId: AiProviderId,
        block: suspend (AiProviderAdapter, ProviderCredential) -> T,
    ): T {
        val adapter = requireAdapter(providerId)
        val credential = try {
            credentialStore.read(providerId)
        } catch (error: CredentialStoreException) {
            throw credentialFailure(error)
        } ?: throw providerFailure(AiErrorCode.MISSING_CREDENTIAL)

        return try {
            block(adapter, credential)
        } finally {
            credential.close()
        }
    }

    private fun requireAdapter(providerId: AiProviderId): AiProviderAdapter =
        registry.adapter(providerId) ?: throw providerFailure(AiErrorCode.NO_PROVIDER_SELECTED)

    private fun credentialFailure(error: CredentialStoreException) =
        AiProviderException(AiFailure(AiErrorCode.CREDENTIAL_STORAGE_FAILURE, retryable = false))

    private fun providerFailure(code: AiErrorCode) = AiProviderException(
        AiFailure(
            code = code,
            retryable = code in setOf(
                AiErrorCode.RATE_LIMITED,
                AiErrorCode.PROVIDER_UNAVAILABLE,
                AiErrorCode.NETWORK_TIMEOUT,
                AiErrorCode.NETWORK_UNAVAILABLE,
            ),
        ),
    )
}
