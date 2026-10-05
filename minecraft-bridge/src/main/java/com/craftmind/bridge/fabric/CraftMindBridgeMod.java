package com.craftmind.bridge.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-only Fabric entry point. Construction runs in bounded server-tick batches after explicit preflight. */
public final class CraftMindBridgeMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("CraftMindBridge");
    private static volatile BridgeRuntime runtime;

    @Override
    public void onInitialize() {
        try {
            runtime = BridgeRuntime.create(FabricLoader.getInstance().getConfigDir());
            LOGGER.info("CraftMind Bridge identity initialized; use /craftmind identity to verify the public fingerprint.");
        } catch (Exception error) {
            runtime = null;
            String reason = error.getMessage() == null ? "BRIDGE_INITIALIZATION_FAILED" : error.getMessage();
            LOGGER.error("CraftMind Bridge is disabled code={}; no network listener was opened.", reason);
        }
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BridgeRuntime current = runtime;
            if (current != null) current.start(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            BridgeRuntime current = runtime;
            if (current != null) current.endServerTick();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            BridgeRuntime current = runtime;
            if (current != null) {
                current.serverStopping();
                current.close();
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("craftmind")
                        .then(CommandManager.literal("identity")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(context -> showIdentity(context.getSource())))
                        .then(CommandManager.literal("pair")
                                .then(CommandManager.literal("open")
                                        .requires(source -> source.hasPermissionLevel(2))
                                        .executes(context -> openPairing(context.getSource())))
                                .then(CommandManager.literal("close")
                                        .requires(source -> source.hasPermissionLevel(2))
                                        .executes(context -> closePairing(context.getSource())))
                                .then(CommandManager.literal("list")
                                        .requires(source -> source.hasPermissionLevel(2))
                                        .executes(context -> listClients(context.getSource())))
                                .then(CommandManager.literal("revoke")
                                        .requires(source -> source.hasPermissionLevel(2))
                                        .then(CommandManager.argument("clientId", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .executes(context -> revokeClient(context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "clientId"))))))
                        .then(CommandManager.literal("origin")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(CommandManager.literal("set")
                                        .executes(context -> selectOrigin(context.getSource())))
                                .then(CommandManager.literal("clear")
                                        .executes(context -> clearOrigin(context.getSource())))
                                .then(CommandManager.literal("status")
                                        .executes(context -> showOrigin(context.getSource()))))));
    }

    private int showIdentity(ServerCommandSource source) {
        BridgeRuntime current = runtime;
        if (current == null) return unavailable(source);
        source.sendFeedback(() -> Text.literal("CraftMind Bridge " + current.bridgeId() +
                " · TLS SHA-256 " + current.fingerprint() + " · protocol 1 · listener " +
                (current.isListening() ? "active" : "not active")), false);
        return 1;
    }

    private int openPairing(ServerCommandSource source) {
        BridgeRuntime current = runtime;
        if (current == null) return unavailable(source);
        if (!(source.getEntity() instanceof ServerPlayerEntity)) {
            source.sendError(Text.literal("Run this from an in-game operator account so the one-time code is not written to server logs."));
            return 0;
        }
        try {
            BridgeAuthenticationService.PairingCode code = current.openPairing();
            source.sendFeedback(() -> Text.literal("Private pairing code: " + code.value +
                    " · expires in 3 minutes · enter it only in CraftMind after checking /craftmind identity."), false);
            return 1;
        } catch (BridgeAuthenticationService.ServiceException error) {
            source.sendError(Text.literal("Pairing could not open (" + error.getCode().name() + ")."));
            return 0;
        }
    }

    private int closePairing(ServerCommandSource source) {
        BridgeRuntime current = runtime;
        if (current == null) return unavailable(source);
        current.closePairing();
        source.sendFeedback(() -> Text.literal("CraftMind Bridge pairing window closed."), false);
        return 1;
    }

    private int listClients(ServerCommandSource source) {
        BridgeRuntime current = runtime;
        if (current == null) return unavailable(source);
        java.util.List<TrustedBridgeClient> clients = current.trustedClients();
        source.sendFeedback(() -> Text.literal(clients.isEmpty()
                ? "No CraftMind Android devices are trusted."
                : "Trusted CraftMind devices (" + clients.size() + "):"), false);
        for (TrustedBridgeClient client : clients) {
            source.sendFeedback(() -> Text.literal(client.displayName + " · " + client.clientId), false);
        }
        return 1;
    }

    private int revokeClient(ServerCommandSource source, String clientId) {
        BridgeRuntime current = runtime;
        if (current == null) return unavailable(source);
        try {
            if (!current.revokeClient(clientId)) {
                source.sendError(Text.literal("That client ID is not trusted by this bridge."));
                return 0;
            }
            source.sendFeedback(() -> Text.literal("CraftMind Android pairing revoked. Existing sessions are invalid."), false);
            return 1;
        } catch (BridgeAuthenticationService.ServiceException error) {
            source.sendError(Text.literal("Revocation failed (" + error.getCode().name() + ")."));
            return 0;
        }
    }

    private int selectOrigin(ServerCommandSource source) {
        BridgeRuntime current = runtime;
        if (current == null) return unavailable(source);
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("Run /craftmind origin set in game from an operator account; no coordinates can be entered remotely."));
            return 0;
        }
        BridgeBuildOrigin origin = current.selectOrigin(source.getWorld(), player.getBlockPos());
        if (origin == null) {
            source.sendError(Text.literal("The server world is not ready; no build origin was selected."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("CraftMind build origin selected at " + origin.position.x + ", " +
                origin.position.y + ", " + origin.position.z + " in " + origin.dimensionId +
                ". This in-memory origin expires when the server restarts."), false);
        return 1;
    }

    private int clearOrigin(ServerCommandSource source) {
        BridgeRuntime current = runtime;
        if (current == null) return unavailable(source);
        current.clearOrigin();
        source.sendFeedback(() -> Text.literal("CraftMind build origin cleared; construction capability is disabled until an operator selects another."), false);
        return 1;
    }

    private int showOrigin(ServerCommandSource source) {
        BridgeRuntime current = runtime;
        if (current == null) return unavailable(source);
        BridgeBuildOrigin origin = current.currentOrigin();
        if (origin == null) {
            source.sendFeedback(() -> Text.literal("No CraftMind build origin is selected."), false);
        } else {
            source.sendFeedback(() -> Text.literal("CraftMind build origin: " + origin.position.x + ", " +
                    origin.position.y + ", " + origin.position.z + " in " + origin.dimensionId +
                    " · memory-only until server restart."), false);
        }
        return 1;
    }

    private int unavailable(ServerCommandSource source) {
        source.sendError(Text.literal("CraftMind Bridge is unavailable; check the server operator setup."));
        return 0;
    }
}
