package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BuildPlanDocument;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** An operator-selected feet-level anchor. It is memory-only and invalidated on every server start. */
final class BridgeBuildOrigin {
    final String dimensionId;
    final String worldSessionId;
    final BuildPlanDocument.Position position;

    BridgeBuildOrigin(String dimensionId, String worldSessionId, BuildPlanDocument.Position position) {
        this.dimensionId = dimensionId;
        this.worldSessionId = worldSessionId;
        this.position = BridgeExecutionRecord.copyPosition(position);
    }

    static BridgeBuildOrigin selected(ServerWorld world, BlockPos position, String worldSessionId) {
        BuildPlanDocument.Position anchor = new BuildPlanDocument.Position();
        anchor.x = position.getX();
        anchor.y = position.getY();
        anchor.z = position.getZ();
        return new BridgeBuildOrigin(world.getRegistryKey().getValue().toString(), worldSessionId, anchor);
    }

    BridgeBuildOrigin copy() {
        return new BridgeBuildOrigin(dimensionId, worldSessionId, position);
    }
}
