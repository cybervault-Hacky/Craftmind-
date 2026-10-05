package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeCapabilities;
import com.craftmind.bridge.protocol.BridgeProtocol;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the bridge lifecycle for one dedicated Fabric server process. */
final class BridgeRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("CraftMindBridge");
    private final CraftMindBridgeConfig config;
    private final BridgeIdentity identity;
    private final TrustedClientRepository clients;
    private final BridgeAuthenticationService authentication;
    private BridgeHttpServer httpServer;

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

    synchronized void start() {
        if (httpServer != null) return;
        BridgeCapabilities capabilities = capabilities();
        httpServer = new BridgeHttpServer(config, identity, authentication, capabilities, new MinecraftBlockSupport());
        try {
            httpServer.start();
        } catch (Exception error) {
            httpServer.close();
            httpServer = null;
            LOGGER.error("CraftMind Bridge listener unavailable code=LISTENER_START_FAILED; Minecraft actions remain disabled.");
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

    @Override public synchronized void close() {
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        } else {
            authentication.close();
        }
    }

    private BridgeCapabilities capabilities() {
        BridgeCapabilities result = new BridgeCapabilities();
        result.protocolVersion = BridgeProtocol.VERSION;
        result.bridgeId = identity.bridgeId();
        result.identityFingerprint = identity.fingerprint();
        result.bridgeVersion = modVersion();
        result.minecraftVersion = "1.20.1";
        result.loaderName = "Fabric";
        result.loaderVersion = modVersion("fabricloader");
        result.worldAccess = false;
        result.constructionExecute = false;
        result.cancellation = false;
        result.maximumValidatedOperations = BridgeProtocol.MAX_OPERATIONS;
        result.maximumRequestBytes = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES;
        result.supportedBuildPlanSchemaVersions = new ArrayList<>();
        result.supportedBuildPlanSchemaVersions.add(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION);
        result.dimensionId = null;
        result.worldSessionId = null;
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
