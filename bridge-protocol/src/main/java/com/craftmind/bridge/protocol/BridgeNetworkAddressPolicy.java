package com.craftmind.bridge.protocol;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Shared strict private-IPv4 policy; hostnames, IPv6, wildcard, and public addresses are never resolved. */
public final class BridgeNetworkAddressPolicy {
    private BridgeNetworkAddressPolicy() { }

    public static Inet4Address parsePrivateIpv4(String text) throws UnknownHostException {
        byte[] octets = parseIpv4(text);
        InetAddress address = InetAddress.getByAddress(octets);
        if (!(address instanceof Inet4Address) || !isAllowedBindAddress((Inet4Address) address)) {
            throw new UnknownHostException("unsupported private IPv4 address");
        }
        return (Inet4Address) address;
    }

    public static boolean isAllowedBindAddress(Inet4Address address) {
        byte[] octets = address.getAddress();
        return isLoopback(octets) || isRfc1918(octets);
    }

    public static boolean isAllowedRemote(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] octets = address.getAddress();
        return isLoopback(octets) || isRfc1918(octets) || isLinkLocal(octets);
    }

    private static byte[] parseIpv4(String text) throws UnknownHostException {
        if (text == null || text.length() < 7 || text.length() > 15) throw new UnknownHostException("invalid IPv4 literal");
        String[] pieces = text.split("\\.", -1);
        if (pieces.length != 4) throw new UnknownHostException("invalid IPv4 literal");
        byte[] result = new byte[4];
        for (int index = 0; index < pieces.length; index++) {
            String piece = pieces[index];
            if (piece.isEmpty() || piece.length() > 3 || (piece.length() > 1 && piece.charAt(0) == '0')) {
                throw new UnknownHostException("invalid IPv4 literal");
            }
            int value = 0;
            for (int offset = 0; offset < piece.length(); offset++) {
                char digit = piece.charAt(offset);
                if (digit < '0' || digit > '9') throw new UnknownHostException("invalid IPv4 literal");
                value = value * 10 + digit - '0';
            }
            if (value > 255) throw new UnknownHostException("invalid IPv4 literal");
            result[index] = (byte) value;
        }
        return result;
    }

    private static boolean isLoopback(byte[] ip) {
        return ip != null && ip.length == 4 && (ip[0] & 0xff) == 127;
    }

    private static boolean isRfc1918(byte[] ip) {
        if (ip == null || ip.length != 4) return false;
        int first = ip[0] & 0xff;
        int second = ip[1] & 0xff;
        return first == 10 || (first == 172 && second >= 16 && second <= 31) ||
                (first == 192 && second == 168);
    }

    private static boolean isLinkLocal(byte[] ip) {
        return ip != null && ip.length == 4 && (ip[0] & 0xff) == 169 && (ip[1] & 0xff) == 254;
    }
}
