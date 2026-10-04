package com.craftmind.app.domain.security

import com.craftmind.app.domain.ai.AiProviderId

/**
 * Secure credential boundary for future provider adapters. Phase 1 deliberately provides no
 * implementation and collects or stores no API keys. A future Android implementation must use an
 * encrypted, Keystore-backed store and must never log values.
 */
interface CredentialStore {
    suspend fun read(providerId: AiProviderId): ProviderCredential?
    suspend fun write(providerId: AiProviderId, credential: ProviderCredential)
    suspend fun remove(providerId: AiProviderId)
}

/** A short-lived secret container that avoids exposing credentials through data-class toString(). */
class ProviderCredential private constructor(private val characters: CharArray) : AutoCloseable {
    private var closed: Boolean = false

    @Synchronized
    fun <T> useCharacters(block: (CharArray) -> T): T {
        check(!closed) { "Credential has already been cleared" }
        val temporaryCopy = characters.copyOf()
        return try {
            block(temporaryCopy)
        } finally {
            temporaryCopy.fill('\u0000')
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            characters.fill('\u0000')
            closed = true
        }
    }

    override fun toString(): String = "ProviderCredential([REDACTED])"

    companion object {
        fun fromCharacters(value: CharArray): ProviderCredential = ProviderCredential(value.copyOf())
    }
}
