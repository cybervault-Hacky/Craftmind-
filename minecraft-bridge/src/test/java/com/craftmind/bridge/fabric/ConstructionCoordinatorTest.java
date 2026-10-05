package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeExecutionRequest;
import com.craftmind.bridge.protocol.BuildPlanDocument;
import com.craftmind.bridge.protocol.ExecutionProtocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConstructionCoordinatorTest {
    private static final String CLIENT_ID = "cm-0123456789abcdef01234567";
    private static final String WORLD_SESSION = "8cbb1e95-3dae-4c3a-81e2-f1c6077e3097";

    @Test
    public void wholePlanPreflightMustPassBeforeAnyPlacement() throws Exception {
        Bundle bundle = bundle(4);
        FakeWorld world = new FakeWorld();
        world.preflightFailure = "BLOCK_OCCUPIED";

        ConstructionCoordinator.PrepareResult result = bundle.coordinator.prepare(CLIENT_ID, request("one"), hash('a'),
                operations(), origin(), world);

        assertNull(result.ready);
        assertEquals("BLOCK_OCCUPIED", result.reasonCode);
        assertTrue(world.placements.isEmpty());
        ExecutionProtocol.ExecutionSnapshot snapshot = bundle.coordinator.status(CLIENT_ID, executionId("one"));
        assertNotNull(snapshot);
        assertEquals("FAILED", snapshot.state);
        assertEquals(0, snapshot.completedOperations);
    }

    @Test
    public void preparationIsIdempotentAndStartQueuesOnlyOnceInSemanticOrder() throws Exception {
        Bundle bundle = bundle(1);
        FakeWorld world = new FakeWorld();
        BridgeExecutionRequest request = request("two");

        ConstructionCoordinator.PrepareResult prepared = bundle.coordinator.prepare(CLIENT_ID, request, hash('b'),
                operations(), origin(), world);
        ConstructionCoordinator.PrepareResult retry = bundle.coordinator.prepare(CLIENT_ID, request, hash('b'),
                operations(), origin(), world);
        assertNotNull(prepared.ready);
        assertEquals(prepared.ready.preflightToken, retry.ready.preflightToken);
        assertEquals(1, world.preflightCount);

        ConstructionCoordinator.StartResult invalidToken = bundle.coordinator.start(CLIENT_ID, request.executionId,
                "wrong-token", world);
        assertFalse(invalidToken.accepted);
        assertTrue(world.placements.isEmpty());

        ConstructionCoordinator.StartResult start = bundle.coordinator.start(CLIENT_ID, request.executionId,
                prepared.ready.preflightToken, world);
        assertTrue(start.accepted);
        assertEquals("QUEUED", start.snapshot.state);
        ConstructionCoordinator.StartResult duplicateStart = bundle.coordinator.start(CLIENT_ID, request.executionId,
                prepared.ready.preflightToken, world);
        assertTrue(duplicateStart.accepted);
        assertEquals("QUEUED", duplicateStart.snapshot.state);

        bundle.coordinator.tick();
        assertEquals(List.of(0), world.placements);
        assertEquals("RUNNING", bundle.coordinator.status(CLIENT_ID, request.executionId).state);
        bundle.coordinator.tick();
        assertEquals(List.of(0, 1), world.placements);
        bundle.coordinator.tick();
        assertEquals(List.of(0, 1, 2), world.placements);

        ExecutionProtocol.ExecutionSnapshot completed = bundle.coordinator.status(CLIENT_ID, request.executionId);
        assertEquals("COMPLETED", completed.state);
        assertEquals(3, completed.completedOperations);
        assertEquals(3, completed.totalOperations);
        assertEquals(2, world.preflightCount);
    }

    @Test
    public void onlyOnePreflightOrBuildCanBeActiveAndExecutionIdsAreIdempotencyKeys() throws Exception {
        Bundle bundle = bundle(2);
        FakeWorld world = new FakeWorld();
        BridgeExecutionRequest first = request("three");
        ConstructionCoordinator.PrepareResult prepared = bundle.coordinator.prepare(CLIENT_ID, first, hash('c'),
                operations(), origin(), world);
        assertNotNull(prepared.ready);

        BridgeExecutionRequest second = request("four");
        assertEquals("ACTIVE_EXECUTION_EXISTS", bundle.coordinator.prepare(CLIENT_ID, second, hash('d'),
                operations(), origin(), world).reasonCode);
        assertEquals("EXECUTION_ID_CONFLICT", bundle.coordinator.prepare(CLIENT_ID, first, hash('e'),
                operations(), origin(), world).reasonCode);
        assertNull(bundle.coordinator.status("cm-aaaaaaaaaaaaaaaaaaaaaaaa", first.executionId));
        assertEquals("EXECUTION_NOT_FOUND", bundle.coordinator.cancel("cm-aaaaaaaaaaaaaaaaaaaaaaaa", first.executionId).outcome);
    }

    @Test
    public void operatorOriginChangeStopsQueuedWorkBeforeAnyPlacement() throws Exception {
        Bundle bundle = bundle(4);
        FakeWorld world = new FakeWorld();
        BridgeExecutionRequest request = request("origin-change");
        ConstructionCoordinator.PrepareResult prepared = bundle.coordinator.prepare(
                CLIENT_ID, request, hash('i'), operations(), origin(), world);
        assertNotNull(prepared.ready);
        bundle.coordinator.start(CLIENT_ID, request.executionId, prepared.ready.preflightToken, world);

        BuildPlanDocument.Position moved = new BuildPlanDocument.Position();
        moved.x = origin().position.x + 1;
        moved.y = origin().position.y;
        moved.z = origin().position.z;
        bundle.coordinator.tick(new BridgeBuildOrigin("minecraft:overworld", WORLD_SESSION, moved));

        ExecutionProtocol.ExecutionSnapshot failed = bundle.coordinator.status(CLIENT_ID, request.executionId);
        assertEquals("FAILED", failed.state);
        assertEquals("ORIGIN_CHANGED", failed.reasonCode);
        assertEquals(0, failed.completedOperations);
        assertTrue(world.placements.isEmpty());
    }

    @Test
    public void cancellationAndFailureReportOnlySuccessfullyPlacedOperations() throws Exception {
        Bundle cancelledBundle = bundle(1);
        FakeWorld cancelWorld = new FakeWorld();
        BridgeExecutionRequest cancellable = request("five");
        ConstructionCoordinator.PrepareResult preview = cancelledBundle.coordinator.prepare(CLIENT_ID, cancellable, hash('f'),
                operations(), origin(), cancelWorld);
        cancelledBundle.coordinator.start(CLIENT_ID, cancellable.executionId, preview.ready.preflightToken, cancelWorld);
        cancelledBundle.coordinator.tick();
        assertEquals(1, cancelledBundle.coordinator.status(CLIENT_ID, cancellable.executionId).completedOperations);
        assertEquals("CANCELLATION_ACCEPTED", cancelledBundle.coordinator.cancel(CLIENT_ID, cancellable.executionId).outcome);
        cancelledBundle.coordinator.tick();
        ExecutionProtocol.ExecutionSnapshot cancelled = cancelledBundle.coordinator.status(CLIENT_ID, cancellable.executionId);
        assertEquals("CANCELLED", cancelled.state);
        assertEquals(1, cancelled.completedOperations);
        assertEquals(List.of(0), cancelWorld.placements);

        Bundle failedBundle = bundle(8);
        FakeWorld failingWorld = new FakeWorld();
        failingWorld.failOperationIndex = 1;
        BridgeExecutionRequest failing = request("six");
        ConstructionCoordinator.PrepareResult failurePreview = failedBundle.coordinator.prepare(CLIENT_ID, failing, hash('g'),
                operations(), origin(), failingWorld);
        failedBundle.coordinator.start(CLIENT_ID, failing.executionId, failurePreview.ready.preflightToken, failingWorld);
        failedBundle.coordinator.tick();
        ExecutionProtocol.ExecutionSnapshot failed = failedBundle.coordinator.status(CLIENT_ID, failing.executionId);
        assertEquals("FAILED", failed.state);
        assertEquals("PLACEMENT_REJECTED", failed.reasonCode);
        assertEquals(Integer.valueOf(1), failed.failedOperationIndex);
        assertEquals(1, failed.completedOperations);
        assertEquals(List.of(0, 1), failingWorld.placements);
    }

    @Test
    public void aNewBridgeStoreMarksPartiallyCompletedExecutionInterruptedAndNeverResumesIt() throws Exception {
        Path directory = Files.createTempDirectory("craftmind-execution-store-test");
        BridgeExecutionRecordStore firstStore = new BridgeExecutionRecordStore(directory);
        ConstructionCoordinator first = new ConstructionCoordinator(firstStore, limits(1));
        FakeWorld world = new FakeWorld();
        BridgeExecutionRequest request = request("seven");
        ConstructionCoordinator.PrepareResult preview = first.prepare(CLIENT_ID, request, hash('h'), operations(), origin(), world);
        first.start(CLIENT_ID, request.executionId, preview.ready.preflightToken, world);
        first.tick();
        assertEquals(List.of(0), world.placements);
        assertEquals(1, first.status(CLIENT_ID, request.executionId).completedOperations);

        BridgeExecutionRecordStore restartedStore = new BridgeExecutionRecordStore(directory);
        ConstructionCoordinator restarted = new ConstructionCoordinator(restartedStore, limits(1));
        assertFalse(restarted.hasActiveExecution());
        restarted.tick();
        assertEquals(List.of(0), world.placements);
        BridgeExecutionRecord interrupted = restartedStore.find(request.executionId);
        assertNotNull(interrupted);
        assertEquals("FAILED", interrupted.state);
        assertEquals("SERVER_RESTARTED", interrupted.reasonCode);
        assertEquals(1, interrupted.completedOperations);
        assertEquals(List.of(0), world.placements);
    }

    private static Bundle bundle(int operationsPerTick) throws Exception {
        Path directory = Files.createTempDirectory("craftmind-execution-test");
        BridgeExecutionRecordStore store = new BridgeExecutionRecordStore(directory);
        return new Bundle(new ConstructionCoordinator(store, limits(operationsPerTick)));
    }

    private static BridgeExecutionLimits limits(int operationsPerTick) {
        return new BridgeExecutionLimits(4096, 1_048_576, operationsPerTick, 300);
    }

    private static BridgeExecutionRequest request(String suffix) {
        BridgeExecutionRequest request = new BridgeExecutionRequest();
        request.executionId = executionId(suffix);
        request.buildId = "build_" + suffix;
        request.planRecordId = "build_" + suffix + "_v1";
        request.planVersion = 1;
        request.buildPlanSchemaVersion = 2;
        request.buildPlan = new BuildPlanDocument();
        request.buildPlan.planId = "plan_" + suffix;
        request.buildPlan.originStrategy = "WORLD_ORIGIN";
        request.buildPlan.metadata = new BuildPlanDocument.Metadata();
        request.buildPlan.metadata.title = "Test build";
        return request;
    }

    private static String executionId(String suffix) {
        return java.util.UUID.nameUUIDFromBytes(suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static BridgeBuildOrigin origin() {
        BuildPlanDocument.Position position = new BuildPlanDocument.Position();
        position.x = -4;
        position.y = 64;
        position.z = -8;
        return new BridgeBuildOrigin("minecraft:overworld", WORLD_SESSION, position);
    }

    private static List<BuildPlanTransformer.TransformedOperation> operations() throws Exception {
        BuildPlanDocument plan = new BuildPlanDocument();
        plan.originStrategy = "WORLD_ORIGIN";
        plan.metadata = new BuildPlanDocument.Metadata();
        plan.metadata.dimensions = new BuildPlanDocument.Dimensions();
        plan.metadata.dimensions.width = 4;
        plan.metadata.dimensions.height = 2;
        plan.metadata.dimensions.depth = 2;
        plan.operations = new ArrayList<>();
        plan.operations.add(operation(0, 0));
        plan.operations.add(operation(1, 1));
        plan.operations.add(operation(2, 2));
        return BuildPlanTransformer.transform(plan, origin().position, 4096);
    }

    private static BuildPlanDocument.Operation operation(int sequence, int x) {
        BuildPlanDocument.Operation operation = new BuildPlanDocument.Operation();
        operation.sequence = sequence;
        operation.kind = "PLACE_BLOCK";
        operation.blockId = "minecraft:stone";
        operation.position = new BuildPlanDocument.Position();
        operation.position.x = x;
        operation.position.y = 0;
        operation.position.z = 0;
        operation.blockState = new HashMap<>();
        return operation;
    }

    private static final class Bundle {
        final ConstructionCoordinator coordinator;
        Bundle(ConstructionCoordinator coordinator) { this.coordinator = coordinator; }
    }

    private static final class FakeWorld implements ExecutionWorldAccess {
        final List<Integer> placements = new ArrayList<>();
        String preflightFailure;
        int failOperationIndex = -1;
        int preflightCount;
        @Override public String preflight(List<BuildPlanTransformer.TransformedOperation> operations) {
            preflightCount++;
            return preflightFailure;
        }
        @Override public PlacementResult place(BuildPlanTransformer.TransformedOperation operation) {
            placements.add(operation.sequence);
            if (operation.sequence == failOperationIndex) return PlacementResult.failed("PLACEMENT_REJECTED");
            return PlacementResult.placed();
        }
    }
}
