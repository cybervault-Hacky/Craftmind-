package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelCapabilities
import com.craftmind.app.domain.ai.AiProviderAdapter
import com.craftmind.app.domain.ai.AiProviderCapabilities
import com.craftmind.app.domain.ai.AiProviderDefinition
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.AiProviderRequest
import com.craftmind.app.domain.ai.AiProviderResponse
import com.craftmind.app.domain.ai.AiProviderSelection
import com.craftmind.app.domain.ai.AiProviderSelectionRepository
import com.craftmind.app.domain.ai.StructuredOutputMode
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.security.CredentialStore
import com.craftmind.app.domain.security.ProviderCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBuildEngineTest {
    private val providerId = AiProviderId("test_provider")
    private val model = AiModel(
        id = "test-model",
        providerId = providerId,
        displayName = "Test model",
        capabilities = AiModelCapabilities(
            textGeneration = true,
            vision = false,
            publicUrlReferences = false,
            structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
            maximumContextTokens = 16_000,
            maximumOutputTokens = 2_000,
        ),
    )

    @Test
    fun callsSelectedProviderAndOnlyReturnsStrictlyValidatedAiPlan() = kotlinx.coroutines.runBlocking {
        val provider = FakeProvider(model, validDocument())
        val engine = engine(provider, credential = "test-key", selection = AiProviderSelection(providerId, model.id))
        val request = buildRequest()

        val response = engine.generate(request)

        assertEquals(1, provider.generateCalls)
        assertEquals("A stone garden pavilion", response.plan.plan.metadata.title)
        assertEquals(request.requestId, response.plan.plan.metadata.sourceRequestId)
        assertEquals("test_provider", response.plan.plan.metadata.providerId)
        assertEquals("test-model", response.plan.plan.metadata.modelId)
        assertEquals(BuildStatus.READY, response.plan.plan.status)
        assertEquals(1, response.plan.plan.operations.size)
    }

    @Test
    fun optionalImageAndUrlReferencesAreDisclosedButNotUploadedOrFetched() = kotlinx.coroutines.runBlocking {
        val provider = FakeProvider(model, validDocument())
        val engine = engine(provider, credential = "test-key", selection = AiProviderSelection(providerId, model.id))

        engine.generate(buildRequest(withReferences = true))

        val sentPrompt = provider.lastRequest!!.prompt
        assertTrue(sentPrompt.contains("not upload or analyze it"))
        assertTrue(sentPrompt.contains("not fetched, opened, or analyzed"))
        assertTrue(!sentPrompt.contains("content://private/image"))
        assertTrue(!sentPrompt.contains("https://example.org/reference"))
    }

    @Test
    fun refusesGenerationWithoutCredentialOrVerifiedModelSelection() = kotlinx.coroutines.runBlocking {
        val provider = FakeProvider(model, validDocument())
        val noCredential = engine(provider, credential = null, selection = AiProviderSelection(providerId, model.id))
        assertEquals(AiErrorCode.MISSING_CREDENTIAL, failureCode { noCredential.generate(buildRequest()) })

        val noSelection = engine(provider, credential = "test-key", selection = null)
        assertEquals(AiErrorCode.NO_MODEL_SELECTED, failureCode { noSelection.generate(buildRequest()) })
    }

    @Test
    fun rejectsAStaleModelBeforeGeneration() = kotlinx.coroutines.runBlocking {
        val provider = FakeProvider(model, validDocument())
        val staleSelection = AiProviderSelection(providerId, "removed-model")
        val engine = engine(provider, credential = "test-key", selection = staleSelection)

        assertEquals(AiErrorCode.MODEL_UNAVAILABLE, failureCode { engine.generate(buildRequest()) })
        assertEquals(0, provider.generateCalls)
    }

    @Test
    fun connectionTestPerformsLiveModelLookupAndRequiresCompatibleModels() = kotlinx.coroutines.runBlocking {
        val provider = FakeProvider(model, validDocument())
        val engine = engine(provider, credential = "test-key", selection = null)

        assertEquals(listOf(model), engine.verifyProvider(providerId))
        assertEquals(1, provider.listModelsCalls)

        val emptyProvider = FakeProvider(model, validDocument(), exposeModel = false)
        val emptyEngine = engine(emptyProvider, credential = "test-key", selection = null)
        assertEquals(AiErrorCode.NO_MODELS_AVAILABLE, failureCode { emptyEngine.verifyProvider(providerId) })
    }

    private fun engine(
        provider: FakeProvider,
        credential: String?,
        selection: AiProviderSelection?,
    ): AiBuildEngine = AiBuildEngine(
        registry = com.craftmind.app.domain.ai.AiProviderRegistry(listOf(provider)),
        credentialStore = MemoryCredentialStore(credential),
        selections = MemorySelectionRepository(selection),
        parser = BuildPlanParser(DefaultBuildPlanValidator()),
        nowEpochMillis = { 456L },
    )

    private fun buildRequest(withReferences: Boolean = false) = BuildRequest(
        requestId = "request-engine-test",
        prompt = "A stone garden pavilion",
        imageReference = if (withReferences) BuildInput.ImageReference(
            contentUri = "content://private/image",
            mediaType = "image/png",
            sizeBytes = 1024,
        ) else null,
        urlReference = if (withReferences) BuildInput.UrlReference("https://example.org/reference") else null,
        createdAtEpochMillis = 123L,
    )

    private suspend fun failureCode(block: suspend () -> Unit): AiErrorCode {
        val error = try {
            block()
            throw AssertionError("Expected a safe provider failure")
        } catch (expected: AiProviderException) {
            expected
        }
        return error.failure.code
    }

    private class FakeProvider(
        private val model: AiModel,
        private val responseContent: String,
        private val exposeModel: Boolean = true,
    ) : AiProviderAdapter {
        override val definition = AiProviderDefinition(
            id = model.providerId,
            displayName = "Test provider",
            credentialType = com.craftmind.app.domain.ai.CredentialType.API_KEY,
            baseEndpoint = "https://provider.example/",
            capabilities = AiProviderCapabilities(
                textGeneration = true,
                vision = false,
                publicUrlReferences = false,
                structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
                cancellation = true,
                streaming = false,
            ),
        )
        var listModelsCalls = 0
        var generateCalls = 0
        var lastRequest: AiProviderRequest? = null

        override suspend fun listModels(credential: ProviderCredential): List<AiModel> {
            listModelsCalls++
            return if (exposeModel) listOf(model) else emptyList()
        }

        override suspend fun generateContent(
            request: AiProviderRequest,
            credential: ProviderCredential,
        ): AiProviderResponse {
            generateCalls++
            lastRequest = request
            return AiProviderResponse(responseContent)
        }
    }

    private class MemoryCredentialStore(key: String?) : CredentialStore {
        private val value = key?.toCharArray()
        override suspend fun read(providerId: AiProviderId): ProviderCredential? =
            value?.let { ProviderCredential.fromCharacters(it) }
        override suspend fun write(providerId: AiProviderId, credential: ProviderCredential) {
            error("Not used by generation test")
        }
        override suspend fun remove(providerId: AiProviderId) = Unit
    }

    private class MemorySelectionRepository(selectionValue: AiProviderSelection?) : AiProviderSelectionRepository {
        private val mutableSelection = MutableStateFlow(selectionValue)
        override val selection: StateFlow<AiProviderSelection?> = mutableSelection
        override suspend fun select(selection: AiProviderSelection) { mutableSelection.value = selection }
        override suspend fun clear() { mutableSelection.value = null }
    }

    private fun validDocument(): String = """
        {
          "schemaVersion": 1,
          "buildId": "generated-garden",
          "title": "A stone garden pavilion",
          "description": "A compact open pavilion in a quiet garden.",
          "dimensions": {"width": 8, "height": 5, "depth": 8},
          "originStrategy": "CENTERED_GROUND",
          "components": [{"componentId": "main", "name": "Pavilion", "purpose": "Covered gathering area"}],
          "operations": [{"x": 0, "y": 0, "z": 0, "blockId": "minecraft:stone", "blockState": {}, "componentId": "main"}]
        }
    """.trimIndent()
}
