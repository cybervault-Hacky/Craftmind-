package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeCrypto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FileTrustedClientRepositoryTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void persistsOnlyPublicClientRecordAndCanRevokeDurably() throws Exception {
        Path directory = temporaryFolder.newFolder("bridge-data").toPath();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        String clientId = BridgeCrypto.clientId(keyPair.getPublic().getEncoded());
        String publicKey = BridgeCrypto.base64Url(keyPair.getPublic().getEncoded());

        FileTrustedClientRepository repository = new FileTrustedClientRepository(directory);
        repository.add(new TrustedBridgeClient(clientId, "Android phone", publicKey, 1_700_000_000_000L));

        Path store = directory.resolve("trusted-clients.json");
        assertTrue(Files.exists(store));
        String contents = Files.readString(store);
        assertTrue(contents.contains(clientId));
        assertTrue(contents.contains(publicKey));
        assertFalse(contents.contains("PRIVATE KEY"));
        assertFalse(contents.contains("pairingCode"));

        FileTrustedClientRepository reloaded = new FileTrustedClientRepository(directory);
        assertNotNull(reloaded.find(clientId));
        assertEquals("Android phone", reloaded.find(clientId).displayName);
        assertTrue(reloaded.remove(clientId));
        assertNull(new FileTrustedClientRepository(directory).find(clientId));
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsClientIdThatDoesNotMatchPublicKey() throws Exception {
        Path directory = temporaryFolder.newFolder("bad-bridge-data").toPath();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        FileTrustedClientRepository repository = new FileTrustedClientRepository(directory);
        repository.add(new TrustedBridgeClient("cm-000000000000000000000000", "Android phone",
                BridgeCrypto.base64Url(keyPair.getPublic().getEncoded()), 1_700_000_000_000L));
    }
}
