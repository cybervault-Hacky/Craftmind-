package com.craftmind.bridge.fabric;

import java.util.Map;
import net.minecraft.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftBlockSupportTest {
    private static final MinecraftBlockSupport SUPPORT = new MinecraftBlockSupport();

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        Bootstrap.initialize();
    }

    @Test
    public void acceptsRegisteredVanillaBlocksAndOnlyRecognizedPropertyValues() {
        assertTrue(SUPPORT.isSupportedBlock("minecraft:stone"));
        assertTrue(SUPPORT.hasValidState("minecraft:oak_stairs", Map.of(
                "facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false")));
        assertFalse(SUPPORT.hasValidState("minecraft:oak_stairs", Map.of("waterlogged", "true")));
        assertFalse(SUPPORT.isSupportedBlock("example:stone"));
        assertFalse(SUPPORT.isSupportedBlock("minecraft:not_a_real_block"));
        assertFalse(SUPPORT.hasValidState("minecraft:oak_stairs", Map.of("facing", "sideways")));
        assertFalse(SUPPORT.hasValidState("minecraft:oak_stairs", Map.of("admin", "true")));
    }

    @Test
    public void rejectsAdministrativeFluidDynamicAndBlockEntityBlocks() {
        assertFalse(SUPPORT.isSupportedBlock("minecraft:command_block"));
        assertFalse(SUPPORT.isSupportedBlock("minecraft:water"));
        assertFalse(SUPPORT.isSupportedBlock("minecraft:sand"));
        assertFalse(SUPPORT.isSupportedBlock("minecraft:white_concrete_powder"));
        assertFalse(SUPPORT.isSupportedBlock("minecraft:chest"));
        assertFalse(SUPPORT.hasValidState("minecraft:chest", Map.of()));
    }
}
