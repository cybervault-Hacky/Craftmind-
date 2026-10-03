package com.craftmind.app.presentation.settings

import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiProvider
import com.craftmind.app.domain.ai.AiProviderDescriptor
import com.craftmind.app.domain.ai.AiProviderError
import com.craftmind.app.domain.ai.AiProviderErrorCode
import com.craftmind.app.domain.ai.AiProviderRegistry
import com.craftmind.app.domain.ai.AiProviderResult
import com.craftmind.app.domain.ai.CredentialAlias
import com.craftmind.app.domain.ai.CredentialStore
import com.craftmind.app.domain.ai.ProviderConfiguration
import com.craftmind.app.domain.ai.ProviderConfigurationRepository
import com.craftmind.app.domain.ai.ProviderId
import com.craftmind.app.domain.ai.SecretValue
import com.craftmind.app.domain.ai.TestProviderConnectionUseCase
import com.craftmind.app.domain.model.BuildPlanDraft
import com.craftmind.app.domain.model.BuildRequest
import com.craftmind.app.presentation.builder.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiProviderSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun savesOnlySafeCredentialMetadataAndWipesCallerBuffer() {
        val configuration = InMemoryProviderConfigurationRepository()
        val credentials = RecordingCredentialStore()
        val provider = FakeProvider()
        val viewModel = viewModel(configuration, credentials, provider)
        viewModel.selectProvider(ProviderId.OPENAI)
        viewModel.onModelIdChanged("gpt-4o-mini")
        val raw = "development-test-key".toCharArray()

        viewModel.saveApiKey(raw)

        assertEquals("\u0000".repeat(raw.size), raw.concatToString())
        assertTrue(credentials.configured)
        assertEquals("development-test-key", credentials.lastWrittenForTest)
        assertTrue(viewModel.uiState.value.apiKeyConfigured)
        assertFalse(viewModel.uiState.value.toString().contains("development-test-key"))
        assertEquals(null, viewModel.uiState.value.message)
    }

    @Test
    fun testConnectionPersistsModelAndCallsNonGenerativeProviderCheck() {
        val configuration = InMemoryProviderConfigurationRepository()
        val credentials = RecordingCredentialStore().apply { configured = true }
        val provider = FakeProvider()
        val viewModel = viewModel(configuration, credentials, provider)
        viewModel.selectProvider(ProviderId.OPENAI)
        viewModel.onModelIdChanged(" gpt-4o-mini ")

        viewModel.testConnection()

        assertEquals(ProviderConfiguration(ProviderId.OPENAI, "gpt-4o-mini"), configuration.current.value)
        assertEquals(ProviderConnectionUiState.Connected, viewModel.uiState.value.connection)
        assertEquals(1, provider.connectionCalls)
        assertEquals(0, provider.generationCalls)
        assertTrue(viewModel.uiState.value.apiKeyConfigured)
    }

    @Test
    fun invalidModelAndMissingKeyExposeTypedStateWithoutCallingProvider() {
        val configuration = InMemoryProviderConfigurationRepository()
        val credentials = RecordingCredentialStore()
        val provider = FakeProvider()
        val viewModel = viewModel(configuration, credentials, provider)
        viewModel.selectProvider(ProviderId.OPENAI)
        viewModel.onModelIdChanged("bad/model")

        viewModel.testConnection()

        assertEquals(ProviderSettingsMessage.MODEL_INVALID, viewModel.uiState.value.message)
        assertEquals(0, provider.connectionCalls)

        viewModel.onModelIdChanged("gpt-4o-mini")
        viewModel.testConnection()
        val failure = viewModel.uiState.value.connection as ProviderConnectionUiState.Failed
        assertEquals(com.craftmind.app.domain.model.BuildErrorCode.MISSING_API_KEY, failure.error.code)
        assertEquals(0, provider.connectionCalls)
    }

    @Test
    fun providerFailuresRemainSafeTypedMessages() {
        val configuration = InMemoryProviderConfigurationRepository()
        val credentials = RecordingCredentialStore().apply { configured = true }
        val provider = FakeProvider(
            connectionResult = AiProviderResult.Failure(
                AiProviderError(AiProviderErrorCode.INVALID_API_KEY),
            ),
        )
        val viewModel = viewModel(configuration, credentials, provider)
        viewModel.selectProvider(ProviderId.OPENAI)
        viewModel.onModelIdChanged("gpt-4o-mini")

        viewModel.testConnection()

        val failure = viewModel.uiState.value.connection as ProviderConnectionUiState.Failed
        assertEquals(com.craftmind.app.domain.model.BuildErrorCode.INVALID_API_KEY, failure.error.code)
        assertFalse(viewModel.uiState.value.toString().contains("key"))
    }

    private fun viewModel(
        configuration: InMemoryProviderConfigurationRepository,
        credentials: RecordingCredentialStore,
        provider: FakeProvider,
    ): AiProviderSettingsViewModel {
        val registry = object : AiProviderRegistry {
            override fun availableProviders() = listOf(provider.descriptor)
            override fun find(providerId: ProviderId): AiProvider? = provider.takeIf { providerId == ProviderId.OPENAI }
        }
        return AiProviderSettingsViewModel(
            configuration,
            credentials,
            registry,
            TestProviderConnectionUseCase(credentials, registry),
        )
    }

    private class InMemoryProviderConfigurationRepository : ProviderConfigurationRepository {
        val current = MutableStateFlow(ProviderConfiguration())
        override val configuration = current
        override suspend fun save(configuration: ProviderConfiguration) {
            current.value = configuration
        }
    }

    private class RecordingCredentialStore : CredentialStore {
        var configured = false
        var lastWrittenForTest: String? = null

        override suspend fun put(alias: CredentialAlias, value: SecretValue) {
            lastWrittenForTest = value.use { it.concatToString() }
            configured = true
            value.clear()
        }

        override suspend fun get(alias: CredentialAlias): SecretValue? =
            if (configured) SecretValue.copyOf("development-test-key".toCharArray()) else null

        override suspend fun contains(alias: CredentialAlias) = configured
        override suspend fun remove(alias: CredentialAlias) {
            configured = false
        }
    }

    private class FakeProvider(
        private val connectionResult: AiProviderResult<Unit> = AiProviderResult.Success(Unit),
    ) : AiProvider {
        override val descriptor = AiProviderDescriptor(ProviderId.OPENAI, "OpenAI", "fake provider for tests")
        var connectionCalls = 0
        var generationCalls = 0

        override suspend fun generateBuildPlan(
            request: BuildRequest,
            model: AiModel,
            credential: SecretValue,
        ): AiProviderResult<BuildPlanDraft> {
            generationCalls++
            error("Generation is not part of a settings connection check")
        }

        override suspend fun testConnection(model: AiModel, credential: SecretValue): AiProviderResult<Unit> {
            connectionCalls++
            return connectionResult
        }
    }
}
