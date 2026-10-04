package com.craftmind.app.presentation.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.craftmind.app.designsystem.CraftMindTheme
import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildDimensions
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
