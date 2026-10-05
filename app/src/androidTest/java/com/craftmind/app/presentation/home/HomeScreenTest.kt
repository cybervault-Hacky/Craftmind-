package com.craftmind.app.presentation.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.craftmind.app.designsystem.CraftMindTheme
import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelCapabilities
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.StructuredOutputMode
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildOriginStrategy
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanComponent
import com.craftmind.app.domain.buildplan.BuildPlanMetadata
import com.craftmind.app.domain.buildplan.BuildPlanOperation
import com.craftmind.app.domain.buildplan.BuildPlanOperationKind
import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.buildplan.ValidatedBuildPlan
import com.craftmind.app.domain.settings.ThemeMode
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reviewEntryAppearsOnlyForSuccessfulValidatedPlan() {
        var state by mutableStateOf(
            BuildComposerState(
                prompt = "A stone pavilion",
                generation = BuildGenerationState.Failed(
                    request = request(),
                    code = AiErrorCode.INVALID_BUILD_PLAN,
                    retryable = false,
                ),
            ),
        )
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                HomeScreen(
                    state = state,
                    onEvent = {},
                    onPickImage = {},
                    onReviewPlan = {},
                )
            }
        }
        compose.onNodeWithText("Review plan details").assertDoesNotExist()

        compose.runOnIdle {
            state = BuildComposerState(
                prompt = "A stone pavilion",
                generation = BuildGenerationState.Ready(
                    request = request(),
                    plan = validPlan(),
                    localRecord = null,
                    localSaveFailed = true,
                ),
            )
        }
        compose.onNodeWithText("Review plan details").assertExists()
        compose.onNodeWithText("The validated plan is available for review but could not be saved to the local Builds list.").assertExists()
    }

    @Test
    fun imageRequestIsBlockedUntilTheSelectedVerifiedModelSupportsVision() {
        val textOnly = model(vision = false)
        val visionCapable = model(vision = true)
        var selectedModel by mutableStateOf(textOnly)
        val state = BuildComposerState(
            imageReference = BuildInput.ImageReference(
                contentUri = "content://test/unavailable-thumbnail",
                mediaType = "image/jpeg",
                sizeBytes = 100L,
            ),
        )
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                HomeScreen(
                    state = state,
                    onEvent = {},
                    onPickImage = {},
                    onReviewPlan = {},
                    selectedModelId = selectedModel.id,
                    selectedModel = selectedModel,
                )
            }
        }

        compose.onNodeWithText("Generate with AI").assertIsNotEnabled()
        compose.onNodeWithText("A verified vision model is required").assertExists()

        compose.runOnIdle { selectedModel = visionCapable }
        compose.onNodeWithText("Generate with AI").assertIsEnabled()
        compose.onNodeWithText("A verified vision model is required").assertDoesNotExist()
    }

    @Test
    fun videoGenerationIsBlockedUnlessTheExactSelectedModelAdvertisesMultiImageVision() {
        var selectedModel by mutableStateOf(model(vision = true, multipleImages = false))
        val state = BuildComposerState(
            prompt = "Build the completed pavilion",
            urlReference = BuildInput.UrlReference("https://raw.githubusercontent.com/owner/repo/main/video.mp4"),
        )
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                HomeScreen(
                    state = state,
                    onEvent = {},
                    onPickImage = {},
                    onReviewPlan = {},
                    selectedModelId = selectedModel.id,
                    selectedModel = selectedModel,
                )
            }
        }

        compose.onNodeWithText("Generate with AI").assertIsNotEnabled()
        compose.onNodeWithText("A verified multi-image Vision model is required").assertExists()
        compose.onNodeWithText("Gemini vision model supports one-image vision but not the bounded multi-frame video request. Choose a model labeled Multi-image Vision; CraftMind will not switch models.").assertExists()

        compose.runOnIdle { selectedModel = model(vision = true, multipleImages = true) }
        compose.onNodeWithText("Generate with AI").assertIsEnabled()
        compose.onNodeWithText("A verified multi-image Vision model is required").assertDoesNotExist()
    }

    private fun model(vision: Boolean, multipleImages: Boolean = false) = AiModel(
        id = if (multipleImages) "gemini-3.5-flash" else if (vision) "gemini-vision-only" else "gemini-text-only",
        providerId = AiProviderId("google_gemini"),
        displayName = if (vision) "Gemini vision model" else "Gemini text model",
        capabilities = AiModelCapabilities(
            textGeneration = true,
            vision = vision,
            publicUrlReferences = false,
            structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
            maximumContextTokens = 64_000,
            maximumOutputTokens = 8_000,
            multipleImages = multipleImages,
        ),
    )

    private fun request() = BuildRequest(
        requestId = "ui-request",
        prompt = "A stone pavilion",
        imageReference = null,
        urlReference = null,
        createdAtEpochMillis = 123L,
    )

    private fun validPlan(): ValidatedBuildPlan {
        val plan = BuildPlan(
            planId = "ui-plan",
            metadata = BuildPlanMetadata(
                schemaVersion = 1,
                sourceRequestId = "ui-request",
                providerId = "google_gemini",
                modelId = "gemini-test",
                title = "Stone pavilion",
                summary = "An open stone pavilion.",
                generatedAtEpochMillis = 456L,
                dimensions = BuildDimensions(4, 3, 4),
            ),
            originStrategy = BuildOriginStrategy.CENTERED_GROUND,
            components = listOf(BuildPlanComponent("main", "Pavilion", "A covered seating area")),
            operations = listOf(
                BuildPlanOperation(
                    sequence = 0,
                    kind = BuildPlanOperationKind.PLACE_BLOCK,
                    blockId = "minecraft:stone",
                    position = BlockPosition(0, 0, 0),
                    componentId = "main",
                ),
            ),
            status = BuildStatus.DRAFT,
        )
        return (DefaultBuildPlanValidator().validate(plan) as BuildPlanValidationResult.Valid).plan
    }
}
