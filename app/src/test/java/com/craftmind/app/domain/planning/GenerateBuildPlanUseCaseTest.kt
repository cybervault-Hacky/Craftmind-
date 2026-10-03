package com.craftmind.app.domain.planning

import com.craftmind.app.domain.model.BuildLimits
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiProvider
import com.craftmind.app.domain.ai.AiProviderDescriptor
import com.craftmind.app.domain.ai.AiProviderError
import com.craftmind.app.domain.ai.AiProviderErrorCode
import com.craftmind.app.domain.ai.AiProviderRegistry
import com.craftmind.app.domain.ai.AiProviderResult
import com.craftmind.app.domain.ai.BuildGenerationEvent
import com.craftmind.app.domain.ai.CredentialAlias
import com.craftmind.app.domain.ai.CredentialStore
import com.craftmind.app.domain.ai.ProviderConfiguration
import com.craftmind.app.domain.ai.ProviderConfigurationRepository
import com.craftmind.app.domain.ai.ProviderId
import com.craftmind.app.domain.ai.SecretValue
import com.craftmind.app.domain.model.BlockCoordinate
import com.craftmind.app.domain.model.BlockOperation
import com.craftmind.app.domain.model.BlockState
import com.craftmind.app.domain.model.BuildDimensions
import com.craftmind.app.domain.model.BuildError
import com.craftmind.app.domain.model.BuildErrorCode
import com.craftmind.app.domain.model.BuildPlanDraft
import com.craftmind.app.domain.model.BuildPlanStep
import com.craftmind.app.domain.model.BuildPlanValidationIssue
import com.craftmind.app.domain.model.BuildRequest
import com.craftmind.app.domain.model.ImageReference
import com.craftmind.app.domain.model.MaterialRequirement
import com.craftmind.app.domain.model.TextInput
import com.craftmind.app.domain.model.UrlReference
import com.craftmind.app.domain.validation.BuildRequestValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateBuildPlanUseCaseTest {
    @Test
    fun validTextRequestProducesOnlyValidatedReadyResult() = runTest {
        val provider = FakeProvider(AiProviderResult.Success(validDraft()))
        val credentials = FakeCredentialStore()
        val useCase = useCase(provider, credentials, ProviderConfiguration(ProviderId.OPENAI, "gpt-4o-mini"))

        val events = useCase(BuildRequest(listOf(TextInput(" A small stone hut ")))).toList()

        assertEquals(BuildGenerationEvent.ValidatingRequest, events[0])
        assertEquals(BuildGenerationEvent.Generating, events[1])
        assertEquals(BuildGenerationEvent.ValidatingPlan, events[2])
        assertTrue(events[3] is BuildGenerationEvent.Ready)
        val result = (events[3] as BuildGenerationEvent.Ready).result
        assertEquals("plan-test", result.buildId)
        assertEquals(1, result.plan.operations.size)
        assertEquals(1_700_000_000_000L, result.plan.generatedAtEpochMillis)
        assertEquals("gpt-4o-mini", result.plan.generator.modelId)
        assertEquals("\u0000".repeat("test-key".length), credentials.lastReturnedSecret!!.use { it.concatToString() })
    }

    @Test
    fun imageAndUrlReferencesAreRejectedBeforeProviderCall() = runTest {
        val provider = FakeProvider(AiProviderResult.Success(validDraft()))
        val useCase = useCase(provider, FakeCredentialStore(), ProviderConfiguration(ProviderId.OPENAI, "gpt-4o-mini"))
        val request = BuildRequest(
            listOf(
                TextInput("A hut"),
                ImageReference("content://images/ref", "image/jpeg", "ref.jpg", 512),
                UrlReference("https://example.com/reference"),
            ),
        )

        val events = useCase(request).toList()

        assertEquals(
            BuildGenerationEvent.Failed(BuildError(BuildErrorCode.UNSUPPORTED_REFERENCE)),
            events.last(),
        )
        assertEquals(0, provider.generationCalls)
    }

    @Test
    fun missingProviderModelKeyAndInvalidPlanNeverProduceReady() = runTest {
        val provider = FakeProvider(AiProviderResult.Success(validDraft()))
        val credentialStore = FakeCredentialStore(hasCredential = false)

        val missingProvider = useCase(provider, credentialStore, ProviderConfiguration())
            .invoke(BuildRequest(listOf(TextInput("A hut")))).toList().last()
        assertEquals(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.MISSING_PROVIDER)), missingProvider)

        val missingModel = useCase(provider, credentialStore, ProviderConfiguration(ProviderId.OPENAI, ""))
            .invoke(BuildRequest(listOf(TextInput("A hut")))).toList().last()
        assertEquals(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.MISSING_MODEL)), missingModel)

        val missingKey = useCase(provider, credentialStore, ProviderConfiguration(ProviderId.OPENAI, "gpt-4o-mini"))
            .invoke(BuildRequest(listOf(TextInput("A hut")))).toList().last()
        assertEquals(BuildGenerationEvent.Failed(BuildError(BuildErrorCode.MISSING_API_KEY)), missingKey)

        val invalidPlanUseCase = useCase(
            FakeProvider(AiProviderResult.Success(validDraft().copy(schemaVersion = 7))),
            FakeCredentialStore(),
            ProviderConfiguration(ProviderId.OPENAI, "gpt-4o-mini"),
        )
        val invalidPlanEvents = invalidPlanUseCase(BuildRequest(listOf(TextInput("A hut")))).toList()
        assertEquals(0, invalidPlanEvents.count { it is BuildGenerationEvent.Ready })
        assertEquals(
            BuildGenerationEvent.Failed(
                BuildError(BuildErrorCode.BUILD_PLAN_REJECTED, retryable = true, validationIssue = BuildPlanValidationIssue.UNSUPPORTED_SCHEMA_VERSION),
            ),
            invalidPlanEvents.last(),
        )
    }

    @Test
    fun providerFailureRemainsTypedAndSafe() = runTest {
        val provider = FakeProvider(
            AiProviderResult.Failure(AiProviderError(AiProviderErrorCode.RATE_LIMITED, retryable = true)),
        )
        val useCase = useCase(provider, FakeCredentialStore(), ProviderConfiguration(ProviderId.OPENAI, "gpt-4o-mini"))

        val failure = useCase(BuildRequest(listOf(TextInput("A hut")))).toList().last() as BuildGenerationEvent.Failed

        assertEquals(BuildErrorCode.RATE_LIMITED, failure.error.code)
        assertTrue(failure.error.retryable)
        assertFalse(failure.toString().contains("test-key"))
    }

    @Test
    fun cancellationIsNotConvertedToAnOrdinaryFailure() = runTest {
        val provider = object : FakeProvider(AiProviderResult.Success(validDraft())) {
            override suspend fun generateBuildPlan(
                request: BuildRequest,
                model: AiModel,
                credential: SecretValue,
            ): AiProviderResult<BuildPlanDraft> {
                awaitCancellation()
            }
        }
        val useCase = useCase(provider, FakeCredentialStore(), ProviderConfiguration(ProviderId.OPENAI, "gpt-4o-mini"))

        val failure = try {
            withTimeout(1) { useCase(BuildRequest(listOf(TextInput("A hut")))).toList() }
            null
        } catch (cancelled: TimeoutCancellationException) {
            cancelled
        }
        assertTrue(failure is CancellationException)
    }

    private fun useCase(
        provider: FakeProvider,
        credentials: FakeCredentialStore,
        providerConfiguration: ProviderConfiguration,
    ) = GenerateBuildPlanUseCase(
        requestValidator = BuildRequestValidator(),
        configurationRepository = object : ProviderConfigurationRepository {
            override val configuration: Flow<ProviderConfiguration> = flowOf(providerConfiguration)
            override suspend fun save(configuration: ProviderConfiguration) = Unit
        },
        credentialStore = credentials,
        providerRegistry = object : AiProviderRegistry {
            override fun availableProviders() = listOf(provider.descriptor)
            override fun find(providerId: ProviderId): AiProvider? = provider.takeIf { providerId == ProviderId.OPENAI }
        },
        planValidator = BuildPlanValidator(),
        currentTimeMillis = { 1_700_000_000_000L },
        newPlanId = { "plan-test" },
    )

    private open class FakeProvider(
        private val generationResult: AiProviderResult<BuildPlanDraft>,
    ) : AiProvider {
        override val descriptor = AiProviderDescriptor(ProviderId.OPENAI, "OpenAI", "test provider")
        var generationCalls = 0

        override suspend fun generateBuildPlan(
            request: BuildRequest,
            model: AiModel,
            credential: SecretValue,
        ): AiProviderResult<BuildPlanDraft> {
            generationCalls++
            return generationResult
        }

        override suspend fun testConnection(model: AiModel, credential: SecretValue): AiProviderResult<Unit> =
            AiProviderResult.Success(Unit)
    }

    private class FakeCredentialStore(
        private val hasCredential: Boolean = true,
    ) : CredentialStore {
        var lastReturnedSecret: SecretValue? = null
        override suspend fun put(alias: CredentialAlias, value: SecretValue) = value.clear()
        override suspend fun get(alias: CredentialAlias): SecretValue? =
            if (hasCredential) SecretValue.copyOf("test-key".toCharArray()).also { lastReturnedSecret = it } else null
        override suspend fun contains(alias: CredentialAlias) = hasCredential
        override suspend fun remove(alias: CredentialAlias) = Unit
    }

    private fun validDraft() = BuildPlanDraft(
        schemaVersion = BuildLimits.PLAN_SCHEMA_VERSION,
        title = "Small stone hut",
        style = "rustic",
        dimensions = BuildDimensions(1, 1, 1),
        origin = BlockCoordinate(0, 64, 0),
        materials = listOf(MaterialRequirement("minecraft:stone", 1)),
        steps = listOf(BuildPlanStep("foundation", "Foundation", "Place the base")),
        operations = listOf(
            BlockOperation(0, BlockCoordinate(0, 0, 0), BlockState("minecraft:stone"), "foundation"),
        ),
        estimatedOperationCount = 1,
    )
}
