package com.craftmind.app.presentation.builds

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.craftmind.app.domain.buildplan.BuildImageAnalysis
import com.craftmind.app.domain.buildplan.BuildImageAnalysisSource
import com.craftmind.app.domain.buildplan.BuildReferenceAnalysisSource
import com.craftmind.app.domain.buildplan.BuildReferenceSourceType
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
import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRecord
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.settings.ThemeMode
import com.craftmind.app.presentation.settings.BridgePairingState
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
    fun imageReviewShowsProviderProvenanceAndKeepsObservedInferredAndUncertainNotesDistinct() {
        val base = plan()
        val source = BuildImageAnalysisSource(
            providerId = base.metadata.providerId,
            modelId = base.metadata.modelId,
            analysis = BuildImageAnalysis(
                summary = "A compact pavilion.",
                observedDetails = listOf("Four narrow supports are visible."),
                inferredDetails = listOf("The supports may be timber."),
                uncertainties = listOf("The rear side is hidden."),
            ),
        )
        val record = record(base, version = 1).copy(
            request = BuildRequestSnapshot(
                prompt = "",
                imageContentUri = "content://temporary/unavailable-image",
                imageMediaType = "image/png",
                imageAnalysisSource = source,
            ),
        )
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                PlanReviewScreen(
                    plan = base,
                    request = record.request,
                    record = record,
                    versions = listOf(record),
                    refinementState = BuildRefinementState.Idle,
                    onRefinementEvent = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Image-only request; no written prompt was supplied.").assertExists()
        compose.onNodeWithText("Initial image analysis · AI-generated, not verified").assertExists()
        compose.onNodeWithText("Observed details · may be inaccurate").assertExists()
        compose.onNodeWithText("Inferred details · uncertain suggestions").assertExists()
        compose.onNodeWithText("Uncertainties").assertExists()
        compose.onNodeWithText("Four narrow supports are visible.").assertExists()
        compose.onNodeWithText("The supports may be timber.").assertExists()
        compose.onNodeWithText("The rear side is hidden.").assertExists()
        compose.onNodeWithText("Refinement uses the validated plan and these saved text notes only; it does not resend or reanalyze the image. The notes are not a visual-accuracy guarantee.").assertExists()
    }

    @Test
    fun publicVideoReviewShowsFrameProvenanceAndObservedInferredUnknownEvidenceWithoutFullUrl() {
        val base = plan()
        val url = "https://raw.githubusercontent.com/owner/repo/main/video.mp4"
        val source = BuildReferenceAnalysisSource(
            sourceType = BuildReferenceSourceType.RAW_GITHUB_VIDEO,
            sourceDomain = "raw.githubusercontent.com",
            mediaType = "video/mp4",
            durationMillis = 10_000L,
            sampledTimestampsMillis = listOf(1_000L, 9_500L),
            providerId = base.metadata.providerId,
            modelId = base.metadata.modelId,
            analysis = BuildImageAnalysis(
                summary = "A small pavilion is visible across two stages.",
                observedDetails = listOf("The later sample shows four supports and a shallow roof."),
                inferredDetails = listOf("The supports may be stone."),
                uncertainties = listOf("The rear wall is partly occluded."),
            ),
        )
        val record = record(base, version = 1).copy(
            request = BuildRequestSnapshot(
                prompt = "",
                urlReference = url,
                referenceAnalysisSource = source,
            ),
        )
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                PlanReviewScreen(
                    plan = base,
                    request = record.request,
                    record = record,
                    versions = listOf(record),
                    refinementState = BuildRefinementState.Idle,
                    onRefinementEvent = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Video-only request; no written prompt was supplied.").assertExists()
        compose.onNodeWithText("Public video analyzed once: raw.githubusercontent.com · video/mp4 · 00:10 · 2 distinct frames. Source path: raw.githubusercontent.com/owner/repo/main/video.mp4.").assertExists()
        compose.onNodeWithText("Initial video-frame analysis · AI-generated, not verified").assertExists()
        compose.onNodeWithText("Approximate sample points: 00:01, 00:09. Frames are stages/views of one video; the latest clear frame is prioritized but is not assumed complete.").assertExists()
        compose.onNodeWithText("Observed details · may be inaccurate").assertExists()
        compose.onNodeWithText("Inferred details · uncertain suggestions").assertExists()
        compose.onNodeWithText("Unknown, occluded, or conflicting details").assertExists()
        compose.onNodeWithText("The rear wall is partly occluded.").assertExists()
        compose.onNodeWithText(url).assertDoesNotExist()
        compose.onNodeWithText("The raw video is not saved by CraftMind. Sampled frame images were sent directly to ${source.providerId} / ${source.modelId}; that provider's terms govern its processing and retention. Refinement uses only saved text notes and never re-downloads the video.").assertExists()
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

    @Test
    fun finalPlacementRequiresASecondConfirmationWithResolvedWorldOriginVersionAndCount() {
        val base = plan()
        val record = record(base, version = 1)
        val preview = MinecraftExecutionPreview(
            executionId = "123e4567-e89b-42d3-a456-426614174000",
            preflightToken = "A".repeat(43),
            planRecordId = record.recordId,
            planVersion = record.version,
            planTitle = base.metadata.title,
            dimensionId = "minecraft:overworld",
            worldSessionId = "world-session-1",
            resolvedOrigin = BlockPosition(12, 64, -8),
            originStrategy = "SERVER_SELECTED_ORIGIN",
            operationCount = base.operations.size,
            createdAtEpochMillis = System.currentTimeMillis(),
            eventSequence = 1,
            expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
        )
        var event: BuildExecutionEvent? = null
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                PlanReviewScreen(
                    plan = base,
                    request = record.request,
                    record = record,
                    versions = listOf(record),
                    refinementState = BuildRefinementState.Idle,
                    onRefinementEvent = {},
                    bridgeState = compatibleBridgeState(),
                    executionState = BuildExecutionState(
                        flow = BuildExecutionFlow.PreviewReady(record.recordId, preview),
                        isLoading = false,
                    ),
                    onExecutionEvent = { event = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Resolved world: Test realm · 192.168.1.20:19872 · world session world-session-1").assertExists()
        compose.onNodeWithText("Operator-selected origin: (12, 64, -8) · server-resolved").assertExists()
        compose.onNodeWithText("Immutable plan: Stone pavilion · version 1 · 1 placements").assertExists()
        compose.onNodeWithText("Review final confirmation").performClick()
        compose.onNodeWithText("Authorize block placement?").assertExists()
        compose.onNodeWithText("Confirm and start construction").performClick()
        compose.runOnIdle { assertEquals(BuildExecutionEvent.Confirm, event) }
    }

    @Test
    fun finalConfirmationRemainsDisabledWhenLocalExecutionHistoryCouldNotLoad() {
        val base = plan()
        val record = record(base, version = 1)
        val preview = MinecraftExecutionPreview(
            executionId = "123e4567-e89b-42d3-a456-426614174000",
            preflightToken = "A".repeat(43),
            planRecordId = record.recordId,
            planVersion = record.version,
            planTitle = base.metadata.title,
            dimensionId = "minecraft:overworld",
            worldSessionId = "world-session-1",
            resolvedOrigin = BlockPosition(12, 64, -8),
            originStrategy = "SERVER_SELECTED_ORIGIN",
            operationCount = base.operations.size,
            createdAtEpochMillis = System.currentTimeMillis(),
            eventSequence = 1,
            expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
        )
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                PlanReviewScreen(
                    plan = base,
                    request = record.request,
                    record = record,
                    versions = listOf(record),
                    refinementState = BuildRefinementState.Idle,
                    onRefinementEvent = {},
                    bridgeState = compatibleBridgeState(),
                    executionState = BuildExecutionState(
                        flow = BuildExecutionFlow.PreviewReady(record.recordId, preview),
                        isLoading = false,
                        loadFailed = true,
                    ),
                    onExecutionEvent = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Local execution history could not be read. Construction is disabled until storage is available.").assertExists()
        compose.onNodeWithText("Review final confirmation").assertExists().assertIsNotEnabled()
    }

    @Test
    fun interruptedExecutionShowsCheckpointUncertaintyWithoutClaimingWorldState() {
        val base = plan()
        val record = record(base, version = 1)
        val interrupted = LocalBuildExecutionRecord(
            executionId = "123e4567-e89b-42d3-a456-426614174000",
            buildId = record.buildId,
            planRecordId = record.recordId,
            planVersion = record.version,
            bridgeId = "bridge-0123456789abcdef0123456789abcdef",
            phase = MinecraftExecutionPhase.FAILED,
            completedOperations = 1,
            totalOperations = 2,
            eventSequence = 4,
            createdAtEpochMillis = 1_700_000_000_000,
            updatedAtEpochMillis = 1_700_000_000_100,
            dimensionId = "minecraft:overworld",
            worldSessionId = "world-session-1",
            resolvedOrigin = BlockPosition(12, 64, -8),
            reasonCode = "SERVER_RESTARTED",
        )
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                PlanReviewScreen(
                    plan = base,
                    request = record.request,
                    record = record,
                    versions = listOf(record),
                    refinementState = BuildRefinementState.Idle,
                    onRefinementEvent = {},
                    executionState = BuildExecutionState(
                        records = listOf(interrupted),
                        flow = BuildExecutionFlow.Tracking(interrupted),
                        isLoading = false,
                    ),
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Bridge execution: FAILED").assertExists()
        compose.onNodeWithText("Bridge-reported operation count: 1 / 2 · event 4").assertExists()
        compose.onNodeWithText("This is the last persisted bridge checkpoint. A server crash may have happened between a world write and its saved count, so the actual world can differ. Inspect the world in game before any new build; CraftMind will not resume or roll back.").assertExists()
    }

    @Test
    fun constructionActionRemainsDisabledWithoutCompatibleAuthenticatedCapability() {
        val base = plan()
        val record = record(base, version = 1)
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                PlanReviewScreen(
                    plan = base,
                    request = record.request,
                    record = record,
                    versions = listOf(record),
                    refinementState = BuildRefinementState.Idle,
                    onRefinementEvent = {},
                    bridgeState = compatibleBridgeState(constructionExecute = false),
                    executionState = BuildExecutionState(isLoading = false),
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Preflight with Minecraft bridge").assertExists().assertIsNotEnabled()
        compose.onNodeWithText("Disabled: compatibility must resolve to SUPPORTED for this plan, the exact registered adapter must match, all required capabilities and limits must pass, and authenticated server preflight must succeed. Pairing alone is not compatibility.").assertExists()
    }

    private fun compatibleBridgeState(constructionExecute: Boolean = true): BridgePairingState {
        val bridge = TrustedMinecraftBridge(
            host = "192.168.1.20",
            port = 19872,
            tlsFingerprint = "00".repeat(32),
            bridgeId = "bridge-0123456789abcdef0123456789abcdef",
            clientId = "client-test",
            displayName = "Test realm",
            pairedAtEpochMillis = 1_700_000_000_000,
        )
        val capabilities = BridgeCapabilitiesSnapshot(
            protocolVersion = 2,
            bridgeId = bridge.bridgeId,
            identityFingerprint = bridge.tlsFingerprint,
            bridgeVersion = "1.2.0",
            clientAppVersion = "1.0.0-test",
            editionName = "java",
            minecraftVersion = "1.20.1",
            javaRuntimeMajor = 17,
            loaderName = "Fabric",
            loaderVersion = "0.16.10",
            fabricApiVersion = "0.92.2+1.20.1",
            supportedCapabilities = buildSet {
                add(MinecraftCapability.WORLD_ACCESS)
                add(MinecraftCapability.WORLD_VALIDATION)
                add(MinecraftCapability.ORIGIN_RESOLUTION)
                add(MinecraftCapability.BUILD_PLAN_V2)
                if (constructionExecute) addAll(setOf(
                    MinecraftCapability.BUILD_EXECUTION,
                    MinecraftCapability.BLOCK_PLACEMENT,
                    MinecraftCapability.BLOCK_STATE_SUPPORT,
                    MinecraftCapability.STRUCTURE_BATCHING,
                    MinecraftCapability.PROGRESS_REPORTING,
                    MinecraftCapability.BUILD_STATUS,
                    MinecraftCapability.CANCELLATION,
                ))
            },
            worldAccess = true,
            constructionExecute = constructionExecute,
            cancellation = constructionExecute,
            maximumValidatedOperations = 4096,
            maximumRequestBytes = 1_048_576,
            maximumOperationsPerTick = 32,
            maximumExecutionSeconds = 300,
            supportedBuildPlanSchemaVersions = listOf(2),
            dimensionId = "minecraft:overworld",
            worldSessionId = "world-session-1",
        )
        return BridgePairingState(
            profile = bridge,
            connection = BridgeConnectionState.Connected(bridge, capabilities, System.currentTimeMillis()),
        )
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
