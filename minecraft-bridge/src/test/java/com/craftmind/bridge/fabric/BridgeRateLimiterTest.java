package com.craftmind.bridge.fabric;

import java.net.InetAddress;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BridgeRateLimiterTest {
    @Test
    public void executionRoutesHaveBoundedLimitsWithStatusBudgetForTruthQueries() throws Exception {
        BridgeRateLimiter limiter = new BridgeRateLimiter();
        InetAddress address = InetAddress.getByName("192.168.1.10");
        for (int count = 0; count < 6; count++) assertTrue(limiter.allow(address, "/v1/executions/prepare", 1_000));
        assertFalse(limiter.allow(address, "/v1/executions/prepare", 1_001));

        for (int count = 0; count < 40; count++) assertTrue(limiter.allow(address, "/v1/executions/status", 1_000));
        assertFalse(limiter.allow(address, "/v1/executions/status", 1_001));

        for (int count = 0; count < 12; count++) assertTrue(limiter.allow(address, "/v1/executions/cancel", 1_000));
        assertFalse(limiter.allow(address, "/v1/executions/cancel", 1_001));
    }

    @Test
    public void rateBucketsAreIsolatedByClientAddressRouteAndWindow() throws Exception {
        BridgeRateLimiter limiter = new BridgeRateLimiter();
        InetAddress first = InetAddress.getByName("192.168.1.10");
        InetAddress second = InetAddress.getByName("192.168.1.11");
        for (int count = 0; count < 6; count++) assertTrue(limiter.allow(first, "/v1/executions/start", 5_000));
        assertFalse(limiter.allow(first, "/v1/executions/start", 5_001));
        assertTrue(limiter.allow(second, "/v1/executions/start", 5_001));
        assertTrue(limiter.allow(first, "/v1/executions/start", 65_000));
    }
}
