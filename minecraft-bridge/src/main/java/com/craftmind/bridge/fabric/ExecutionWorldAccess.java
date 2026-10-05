package com.craftmind.bridge.fabric;

import java.util.List;

/** Narrow world adapter used by the coordinator; no commands, player input, or arbitrary operations exist. */
interface ExecutionWorldAccess {
    /** Validates every target before any world write; null means the entire plan passed preflight. */
    String preflight(List<BuildPlanTransformer.TransformedOperation> operations);

    /** Performs one exact vanilla block-state placement and returns measured success or a typed failure. */
    PlacementResult place(BuildPlanTransformer.TransformedOperation operation);

    final class PlacementResult {
        final boolean placed;
        final String reasonCode;
        private PlacementResult(boolean placed, String reasonCode) {
            this.placed = placed;
            this.reasonCode = reasonCode;
        }
        static PlacementResult placed() { return new PlacementResult(true, null); }
        static PlacementResult failed(String reasonCode) { return new PlacementResult(false, reasonCode); }
    }
}
