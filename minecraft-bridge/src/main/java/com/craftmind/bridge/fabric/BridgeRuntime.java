package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeCapabilities;
import com.craftmind.bridge.protocol.BridgeProtocol;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the bridge lifecycle, the one per-server origin, and the server-thread construction service. */
final class BridgeRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("CraftMindBridge");
    private final CraftMindBridgeConfig config;
    private final BridgeIdentity identity;
    private final TrustedClientRepository clients;
    private final BridgeAuthenticationService authentication;
    private final MinecraftBlockSupport blockSupport = new MinecraftBlockSupport();
    private volatile BridgeHttpServer httpServer;
    private volatile MinecraftServer minecraftServer;
    private volatile String worldSessionId;
    private volatile BridgeBuildOrigin buildOrigin;
    private volatile BridgeExecutionService executionService;
    private volatile boolean compatibleServerModSet;

    private BridgeRuntime(CraftMindBridgeConfig config, BridgeIdentity identity, TrustedClientRepository clients) {
        this.config = config;
        this.identity = identity;
        this.clients = clients;
        this.authentication = new BridgeAuthenticationService(identity, clients);
    }

    static BridgeRuntime create(Path configDirectory) throws Exception {
        CraftMindBridgeConfig config = CraftMindBridgeConfig.load(configDirectory);
        BridgeIdentity identity;
        try {
            identity = new BridgeIdentityStore().loadOrCreate(config);
        } catch (BridgeIdentityStore.IdentityStoreException error) {
            throw new IllegalStateException(error.getMessage());
        }
        TrustedClientRepository clients = new FileTrustedClientRepository(config.dataDirectory());
        return new BridgeRuntime(config, identity, clients);
    }

    synchronized void start(MinecraftServer server) {
        if (httpServer != null) return;
        this.minecraftServer = server;
        this.worldSessionId = UUID.randomUUID().toString();
        this.buildOrigin = null;
        this.compatibleServerModSet = ServerModSafetyPolicy.supports(
                FabricLoader.getInstance().getAllMods().stream()
                        .map(container -> container.getMetadata().getId())
                        .collect(Collectors.toSet()));
        try {
            BridgeExecutionRecordStore recordStore = new BridgeExecutionRecordStore(config.dataDirectory());
            ConstructionCoordinator coordinator = new ConstructionCoordinator(recordStore, config.executionLimits());
            executionService = new BridgeExecutionService(config, blockSupport, coordinator,
                    () -> {
                        BridgeBuildOrigin current = buildOrigin;
                        return current == null ? null : current.copy();
                    }, compatibleServerModSet, server);
        } catch (Exception error) {
            executionService = null;
            LOGGER.error("Construction status store unavailable code=EXECUTION_STORE_UNAVAILABLE; placement capability is disabled.");
        }
        httpServer = new BridgeHttpServer(config, identity, authentication,
                this::capabilities, this::publicCapabilities, executionService);
        try {
            httpServer.start();
            LOGGER.info("CraftMind Bridge server hooks ready; protocol 1, construction capability {}.",
                    executionService != null && executionService.constructionAvailable() ? "available" : "disabled");
        } catch (Exception error) {
            httpServer.close();
            httpServer = null;
            executionService = null;
            LOGGER.error("CraftMind Bridge listener unavailable code=LISTENER_START_FAILED; no network listener was opened.");
        }
    }

    synchronized BridgeAuthenticationService.PairingCode openPairing() throws BridgeAuthenticationService.ServiceException {
        if (httpServer == null) throw new BridgeAuthenticationService.ServiceException(BridgeProtocol.ErrorCode.PAIRING_CLOSED);
        return authentication.openPairing();
    }

    synchronized void closePairing() {
        authentication.closePairing();
    }

    synchronized boolean revokeClient(String clientId) throws BridgeAuthenticationService.ServiceException {
        return authentication.revoke(clientId);
    }

    synchronized List<TrustedBridgeClient> trustedClients() {
        return new ArrayList<>(clients.all());
    }

    synchronized String bridgeId() { return identity.bridgeId(); }
    synchronized String fingerprint() { return identity.fingerprint(); }
    synchronized boolean isListening() { return httpServer != null; }

    synchronized BridgeBuildOrigin selectOrigin(ServerWorld world, BlockPos playerFeetPosition) {
        if (worldSessionId == null || world == null || playerFeetPosition == null) return null;
        BridgeBuildOrigin selected = BridgeBuildOrigin.selected(world, playerFeetPosition, worldSessionId);
        buildOrigin = selected;
        return selected.copy();
    }

    synchronized void clearOrigin() {
        buildOrigin = null;
    }

    synchronized BridgeBuildOrigin currentOrigin() {
        BridgeBuildOrigin current = buildOrigin;
        return current == null ? null : current.copy();
    }

    void endServerTick() {
        BridgeExecutionService service = executionService;
        if (service != null) service.tick();
    }

    void serverStopping() {
        BridgeExecutionService service = executionService;
        if (service != null) service.serverStopping();
        executionService = null;
        buildOrigin = null;
        worldSessionId = null;
        minecraftServer = null;
    }

    @Override public synchronized void close() {
        BridgeExecutionService service = executionService;
        if (service != null) service.serverStopping();
        executionService = null;
        buildOrigin = null;
        worldSessionId = null;
        minecraftServer = null;
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        } else {
            authentication.close();
        }
    }

    private BridgeCapabilities capabilities() {
        BridgeCapabilities result = baseCapabilities();
        BridgeExecutionService service = executionService;
        BridgeBuildOrigin origin = currentOrigin();
        boolean worldAvailable = minecraftServer != null && service != null;
        boolean execute = service != null && service.constructionAvailable();
        result.worldAccess = worldAvailable;
        result.constructionExecute = execute;
        result.cancellation = execute;
        result.maximumValidatedOperations = config.executionLimits().maxOperations();
        result.maximumRequestBytes = config.executionLimits().maxRequestBytes();
        result.dimensionId = origin == null ? null : origin.dimensionId;
        result.worldSessionId = origin == null ? null : origin.worldSessionId;
        return result;
    }

    private BridgeCapabilities publicCapabilities() {
        BridgeCapabilities result = baseCapabilities();
        result.worldAccess = false;
        result.constructionExecute = false;
        result.cancellation = false;
        result.maximumValidatedOperations = config.executionLimits().maxOperations();
        result.maximumRequestBytes = config.executionLimits().maxRequestBytes();
        result.dimensionId = null;
        result.worldSessionId = null;
        return result;
    }

    private BridgeCapabilities baseCapabilities() {
        BridgeCapabilities result = new BridgeCapabilities();
        result.protocolVersion = BridgeProtocol.VERSION;
        result.bridgeId = identity.bridgeId();
        result.identityFingerprint = identity.fingerprint();
        result.bridgeVersion = modVersion();
        result.minecraftVersion = "1.20.1";
        result.loaderName = "Fabric";
        result.loaderVersion = modVersion("fabricloader");
        result.supportedBuildPlanSchemaVersions = new ArrayList<>();
        result.supportedBuildPlanSchemaVersions.add(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION);
        return result;
    }

    private String modVersion() {
        return modVersion("craftmind_bridge");
    }

    private String modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(ModContainer::getMetadata)
                .map(metadata -> metadata.getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
