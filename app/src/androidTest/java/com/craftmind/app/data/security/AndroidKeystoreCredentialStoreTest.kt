package com.craftmind.app.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.security.CredentialStoreError
import com.craftmind.app.domain.security.CredentialStoreException
import com.craftmind.app.domain.security.ProviderCredential
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialStoreTest {
    private val providerId = AiProviderId("google_gemini")

    @Test
    fun encryptsRoundTripsReplacesAndRemovesProviderCredential() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidKeystoreCredentialStore(context)
        val testCredential = "phase2-test-key-9876"
        val first = ProviderCredential.fromCharacters(testCredential.toCharArray())
        try {
            store.write(providerId, first)
        } finally {
            first.close()
        }

        val credentialFiles = File(context.noBackupFilesDir, "provider_credentials")
            .listFiles()
            .orEmpty()
        assertEquals(1, credentialFiles.size)
        val storedBytes = credentialFiles.single().readBytes()
        assertFalse(String(storedBytes, StandardCharsets.UTF_8).contains(testCredential))
        storedBytes.fill(0)

        val restored = store.read(providerId)
        assertNotNull(restored)
        restored!!.useCharacters { assertEquals(secret, String(it)) }
        restored.close()

        val replacementText = "new-phase2-test-key-4321"
        val replacement = ProviderCredential.fromCharacters(replacementText.toCharArray())
        try {
            store.write(providerId, replacement)
        } finally {
            replacement.close()
        }
        val replaced = store.read(providerId)
        assertNotNull(replaced)
        replaced!!.useCharacters { assertEquals(replacementText, String(it)) }
        replaced.close()

        store.remove(providerId)
        assertNull(store.read(providerId))
    }

    @Test
    fun rejectsTamperedCiphertextWithoutReturningAKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidKeystoreCredentialStore(context)
        val credential = ProviderCredential.fromCharacters("tamper-test-key".toCharArray())
        try {
            store.write(providerId, credential)
        } finally {
            credential.close()
        }
        val file = File(context.noBackupFilesDir, "provider_credentials").listFiles().orEmpty().single()
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        bytes.fill(0)

        val error = try {
            store.read(providerId)
            throw AssertionError("Expected modified ciphertext to fail authentication")
        } catch (expected: CredentialStoreException) {
            expected
        } finally {
            store.remove(providerId)
        }
        assertEquals(CredentialStoreError.CORRUPTED_CREDENTIAL, error.error)
    }
}
