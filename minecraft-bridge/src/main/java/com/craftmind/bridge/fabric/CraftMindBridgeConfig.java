package com.craftmind.bridge.fabric;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Set;

/** Non-secret listener and explicitly opted-in construction configuration. */
public final class CraftMindBridgeConfig {
    public static final String KEYSTORE_PASSWORD_ENV = "CRAFTMIND_BRIDGE_KEYSTORE_PASSWORD";
    private final Inet4Address bindAddress;
    private final int port;
    private final Path keyStorePath;
    private final Path dataDirectory;
    private final boolean constructionEnabled;
    private final BridgeExecutionLimits executionLimits;

    private CraftMindBridgeConfig(Inet4Address bindAddress, int port, Path dataDirectory,
                                  boolean constructionEnabled, BridgeExecutionLimits executionLimits) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.dataDirectory = dataDirectory;
        this.keyStorePath = dataDirectory.resolve("bridge-identity.p12");
        this.constructionEnabled = constructionEnabled;
        this.executionLimits = executionLimits;
    }

    public static CraftMindBridgeConfig load(Path configDirectory) throws IOException, UnknownHostException {
        Path file = configDirectory.resolve("craftmind-bridge.properties");
        if (!Files.exists(file)) {
            Files.createDirectories(configDirectory);
            String defaults = "# CraftMind Bridge v1. Bind only to one private IPv4 interface.\n" +
                    "# The default is loopback-only. Set bindAddress to the server's RFC1918 LAN IPv4 to pair a phone.\n" +
                    "bindAddress=127.0.0.1\n" +
                    "port=19872\n\n" +
                    "# Construction is disabled unless an operator explicitly enables it after reviewing the README safety notes.\n" +
                    "constructionEnabled=false\n" +
                    "maxOperations=4096\n" +
                    "maxRequestBytes=1048576\n" +
                    "operationsPerTick=32\n" +
                    "maxExecutionSeconds=300\n";
            Files.write(file, defaults.getBytes(StandardCharsets.UTF_8));
        }
        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String host = properties.getProperty("bindAddress", "127.0.0.1").trim();
        Inet4Address bindAddress = NetworkAddressPolicy.parseBindAddress(host);
        final int port;
        try {
            port = Integer.parseInt(properties.getProperty("port", "19872").trim());
        } catch (NumberFormatException error) {
            throw new IOException("invalid bridge port configuration");
        }
        if (port < 1024 || port > 65535) throw new IOException("invalid bridge port configuration");
        boolean constructionEnabled = booleanProperty(properties, "constructionEnabled", false);
        BridgeExecutionLimits limits = BridgeExecutionLimits.fromProperties(properties);
        Path directory = configDirectory.resolve("craftmind-bridge");
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("bridge data directory rejected");
        }
        try {
            Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // The server account's private config directory remains the platform security boundary on Windows.
        }
        return new CraftMindBridgeConfig(bindAddress, port, directory, constructionEnabled, limits);
    }

    private static boolean booleanProperty(Properties properties, String key, boolean fallback) throws IOException {
        String value = properties.getProperty(key, Boolean.toString(fallback)).trim();
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IOException("invalid bridge boolean configuration");
    }

    public Inet4Address bindAddress() { return bindAddress; }
    public int port() { return port; }
    public Path keyStorePath() { return keyStorePath; }
    public Path dataDirectory() { return dataDirectory; }
    public boolean constructionEnabled() { return constructionEnabled; }
    public BridgeExecutionLimits executionLimits() { return executionLimits; }
}
