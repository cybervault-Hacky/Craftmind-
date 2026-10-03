package com.craftmind.app.data.credentials

import com.craftmind.app.domain.ai.CredentialAlias
import com.craftmind.app.domain.ai.SecretValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedCredentialStoreTest {
    @Test
    fun persistsOnlyCiphertextAndRoundTripsASecret() = runTest {
        val records = InMemoryCredentialRecordStore()
        val store = EncryptedCredentialStore(XorTestCipher, records)
        val alias = CredentialAlias("openai")
        val secret = SecretValue.copyOf("test-key-not-real".toCharArray())

        store.put(alias, secret)

        assertEquals("\u0000".repeat("test-key-not-real".length), secret.use { it.concatToString() })
        val stored = records.values.getValue(alias)
        assertFalse(stored.contentEquals("test-key-not-real".toByteArray()))
        val restored = store.get(alias) ?: error("Expected stored value")
        assertEquals("test-key-not-real", restored.use { it.concatToString() })
        restored.clear()
    }

    @Test
    fun malformedAliasAndEmptySecretAreRejectedWithoutRevealingDetails() = runTest {
        val store = EncryptedCredentialStore(XorTestCipher, InMemoryCredentialRecordStore())
        val invalidAliasError = captureFailure {
            store.put(CredentialAlias("bad alias"), SecretValue.copyOf("x".toCharArray()))
        }
        assertTrue(invalidAliasError is CredentialStorageException)
        assertEquals("Secure credential storage is unavailable.", invalidAliasError?.message)

        val emptySecretError = captureFailure {
            store.put(CredentialAlias("openai"), SecretValue.copyOf(charArrayOf()))
        }
        assertTrue(emptySecretError is CredentialStorageException)
        assertEquals("Secure credential storage is unavailable.", emptySecretError?.message)
    }

    @Test
    fun secretRenderingIsRedactedAndClearOverwritesCharacters() {
        val value = SecretValue.copyOf("private".toCharArray())

        assertEquals("SecretValue([REDACTED])", value.toString())
        value.clear()
        assertEquals("\u0000".repeat(7), value.use { it.concatToString() })
    }

    private suspend fun captureFailure(action: suspend () -> Unit): Throwable? = try {
        action()
        null
    } catch (error: Throwable) {
        error
    }

    private object XorTestCipher : CredentialCipher {
        override fun encrypt(alias: CredentialAlias, plaintext: ByteArray): ByteArray =
            ByteArray(plaintext.size) { index -> (plaintext[index].toInt() xor 0x5A).toByte() }

        override fun decrypt(alias: CredentialAlias, ciphertext: ByteArray): ByteArray =
            ByteArray(ciphertext.size) { index -> (ciphertext[index].toInt() xor 0x5A).toByte() }
    }

    private class InMemoryCredentialRecordStore : CredentialRecordStore {
        val values = mutableMapOf<CredentialAlias, ByteArray>()

        override suspend fun read(alias: CredentialAlias): ByteArray? = values[alias]?.copyOf()
        override suspend fun write(alias: CredentialAlias, ciphertext: ByteArray) {
            values[alias] = ciphertext.copyOf()
        }
        override suspend fun remove(alias: CredentialAlias) {
            values.remove(alias)?.fill(0)
        }
    }
}
