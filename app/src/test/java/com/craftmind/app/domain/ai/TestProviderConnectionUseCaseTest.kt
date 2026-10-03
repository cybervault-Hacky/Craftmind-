package com.craftmind.app.domain.ai

import com.craftmind.app.domain.model.BuildErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestProviderConnectionUseCaseTest {
    @Test
    fun requiresAConfiguredProviderModelAndCredential() = runTest {
        val provider = CountingProvider()
        val credentials = TestCredentialStore(configured = false)
        val useCase = TestProviderConnectionUseCase(credentials, TestRegistry(provider))

        val noProvider = useCase(ProviderConfiguration()) as ProviderConnectionResult.Failed
        assertEquals(BuildErrorCode.MISSING_PROVIDER, noProvider.error.code)
        val noModel = useCase(ProviderConfiguration(ProviderId.OPENAI, "")) as ProviderConnectionResult.Failed
        assertEquals(BuildErrorCode.MISSING_MODEL, noModel.error.code)
        val noKey = useCase(ProviderConfiguration(ProviderId.OPENAI, "gpt-4o-mini")) as ProviderConnectionResult.Failed
        assertEquals(BuildErrorCode.MISSING_API_KEY, noKey.error.code)
        assertEquals(0, provider.connectionCalls)
    }

    @Test
    fun reportsSuccessOnlyWhenProviderCheckSucceedsAndClearsCredential() = runTest {
        val provider = CountingProvider(AiProviderResult.Success(Unit))
        val credentials = TestCredentialStore()
        val useCase = TestProviderConnectionUseCase(credentials, TestRegistry(provider))

        val result = useCase(ProviderConfiguration(ProviderId.OPENAI, " gpt-4o-mini "))

        assertEquals(ProviderConnectionResult.Connected, result)
        assertEquals(1, provider.connectionCalls)
        assertEquals(0, provider.generationCalls)
        assertEquals("\u0000".repeat("connection-test-key".length), credentials.lastSecret!!.use { it.concatToString() })
    }

    @Test
    fun mapsProviderFailureAndPreservesCancellation() = runTest {
        val rejectedProvider = CountingProvider(
            AiProviderResult.Failure(AiProviderError(AiProviderErrorCode.INVALID_API_KEY)),
        )
        val result = TestProviderConnectionUseCase(
            TestCredentialStore(),
            TestRegistry(rejectedProvider),
        )(ProviderConfiguration(ProviderId.OPENAI, "gpt-4o-mini")) as ProviderConnectionResult.Failed
        assertEquals(BuildErrorCode.INVALID_API_KEY, result.error.code)

        val waitingProvider = object : CountingProvider() {
            override suspend fun testConnection(model: AiModel, credential: SecretValue): AiProviderResult<Unit> {
                awaitCancellation()
            }
        }
        val cancellation = try {
            withTimeout(1) {
                TestProviderConnectionUseCase(TestCredentialStore(), TestRegistry(waitingProvider))(
                    ProviderConfiguration(ProviderId.OPENAI, "gpt-4o-mini"),
                )
            }
            null
        } catch (cancelled: TimeoutCancellationException) {
            cancelled
        }
        assertTrue(cancellation is CancellationException)
    }

    private class TestRegistry(private val provider: AiProvider) : AiProviderRegistry {
        override fun availableProviders() = listOf(provider.descriptor)
        override fun find(providerId: ProviderId): AiProvider? = provider.takeIf { providerId == ProviderId.OPENAI }
    }

    private open class CountingProvider(
        private val result: AiProviderResult<Unit> = AiProviderResult.Success(Unit),
    ) : AiProvider {
        override val descriptor = AiProviderDescriptor(ProviderId.OPENAI, "OpenAI", "test")
        var connectionCalls = 0
        var generationCalls = 0

        override suspend fun generateBuildPlan(
            request: com.craftmind.app.domain.model.BuildRequest,
            model: AiModel,
            credential: SecretValue,
        ): AiProviderResult<com.craftmind.app.domain.model.BuildPlanDraft> {
            generationCalls++
            error("Generation is not expected in a connection check")
        }

        override suspend fun testConnection(model: AiModel, credential: SecretValue): AiProviderResult<Unit> {
            connectionCalls++
            return result
        }
    }

    private class TestCredentialStore(private val configured: Boolean = true) : CredentialStore {
        var lastSecret: SecretValue? = null
        override suspend fun put(alias: CredentialAlias, value: SecretValue) = value.clear()
        override suspend fun get(alias: CredentialAlias): SecretValue? =
            if (configured) SecretValue.copyOf("connection-test-key".toCharArray()).also { lastSecret = it } else null
        override suspend fun contains(alias: CredentialAlias) = configured
        override suspend fun remove(alias: CredentialAlias) = Unit
    }
}
