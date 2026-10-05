package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BuildPlanDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class BuildPlanTransformerTest {
    @Test
    public void centeredGroundSupportsNegativeWorldCoordinatesAndPreservesSemanticOrder() throws Exception {
        BuildPlanDocument plan = plan("CENTERED_GROUND", 4, 3, 6,
                operation(0, 0, 0, 0), operation(1, 3, 2, 5));
        List<BuildPlanTransformer.TransformedOperation> transformed = BuildPlanTransformer.transform(
                plan, position(-10, 62, -20), 4096);

        assertEquals(-12, transformed.get(0).position.x);
        assertEquals(62, transformed.get(0).position.y);
        assertEquals(-23, transformed.get(0).position.z);
        assertEquals(-9, transformed.get(1).position.x);
        assertEquals(64, transformed.get(1).position.y);
        assertEquals(-18, transformed.get(1).position.z);
        assertEquals(0, transformed.get(0).sequence);
        assertEquals(1, transformed.get(1).sequence);
    }

    @Test
    public void worldOriginAddsEveryLocalCoordinateWithoutCentering() throws Exception {
        BuildPlanDocument plan = plan("WORLD_ORIGIN", 4, 3, 6, operation(0, 3, 2, 5));
        List<BuildPlanTransformer.TransformedOperation> transformed = BuildPlanTransformer.transform(
                plan, position(-30_000_000, -64, 29_999_990), 4096);
        assertEquals(-29_999_997, transformed.get(0).position.x);
        assertEquals(-62, transformed.get(0).position.y);
        assertEquals(29_999_995, transformed.get(0).position.z);
    }

    @Test
    public void rejectsLocalYOutsidePlanAndAbsoluteCoordinateOverflow() {
        BuildPlanDocument invalidLocal = plan("WORLD_ORIGIN", 2, 2, 2, operation(0, 0, 2, 0));
        assertEquals("INVALID_BUILD_PLAN", assertThrows(BuildPlanTransformer.TransformException.class,
                () -> BuildPlanTransformer.transform(invalidLocal, position(0, 0, 0), 4096)).reasonCode());

        BuildPlanDocument overflow = plan("WORLD_ORIGIN", 2, 2, 2, operation(0, 1, 0, 0));
        assertEquals("WORLD_BOUNDS_REJECTED", assertThrows(BuildPlanTransformer.TransformException.class,
                () -> BuildPlanTransformer.transform(overflow, position(BridgeExecutionLimits.MAX_COORDINATE, 0, 0), 4096))
                .reasonCode());
    }

    @Test
    public void rejectsTransformedYOutsideTheConservativeMinecraftRange() {
        BuildPlanDocument tooHigh = plan("WORLD_ORIGIN", 1, 2, 1, operation(0, 0, 1, 0));
        assertEquals("WORLD_BOUNDS_REJECTED", assertThrows(BuildPlanTransformer.TransformException.class,
                () -> BuildPlanTransformer.transform(tooHigh, position(0, BridgeExecutionLimits.MAX_TRANSFORM_Y, 0), 4096))
                .reasonCode());

        BuildPlanDocument tooLow = plan("WORLD_ORIGIN", 1, 2, 1, operation(0, 0, 0, 0));
        assertEquals("WORLD_BOUNDS_REJECTED", assertThrows(BuildPlanTransformer.TransformException.class,
                () -> BuildPlanTransformer.transform(tooLow, position(0, -BridgeExecutionLimits.MAX_TRANSFORM_Y - 1, 0), 4096))
                .reasonCode());
    }

    @Test
    public void refusesToReorderOrExceedTheConfiguredOperationLimit() {
        BuildPlanDocument unordered = plan("WORLD_ORIGIN", 4, 2, 2,
                operation(1, 0, 0, 0), operation(0, 1, 0, 0));
        assertThrows(BuildPlanTransformer.TransformException.class,
                () -> BuildPlanTransformer.transform(unordered, position(0, 64, 0), 4096));

        BuildPlanDocument tooMany = plan("WORLD_ORIGIN", 4, 2, 2, operation(0, 0, 0, 0));
        assertThrows(BuildPlanTransformer.TransformException.class,
                () -> BuildPlanTransformer.transform(tooMany, position(0, 64, 0), 0));
    }

    private static BuildPlanDocument plan(String strategy, int width, int height, int depth,
                                          BuildPlanDocument.Operation... operations) {
        BuildPlanDocument plan = new BuildPlanDocument();
        plan.originStrategy = strategy;
        plan.metadata = new BuildPlanDocument.Metadata();
        plan.metadata.dimensions = new BuildPlanDocument.Dimensions();
        plan.metadata.dimensions.width = width;
        plan.metadata.dimensions.height = height;
        plan.metadata.dimensions.depth = depth;
        plan.operations = new ArrayList<>(List.of(operations));
        return plan;
    }

    private static BuildPlanDocument.Operation operation(int sequence, int x, int y, int z) {
        BuildPlanDocument.Operation operation = new BuildPlanDocument.Operation();
        operation.sequence = sequence;
        operation.kind = "PLACE_BLOCK";
        operation.blockId = "minecraft:stone";
        operation.position = position(x, y, z);
        operation.blockState = new HashMap<>();
        return operation;
    }

    private static BuildPlanDocument.Position position(int x, int y, int z) {
        BuildPlanDocument.Position position = new BuildPlanDocument.Position();
        position.x = x;
        position.y = y;
        position.z = z;
        return position;
    }
}
