package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeProtocol;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/** Vanilla-only preflight and placement adapter. Called exclusively on the Minecraft server thread. */
final class MinecraftServerWorldExecutionAccess implements ExecutionWorldAccess {
    private final MinecraftServer server;
    private final ServerWorld world;
    private final MinecraftBlockSupport blockSupport;

    MinecraftServerWorldExecutionAccess(MinecraftServer server, ServerWorld world, MinecraftBlockSupport blockSupport) {
        this.server = server;
        this.world = world;
        this.blockSupport = blockSupport;
    }

    @Override
    public String preflight(List<BuildPlanTransformer.TransformedOperation> operations) {
        if (operations == null || operations.isEmpty() || operations.size() > BridgeProtocol.MAX_OPERATIONS) {
            return BridgeProtocol.ErrorCode.BUILD_TOO_LARGE.name();
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        Map<BlockPos, BlockState> plannedStates = new HashMap<>();
        WorldView projectedView = projectedView(plannedStates);
        for (BuildPlanTransformer.TransformedOperation operation : operations) {
            String problem = inspect(operation, true);
            if (problem != null) return problem;
            BlockState target = blockSupport.resolveBlockState(operation.blockId, operation.blockState);
            BlockPos position = new BlockPos(operation.position.x, operation.position.y, operation.position.z);
            if (target == null || !target.canPlaceAt(projectedView, position)) {
                return BridgeProtocol.ErrorCode.PLACEMENT_REJECTED.name();
            }
            plannedStates.put(position.toImmutable(), target);
            minX = Math.min(minX, operation.position.x);
            minY = Math.min(minY, operation.position.y);
            minZ = Math.min(minZ, operation.position.z);
            maxX = Math.max(maxX, operation.position.x);
            maxY = Math.max(maxY, operation.position.y);
            maxZ = Math.max(maxZ, operation.position.z);
        }
        Box occupiedVolume = new Box(minX, minY, minZ, (double) maxX + 1.0, (double) maxY + 1.0, (double) maxZ + 1.0);
        if (!world.getOtherEntities(null, occupiedVolume, entity -> !entity.isSpectator()).isEmpty()) {
            return BridgeProtocol.ErrorCode.ENTITY_IN_BUILD_AREA.name();
        }
        return null;
    }

    @Override
    public PlacementResult place(BuildPlanTransformer.TransformedOperation operation) {
        String problem = inspect(operation, true);
        if (problem != null) return PlacementResult.failed(problem);
        BlockState target = blockSupport.resolveBlockState(operation.blockId, operation.blockState);
        if (target == null) return PlacementResult.failed(BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN.name());
        BlockPos position = new BlockPos(operation.position.x, operation.position.y, operation.position.z);
        if (!target.canPlaceAt(world, position)) {
            return PlacementResult.failed(BridgeProtocol.ErrorCode.PLACEMENT_REJECTED.name());
        }
        Box cell = new Box(position);
        if (!world.getOtherEntities(null, cell, entity -> !entity.isSpectator()).isEmpty()) {
            return PlacementResult.failed(BridgeProtocol.ErrorCode.ENTITY_IN_BUILD_AREA.name());
        }
        boolean changed;
        try {
            changed = world.setBlockState(position, target, Block.NOTIFY_ALL);
        } catch (RuntimeException error) {
            return PlacementResult.failed(BridgeProtocol.ErrorCode.PLACEMENT_REJECTED.name());
        }
        if (!changed) return PlacementResult.failed(BridgeProtocol.ErrorCode.PLACEMENT_REJECTED.name());
        BlockState placed = world.getBlockState(position);
        return placed.getBlock() == target.getBlock() ? PlacementResult.placed()
                : PlacementResult.failed(BridgeProtocol.ErrorCode.PLACEMENT_REJECTED.name());
    }

    /** Overlays only planned block reads so vanilla canPlaceAt checks can see earlier semantic operations without writes. */
    private WorldView projectedView(Map<BlockPos, BlockState> plannedStates) {
        return (WorldView) Proxy.newProxyInstance(
                WorldView.class.getClassLoader(),
                new Class<?>[]{WorldView.class},
                (proxy, method, arguments) -> {
                    if ("getBlockState".equals(method.getName()) && arguments != null && arguments.length == 1 &&
                            arguments[0] instanceof BlockPos position) {
                        BlockState planned = plannedStates.get(position);
                        if (planned != null) return planned;
                    }
                    try {
                        return method.invoke(world, arguments);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    private String inspect(BuildPlanTransformer.TransformedOperation operation, boolean requireAir) {
        if (operation == null || operation.position == null) return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN.name();
        int x = operation.position.x;
        int y = operation.position.y;
        int z = operation.position.z;
        if (y < world.getBottomY() || y >= world.getTopY() ||
                x < -BridgeExecutionLimits.MAX_COORDINATE || x > BridgeExecutionLimits.MAX_COORDINATE ||
                z < -BridgeExecutionLimits.MAX_COORDINATE || z > BridgeExecutionLimits.MAX_COORDINATE) {
            return BridgeProtocol.ErrorCode.WORLD_BOUNDS_REJECTED.name();
        }
        BlockPos position = new BlockPos(x, y, z);
        if (!world.getWorldBorder().contains(position)) return BridgeProtocol.ErrorCode.WORLD_BOUNDS_REJECTED.name();
        if (!world.isChunkLoaded(position)) return BridgeProtocol.ErrorCode.CHUNK_NOT_LOADED.name();
        if (server.getSpawnProtectionRadius() > 0 && world.getRegistryKey().equals(World.OVERWORLD)) {
            BlockPos spawn = server.getOverworld().getSpawnPos();
            int radius = server.getSpawnProtectionRadius();
            if (Math.abs((long) x - spawn.getX()) <= radius && Math.abs((long) z - spawn.getZ()) <= radius) {
                return BridgeProtocol.ErrorCode.SPAWN_PROTECTED.name();
            }
        }
        if (requireAir && !world.getBlockState(position).isAir()) {
            return BridgeProtocol.ErrorCode.BLOCK_OCCUPIED.name();
        }
        if (blockSupport.resolveBlockState(operation.blockId, operation.blockState) == null) {
            return BridgeProtocol.ErrorCode.INVALID_BUILD_PLAN.name();
        }
        return null;
    }
}
