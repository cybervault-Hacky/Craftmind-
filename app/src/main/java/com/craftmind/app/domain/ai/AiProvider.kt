package com.craftmind.app.domain.ai

import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.BuildDiff
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.ValidatedBuildPlan
import com.craftmind.app.domain.security.ProviderCredential

@JvmInline
value class AiProviderId(val value: String)

enum class CredentialType {
    API_KEY,
}

enum class StructuredOutputMode {
    JSON_SCHEMA,
    JSON_MIME_TYPE,
    PROMPT_CONSTRAINED_JSON,
    UNSUPPORTED,
}

data class AiProviderCapabilities(
    val textGeneration: Boolean,
    val vision: Boolean,
    val publicUrlReferences: Boolean,
    val structuredOutput: StructuredOutputMode,
    val cancellation: Boolean,
    val streaming: Boolean,
)

data class AiModelCapabilities(
    val textGeneration: Boolean,
    val vision: Boolean,
    val publicUrlReferences: Boolean,
    val structuredOutput: StructuredOutputMode,
    val maximumContextTokens: Int?,
    val maximumOutputTokens: Int?,
    val toolCalling: Boolean = false,
    val streaming: Boolean = false,
)

data class AiProviderDefinition(
    val id: AiProviderId,
    val displayName: String,
    val credentialType: CredentialType,
    /** A trusted built-in endpoint; never populated from a user's URL in this phase. */
    val baseEndpoint: String,
    val capabilities: AiProviderCapabilities,
)

data class AiModel(
    val id: String,
    val providerId: AiProviderId,
    val displayName: String,
    val capabilities: AiModelCapabilities,
)

data class AiGenerationRequest(
    val buildRequest: BuildRequest,
    val model: AiModel,
)

data class AiProviderRequest(
    val model: AiModel,
    val systemInstruction: String,
    val prompt: String,
)

data class AiProviderResponse(
    val content: String,
    val usage: AiUsage? = null,
)

data class AiGenerationResponse(
    val plan: ValidatedBuildPlan,
    val usage: AiUsage?,
)

data class AiRefinementResponse(
    val plan: ValidatedBuildPlan,
    val diff: BuildDiff,
    val usage: AiUsage?,
)

data class AiUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val providerRequestId: String? = null,
)

/** One adapter per vendor/API family. UI code receives metadata, never transport details. */
interface AiBuildGenerator {
    suspend fun generate(request: BuildRequest): AiGenerationResponse
}

interface AiBuildRefiner {
    suspend fun refine(request: BuildEditRequest): AiRefinementResponse
}

interface AiProviderAdapter {
    val definition: AiProviderDefinition

    /** Returns the provider's current compatible models; do not assume model IDs stay static. */
    suspend fun listModels(credential: ProviderCredential): List<AiModel>

    suspend fun generateContent(
        request: AiProviderRequest,
        credential: ProviderCredential,
    ): AiProviderResponse
}
