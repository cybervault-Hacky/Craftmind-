package com.craftmind.app.presentation.builds

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.craftmind.app.designsystem.CraftMindTheme
import com.craftmind.app.domain.ai.AiUsage
import com.craftmind.app.domain.buildplan.BlockBounds
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildComponentType
import com.craftmind.app.domain.buildplan.BuildDiffCalculator
import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.BuildIntent
import com.craftmind.app.domain.buildplan.BuildOriginStrategy
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanComponent
import com.craftmind.app.domain.buildplan.BuildPlanMetadata
import com.craftmind.app.domain.buildplan.BuildPlanOperation
import com.craftmind.app.domain.buildplan.BuildPlanOperationKind
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlanReviewScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun naturalLanguageComposerDispatchesRefinementWithoutManualBuilderControls() {
        val base = plan()
        val record = record(base, version = 1)
        var event: BuildRefinementEvent? = null
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                PlanReviewScreen(
                    plan = base,
                    request = record.request,
                    record = record,
                    versions = listOf(record),
                    refinementState = BuildRefinementState.Idle,
                    onRefinementEvent = { event = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Describe a change…").assertExists()
        compose.onNodeWithText("For example: Add a rooftop helipad").assertExists()
        compose.onNodeWithText("Generate refinement").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("Add a stone garden wall")
        compose.onNodeWithText("Generate refinement").performClick()
        compose.runOnIdle {
            assertEquals(BuildRefinementEvent.Refine(record, "Add a stone garden wall"), event)
        }
    }

    @Test
    fun candidateReviewShowsDiffAndAcceptanceIsAnExplicitAction() {
        val base = plan()
        val candidate = base.copy(
            metadata = base.metadata.copy(providerId = "google", modelId = "gemini-test", generatedAtEpochMillis = 1_700_000_000_500),
            components = listOf(base.components.first().copy(purpose = "Stone pavilion with a brick floor.")),
            operations = listOf(base.operations.first().copy(blockId = "minecraft:bricks")),
        )
        val record = record(base, version = 1)
        val validatedCandidate = (DefaultBuildPlanValidator().validate(candidate) as BuildPlanValidationResult.Valid).plan
        val diff = BuildDiffCalculator().compare(base, candidate, "Changed the pavilion floor", listOf("main"), emptyList(), "Change the floor")
        val request = BuildEditRequest(record.recordId, record.buildId, record.version, base, record.request, "Change the floor", 1_700_000_000_400)
        var event: BuildRefinementEvent? = null
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                PlanReviewScreen(
                    plan = base,
                    request = record.request,
                    record = record,
                    versions = listOf(record),
                    refinementState = BuildRefinementState.ReadyForReview(
                        record,
                        request,
                        validatedCandidate,
                        diff,
                        AiUsage(inputTokens = 100, outputTokens = 50),
                    ),
                    onRefinementEvent = { event = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Candidate refinement").assertExists()
        compose.onNodeWithText("Changes proposed").assertExists()
        compose.onNodeWithText("Changed the pavilion floor").assertExists()
        compose.onNodeWithText("Accept candidate").performClick()
        compose.runOnIdle { assertEquals(BuildRefinementEvent.Accept, event) }
    }

    @Test
    fun versionRestoreIsClearlyLocalAndDispatchesSelectedHistoryEvent() {
        val originalPlan = plan()
        val original = record(originalPlan, version = 1)
        val currentPlan = originalPlan.copy(operations = listOf(originalPlan.operations.first().copy(blockId = "minecraft:bricks")))
        val diff = BuildDiffCalculator().compare(originalPlan, currentPlan, "Changed floor")
        val current = LocalBuildRecord(
            recordId = "build-ui-v2",
            plan = currentPlan,
            request = original.request,
            savedAtEpochMillis = original.savedAtEpochMillis + 1,
            buildId = original.buildId,
            version = 2,
            parentRecordId = original.recordId,
            changeSummary = diff.summary,
            diff = diff,
        )
        var event: BuildRefinementEvent? = null
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                PlanReviewScreen(
                    plan = currentPlan,
                    request = current.request,
                    record = current,
                    versions = listOf(original, current),
                    refinementState = BuildRefinementState.Idle,
                    onRefinementEvent = { event = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Restore").assertExists().performClick()
        compose.runOnIdle { assertEquals(BuildRefinementEvent.RestoreVersion(current, 1), event) }
        compose.onNodeWithText("Restoring creates a new local version. It is not Minecraft undo.").assertExists()
    }

    private fun plan(): BuildPlan {
        val dimensions = BuildDimensions(4, 4, 4)
        return BuildPlan(
            planId = "ui-plan",
            metadata = BuildPlanMetadata(
                schemaVersion = 2,
                sourceRequestId = "ui-request",
                providerId = "google",
                modelId = "gemini-test",
                title = "Stone pavilion",
                summary = "A small open stone pavilion.",
                generatedAtEpochMillis = 1_700_000_000_000,
                dimensions = dimensions,
                intent = BuildIntent("pavilion", style = "stone", floorCount = 1),
            ),
            originStrategy = BuildOriginStrategy.CENTERED_GROUND,
            components = listOf(
                BuildPlanComponent(
                    componentId = "main",
                    type = BuildComponentType.BUILDING,
                    name = "Pavilion",
                    purpose = "Covered gathering area",
                    bounds = BlockBounds(BlockPosition(0, 0, 0), dimensions),
                    constructionOrder = 0,
                ),
            ),
            operations = listOf(
                BuildPlanOperation(0, BuildPlanOperationKind.PLACE_BLOCK, "minecraft:stone", BlockPosition(0, 0, 0), componentId = "main"),
            ),
            status = BuildStatus.READY,
        )
    }

    private fun record(plan: BuildPlan, version: Int) = LocalBuildRecord(
        recordId = "build-ui-v$version",
        plan = plan,
        request = BuildRequestSnapshot("Build a stone pavilion"),
        savedAtEpochMillis = 1_700_000_000_100 + version,
        buildId = "build-ui",
        version = version,
        parentRecordId = if (version == 1) null else "build-ui-v${version - 1}",
    )
}
