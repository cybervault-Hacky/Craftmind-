package com.craftmind.app.presentation.builds

import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import com.craftmind.app.domain.buildplan.BuildPlanTestFixtures
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRecord
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import com.craftmind.app.domain.buildplan.BlockPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * Builds library card contract (Phase 15 §8).
 *
 * A card may only state what local history really holds: the save time, what the plan was generated from, its place
 * in the version history, and the execution phase the bridge actually reported. Nothing is invented to fill a card.
 */
class BuildRecordSummaryTest {

    @Test
    fun aSavedVersionDescribesItselfFromRealRecordFields() {
        val record = BuildPlanTestFixtures.record(version = 1)
        val summary = buildRecordSummary(
            record = record,
            versionCount = 1,
            zone = ZoneOffset.UTC,
        )
        assertEquals(record.plan.metadata.title, summary.title)
        assertEquals(record.plan.metadata.summary, summary.summary)
        assertEquals("Version 1", summary.versionLabel)
        assertEquals("Text prompt", summary.sourceLabel)
        assertTrue(summary.savedAtLabel.isNotBlank())
        assertFalse(summary.hasNewerVersion)
        assertNull(summary.refinementLabel)
        assertNull(summary.executionStatusLabel)
        assertEquals(
            "${record.plan.metadata.dimensions.width} × ${record.plan.metadata.dimensions.height} × " +
                "${record.plan.metadata.dimensions.depth} blocks",
            summary.dimensionLabel,
        )
        assertEquals("${record.plan.operations.size} placements", summary.operationCountLabel)
        assertEquals(
            "${record.plan.metadata.providerId} / ${record.plan.metadata.modelId}",
            summary.modelLabel,
        )
    }

    @Test
    fun versionHistoryIsReportedWithoutInventingARefinement() {
        val refined = BuildPlanTestFixtures.record(version = 2)
            .copy(refinementInstruction = "Add a glass roof", changeSummary = "Roof glazing added")
        val summary = buildRecordSummary(record = refined, versionCount = 2, zone = ZoneOffset.UTC)
        assertEquals("Version 2 of 2 saved", summary.versionLabel)
        assertEquals("Refined · Roof glazing added", summary.refinementLabel)
        assertTrue(summary.hasNewerVersion.not())

        val older = BuildPlanTestFixtures.record(version = 1)
        val olderSummary = buildRecordSummary(record = older, versionCount = 3, zone = ZoneOffset.UTC)
        assertTrue("an older version must say a newer one exists", olderSummary.hasNewerVersion)
        assertNull("an unrefined version must not claim a refinement", olderSummary.refinementLabel)

        val restored = BuildPlanTestFixtures.record(version = 3).copy(restoredFromVersion = 1)
        assertEquals(
            "Restored from version 1",
            buildRecordSummary(record = restored, versionCount = 3, zone = ZoneOffset.UTC).refinementLabel,
        )
    }

    @Test
    fun theSourceLabelDistinguishesAnalyzedReferencesFromLocalOnes() {
        val base = BuildPlanTestFixtures.record()
        fun summaryOf(request: BuildRequestSnapshot) =
            describeRequestSource(base.copy(request = request))

        assertEquals("Text prompt", summaryOf(BuildRequestSnapshot(prompt = "A courtyard")))
        assertEquals(
            "Prompt + analyzed image",
            summaryOf(
                BuildRequestSnapshot(
                    prompt = "A courtyard",
                    imageContentUri = "content://media/1",
                    imageAnalysisSource = com.craftmind.app.domain.buildplan.BuildImageAnalysisSource(
                        providerId = "google_gemini",
                        modelId = "gemini-vision",
                        analysis = com.craftmind.app.domain.buildplan.BuildImageAnalysis(
                            summary = "A stone courtyard",
                            observedDetails = emptyList(),
                            inferredDetails = emptyList(),
                            uncertainties = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            "Prompt + local image reference",
            summaryOf(BuildRequestSnapshot(prompt = "A courtyard", imageContentUri = "content://media/1")),
        )
        assertEquals(
            "Prompt + public video reference",
            summaryOf(
                BuildRequestSnapshot(
                    prompt = "A courtyard",
                    urlReference = "https://raw.githubusercontent.com/user/repo/main/video.mp4",
                ),
            ),
        )
        assertEquals("Local image reference", summaryOf(BuildRequestSnapshot(prompt = "", imageContentUri = "content://media/1")))
        assertEquals("No description recorded", summaryOf(BuildRequestSnapshot(prompt = "   ")))
    }

    @Test
    fun executionStatusComesOnlyFromARecordedBridgePhase() {
        val record = BuildPlanTestFixtures.record()
        val executions = listOf(
            execution(MinecraftExecutionPhase.PREPARED, completed = 0, updatedAt = 10),
            execution(MinecraftExecutionPhase.COMPLETED, completed = 40, updatedAt = 30),
        )
        val summary = buildRecordSummary(
            record = record,
            versionCount = 1,
            executions = executions,
            zone = ZoneOffset.UTC,
        )
        assertEquals("Built in Minecraft · 40/40 operations completed", summary.executionStatusLabel)
        assertEquals(CraftMindTone.POSITIVE, summary.executionTone)

        // The newest record wins, and its phase drives the tone.
        assertEquals(
            execution(MinecraftExecutionPhase.COMPLETED, completed = 40, updatedAt = 30),
            latestExecutionFor(record.buildId, executions),
        )
        assertNull(latestExecutionFor("another-build", executions))

        assertEquals(
            "Build failed in Minecraft (BRIDGE_UNAVAILABLE) · 12/40 operations reported" to CraftMindTone.NEGATIVE,
            describeExecution(
                execution(MinecraftExecutionPhase.FAILED, completed = 12, updatedAt = 40)
                    .copy(reasonCode = "BRIDGE_UNAVAILABLE"),
            ),
        )
        assertEquals(
            CraftMindTone.BRAND,
            describeExecution(execution(MinecraftExecutionPhase.RUNNING, completed = 5, updatedAt = 50)).second,
        )
        assertEquals(
            CraftMindTone.CAUTION,
            describeExecution(execution(MinecraftExecutionPhase.CANCELLED, completed = 5, updatedAt = 60)).second,
        )
        assertEquals(
            CraftMindTone.INFORMATIVE,
            describeExecution(execution(MinecraftExecutionPhase.PREPARED, completed = 0, updatedAt = 70)).second,
        )
    }

    @Test
    fun aBuildWithNoExecutionRecordNeverClaimsOneHappened() {
        val summary = buildRecordSummary(
            record = BuildPlanTestFixtures.record(),
            versionCount = 1,
            executions = listOf(execution(MinecraftExecutionPhase.COMPLETED, completed = 40, updatedAt = 1).copy(buildId = "other-build")),
            zone = ZoneOffset.UTC,
        )
        assertNull(summary.executionStatusLabel)
        assertEquals(CraftMindTone.NEUTRAL, summary.executionTone)
    }

    @Test
    fun saveTimeFormattingNeverFabricatesATimestamp() {
        assertEquals("Saved time not recorded", formatSavedAt(0L, ZoneOffset.UTC))
        assertEquals("Saved time not recorded", formatSavedAt(-1L, ZoneOffset.UTC))
        val formatted = formatSavedAt(1_700_000_000_100L, ZoneOffset.UTC)
        assertTrue("expected a real localised timestamp, got '$formatted'", formatted.contains("2023"))
    }

    private fun execution(
        phase: MinecraftExecutionPhase,
        completed: Int,
        updatedAt: Long,
    ) = LocalBuildExecutionRecord(
        executionId = "execution-$updatedAt",
        buildId = BuildPlanTestFixtures.record().buildId,
        planRecordId = BuildPlanTestFixtures.record().recordId,
        planVersion = 1,
        bridgeId = "bridge-0123456789abcdef0123456789abcdef",
        phase = phase,
        completedOperations = completed,
        totalOperations = 40,
        eventSequence = updatedAt,
        createdAtEpochMillis = updatedAt,
        updatedAtEpochMillis = updatedAt,
        dimensionId = "minecraft:overworld",
        worldSessionId = "world-session-1",
        resolvedOrigin = BlockPosition(0, 64, 0),
    )
}
