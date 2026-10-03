package com.craftmind.app.domain.planning

import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelIdValidator
import com.craftmind.app.domain.ai.AiProviderError
import com.craftmind.app.domain.ai.AiProviderErrorCode
import com.craftmind.app.domain.ai.AiProviderRegistry
import com.craftmind.app.domain.ai.AiProviderResult
import com.craftmind.app.domain.ai.BuildGenerationEvent
import com.craftmind.app.domain.ai.BuildPlanGenerationUseCase
import com.craftmind.app.domain.ai.CredentialAlias
import com.craftmind.app.domain.ai.CredentialStore
import com.craftmind.app.domain.ai.ProviderConfigurationRepository
import com.craftmind.app.domain.ai.toBuildError
import com.craftmind.app.domain.model.BuildError
import com.craftmind.app.domain.model.BuildErrorCode
import com.craftmind.app.domain.model.BuildResult
import com.craftmind.app.domain.validation.BuildRequestValidationResult
import com.craftmind.app.domain.validation.BuildRequestValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.UUID

class GenerateBuildPlanUseCase(
    private val requestValidator: BuildRequestValidator,
    private val configurationRepository: ProviderConfigurationRepository,
    private val credentialStore: CredentialStore,
    private val providerRegistry: AiProviderRegistry,
    private val planValidator: BuildPlanValidator,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
    private val newPlanId: () -> String = { UUID.randomUUID().toString() },
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BuildPlanGenerationUseCase {

    override fun invoke(request: com.craftmind.app.domain.model.BuildRequest): Flow<BuildGenerationEvent> = flow {
        emit(BuildGenerationEvent.ValidatingRequest)
        val validatedRequest = when (val result = withContext(computationDispatcher) {
            requestValidator.validate(request)
        }) {
            is BuildRequestValidationResult.Valid -> result.request
            is BuildRequestValidationResult.Invalid -> {
                emit(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.INVALID_INPUT)))
                return@flow
            }
        }

        // Phase 2 intentionally supports text only. Never silently drop or upload attachments.
        if (validatedRequest.imageReferences.isNotEmpty() || validatedRequest.urlReferences.isNotEmpty()) {
            emit(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.UNSUPPORTED_REFERENCE)))
            return@flow
        }
        if (validatedRequest.text == null) {
            emit(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.INVALID_INPUT)))
            return@flow
        }

        val configuration = try {
            configurationRepository.configuration.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.UNKNOWN, retryable = true)))
            return@flow
        }
        val providerId = configuration.providerId
        if (providerId == null) {
            emit(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.MISSING_PROVIDER)))
            return@flow
        }
        val modelId = AiModelIdValidator.normalize(configuration.modelId)
        if (modelId == null) {
            emit(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.MISSING_MODEL)))
            return@flow
        }
        val provider = providerRegistry.find(providerId)
        if (provider == null) {
            emit(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.UNSUPPORTED_PROVIDER)))
            return@flow
        }
        val credential = try {
            credentialStore.get(CredentialAlias(providerId.value))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.CREDENTIAL_STORAGE_FAILED)))
            return@flow
        }
        if (credential == null) {
            emit(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.MISSING_API_KEY)))
            return@flow
        }

        val providerResult = try {
            emit(BuildGenerationEvent.Generating)
            provider.generateBuildPlan(validatedRequest, AiModel(modelId), credential)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AiProviderResult.Failure(AiProviderError(AiProviderErrorCode.UNKNOWN, retryable = true))
        } finally {
            credential.clear()
        }

        when (providerResult) {
            is AiProviderResult.Failure -> emit(BuildGenerationEvent.Failed(providerResult.error.toBuildError()))
            is AiProviderResult.Success -> {
                emit(BuildGenerationEvent.ValidatingPlan)
                val validation = withContext(computationDispatcher) {
                    planValidator.validate(
                        draft = providerResult.value,
                        planId = newPlanId(),
                        generatedAtEpochMillis = currentTimeMillis(),
                        providerId = providerId.value,
                        modelId = modelId,
                    )
                }
                when (validation) {
                    is BuildPlanValidationResult.Invalid -> emit(
                        BuildGenerationEvent.Failed(
                            BuildError(
                                code = BuildErrorCode.BUILD_PLAN_REJECTED,
                                retryable = true,
                                validationIssue = validation.issue,
                            ),
                        ),
                    )
                    is BuildPlanValidationResult.Valid -> emit(
                        BuildGenerationEvent.Ready(BuildResult(validation.plan.id, validation.plan)),
                    )
                }
            }
        }
    }.catch { throwable ->
        if (throwable is CancellationException) throw throwable
        emit(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.UNKNOWN, retryable = true)))
    }
}
