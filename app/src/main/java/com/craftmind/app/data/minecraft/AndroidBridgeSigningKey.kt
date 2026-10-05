package com.craftmind.app.data.minecraft

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.craftmind.bridge.protocol.BridgeCrypto
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** P-256 client identity whose private key is generated and retained inside Android Keystore. */
internal class AndroidBridgeSigningKey {
    @Synchronized
    fun clientId(): String {
        val keyPair = keyPair()
        return BridgeCrypto.clientId(keyPair.public.encoded)
    }

    @Synchronized
    fun publicKeyX509(): ByteArray {
        val encoded = keyPair().public.encoded
        require(BridgeCrypto.isP256PublicKey(encoded)) { "BRIDGE_KEY_INVALID" }
        return encoded
    }

    @Synchronized
    fun sign(message: ByteArray): ByteArray {
        val privateKey = keyPair().private
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(message)
        return signer.sign()
    }

    @Synchronized
    fun delete() {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE)
        store.load(null)
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
    }

    private fun keyPair(): KeyPair {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE)
        store.load(null)
        if (!store.containsAlias(KEY_ALIAS)) createKey()
        val certificate = store.getCertificate(KEY_ALIAS)
                ?: throw IllegalStateException("BRIDGE_KEY_UNAVAILABLE")
        val privateKey = store.getKey(KEY_ALIAS, null) as? PrivateKey
                ?: throw IllegalStateException("BRIDGE_KEY_UNAVAILABLE")
        val pair = KeyPair(certificate.publicKey, privateKey)
        if (!BridgeCrypto.isP256PublicKey(pair.public.encoded)) {
            throw IllegalStateException("BRIDGE_KEY_INVALID")
        }
        return pair
    }

    private fun createKey() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setKeySize(256)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(specification)
        generator.generateKeyPair()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.craftmind.minecraft-bridge.client-v1"
    }
}
