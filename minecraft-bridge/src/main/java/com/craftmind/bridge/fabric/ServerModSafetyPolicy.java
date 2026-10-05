package com.craftmind.bridge.fabric;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The placement adapter supports only the pinned vanilla/Fabric API stack. Unknown mods fail closed because
 * protection, claims, replacement placement hooks, or other world mutation policies cannot be delegated to.
 */
final class ServerModSafetyPolicy {
    private static final Set<String> BASE_MODS = Set.of(
            "minecraft", "fabricloader", "fabric-api", "craftmind_bridge");
    private static final Set<String> SUPPORTED_FABRIC_API_MODULES = Set.of(
            "fabric-api-base", "fabric-api-lookup-api-v1", "fabric-biome-api-v1", "fabric-block-api-v1",
            "fabric-block-view-api-v2", "fabric-blockrenderlayer-v1", "fabric-client-tags-api-v1",
            "fabric-command-api-v1", "fabric-command-api-v2", "fabric-commands-v0", "fabric-containers-v0",
            "fabric-content-registries-v0",
            "fabric-convention-tags-v1", "fabric-crash-report-info-v1", "fabric-data-attachment-api-v1",
            "fabric-data-generation-api-v1", "fabric-dimensions-v1", "fabric-entity-events-v1",
            "fabric-events-interaction-v0", "fabric-events-lifecycle-v0", "fabric-game-rule-api-v1",
            "fabric-item-api-v1", "fabric-item-group-api-v1", "fabric-key-binding-api-v1", "fabric-keybindings-v0",
            "fabric-lifecycle-events-v1", "fabric-loot-api-v2", "fabric-loot-tables-v1", "fabric-message-api-v1",
            "fabric-mining-level-api-v1", "fabric-model-loading-api-v1", "fabric-models-v0", "fabric-networking-api-v1",
            "fabric-networking-v0", "fabric-object-builder-api-v1", "fabric-particles-v1", "fabric-recipe-api-v1",
            "fabric-registry-sync-v0", "fabric-renderer-api-v1", "fabric-renderer-indigo", "fabric-renderer-registries-v1",
            "fabric-rendering-data-attachment-v1", "fabric-rendering-fluids-v1", "fabric-rendering-v0",
            "fabric-rendering-v1", "fabric-resource-conditions-api-v1", "fabric-resource-loader-v0", "fabric-screen-api-v1",
            "fabric-screen-handler-api-v1", "fabric-sound-api-v1", "fabric-transfer-api-v1",
            "fabric-transitive-access-wideners-v1");

    private ServerModSafetyPolicy() { }

    static boolean supports(Collection<String> modIds) {
        if (modIds == null || modIds.isEmpty()) return false;
        Set<String> found = new HashSet<>(modIds);
        if (!found.containsAll(BASE_MODS)) return false;
        for (String id : found) {
            if (id == null || (!BASE_MODS.contains(id) && !SUPPORTED_FABRIC_API_MODULES.contains(id))) return false;
        }
        return true;
    }
}
