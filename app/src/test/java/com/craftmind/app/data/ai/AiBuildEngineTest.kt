package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelCapabilities
import com.craftmind.app.domain.ai.AiGenerationStage
import com.craftmind.app.domain.ai.AiImageInput
import com.craftmind.app.domain.ai.AiImageInputPreparer
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
import com.craftmind.app.domain.buildplan.AiBlockOperationDocument
import com.craftmind.app.domain.buildplan.AiBuildComponentDocument
import com.craftmind.app.domain.buildplan.AiBuildEditDocument
import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.BuildImageAnalysis
import com.craftmind.app.domain.buildplan.BuildImageAnalysisSource
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import com.craftmind.app.domain.buildplan.BlockBounds
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildComponentType
import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanComponent
import com.craftmind.app.domain.buildplan.BuildPlanMetadata
import com.craftmind.app.domain.buildplan.BuildPlanOperation
import com.craftmind.app.domain.buildplan.BuildPlanOperationKind
import com.craftmind.app.domain.buildplan.BuildOriginStrategy
import com.craftmind.app.domain.buildplan.BuildIntent
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.BuildPlanTestFixtures
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.security.CredentialStore
import com.craftmind.app.domain.security.ProviderCredential
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun refusesImageWhenTheExactSelectedModelDoesNotAdvertiseVision() = kotlinx.coroutines.runBlocking {
        val provider = FakeProvider(model, validDocument())
        val preparer = FakeImagePreparer()
        val engine = engine(provider, credential = "test-key", selection = AiProviderSelection(providerId, model.id), imagePreparer = preparer)

        assertEquals(AiErrorCode.VISION_UNSUPPORTED, failureCode { engine.generate(buildRequest(withReferences = true)) })
        assertEquals(0, preparer.calls)
        assertEquals(0, provider.generateCalls)
    }

    @Test
    fun unsupportedPublicVideoHostFailsClosedBeforeProviderGeneration() = kotlinx.coroutines.runBlocking {
        val provider = FakeProvider(model, validDocument())
        val engine = engine(provider, credential = "test-key", selection = AiProviderSelection(providerId, model.id))
        val request = buildRequest().copy(urlReference = BuildInput.UrlReference("https://example.org/private-reference"))

        assertEquals(AiErrorCode.REFERENCE_UNSUPPORTED_SOURCE, failureCode { engine.generate(request) })
        assertEquals(0, provider.generateCalls)
    }

    @Test
    fun publicVideoUsesBoundedFramesAndSameSelectedModelBeforeBuildPlanV2() = kotlinx.coroutines.runBlocking {
        val videoModel = model.copy(capabilities = model.capabilities.copy(vision = true, multipleImages = true))
        val videoUrl = "https://raw.githubusercontent.com/cseitz/sample-files/main/assets/video/mp4/bbb_short.mp4"
        val validatedUrl = (com.craftmind.app.domain.reference.PublicVideoReferenceUrlPolicy.validate(videoUrl)
            as com.craftmind.app.domain.reference.PublicVideoUrlValidation.Valid).value
        val resolved = com.craftmind.app.domain.reference.ResolvedPublicVideoReference(
            canonicalUrl = validatedUrl.canonicalUrl,
            sourceDomain = validatedUrl.sourceDomain,
            mediaType = validatedUrl.mediaType,
            contentLengthBytes = 64_000L,
        )
        val videoFrames = com.craftmind.app.domain.reference.ExtractedPublicVideoFrames(
            durationMillis = 10_000L,
            videoWidth = 640,
            videoHeight = 360,
            frames = listOf(
                com.craftmind.app.domain.reference.PublicVideoFrame(1_000L, AiImageInput("image/jpeg", 2, 2, byteArrayOf(1, 2, 3))),
                com.craftmind.app.domain.reference.PublicVideoFrame(9_500L, AiImageInput("image/jpeg", 2, 2, byteArrayOf(4, 5, 6))),
            ),
        )
        val resolverCalls = mutableListOf<String>()
        val resolver = object : com.craftmind.app.domain.reference.PublicVideoReferenceResolver {
            override suspend fun resolve(url: String): com.craftmind.app.domain.reference.ResolvedPublicVideoReference {
                resolverCalls += url
                return resolved
            }
        }
        val extractor = object : com.craftmind.app.domain.reference.PublicVideoFrameExtractor {
            override suspend fun extract(
                reference: com.craftmind.app.domain.reference.ResolvedPublicVideoReference,
            ) = videoFrames
        }
        val analysisJson = """{"schemaVersion":1,"summary":"A single pavilion is visible at several stages.","observedDetails":["A shallow roof and four posts are visible in the final sample."],"inferredDetails":["The posts may be wooden."],"uncertainties":["The rear wall is partly occluded."]}"""
        val provider = FakeProvider(videoModel, validDocument(), precedingResponses = listOf(analysisJson))
        val engine = engine(
            provider,
            credential = "test-key",
            selection = AiProviderSelection(providerId, videoModel.id),
            videoResolver = resolver,
            videoExtractor = extractor,
        )
        val request = buildRequest().copy(
            prompt = "Make one compact pavilion based on this video",
            urlReference = BuildInput.UrlReference(videoUrl),
        )
        val stages = mutableListOf<AiGenerationStage>()

        val response = engine.generate(request, stages::add)

        assertEquals(listOf(videoUrl), resolverCalls)
        assertEquals(2, provider.generateCalls)
        assertEquals(2, provider.requests[0].imageInputs.size)
        assertTrue(provider.requests[0].prompt.contains("one public video"))
        assertTrue(provider.requests[0].prompt.contains("00:09.500"))
        assertFalse(provider.requests[0].prompt.contains(videoUrl))
        assertEquals(videoModel.id, provider.requests[0].model.id)
        assertTrue(provider.requests[1].imageInputs.isEmpty())
        assertEquals(videoModel.id, provider.requests[1].model.id)
        assertTrue(provider.requests[1].prompt.contains("one source and one build"))
        assertTrue(provider.requests[1].prompt.contains("observedDetails"))
        assertFalse(provider.requests[1].prompt.contains(videoUrl))
        assertEquals(2, response.referenceAnalysisSource?.frameCount)
        assertEquals("A single pavilion is visible at several stages.", response.referenceAnalysisSource?.analysis?.summary)
        assertTrue(stages.contains(AiGenerationStage.RESOLVING_PUBLIC_VIDEO_REFERENCE))
        assertTrue(stages.contains(AiGenerationStage.EXTRACTING_VIDEO_FRAMES))
        assertTrue(stages.contains(AiGenerationStage.ANALYZING_VIDEO_FRAMES_WITH_SELECTED_MODEL))
        assertTrue(runCatching { videoFrames.frames.first().image.useBytes { it.size } }.isFailure)
    }

    @Test
    fun publicVideoRequiresExactSelectedMultiImageCapabilityBeforeResolvingSource() = kotlinx.coroutines.runBlocking {
        val visionOnlyModel = model.copy(capabilities = model.capabilities.copy(vision = true, multipleImages = false))
        val provider = FakeProvider(visionOnlyModel, validDocument())
        var resolverCalls = 0
        val resolver = object : com.craftmind.app.domain.reference.PublicVideoReferenceResolver {
            override suspend fun resolve(url: String): com.craftmind.app.domain.reference.ResolvedPublicVideoReference {
                resolverCalls++
                error("The source must not be accessed for an unsupported model")
            }
        }
        val engine = engine(
            provider,
            credential = "test-key",
            selection = AiProviderSelection(providerId, visionOnlyModel.id),
            videoResolver = resolver,
            videoExtractor = object : com.craftmind.app.domain.reference.PublicVideoFrameExtractor {
                override suspend fun extract(reference: com.craftmind.app.domain.reference.ResolvedPublicVideoReference) =
                    error("Frame extraction must not run")
            },
        )
        val request = buildRequest().copy(
            urlReference = BuildInput.UrlReference("https://raw.githubusercontent.com/owner/repo/main/video.mp4"),
        )

        assertEquals(AiErrorCode.MULTI_IMAGE_UNSUPPORTED, failureCode { engine.generate(request) })
        assertEquals(0, resolverCalls)
        assertEquals(0, provider.generateCalls)
    }

    @Test
    fun mapsPrivateDestinationFailureToSanitizedTypedErrorBeforeProviderAnalysis() = kotlinx.coroutines.runBlocking {
        val videoModel = model.copy(capabilities = model.capabilities.copy(vision = true, multipleImages = true))
        val provider = FakeProvider(videoModel, validDocument())
        val resolver = object : com.craftmind.app.domain.reference.PublicVideoReferenceResolver {
            override suspend fun resolve(url: String): com.craftmind.app.domain.reference.ResolvedPublicVideoReference {
                throw com.craftmind.app.domain.reference.PublicVideoReferenceException(
                    com.craftmind.app.domain.reference.PublicVideoReferenceFailure.UNSAFE_DESTINATION,
                )
            }
        }
        val engine = engine(
            provider,
            credential = "test-key",
            selection = AiProviderSelection(providerId, videoModel.id),
            videoResolver = resolver,
            videoExtractor = object : com.craftmind.app.domain.reference.PublicVideoFrameExtractor {
                override suspend fun extract(reference: com.craftmind.app.domain.reference.ResolvedPublicVideoReference) =
                    error("Frame extraction must not run")
            },
        )
        val url = "https://raw.githubusercontent.com/owner/repo/main/video.mp4"
        val failure = try {
            engine.generate(buildRequest().copy(urlReference = BuildInput.UrlReference(url)))
            throw AssertionError("Expected unsafe destination failure")
        } catch (expected: AiProviderException) {
            expected
        }

        assertEquals(AiErrorCode.REFERENCE_UNSAFE_DESTINATION, failure.failure.code)
        assertFalse(failure.message.orEmpty().contains(url))
        assertEquals(0, provider.generateCalls)
    }

    @Test
    fun cancellationDuringVideoResolutionDoesNotExtractFramesOrCallProvider() = kotlinx.coroutines.runBlocking {
        val videoModel = model.copy(capabilities = model.capabilities.copy(vision = true, multipleImages = true))
        val provider = FakeProvider(videoModel, validDocument())
        val resolverEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resolver = object : com.craftmind.app.domain.reference.PublicVideoReferenceResolver {
            override suspend fun resolve(url: String): com.craftmind.app.domain.reference.ResolvedPublicVideoReference {
                resolverEntered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        var extractionCalls = 0
        val engine = engine(
            provider,
            credential = "test-key",
            selection = AiProviderSelection(providerId, videoModel.id),
            videoResolver = resolver,
            videoExtractor = object : com.craftmind.app.domain.reference.PublicVideoFrameExtractor {
                override suspend fun extract(reference: com.craftmind.app.domain.reference.ResolvedPublicVideoReference):
                    com.craftmind.app.domain.reference.ExtractedPublicVideoFrames {
                    extractionCalls++
                    error("Cancelled resolution must not proceed to frame extraction")
                }
            },
        )
        val request = buildRequest().copy(
            urlReference = BuildInput.UrlReference("https://raw.githubusercontent.com/owner/repo/main/video.mp4"),
        )
        val job = launch(Dispatchers.Default) { engine.generate(request) }

        withTimeout(5_000L) { resolverEntered.await() }
        job.cancelAndJoin()

        assertEquals(0, extractionCalls)
        assertEquals(0, provider.generateCalls)
    }

    @Test
    fun cancellationDuringVideoAnalysisClosesAllFramesAndSkipsBuildPlanGeneration() = kotlinx.coroutines.runBlocking {
        val videoModel = model.copy(capabilities = model.capabilities.copy(vision = true, multipleImages = true))
        val videoUrl = "https://raw.githubusercontent.com/owner/repo/main/video.mp4"
        val validatedUrl = (com.craftmind.app.domain.reference.PublicVideoReferenceUrlPolicy.validate(videoUrl)
            as com.craftmind.app.domain.reference.PublicVideoUrlValidation.Valid).value
        val resolved = com.craftmind.app.domain.reference.ResolvedPublicVideoReference(
            validatedUrl.canonicalUrl,
            validatedUrl.sourceDomain,
            validatedUrl.mediaType,
            64_000L,
        )
        val frames = testVideoFrames()
        val provider = FakeProvider(videoModel, validDocument(), suspendOnImageRequest = true)
        val engine = engine(
            provider,
            credential = "test-key",
            selection = AiProviderSelection(providerId, videoModel.id),
            videoResolver = object : com.craftmind.app.domain.reference.PublicVideoReferenceResolver {
                override suspend fun resolve(url: String) = resolved
            },
            videoExtractor = object : com.craftmind.app.domain.reference.PublicVideoFrameExtractor {
                override suspend fun extract(reference: com.craftmind.app.domain.reference.ResolvedPublicVideoReference) = frames
            },
        )
        val request = buildRequest().copy(urlReference = BuildInput.UrlReference(videoUrl))
        val job = launch(Dispatchers.Default) { engine.generate(request) }

        withTimeout(5_000L) { provider.imageCallStarted.await() }
        job.cancelAndJoin()

        assertEquals(1, provider.generateCalls)
        assertEquals(2, provider.requests.single().imageInputs.size)
        assertTrue(runCatching { frames.frames.first().image.useBytes { it.size } }.isFailure)
    }

    @Test
    fun imageOnlyRequestUsesOneSelectedVisionModelForAnalysisThenTextPlanAndReturnsEvidence() = kotlinx.coroutines.runBlocking {
        val visionModel = model.copy(capabilities = model.capabilities.copy(vision = true))
        val analysisJson = """{"schemaVersion":1,"summary":"An open pavilion.","observedDetails":["Four supports and a shallow roof are visible."],"inferredDetails":["The supports may be stone."],"uncertainties":["The rear is occluded."]}"""
        val provider = FakeProvider(visionModel, validDocument(), precedingResponses = listOf(analysisJson))
        val preparer = FakeImagePreparer()
        val engine = engine(
            provider,
            credential = "test-key",
            selection = AiProviderSelection(providerId, visionModel.id),
            imagePreparer = preparer,
        )
        val request = buildRequest(withReferences = true).copy(prompt = "")
        val stages = mutableListOf<AiGenerationStage>()

        val response = engine.generate(request, stages::add)

        assertEquals(2, provider.generateCalls)
        assertTrue(provider.requests[0].model.capabilities.vision)
        assertEquals(visionModel.id, provider.requests[0].model.id)
        assertEquals(1, provider.requests[0].imageInputs.size)
        assertEquals("image/png", provider.requests[0].imageInputs.single().mediaType)
        assertEquals(visionModel.id, provider.requests[1].model.id)
        assertEquals(providerId, provider.requests[1].model.providerId)
        assertTrue(provider.requests[0].prompt.contains("no written description"))
        assertTrue(provider.requests[1].imageInputs.isEmpty())
        assertTrue(provider.requests[1].prompt.contains("observedDetails"))
        assertTrue(provider.requests[1].prompt.contains("inferredDetails"))
        assertTrue(provider.requests[1].prompt.contains("rear is occluded"))
        assertEquals(providerId.value, response.plan.plan.metadata.providerId)
        assertEquals(visionModel.id, response.plan.plan.metadata.modelId)
        assertEquals(providerId.value, response.imageAnalysisSource?.providerId)
        assertEquals(visionModel.id, response.imageAnalysisSource?.modelId)
        assertEquals("Four supports and a shallow roof are visible.", response.imageAnalysisSource?.analysis?.observedDetails?.single())
        assertEquals(1, preparer.calls)
        assertTrue(stages.contains(AiGenerationStage.ANALYZING_IMAGE_WITH_SELECTED_MODEL))
        assertTrue(stages.contains(AiGenerationStage.GENERATING_BUILD_PLAN))
    }

    @Test
    fun cancellationDuringImageUploadClosesTheEphemeralPayloadAndCreatesNoPlan() = kotlinx.coroutines.runBlocking {
        val visionModel = model.copy(capabilities = model.capabilities.copy(vision = true))
        val provider = FakeProvider(visionModel, validDocument(), suspendOnImageRequest = true)
        val preparer = FakeImagePreparer()
        val engine = engine(
            provider,
            credential = "test-key",
            selection = AiProviderSelection(providerId, visionModel.id),
            imagePreparer = preparer,
        )
        val job = launch(Dispatchers.Default) { engine.generate(buildRequest(withReferences = true)) }

        withTimeout(5_000) { provider.imageCallStarted.await() }
        job.cancelAndJoin()

        assertEquals(1, provider.generateCalls)
        assertTrue(runCatching { preparer.lastImage!!.useBytes { it.size } }.isFailure)
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
    fun refinementUsesSelectedSemanticModelAndReturnsValidatedDiffWithoutReplacingBase() = kotlinx.coroutines.runBlocking {
        val provider = FakeProvider(model, validEditDocument())
        val engine = engine(provider, credential = "test-key", selection = AiProviderSelection(providerId, model.id))
        val base = BuildPlanTestFixtures.semanticPlan()
        val request = editRequest(base, "Change the roof to brick")

        val response = engine.refine(request)

        assertEquals(1, provider.generateCalls)
        assertTrue(provider.lastRequest!!.systemInstruction.contains("one or more existing semantic components"))
        assertTrue(provider.lastRequest!!.prompt.contains("Target component hints"))
        assertTrue(provider.lastRequest!!.prompt.contains("roof"))
        assertTrue(provider.lastRequest!!.prompt.contains("no visual analysis is available"))
        assertTrue(provider.lastRequest!!.prompt.contains("not fetched or analyzed"))
        assertTrue(provider.lastRequest!!.imageInputs.isEmpty())
        assertTrue(!provider.lastRequest!!.prompt.contains("content://private/original-image"))
        assertTrue(!provider.lastRequest!!.prompt.contains("https://example.org/private-reference"))
        assertEquals(base, request.basePlan)
        assertEquals("minecraft:bricks", response.plan.plan.operations[2].blockId)
        assertEquals("minecraft:stone", response.plan.plan.operations[0].blockId)
        assertEquals(1, response.diff.changedOperations.size)
    }

    @Test
    fun refinementUsesSavedTextOnlyImageNotesWithoutResendingImageBytes() = kotlinx.coroutines.runBlocking {
        val provider = FakeProvider(model, validEditDocument())
        val engine = engine(provider, credential = "test-key", selection = AiProviderSelection(providerId, model.id))
        val source = BuildImageAnalysisSource(
            providerId = providerId.value,
            modelId = model.id,
            analysis = BuildImageAnalysis(
                summary = "A compact pavilion.",
                observedDetails = listOf("Four narrow supports are visible."),
                inferredDetails = listOf("The supports may be wooden."),
                uncertainties = listOf("The rear side is hidden."),
            ),
        )
        val request = editRequest(BuildPlanTestFixtures.semanticPlan(), "Refine the roof").copy(
            originalRequest = BuildRequestSnapshot(
                prompt = "Build a compact pavilion",
                imageContentUri = "content://private/original-image",
                imageMediaType = "image/jpeg",
                imageAnalysisSource = source,
            ),
        )

        engine.refine(request)

        assertTrue(provider.lastRequest!!.imageInputs.isEmpty())
        assertTrue(provider.lastRequest!!.prompt.contains("Only the saved text-only visual notes"))
        assertTrue(provider.lastRequest!!.prompt.contains("observedDetails"))
        assertTrue(provider.lastRequest!!.prompt.contains("inferredDetails"))
        assertTrue(provider.lastRequest!!.prompt.contains("Four narrow supports are visible"))
        assertTrue(provider.lastRequest!!.prompt.contains("The supports may be wooden"))
        assertTrue(!provider.lastRequest!!.prompt.contains("content://private/original-image"))
        assertTrue(provider.lastRequest!!.prompt.contains("does not reanalyze the image"))
        assertTrue(provider.lastRequest!!.systemInstruction.contains("untrusted visual evidence"))
    }

    @Test
    fun videoRefinementUsesSavedBoundedTextNotesAndNeverReFetchesUrlOrResendsFrames() = kotlinx.coroutines.runBlocking {
        val provider = FakeProvider(model, validEditDocument())
        var resolverCalls = 0
        var extractorCalls = 0
        val resolver = object : com.craftmind.app.domain.reference.PublicVideoReferenceResolver {
            override suspend fun resolve(url: String): com.craftmind.app.domain.reference.ResolvedPublicVideoReference {
                resolverCalls++
                error("Refinement must never resolve the public-video URL")
            }
        }
        val extractor = object : com.craftmind.app.domain.reference.PublicVideoFrameExtractor {
            override suspend fun extract(reference: com.craftmind.app.domain.reference.ResolvedPublicVideoReference):
                com.craftmind.app.domain.reference.ExtractedPublicVideoFrames {
                extractorCalls++
                error("Refinement must never extract video frames")
            }
        }
        val engine = engine(
            provider,
            credential = "test-key",
            selection = AiProviderSelection(providerId, model.id),
            videoResolver = resolver,
            videoExtractor = extractor,
        )
        val videoUrl = "https://raw.githubusercontent.com/owner/repo/main/video.mp4"
        val source = com.craftmind.app.domain.buildplan.BuildReferenceAnalysisSource(
            sourceType = com.craftmind.app.domain.buildplan.BuildReferenceSourceType.RAW_GITHUB_VIDEO,
            sourceDomain = "raw.githubusercontent.com",
            mediaType = "video/mp4",
            durationMillis = 10_000L,
            sampledTimestampsMillis = listOf(1_000L, 9_500L),
            providerId = providerId.value,
            modelId = model.id,
            analysis = BuildImageAnalysis(
                summary = "A compact pavilion is visible across two stages.",
                observedDetails = listOf("The later frame shows a shallow roof and four supports."),
                inferredDetails = listOf("The supports may be timber."),
                uncertainties = listOf("The rear is not visible."),
            ),
        )
        val request = editRequest(BuildPlanTestFixtures.semanticPlan(), "Refine the roof").copy(
            originalRequest = BuildRequestSnapshot(
                prompt = "Build a pavilion",
                urlReference = videoUrl,
                referenceAnalysisSource = source,
            ),
        )

        engine.refine(request)

        assertEquals(1, provider.generateCalls)
        assertEquals(0, resolverCalls)
        assertEquals(0, extractorCalls)
        assertTrue(provider.lastRequest!!.imageInputs.isEmpty())
        assertTrue(provider.lastRequest!!.prompt.contains("The original public video was fetched once"))
        assertTrue(provider.lastRequest!!.prompt.contains("observedDetails"))
        assertTrue(provider.lastRequest!!.prompt.contains("The later frame shows a shallow roof and four supports"))
        assertTrue(provider.lastRequest!!.prompt.contains("The supports may be timber"))
        assertTrue(provider.lastRequest!!.prompt.contains("does not redownload the video"))
        assertFalse(provider.lastRequest!!.prompt.contains(videoUrl))
        assertFalse(provider.lastRequest!!.prompt.contains("videoBytes"))
    }

    @Test
    fun keepsCredentialsOutOfRefinementPromptsAndTypedErrors() = kotlinx.coroutines.runBlocking {
        val secret = "refinementCredentialSentinelNotARealKey"
        val provider = FakeProvider(model, "not-json")
        val engine = engine(provider, credential = secret, selection = AiProviderSelection(providerId, model.id))

        val error = try {
            engine.refine(editRequest(BuildPlanTestFixtures.semanticPlan(), "Change the roof"))
            throw AssertionError("Expected malformed AI output to be rejected")
        } catch (expected: AiProviderException) {
            expected
        }

        assertEquals(AiErrorCode.INVALID_AI_RESPONSE, error.failure.code)
        assertFalse(error.message.orEmpty().contains(secret))
        val sentRequest = provider.lastRequest!!
        assertFalse(sentRequest.prompt.contains(secret))
        assertFalse(sentRequest.systemInstruction.contains(secret))
    }

    @Test
    fun refusesRefinementWhenSelectedModelLacksJsonCapabilityWithoutFallback() = kotlinx.coroutines.runBlocking {
        val incapableModel = model.copy(capabilities = model.capabilities.copy(structuredOutput = StructuredOutputMode.UNSUPPORTED))
        val provider = FakeProvider(incapableModel, validEditDocument())
        val engine = engine(provider, credential = "test-key", selection = AiProviderSelection(providerId, incapableModel.id))

        assertEquals(
            AiErrorCode.UNSUPPORTED_CAPABILITY,
            failureCode { engine.refine(editRequest(BuildPlanTestFixtures.semanticPlan(), "Change the roof")) },
        )
        assertEquals(0, provider.generateCalls)
    }

    @Test
    fun rejectsBroadRefinementWhenBoundedExistingContextWouldBeIncomplete() = kotlinx.coroutines.runBlocking {
        val dimensions = BuildDimensions(96, 64, 96)
        val operations = (0 until 769).map { index ->
            val x = index % 96
            val z = (index / 96) % 96
            val y = index / (96 * 96)
            BuildPlanOperation(index, BuildPlanOperationKind.PLACE_BLOCK, "minecraft:stone", BlockPosition(x, y, z), componentId = "main")
        }
        val largePlan = BuildPlan(
            planId = "large-plan",
            metadata = BuildPlanMetadata(
                schemaVersion = 2,
                sourceRequestId = "request-large",
                providerId = "google",
                modelId = "gemini-test",
                title = "Large build",
                summary = "A large bounded test build.",
                generatedAtEpochMillis = 1_700_000_000_000,
                dimensions = dimensions,
                intent = BuildIntent("structure"),
            ),
            originStrategy = BuildOriginStrategy.CENTERED_GROUND,
            components = listOf(
                BuildPlanComponent("main", "Main structure", "A broad structure", BlockBounds(BlockPosition(0, 0, 0), dimensions), BuildComponentType.BUILDING, null, 0),
            ),
            operations = operations,
            status = BuildStatus.READY,
        )
        val provider = FakeProvider(model, validEditDocument())
        val engine = engine(provider, credential = "test-key", selection = AiProviderSelection(providerId, model.id))

        assertEquals(
            AiErrorCode.REFINEMENT_CONTEXT_TOO_LARGE,
            failureCode { engine.refine(editRequest(largePlan, "Make the whole build twice as large")) },
        )
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
        imagePreparer: AiImageInputPreparer? = null,
        videoResolver: com.craftmind.app.domain.reference.PublicVideoReferenceResolver? = null,
        videoExtractor: com.craftmind.app.domain.reference.PublicVideoFrameExtractor? = null,
    ): AiBuildEngine = AiBuildEngine(
        registry = com.craftmind.app.domain.ai.AiProviderRegistry(listOf(provider)),
        credentialStore = MemoryCredentialStore(credential),
        selections = MemorySelectionRepository(selection),
        parser = BuildPlanParser(DefaultBuildPlanValidator()),
        nowEpochMillis = { 456L },
        imageInputPreparer = imagePreparer,
        publicVideoReferenceResolver = videoResolver,
        publicVideoFrameExtractor = videoExtractor,
    )

    private fun editRequest(base: BuildPlan, instruction: String) = BuildEditRequest(
        baseRecordId = "build-demo-v1",
        buildId = "build-demo",
        baseVersion = 1,
        basePlan = base,
        originalRequest = BuildRequestSnapshot(
            prompt = "Build a compact courtyard home",
            imageContentUri = "content://private/original-image",
            imageMediaType = "image/png",
            urlReference = "https://example.org/private-reference",
        ),
        instruction = instruction,
        createdAtEpochMillis = 1_700_000_000_200,
    )

    private fun validEditDocument(): String = kotlinx.serialization.json.Json.encodeToString(
        AiBuildEditDocument(
            schemaVersion = 1,
            editSummary = "Changed the raised roof to brick",
            targetComponentIds = listOf("roof"),
            preservedComponentIds = listOf("house"),
            removedComponentIds = emptyList(),
            upsertComponents = listOf(
                AiBuildComponentDocument(
                    componentId = "roof",
                    type = BuildComponentType.ROOF,
                    name = "Raised roof",
                    purpose = "Raised brick roof above the main house.",
                    bounds = BlockBounds(BlockPosition(0, 4, 0), BuildDimensions(8, 2, 8)),
                    parentComponentId = "house",
                    constructionOrder = 1,
                ),
            ),
            replacementOperations = listOf(
                com.craftmind.app.domain.buildplan.AiComponentOperationSetDocument(
                    componentId = "roof",
                    operations = listOf(
                        AiBlockOperationDocument(0, 4, 0, "minecraft:bricks", emptyMap(), "roof"),
                        AiBlockOperationDocument(1, 4, 0, "minecraft:oak_planks", emptyMap(), "roof"),
                    ),
                ),
            ),
        ),
    )

    private fun testVideoFrames() = com.craftmind.app.domain.reference.ExtractedPublicVideoFrames(
        durationMillis = 10_000L,
        videoWidth = 640,
        videoHeight = 360,
        frames = listOf(
            com.craftmind.app.domain.reference.PublicVideoFrame(1_000L, AiImageInput("image/jpeg", 2, 2, byteArrayOf(1, 2, 3))),
            com.craftmind.app.domain.reference.PublicVideoFrame(9_500L, AiImageInput("image/jpeg", 2, 2, byteArrayOf(4, 5, 6))),
        ),
    )

    private fun buildRequest(withReferences: Boolean = false) = BuildRequest(
        requestId = "request-engine-test",
        prompt = "A stone garden pavilion",
        imageReference = if (withReferences) BuildInput.ImageReference(
            contentUri = "content://private/image",
            mediaType = "image/png",
            sizeBytes = 1024,
        ) else null,
        urlReference = null,
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
        precedingResponses: List<String> = emptyList(),
        private val suspendOnImageRequest: Boolean = false,
    ) : AiProviderAdapter {
        private val responseSequence = (precedingResponses + responseContent).toMutableList()
        override val definition = AiProviderDefinition(
            id = model.providerId,
            displayName = "Test provider",
            credentialType = com.craftmind.app.domain.ai.CredentialType.API_KEY,
            baseEndpoint = "https://provider.example/",
            capabilities = AiProviderCapabilities(
                textGeneration = true,
                vision = model.capabilities.vision,
                publicUrlReferences = false,
                structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
                cancellation = true,
                streaming = false,
                multipleImages = model.capabilities.multipleImages,
                ),
        )
        var listModelsCalls = 0
        var generateCalls = 0
        var lastRequest: AiProviderRequest? = null
        val requests = mutableListOf<AiProviderRequest>()
        val imageCallStarted = kotlinx.coroutines.CompletableDeferred<Unit>()

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
            requests += request
            if (suspendOnImageRequest && request.imageInputs.isNotEmpty()) {
                imageCallStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            return AiProviderResponse(responseSequence.removeAt(0))
        }
    }

    private class FakeImagePreparer : AiImageInputPreparer {
        var calls = 0
        var lastImage: AiImageInput? = null

        override suspend fun prepare(reference: BuildInput.ImageReference): AiImageInput {
            calls++
            return AiImageInput("image/png", 2, 2, byteArrayOf(1, 2, 3)).also { lastImage = it }
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
          "schemaVersion": 2,
          "buildId": "generated-garden",
          "title": "A stone garden pavilion",
          "description": "A compact open pavilion in a quiet garden.",
          "dimensions": {"width": 8, "height": 5, "depth": 8},
          "originStrategy": "CENTERED_GROUND",
          "intent": {"structureType":"pavilion", "style":"stone", "approximateScale":"small", "floorCount":1, "rooms":[], "specialFeatures":[], "materials":["stone"], "environment":"garden", "constraints":[]},
          "components": [{"componentId": "main", "type":"BUILDING", "name": "Pavilion", "purpose": "Covered gathering area", "bounds":{"origin":{"x":0,"y":0,"z":0},"dimensions":{"width":8,"height":5,"depth":8}}, "parentComponentId":null, "constructionOrder":0}],
          "operations": [{"x": 0, "y": 0, "z": 0, "blockId": "minecraft:stone", "blockState": {}, "componentId": "main"}]
        }
    """.trimIndent()
}
