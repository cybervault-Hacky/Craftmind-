package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildPlanOperationKind
import com.craftmind.app.domain.buildplan.BuildPlanValidationIssue
import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.buildplan.MinecraftBlockCatalog
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.minecraft.compatibility.BuildPlanRequirements
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.JavaFabric1201Adapter
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.bridge.protocol.BridgeProtocol
import com.craftmind.bridge.protocol.BridgeProtocolCodec
import com.craftmind.bridge.protocol.BuildPlanContractValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 14 §7: the deterministic certification BuildPlan.
 *
 * The probe is tiny, bounded, safe, schema-compatible, and validated by the *same* production validator and production
 * block catalog as a user build. It is never a user build, never contains commands, entities, or redstone, and it is
 * never made to pass by weakening production validation: this suite asserts both that the probe passes and that the
 * validator still rejects everything it rejected before.
 */
class CertificationBuildPlanTest {
    private val validator = DefaultBuildPlanValidator()
    private val plan = CertificationBuildPlan.build()

    @Test
    fun theProbePassesTheExactProductionValidatorAndCatalog() {
        val result = validator.validate(plan)
        assertTrue("the certification probe must pass production validation, got $result", result is BuildPlanValidationResult.Valid)
        assertEquals(plan, CertificationBuildPlan.validated().plan)
        // The production catalog, unchanged, supports every block and every block state the probe uses.
        CertificationBuildPlan.PLACEMENTS.forEach { placement ->
            assertTrue("catalog must support ${placement.blockId}", MinecraftBlockCatalog.supports(placement.blockId))
            assertTrue(
                "catalog must accept the state of ${placement.blockId}",
                MinecraftBlockCatalog.validState(placement.blockId, placement.blockState.toMutableMap()),
            )
        }
    }

    @Test
    fun theProbeIsTinyBoundedDeterministicAndSchemaCompatible() {
        assertEquals(CertificationBuildPlan.PLAN_ID, plan.planId)
        assertEquals(BuildPlanLimits.CURRENT_SCHEMA_VERSION, plan.metadata.schemaVersion)
        assertEquals(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION, plan.metadata.schemaVersion)
        assertEquals(3, plan.operations.size)
        assertEquals(1, plan.components.size)
        assertEquals(CertificationBuildPlan.DIMENSIONS, plan.metadata.dimensions)
        assertTrue(plan.metadata.dimensions.width <= BuildPlanLimits.MAX_BUILD_WIDTH)
        assertTrue(plan.metadata.dimensions.height <= BuildPlanLimits.MAX_BUILD_HEIGHT)
        assertTrue(plan.metadata.dimensions.depth <= BuildPlanLimits.MAX_BUILD_DEPTH)
        assertTrue(plan.operations.size <= BuildPlanLimits.MAX_OPERATIONS)
        assertEquals(CertificationBuildPlan.GENERATED_AT_EPOCH_MILLIS, plan.metadata.generatedAtEpochMillis)
        assertEquals(
            com.craftmind.app.domain.buildplan.BuildStatus.READY,
            plan.status,
        )

        // Deterministic: identical inputs produce an identical plan, so a certification run is reproducible.
        assertEquals(CertificationBuildPlan.build(), CertificationBuildPlan.build())
        assertEquals(
            CertificationBuildPlan.localRecord(),
            CertificationBuildPlan.localRecord(),
        )
        assertEquals(
            listOf(0, 1, 2),
            plan.operations.map { it.sequence },
        )
        // Every placement stays inside the declared bounds and inside the single component.
        plan.operations.forEach { operation ->
            assertTrue(operation.position.x in 0 until plan.metadata.dimensions.width)
            assertTrue(operation.position.y in 0 until plan.metadata.dimensions.height)
            assertTrue(operation.position.z in 0 until plan.metadata.dimensions.depth)
            assertEquals(CertificationBuildPlan.COMPONENT_ID, operation.componentId)
        }
        assertEquals(CertificationBuildPlan.PLACEMENTS, CertificationBuildPlan.EXPECTED_WORLD_STATE)
        assertEquals(plan.operations.size, CertificationBuildPlan.OPERATION_COUNT)
    }

    @Test
    fun theProbeIsNeverAUserBuildAndExposesNoCommandEntityOrRedstoneSurface() {
        assertEquals(setOf(BuildPlanOperationKind.PLACE_BLOCK), plan.operations.map { it.kind }.toSet())
        assertEquals("certification-probe", plan.metadata.sourceRequestId)
        assertEquals("certification", plan.metadata.providerId)
        assertEquals(CertificationBuildPlan.BUILD_ID, CertificationBuildPlan.localRecord().buildId)
        assertTrue(plan.metadata.title.contains("certification", ignoreCase = true))
        assertTrue(
            "the probe must state that it is never a user build",
            plan.metadata.intent?.constraints?.any { it.contains("never a user build") } == true,
        )

        val forbiddenBlocks = listOf(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:jigsaw", "minecraft:redstone_wire", "minecraft:redstone_block",
            "minecraft:tnt", "minecraft:dispenser", "minecraft:dropper", "minecraft:hopper", "minecraft:spawner",
        )
        val blockIds = plan.operations.map { it.blockId }
        forbiddenBlocks.forEach { forbidden ->
            assertFalse("the probe must never place $forbidden", forbidden in blockIds)
        }
        val json = kotlinx.serialization.json.Json.encodeToString(BuildPlan.serializer(), plan)
        listOf("command", "shell", "entity", "nbt", "redstone", "exec(").forEach { needle ->
            assertFalse("the probe must not carry '$needle': $json", json.lowercase().contains(needle))
        }
        assertTrue("block states are bounded", plan.operations.all { it.blockState.size <= BuildPlanLimits.MAX_BLOCK_STATE_PROPERTIES })
    }

    @Test
    fun theProbeSatisfiesTheProductionContractValidatorAndRuntimeLimits() = runBlocking {
        val bridge = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        bridge.connect()
        val record = CertificationBuildPlan.localRecord()
        val executionId = CertificationExecutionIds.next("certification-build-plan")
        val payload = bridge.executionRequestPayload(record, executionId)
        val bytes = BridgeProtocolCodec.writeEnvelope(
            BridgeProtocolCodec.newEnvelope("execution.prepare.request", payload, executionId),
        )

        assertNull(
            "the probe must satisfy the production bridge contract",
            BuildPlanContractValidator.validateExecutionPayload(
                payload,
                bytes.size,
                BridgeProtocol.MAX_OPERATIONS,
                BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
                CatalogBlockSupport,
            ),
        )
        assertTrue(bytes.size <= BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES)

        // The production adapter accepts the probe for the certified runtime and preflight places zero blocks.
        val preview = JavaFabric1201Adapter().preflight(bridge, record, executionId)
        assertEquals(plan.operations.size, preview.operationCount)
        assertTrue("preflight must place zero blocks", bridge.worldBlocks.isEmpty())

        val resolution = DefaultMinecraftCompatibility.resolver.runtimeGate.resolvePlan(
            plan = plan,
            report = bridge.authenticatedSnapshot().runtimeReport(
                sessionId = bridge.sessionId,
                requestedAppVersion = SimulatedCertificationBridge.CERTIFICATION_APP_VERSION,
                authenticated = true,
                authenticatedAtEpochMillis = bridge.authenticatedAtEpochMillis,
            ),
        )
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, resolution.compatibility.status)
        assertTrue("the probe must be executable on the certified runtime", resolution.canExecute)
    }

    @Test
    fun productionValidationIsNotWeakenedToMakeTheProbePass() {
        fun issues(transform: (BuildPlan) -> BuildPlan): Set<BuildPlanValidationIssue> =
            when (val result = validator.validate(transform(plan))) {
                is BuildPlanValidationResult.Invalid -> result.issues
                is BuildPlanValidationResult.Valid -> error("expected the mutated probe to be rejected")
            }

        assertTrue(
            BuildPlanValidationIssue.INVALID_BLOCK_ID in issues { probe ->
                probe.copy(
                    operations = probe.operations.mapIndexed { index, operation ->
                        if (index == 0) operation.copy(blockId = "minecraft:craftmind_probe_block") else operation
                    },
                )
            },
        )
        assertTrue(
            BuildPlanValidationIssue.INVALID_BLOCK_STATE in issues { probe ->
                probe.copy(
                    operations = probe.operations.mapIndexed { index, operation ->
                        if (index == 2) operation.copy(blockState = mapOf("north" to "maybe")) else operation
                    },
                )
            },
        )
        assertTrue(BuildPlanValidationIssue.EMPTY_OPERATIONS in issues { it.copy(operations = emptyList()) })
        assertTrue(
            BuildPlanValidationIssue.INVALID_COORDINATE in issues { probe ->
                probe.copy(
                    operations = probe.operations.mapIndexed { index, operation ->
                        if (index == 0) operation.copy(position = BlockPosition(90, 0, 0)) else operation
                    },
                )
            },
        )
        assertTrue(
            BuildPlanValidationIssue.DUPLICATE_COORDINATE in issues { probe ->
                probe.copy(
                    operations = probe.operations.mapIndexed { index, operation ->
                        if (index == 1) operation.copy(position = probe.operations[0].position) else operation
                    },
                )
            },
        )
        assertTrue(
            BuildPlanValidationIssue.UNSUPPORTED_SCHEMA_VERSION in issues { probe ->
                probe.copy(metadata = probe.metadata.copy(schemaVersion = 3))
            },
        )
        assertTrue(
            BuildPlanValidationIssue.TOO_MANY_OPERATIONS in issues { probe ->
                probe.copy(
                    operations = (0..BuildPlanLimits.MAX_OPERATIONS).map { index ->
                        probe.operations[0].copy(
                            sequence = index,
                            position = BlockPosition(index % 3, 0, index / 3),
                        )
                    },
                )
            },
        )
    }

    private companion object {
        val CatalogBlockSupport = object : BuildPlanContractValidator.BlockSupport {
            override fun isSupportedBlock(blockId: String): Boolean = MinecraftBlockCatalog.supports(blockId)

            override fun hasValidState(blockId: String, state: MutableMap<String, String>): Boolean =
                MinecraftBlockCatalog.validState(blockId, state)
        }
    }
}
