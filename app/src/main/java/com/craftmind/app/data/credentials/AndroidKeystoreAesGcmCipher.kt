package com.craftmind.app.data.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.craftmind.app.domain.ai.CredentialAlias
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** AES-256-GCM encryption backed by Android Keystore; key bytes are never exported. */
internal class AndroidKeystoreAesGcmCipher : CredentialCipher {
    override fun encrypt(alias: CredentialAlias, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad(alias))
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES)
        val encrypted = cipher.doFinal(plaintext)
        return ByteArrayOutputStream(VERSION_HEADER_BYTES + iv.size + encrypted.size).use { output ->
            output.write(FORMAT_VERSION)
            output.write(iv.size)
            output.write(iv)
            output.write(encrypted)
            output.toByteArray()
        }.also {
            encrypted.fill(0)
        }
    }

    override fun decrypt(alias: CredentialAlias, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < VERSION_HEADER_BYTES + GCM_IV_BYTES + GCM_TAG_BYTES ||
            ciphertext.size > EncryptedCredentialStore.MAX_STORED_BYTES ||
            ciphertext[0].toInt() != FORMAT_VERSION
        ) {
            throw CredentialStorageException()
        }
        val ivLength = ciphertext[1].toInt() and 0xFF
        if (ivLength != GCM_IV_BYTES || ciphertext.size <= VERSION_HEADER_BYTES + ivLength + GCM_TAG_BYTES - 1) {
            throw CredentialStorageException()
        }
        val ivStart = VERSION_HEADER_BYTES
        val bodyStart = ivStart + ivLength
        val iv = ciphertext.copyOfRange(ivStart, bodyStart)
        val encrypted = ciphertext.copyOfRange(bodyStart, ciphertext.size)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad(alias))
            return cipher.doFinal(encrypted)
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun aad(alias: CredentialAlias): ByteArray =
        "$FORMAT_CONTEXT:${alias.value}".toByteArray(Charsets.UTF_8)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "craftmind.provider.credential.aesgcm.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_CONTEXT = "CraftMindCredentialV1"
        const val FORMAT_VERSION = 1
        const val VERSION_HEADER_BYTES = 2
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    }
}
