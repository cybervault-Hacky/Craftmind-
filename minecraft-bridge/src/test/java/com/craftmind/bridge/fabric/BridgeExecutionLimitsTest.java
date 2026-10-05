package com.craftmind.bridge.fabric;

import java.io.IOException;
import java.util.Properties;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class BridgeExecutionLimitsTest {
    @Test
    public void defaultsAreBoundedAndBatchSizeIsCapped() {
        BridgeExecutionLimits defaults = BridgeExecutionLimits.defaults();
        assertEquals(4096, defaults.maxOperations());
        assertEquals(1_048_576, defaults.maxRequestBytes());
        assertEquals(32, defaults.operationsPerTick());
        assertEquals(300_000L, defaults.maxExecutionMillis());
        assertThrows(IllegalArgumentException.class, () -> new BridgeExecutionLimits(1, 1024, 65, 1));
        assertThrows(IllegalArgumentException.class, () -> new BridgeExecutionLimits(4097, 4096, 1, 1));
    }

    @Test
    public void propertyParserHonorsAllowedOverridesAndRejectsOutOfRangeValues() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("maxOperations", "512");
        properties.setProperty("maxRequestBytes", "262144");
        properties.setProperty("operationsPerTick", "8");
        properties.setProperty("maxExecutionSeconds", "120");
        BridgeExecutionLimits limits = BridgeExecutionLimits.fromProperties(properties);
        assertEquals(512, limits.maxOperations());
        assertEquals(262144, limits.maxRequestBytes());
        assertEquals(8, limits.operationsPerTick());
        assertEquals(120_000L, limits.maxExecutionMillis());

        properties.setProperty("operationsPerTick", "1000");
        assertThrows(IOException.class, () -> BridgeExecutionLimits.fromProperties(properties));
    }
}
