package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeProtocol;
import com.craftmind.bridge.protocol.BuildPlanDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure BuildPlan-v2 local-to-world transform; operation order is intentionally never sorted. */
public final class BuildPlanTransformer {
    private BuildPlanTransformer() { }

    public static List<TransformedOperation> transform(BuildPlanDocument plan,
                                                        BuildPlanDocument.Position anchor,
                                                        int maximumOperations) throws TransformException {
        if (plan == null || anchor == null || plan.metadata == null || plan.metadata.dimensions == null ||
                plan.operations == null || plan.operations.isEmpty() || plan.operations.size() > maximumOperations ||
                maximumOperations < 1 || maximumOperations > BridgeProtocol.MAX_OPERATIONS) {
            throw new TransformException("INVALID_BUILD_PLAN");
        }
        int width = plan.metadata.dimensions.width;
        int height = plan.metadata.dimensions.height;
        int depth = plan.metadata.dimensions.depth;
        if (width < 1 || width > BridgeProtocol.MAX_DIMENSION_WIDTH ||
                height < 1 || height > BridgeProtocol.MAX_DIMENSION_HEIGHT ||
                depth < 1 || depth > BridgeProtocol.MAX_DIMENSION_DEPTH) {
            throw new TransformException("INVALID_BUILD_PLAN");
        }
        boolean centeredGround = "CENTERED_GROUND".equals(plan.originStrategy);
        if (!centeredGround && !"WORLD_ORIGIN".equals(plan.originStrategy)) {
            throw new TransformException("INVALID_BUILD_PLAN");
        }
        List<TransformedOperation> result = new ArrayList<>(plan.operations.size());
        Set<Coordinate> occupied = new HashSet<>(plan.operations.size());
        for (int index = 0; index < plan.operations.size(); index++) {
            BuildPlanDocument.Operation operation = plan.operations.get(index);
            if (operation == null || operation.sequence != index || !"PLACE_BLOCK".equals(operation.kind) ||
                    operation.position == null || operation.blockId == null || operation.blockState == null) {
                throw new TransformException("INVALID_BUILD_PLAN");
            }
            int x = operation.position.x;
            int y = operation.position.y;
            int z = operation.position.z;
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
                throw new TransformException("INVALID_BUILD_PLAN");
            }
            long worldX = (long) anchor.x + x - (centeredGround ? width / 2 : 0);
            long worldY = (long) anchor.y + y;
            long worldZ = (long) anchor.z + z - (centeredGround ? depth / 2 : 0);
            if (worldX < -BridgeExecutionLimits.MAX_COORDINATE || worldX > BridgeExecutionLimits.MAX_COORDINATE ||
                    worldZ < -BridgeExecutionLimits.MAX_COORDINATE || worldZ > BridgeExecutionLimits.MAX_COORDINATE ||
                    worldY < -BridgeExecutionLimits.MAX_TRANSFORM_Y || worldY > BridgeExecutionLimits.MAX_TRANSFORM_Y) {
                throw new TransformException("WORLD_BOUNDS_REJECTED");
            }
            Coordinate coordinate = new Coordinate((int) worldX, (int) worldY, (int) worldZ);
            if (!occupied.add(coordinate)) throw new TransformException("INVALID_BUILD_PLAN");
            result.add(new TransformedOperation(index, operation.blockId, Map.copyOf(operation.blockState), coordinate));
        }
        return List.copyOf(result);
    }

    public static final class TransformedOperation {
        public final int sequence;
        public final String blockId;
        public final Map<String, String> blockState;
        public final Coordinate position;

        private TransformedOperation(int sequence, String blockId, Map<String, String> blockState, Coordinate position) {
            this.sequence = sequence;
            this.blockId = blockId;
            this.blockState = blockState;
            this.position = position;
        }
    }

    public static final class Coordinate {
        public final int x;
        public final int y;
        public final int z;

        public Coordinate(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Coordinate)) return false;
            Coordinate coordinate = (Coordinate) other;
            return x == coordinate.x && y == coordinate.y && z == coordinate.z;
        }

        @Override public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }

    public static final class TransformException extends Exception {
        private final String reasonCode;
        public TransformException(String reasonCode) {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }
        public String reasonCode() { return reasonCode; }
    }
}
