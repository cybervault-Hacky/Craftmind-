package com.craftmind.app.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialTest {
    @Test
    fun credentialIsRedactedAndUseReceivesATemporaryCopy() {
        val input = "not-a-real-key".toCharArray()
        val credential = ProviderCredential.fromCharacters(input)
        input.fill('x')

        assertTrue(credential.toString().contains("REDACTED"))
        val observed = credential.useCharacters { secret -> String(secret) }
        assertEquals("not-a-real-key", observed)
        credential.close()
        assertThrows(IllegalStateException::class.java) {
            credential.useCharacters { String(it) }
        }
    }
}
