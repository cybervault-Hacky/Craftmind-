package com.craftmind.app.domain.security

import com.craftmind.app.domain.ai.AiProviderId

/**
 * Secure credential boundary for provider adapters. The Android implementation encrypts entries
 * with an AES key held by Android Keystore. Provider IDs are keys; model IDs never select a secret.
 * Implementations and callers must never log credential values.
 */
enum class CredentialStoreError {
    INVALID_PROVIDER_ID,
    TOO_LARGE,
    CORRUPTED_CREDENTIAL,
    KEY_UNAVAILABLE,
    STORAGE_FAILURE,
}

class CredentialStoreException(val error: CredentialStoreError) : Exception(error.name)

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
