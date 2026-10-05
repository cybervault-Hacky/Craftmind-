package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiBuildGenerator
import com.craftmind.app.domain.ai.AiBuildRefiner
import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiFailure
import com.craftmind.app.domain.ai.AiGenerationResponse
import com.craftmind.app.domain.ai.AiGenerationStage
import com.craftmind.app.domain.ai.AiImageInputPreparer
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiProviderAdapter
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.AiProviderRegistry
import com.craftmind.app.domain.ai.AiProviderSelection
import com.craftmind.app.domain.ai.AiProviderSelectionRepository
import com.craftmind.app.domain.ai.AiRefinementResponse
import com.craftmind.app.domain.ai.StructuredOutputMode
import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.BuildImageAnalysisSource
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildRequestValidationError
import com.craftmind.app.domain.buildplan.BuildRequestValidator
import com.craftmind.app.domain.buildplan.UrlValidationResult
import com.craftmind.app.domain.buildplan.BuildPlanValidator
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.security.CredentialStore
import com.craftmind.app.domain.security.CredentialStoreException
import com.craftmind.app.domain.security.ProviderCredential
import kotlinx.coroutines.flow.first

/** Provider-independent pipeline. Credentials and transport remain behind Phase 2 boundaries. */
class AiBuildEngine(
    private val registry: AiProviderRegistry,
    private val credentialStore: CredentialStore,
    private val selections: AiProviderSelectionRepository,
    private val parser: BuildPlanParser,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val planValidator: BuildPlanValidator = DefaultBuildPlanValidator(),
    private val contextSerializer: BuildPlanContextSerializer = BuildPlanContextSerializer(),
    private val editParser: BuildPlanEditParser = BuildPlanEditParser(planValidator),
    private val imageInputPreparer: AiImageInputPreparer? = null,
    private val imageAnalysisParser: BuildImageAnalysisParser = BuildImageAnalysisParser(),
    private val requestValidator: BuildRequestValidator = BuildRequestValidator(),
) : AiBuildGenerator, AiBuildRefiner {
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

    override suspend fun generate(request: BuildRequest): AiGenerationResponse = generate(request) {}

    override suspend fun generate(
        request: BuildRequest,
        onStage: (AiGenerationStage) -> Unit,
    ): AiGenerationResponse {
        onStage(AiGenerationStage.VALIDATING_REQUEST)
        validateGenerationRequest(request)
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
            val model = findSelectedModel(adapter, credential, selection)
            if (!model.capabilities.textGeneration ||
                model.capabilities.structuredOutput != StructuredOutputMode.JSON_MIME_TYPE
            ) {
                throw providerFailure(AiErrorCode.UNSUPPORTED_CAPABILITY)
            }

            val reference = request.imageReference
            if (reference == null) {
                onStage(AiGenerationStage.GENERATING_BUILD_PLAN)
                val response = adapter.generateContent(BuildPlanGenerationPrompt.forRequest(request, model), credential)
                onStage(AiGenerationStage.VALIDATING_BUILD_PLAN)
                val plan = parsePlan(response.content, request, selection.providerId.value, model.id)
                AiGenerationResponse(plan = plan, usage = response.usage)
            } else {
                if (!adapter.definition.capabilities.vision || !model.capabilities.vision) {
                    throw providerFailure(AiErrorCode.VISION_UNSUPPORTED)
                }
                val preparer = imageInputPreparer ?: throw providerFailure(AiErrorCode.IMAGE_UNREADABLE)
                onStage(AiGenerationStage.PREPARING_IMAGE_LOCALLY)
                val image = preparer.prepare(reference)
                try {
                    onStage(AiGenerationStage.ANALYZING_IMAGE_WITH_SELECTED_MODEL)
                    val analysisResponse = adapter.generateContent(
                        BuildImageAnalysisPrompt.forRequest(request, model, image),
                        credential,
                    )
                    image.close()
                    val analysis = imageAnalysisParser.parse(analysisResponse.content)
                    onStage(AiGenerationStage.GENERATING_BUILD_PLAN)
                    val planResponse = adapter.generateContent(
                        BuildPlanGenerationPrompt.forRequest(request, model, analysis),
                        credential,
                    )
                    onStage(AiGenerationStage.VALIDATING_BUILD_PLAN)
                    val plan = parsePlan(planResponse.content, request, selection.providerId.value, model.id)
                    AiGenerationResponse(
                        plan = plan,
                        usage = combineUsage(analysisResponse.usage, planResponse.usage),
                        imageAnalysisSource = BuildImageAnalysisSource(
                            providerId = selection.providerId.value,
                            modelId = model.id,
                            analysis = analysis,
                        ),
                    )
                } finally {
                    image.close()
                }
            }
        }
    }

    private fun parsePlan(content: String, request: BuildRequest, providerId: String, modelId: String): com.craftmind.app.domain.buildplan.ValidatedBuildPlan =
        parser.parse(
            content = content,
            request = request,
            providerId = providerId,
            modelId = modelId,
            generatedAtEpochMillis = nowEpochMillis(),
        )

    private fun combineUsage(first: com.craftmind.app.domain.ai.AiUsage?, second: com.craftmind.app.domain.ai.AiUsage?): com.craftmind.app.domain.ai.AiUsage? {
        if (first == null) return second
        if (second == null) return first
        return com.craftmind.app.domain.ai.AiUsage(
            inputTokens = first.inputTokens?.let { left -> second.inputTokens?.let { right -> left + right } ?: left } ?: second.inputTokens,
            outputTokens = first.outputTokens?.let { left -> second.outputTokens?.let { right -> left + right } ?: left } ?: second.outputTokens,
            providerRequestId = second.providerRequestId,
        )
    }

    private fun validateGenerationRequest(request: BuildRequest) {
        if ((request.prompt.isBlank() && request.imageReference == null) ||
            request.prompt.length > BuildRequestValidator.MAX_PROMPT_LENGTH
        ) {
            throw providerFailure(AiErrorCode.INVALID_BUILD_REQUEST)
        }
        request.imageReference?.let { reference ->
            when (requestValidator.validateImage(reference)) {
                null -> Unit
                BuildRequestValidationError.IMAGE_TOO_LARGE -> throw providerFailure(AiErrorCode.IMAGE_TOO_LARGE)
                BuildRequestValidationError.UNSUPPORTED_IMAGE_TYPE,
                BuildRequestValidationError.INVALID_IMAGE_REFERENCE -> throw providerFailure(AiErrorCode.IMAGE_CONTENT_INVALID)
                else -> throw providerFailure(AiErrorCode.INVALID_BUILD_REQUEST)
            }
        }
        request.urlReference?.let { reference ->
            if (requestValidator.validateUrl(reference.url) !is UrlValidationResult.Valid) {
                throw providerFailure(AiErrorCode.INVALID_BUILD_REQUEST)
            }
        }
    }

    override suspend fun refine(request: BuildEditRequest): AiRefinementResponse {
        if (request.instruction.isBlank() || request.instruction.length > BuildPlanLimits.MAX_EDIT_INSTRUCTION_LENGTH ||
            request.baseRecordId.isBlank() || request.buildId.isBlank() || request.baseVersion < 1 ||
            request.basePlan.status != BuildStatus.READY ||
            request.originalRequest.imageAnalysisSource?.let { source ->
                request.originalRequest.imageContentUri == null || !source.isWellFormed()
            } == true ||
            planValidator.validate(request.basePlan) !is BuildPlanValidationResult.Valid
        ) {
            throw providerFailure(AiErrorCode.INVALID_BUILD_EDIT)
        }
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
            val model = findSelectedModel(adapter, credential, selection)
            if (!model.capabilities.textGeneration ||
                model.capabilities.structuredOutput != StructuredOutputMode.JSON_MIME_TYPE
            ) {
                throw providerFailure(AiErrorCode.UNSUPPORTED_CAPABILITY)
            }
            val context = when (val result = contextSerializer.serialize(request, model)) {
                is BuildPlanContextResult.Ready -> result.context
                BuildPlanContextResult.TooLarge -> throw providerFailure(AiErrorCode.REFINEMENT_CONTEXT_TOO_LARGE)
            }
            val providerRequest = if (context.targetComponentHints.isNotEmpty()) {
                BuildComponentModificationPrompt.forRequest(request, model, context)
            } else {
                BuildPlanRefinementPrompt.forRequest(request, model, context)
            }
            val providerResponse = adapter.generateContent(providerRequest, credential)
            editParser.parse(
                content = providerResponse.content,
                request = request,
                providerId = selection.providerId.value,
                modelId = model.id,
                generatedAtEpochMillis = nowEpochMillis(),
                componentsWithFullOperationContext = context.fullOperationComponentIds,
                usage = providerResponse.usage,
            )
        }
    }

    private suspend fun findSelectedModel(
        adapter: AiProviderAdapter,
        credential: ProviderCredential,
        selection: AiProviderSelection,
    ): AiModel {
        val models = adapter.listModels(credential)
        val model = models.firstOrNull { it.id == selection.modelId }
            ?: throw providerFailure(AiErrorCode.MODEL_UNAVAILABLE)
        if (model.providerId != selection.providerId) throw providerFailure(AiErrorCode.UNSUPPORTED_CAPABILITY)
        return model
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
