package com.craftmind.app.domain.ai

import com.craftmind.app.domain.build.BuildPlan
import com.craftmind.app.domain.build.BuildRequest

/** Provider identity is intentionally separate from a model identity. Credentials belong to this ID. */
@JvmInline
value class AiProviderId(val value: String)

data class AiModel(
    val id: String,
    val providerId: AiProviderId,
    val displayName: String,
    val capabilities: AiProviderCapabilities,
)

data class AiProviderCapabilities(
    val acceptsText: Boolean = true,
    val acceptsImages: Boolean = false,
    val acceptsPublicUrls: Boolean = false,
    val supportsStructuredOutput: Boolean = false,
    val supportsStreaming: Boolean = false,
)

data class AiGenerationRequest(
    val buildRequest: BuildRequest,
    val model: AiModel,
)

data class AiGenerationResponse(
    val buildPlan: BuildPlan,
    val usage: AiUsage? = null,
)

data class AiUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val providerRequestId: String? = null,
)

/** Provider adapters can be added independently; no network implementation is included in Phase 1. */
interface AiProvider {
    val id: AiProviderId
    val displayName: String
    val capabilities: AiProviderCapabilities

    suspend fun listModels(): List<AiModel>
    suspend fun generate(request: AiGenerationRequest): AiGenerationResponse
}
