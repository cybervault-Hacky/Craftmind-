package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BuildPlanContractValidator;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.state.property.Property;

/** Resolves every transmitted block against the actual 1.20.1 vanilla registry without touching a world. */
final class MinecraftBlockSupport implements BuildPlanContractValidator.BlockSupport {
    @Override
    public boolean isSupportedBlock(String blockId) {
        Identifier identifier = Identifier.tryParse(blockId);
        if (identifier == null || !"minecraft".equals(identifier.getNamespace())) return false;
        Block block = Registries.BLOCK.getOrEmpty(identifier).orElse(null);
        return block != null && block != Blocks.AIR && block != Blocks.CAVE_AIR && block != Blocks.VOID_AIR;
    }

    @Override
    public boolean hasValidState(String blockId, Map<String, String> state) {
        if (!isSupportedBlock(blockId)) return false;
        Identifier identifier = Identifier.tryParse(blockId);
        Block block = Registries.BLOCK.getOrEmpty(identifier).orElse(null);
        if (block == null) return false;
        for (Map.Entry<String, String> entry : state.entrySet()) {
            Property<?> property = block.getStateManager().getProperty(entry.getKey());
            if (property == null || !property.parse(entry.getValue()).isPresent()) return false;
        }
        return true;
    }
}
