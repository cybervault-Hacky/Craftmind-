package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeNetworkAddressPolicy;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Fabric adapter for the shared private-IPv4 policy. */
public final class NetworkAddressPolicy {
    private NetworkAddressPolicy() { }

    public static Inet4Address parseBindAddress(String text) throws UnknownHostException {
        return BridgeNetworkAddressPolicy.parsePrivateIpv4(text);
    }

    public static boolean isAllowedBindAddress(Inet4Address address) {
        return BridgeNetworkAddressPolicy.isAllowedBindAddress(address);
    }

    public static boolean isAllowedRemote(InetAddress address) {
        return BridgeNetworkAddressPolicy.isAllowedRemote(address);
    }
}
