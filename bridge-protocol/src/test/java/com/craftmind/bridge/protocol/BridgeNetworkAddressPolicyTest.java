package com.craftmind.bridge.protocol;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BridgeNetworkAddressPolicyTest {
    @Test
    public void acceptsPrivateIpv4LiteralsWithoutDnsResolution() throws Exception {
        assertFalse(BridgeNetworkAddressPolicy.parsePrivateIpv4("192.168.1.20").isAnyLocalAddress());
        assertTrue(BridgeNetworkAddressPolicy.parsePrivateIpv4("10.0.0.4").getHostAddress().equals("10.0.0.4"));
        assertTrue(BridgeNetworkAddressPolicy.parsePrivateIpv4("127.0.0.1").isLoopbackAddress());
    }

    @Test
    public void rejectsPublicHostnamesIpv6AndNonCanonicalIpv4() throws Exception {
        assertRejected("example.com");
        assertRejected("8.8.8.8");
        assertRejected("0.0.0.0");
        assertRejected("192.168.001.1");
        assertRejected("::1");
    }

    @Test
    public void acceptsOnlyPrivateOrLinkLocalRemoteAddresses() throws Exception {
        assertTrue(BridgeNetworkAddressPolicy.isAllowedRemote(InetAddress.getByName("192.168.0.8")));
        assertTrue(BridgeNetworkAddressPolicy.isAllowedRemote(InetAddress.getByName("169.254.1.9")));
        assertFalse(BridgeNetworkAddressPolicy.isAllowedRemote(InetAddress.getByName("8.8.8.8")));
        assertFalse(BridgeNetworkAddressPolicy.isAllowedRemote(InetAddress.getByName("2001:db8::1")));
    }

    private static void assertRejected(String address) throws Exception {
        try {
            BridgeNetworkAddressPolicy.parsePrivateIpv4(address);
        } catch (UnknownHostException expected) {
            return;
        }
        throw new AssertionError("Expected private IPv4 rejection");
    }
}
