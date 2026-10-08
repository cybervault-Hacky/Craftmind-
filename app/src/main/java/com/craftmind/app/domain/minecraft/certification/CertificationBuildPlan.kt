package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.buildplan.BlockBounds
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildComponentType
import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildIntent
import com.craftmind.app.domain.buildplan.BuildOriginStrategy
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanComponent
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildPlanMetadata
import com.craftmind.app.domain.buildplan.BuildPlanOperation
import com.craftmind.app.domain.buildplan.BuildPlanOperationKind
import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.ValidatedBuildPlan

/**
 * The deterministic certification probe plan (§7).
 *
 * This is **not** a user build and is never offered in the app: it is a tiny, bounded, fully deterministic BuildPlan
 * v2 used by certification runs so that repeated tests produce byte-identical payloads. It goes through the exact
 * same production validation path as a real plan — [DefaultBuildPlanValidator] plus the shared bridge contract
 * validator — and production validation is never weakened to make it pass.
 *
 * Deliberate safety properties: three placements, one component, no entities, no block entities, no redstone or
 * automation, no commands, no dangerous mechanisms, no random or time-dependent content, and no coordinate outside a
 * 3 × 1 × 3 box.
 */
object CertificationBuildPlan {
    const val PLAN_ID = "certification-probe-v1"
    const val BUILD_ID = "certification-probe"
    const val RECORD_ID = "certification-probe-v1"
    const val COMPONENT_ID = "probe"
    const val TITLE = "CraftMind certification probe"
    const val SUMMARY = "A three-block deterministic probe used only to verify the compatibility pipeline."

    /** Fixed generation timestamp so serialized payloads are reproducible across runs. */
    const val GENERATED_AT_EPOCH_MILLIS = 1_700_000_000_000L

    val DIMENSIONS = BuildDimensions(width = 3, height = 1, depth = 3)

    /** The exact placements, in order. Deterministic block IDs and one deterministic block state. */
    val PLACEMENTS: List<CertificationPlacement> = listOf(
        CertificationPlacement(sequence = 0, blockId = "minecraft:stone", position = BlockPosition(0, 0, 0)),
        CertificationPlacement(sequence = 1, blockId = "minecraft:stone", position = BlockPosition(1, 0, 0)),
        CertificationPlacement(
            sequence = 2,
            blockId = "minecraft:glass_pane",
            position = BlockPosition(2, 0, 0),
            blockState = mapOf("north" to "true"),
        ),
    )

    /** Positions a certification run must read back from a real world to claim `BLOCKS_WRITTEN`. */
    val EXPECTED_WORLD_STATE: List<CertificationPlacement> = PLACEMENTS

    val OPERATION_COUNT: Int get() = PLACEMENTS.size

    data class CertificationPlacement(
        val sequence: Int,
        val blockId: String,
        val position: BlockPosition,
        val blockState: Map<String, String> = emptyMap(),
    )

    /** Builds the probe plan. Identical inputs always produce an identical plan. */
    fun build(): BuildPlan = BuildPlan(
        planId = PLAN_ID,
        metadata = BuildPlanMetadata(
            schemaVersion = BuildPlanLimits.CURRENT_SCHEMA_VERSION,
            sourceRequestId = "certification-probe",
            providerId = "certification",
            modelId = "certification-probe-v1",
            title = TITLE,
            summary = SUMMARY,
            generatedAtEpochMillis = GENERATED_AT_EPOCH_MILLIS,
            dimensions = DIMENSIONS,
            intent = BuildIntent(
                structureType = "certification probe",
                style = null,
                approximateScale = "minimal",
                floorCount = 1,
                rooms = emptyList(),
                specialFeatures = emptyList(),
                materials = listOf("stone", "glass"),
                environment = null,
                constraints = listOf("deterministic certification probe; never a user build"),
            ),
        ),
        originStrategy = BuildOriginStrategy.CENTERED_GROUND,
        components = listOf(
            BuildPlanComponent(
                componentId = COMPONENT_ID,
                type = BuildComponentType.FOUNDATION,
                name = "Certification probe line",
                purpose = "Three deterministic placements used to verify the compatibility pipeline.",
                bounds = BlockBounds(BlockPosition(0, 0, 0), DIMENSIONS),
                parentComponentId = null,
                constructionOrder = 0,
            ),
        ),
        operations = PLACEMENTS.map { placement ->
            BuildPlanOperation(
                sequence = placement.sequence,
                kind = BuildPlanOperationKind.PLACE_BLOCK,
                blockId = placement.blockId,
                position = placement.position,
                blockState = placement.blockState,
                componentId = COMPONENT_ID,
            )
        },
        status = BuildStatus.READY,
    )

    /**
     * Validates the probe through the production validator. A certification run fails loudly if the probe is ever
     * rejected, because that means production validation changed — the probe is never adapted to a weaker validator.
     */
    fun validated(validator: DefaultBuildPlanValidator = DefaultBuildPlanValidator()): ValidatedBuildPlan =
        when (val result = validator.validate(build())) {
            is BuildPlanValidationResult.Valid -> result.plan
            is BuildPlanValidationResult.Invalid -> error(
                "The deterministic certification probe failed production validation: ${result.issues}",
            )
        }

    /** The probe wrapped as a local build record, which is what adapters and the execution gate consume. */
    fun localRecord(version: Int = 1): LocalBuildRecord = LocalBuildRecord(
        recordId = RECORD_ID,
        plan = build(),
        request = BuildRequestSnapshot(prompt = "certification probe"),
        savedAtEpochMillis = GENERATED_AT_EPOCH_MILLIS,
        buildId = BUILD_ID,
        version = version,
    )
}
