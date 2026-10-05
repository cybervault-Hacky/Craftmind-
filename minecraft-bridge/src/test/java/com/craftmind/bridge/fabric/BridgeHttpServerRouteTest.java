package com.craftmind.bridge.fabric;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BridgeHttpServerRouteTest {
    @Test
    public void allowsOnlyExplicitVersionOneBridgeRoutesIncludingSessionDisconnect() {
        assertTrue(BridgeHttpServer.isAllowedPath("/v1/bridge/info"));
        assertTrue(BridgeHttpServer.isAllowedPath("/v1/pair"));
        assertTrue(BridgeHttpServer.isAllowedPath("/v1/session/challenge"));
        assertTrue(BridgeHttpServer.isAllowedPath("/v1/session"));
        assertTrue(BridgeHttpServer.isAllowedPath("/v1/session/disconnect"));
        assertTrue(BridgeHttpServer.isAllowedPath("/v1/capabilities"));
        assertTrue(BridgeHttpServer.isAllowedPath("/v1/pair/revoke"));
        assertTrue(BridgeHttpServer.isAllowedPath("/v1/executions"));
        assertTrue(BridgeHttpServer.isAllowedPath("/v1/executions/cancel"));
    }

    @Test
    public void refusesCommandsShellAliasesAndUnversionedRoutes() {
        assertFalse(BridgeHttpServer.isAllowedPath("/"));
        assertFalse(BridgeHttpServer.isAllowedPath("/command"));
        assertFalse(BridgeHttpServer.isAllowedPath("/v1/commands"));
        assertFalse(BridgeHttpServer.isAllowedPath("/v1/shell"));
        assertFalse(BridgeHttpServer.isAllowedPath("/v1/blocks/place"));
        assertFalse(BridgeHttpServer.isAllowedPath("/v2/bridge/info"));
        assertFalse(BridgeHttpServer.isAllowedPath("/v1/executions/"));
    }
}
