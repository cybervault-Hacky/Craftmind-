package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BuildPlanContractValidator;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

/** Resolves only vanilla 1.20.1 blocks and state properties; it never mutates a world. */
final class MinecraftBlockSupport implements BuildPlanContractValidator.BlockSupport {
    private static final java.util.Set<String> UNSUPPORTED_SENSITIVE_BLOCKS = java.util.Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:command_block",
            "minecraft:chain_command_block", "minecraft:repeating_command_block", "minecraft:structure_block",
            "minecraft:jigsaw", "minecraft:structure_void", "minecraft:barrier", "minecraft:light",
            "minecraft:spawner", "minecraft:bedrock", "minecraft:reinforced_deepslate", "minecraft:end_portal",
            "minecraft:end_gateway", "minecraft:nether_portal", "minecraft:end_portal_frame", "minecraft:tnt",
            "minecraft:lava", "minecraft:water", "minecraft:fire", "minecraft:soul_fire", "minecraft:dragon_egg",
            "minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:suspicious_sand",
            "minecraft:suspicious_gravel", "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil",
            "minecraft:scaffolding", "minecraft:pointed_dripstone", "minecraft:white_concrete_powder",
            "minecraft:orange_concrete_powder", "minecraft:magenta_concrete_powder", "minecraft:light_blue_concrete_powder",
            "minecraft:yellow_concrete_powder", "minecraft:lime_concrete_powder", "minecraft:pink_concrete_powder",
            "minecraft:gray_concrete_powder", "minecraft:light_gray_concrete_powder", "minecraft:cyan_concrete_powder",
            "minecraft:purple_concrete_powder", "minecraft:blue_concrete_powder", "minecraft:brown_concrete_powder",
            "minecraft:green_concrete_powder", "minecraft:red_concrete_powder", "minecraft:black_concrete_powder");

    @Override
    public boolean isSupportedBlock(String blockId) {
        Identifier identifier = Identifier.tryParse(blockId);
        if (identifier == null || !"minecraft".equals(identifier.getNamespace()) ||
                UNSUPPORTED_SENSITIVE_BLOCKS.contains(identifier.toString())) return false;
        Block block = Registries.BLOCK.getOrEmpty(identifier).orElse(null);
        return block != null && !block.getDefaultState().hasBlockEntity();
    }

    @Override
    public boolean hasValidState(String blockId, Map<String, String> state) {
        return resolveBlockState(blockId, state) != null;
    }

    BlockState resolveBlockState(String blockId, Map<String, String> state) {
        if (state == null || !isSupportedBlock(blockId)) return null;
        Identifier identifier = Identifier.tryParse(blockId);
        Block block = identifier == null ? null : Registries.BLOCK.getOrEmpty(identifier).orElse(null);
        if (block == null) return null;
        BlockState resolved = block.getDefaultState();
        for (Map.Entry<String, String> entry : state.entrySet()) {
            Property<?> property = block.getStateManager().getProperty(entry.getKey());
            if (property == null) return null;
            resolved = withProperty(resolved, property, entry.getValue());
            if (resolved == null) return null;
        }
        return resolved.hasBlockEntity() || !resolved.getFluidState().isEmpty() ? null : resolved;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
        java.util.Optional<T> parsed = property.parse(value);
        return parsed.map(item -> state.with(property, item)).orElse(null);
    }
}
