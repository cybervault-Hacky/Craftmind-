package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeProtocol;
import com.craftmind.bridge.protocol.BridgeProtocolCodec;
import com.craftmind.bridge.protocol.BridgeProtocolException;
import com.craftmind.bridge.protocol.BridgeExecutionRequest;
import com.craftmind.bridge.protocol.BuildPlanContractValidator;
import com.craftmind.bridge.protocol.BuildPlanDocument;
import com.craftmind.bridge.protocol.ExecutionProtocol;
import com.google.gson.JsonObject;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/** Authenticated execution orchestration. Every read/write touching a Minecraft world runs on its server thread. */
final class BridgeExecutionService {
    private static final long SERVER_TASK_TIMEOUT_SECONDS = 10L;
    private final CraftMindBridgeConfig config;
    private final MinecraftBlockSupport blockSupport;
    private final ConstructionCoordinator coordinator;
    private final Supplier<BridgeBuildOrigin> originSupplier;
    private final boolean compatibleServerModSet;
    private volatile MinecraftServer server;
    private volatile boolean stopping;

    BridgeExecutionService(CraftMindBridgeConfig config, MinecraftBlockSupport blockSupport,
                           ConstructionCoordinator coordinator, Supplier<BridgeBuildOrigin> originSupplier,
                           boolean compatibleServerModSet, MinecraftServer server) {
        this.config = config;
        this.blockSupport = blockSupport;
        this.coordinator = coordinator;
        this.originSupplier = originSupplier;
        this.compatibleServerModSet = compatibleServerModSet;
        this.server = server;
    }

    boolean constructionAvailable() {
        return config.constructionEnabled() && compatibleServerModSet && coordinator != null &&
                server != null && !stopping && originSupplier.get() != null;
    }

    ConstructionCoordinator.PrepareResult prepare(String clientId, JsonObject payload, int bodyBytes) {
        if (!constructionAvailable()) return ConstructionCoordinator.PrepareResult.rejected(
                BridgeProtocol.ErrorCode.CONSTRUCTION_DISABLED.name());
        BridgeProtocol.ErrorCode validation = BuildPlanContractValidator.validateExecutionPayload(
                payload, bodyBytes, config.executionLimits().maxOperations(),
                config.executionLimits().maxRequestBytes(), blockSupport);
        if (validation != null) {
            BuildPlanContractValidator.BlockValidationFailure blockFailure =
                    validation == BridgeProtocol.ErrorCode.UNSUPPORTED_BLOCK ||
                            validation == BridgeProtocol.ErrorCode.UNSUPPORTED_BLOCK_STATE
                            ? BuildPlanContractValidator.findBlockValidationFailure(payload, blockSupport)
                            : null;
            return ConstructionCoordinator.PrepareResult.rejected(validation.name(), blockFailure);
        }

        final BridgeExecutionRequest request;
        try {
            request = BridgeProtocolCodec.fromJson(payload, BridgeExecutionRequest.class);
        } catch (RuntimeException error) {
            return ConstructionCoordinator.PrepareResult.rejected(BridgeProtocol.ErrorCode.INVALID_BUILD_REQUEST.name());
        }
        BridgeBuildOrigin selected = originSupplier.get();
        String originError = validateRequestedOrigin(request, selected);
        if (originError != null) return ConstructionCoordinator.PrepareResult.rejected(originError);
        final List<BuildPlanTransformer.TransformedOperation> operations;
        try {
            operations = BuildPlanTransformer.transform(request.buildPlan, selected.position,
                    config.executionLimits().maxOperations());
        } catch (BuildPlanTransformer.TransformException error) {
            return ConstructionCoordinator.PrepareResult.rejected(error.reasonCode());
        }
        final String payloadHash;
        try {
            payloadHash = ConstructionCoordinator.hashPayload(payload.toString());
        } catch (GeneralSecurityException error) {
            return ConstructionCoordinator.PrepareResult.rejected(BridgeProtocol.ErrorCode.INTERNAL_ERROR.name());
        }
        return onServer(() -> {
            BridgeBuildOrigin current = originSupplier.get();
            if (!sameOrigin(selected, current)) {
                return ConstructionCoordinator.PrepareResult.rejected(BridgeProtocol.ErrorCode.ORIGIN_CHANGED.name());
            }
            ServerWorld world = findWorld(current.dimensionId);
            if (world == null) return ConstructionCoordinator.PrepareResult.rejected(BridgeProtocol.ErrorCode.WORLD_UNAVAILABLE.name());
            ExecutionWorldAccess worldAccess = new MinecraftServerWorldExecutionAccess(server, world, blockSupport);
            return coordinator.prepare(clientId, request, payloadHash, operations, current, worldAccess);
        }, () -> ConstructionCoordinator.PrepareResult.rejected(BridgeProtocol.ErrorCode.WORLD_UNAVAILABLE.name()));
    }

    ConstructionCoordinator.StartResult start(String clientId, String executionId, String token) {
        if (server == null || stopping || coordinator == null) {
            return ConstructionCoordinator.StartResult.rejected(BridgeProtocol.ErrorCode.WORLD_UNAVAILABLE.name());
        }
        return onServer(() -> {
            ExecutionProtocol.ExecutionSnapshot snapshot = coordinator.status(clientId, executionId);
            BridgeBuildOrigin current = originSupplier.get();
            String admissionError = null;
            if (!constructionAvailable()) {
                admissionError = BridgeProtocol.ErrorCode.CONSTRUCTION_DISABLED.name();
            } else if (snapshot == null || !sameOrigin(snapshot, current)) {
                admissionError = BridgeProtocol.ErrorCode.ORIGIN_CHANGED.name();
            }
            ServerWorld world = current == null ? null : findWorld(current.dimensionId);
            if (world == null && admissionError == null) admissionError = BridgeProtocol.ErrorCode.WORLD_UNAVAILABLE.name();
            ExecutionWorldAccess worldAccess = world == null ? null
                    : new MinecraftServerWorldExecutionAccess(server, world, blockSupport);
            return coordinator.start(clientId, executionId, token, worldAccess, admissionError);
        }, () -> ConstructionCoordinator.StartResult.rejected(BridgeProtocol.ErrorCode.WORLD_UNAVAILABLE.name()));
    }

    ExecutionProtocol.ExecutionSnapshot status(String clientId, String executionId) {
        return coordinator == null ? null : coordinator.status(clientId, executionId);
    }

    ConstructionCoordinator.CancelResult cancel(String clientId, String executionId) {
        return coordinator == null ? ConstructionCoordinator.CancelResult.notFound() : coordinator.cancel(clientId, executionId);
    }

    void tick() {
        if (coordinator != null && !stopping) coordinator.tick(originSupplier.get());
    }

    void serverStopping() {
        stopping = true;
        if (coordinator != null) coordinator.serverStopping();
        server = null;
    }

    private String validateRequestedOrigin(BridgeExecutionRequest request, BridgeBuildOrigin selected) {
        if (request == null || request.origin == null || selected == null ||
                !"BRIDGE_SELECTED_SAFE".equals(request.origin.kind) || request.origin.position != null) {
            return BridgeProtocol.ErrorCode.UNSUPPORTED_ORIGIN.name();
        }
        if (!selected.dimensionId.equals(request.origin.dimensionId)) return BridgeProtocol.ErrorCode.ORIGIN_CHANGED.name();
        if (!selected.worldSessionId.equals(request.origin.worldSessionId)) {
            return BridgeProtocol.ErrorCode.WORLD_SESSION_MISMATCH.name();
        }
        return null;
    }

    private boolean sameOrigin(BridgeBuildOrigin first, BridgeBuildOrigin second) {
        return first != null && second != null && first.dimensionId.equals(second.dimensionId) &&
                first.worldSessionId.equals(second.worldSessionId) &&
                first.position.x == second.position.x && first.position.y == second.position.y && first.position.z == second.position.z;
    }

    private boolean sameOrigin(ExecutionProtocol.ExecutionSnapshot prepared, BridgeBuildOrigin current) {
        return prepared != null && current != null && prepared.resolvedOrigin != null &&
                prepared.dimensionId.equals(current.dimensionId) && prepared.worldSessionId.equals(current.worldSessionId) &&
                prepared.resolvedOrigin.x == current.position.x && prepared.resolvedOrigin.y == current.position.y &&
                prepared.resolvedOrigin.z == current.position.z;
    }

    private ServerWorld findWorld(String dimensionId) {
        Identifier identifier = Identifier.tryParse(dimensionId);
        if (identifier == null) return null;
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, identifier);
        return server == null ? null : server.getWorld(worldKey);
    }

    private <T> T onServer(java.util.concurrent.Callable<T> task, Supplier<T> unavailable) {
        MinecraftServer current = server;
        if (current == null || stopping) return unavailable.get();
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            current.execute(() -> {
                if (future.isCancelled() || stopping) return;
                try {
                    future.complete(task.call());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            return unavailable.get();
        }
        try {
            return future.get(SERVER_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            return unavailable.get();
        } catch (ExecutionException | TimeoutException error) {
            future.cancel(false);
            return unavailable.get();
        }
    }
}
