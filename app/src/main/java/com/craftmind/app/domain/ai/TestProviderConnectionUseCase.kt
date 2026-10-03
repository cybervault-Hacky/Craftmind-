package com.craftmind.app.domain.ai

import com.craftmind.app.domain.model.BuildError
import com.craftmind.app.domain.model.BuildErrorCode
import kotlinx.coroutines.CancellationException

sealed interface ProviderConnectionResult {
    data object Connected : ProviderConnectionResult
    data class Failed(val error: BuildError) : ProviderConnectionResult
}

class TestProviderConnectionUseCase(
    private val credentialStore: CredentialStore,
    private val providerRegistry: AiProviderRegistry,
) {
    suspend operator fun invoke(configuration: ProviderConfiguration): ProviderConnectionResult {
        val providerId = configuration.providerId
            ?: return failed(BuildErrorCode.MISSING_PROVIDER)
        val modelId = AiModelIdValidator.normalize(configuration.modelId)
            ?: return failed(BuildErrorCode.MISSING_MODEL)
        val provider = providerRegistry.find(providerId)
            ?: return failed(BuildErrorCode.UNSUPPORTED_PROVIDER)
        val credential = try {
            credentialStore.get(CredentialAlias(providerId.value))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(BuildErrorCode.CREDENTIAL_STORAGE_FAILED)
        } ?: return failed(BuildErrorCode.MISSING_API_KEY)

        val result = try {
            provider.testConnection(AiModel(modelId), credential)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AiProviderResult.Failure(AiProviderError(AiProviderErrorCode.UNKNOWN, retryable = true))
        } finally {
            credential.clear()
        }
        return when (result) {
            is AiProviderResult.Success -> ProviderConnectionResult.Connected
            is AiProviderResult.Failure -> ProviderConnectionResult.Failed(result.error.toBuildError())
        }
    }

    private fun failed(code: BuildErrorCode) = ProviderConnectionResult.Failed(BuildError(code))
}
