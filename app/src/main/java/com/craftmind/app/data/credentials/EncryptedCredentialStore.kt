package com.craftmind.app.data.credentials

import com.craftmind.app.domain.ai.CredentialAlias
import com.craftmind.app.domain.ai.CredentialLimits
import com.craftmind.app.domain.ai.CredentialStore
import com.craftmind.app.domain.ai.SecretValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal interface CredentialCipher {
    fun encrypt(alias: CredentialAlias, plaintext: ByteArray): ByteArray
    fun decrypt(alias: CredentialAlias, ciphertext: ByteArray): ByteArray
}

internal interface CredentialRecordStore {
    suspend fun read(alias: CredentialAlias): ByteArray?
    suspend fun write(alias: CredentialAlias, ciphertext: ByteArray)
    suspend fun remove(alias: CredentialAlias)
}

class CredentialStorageException(cause: Throwable? = null) :
    Exception("Secure credential storage is unavailable.", cause)

/** Encrypts every value before it reaches the persistent record store. */
class EncryptedCredentialStore internal constructor(
    private val cipher: CredentialCipher,
    private val records: CredentialRecordStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialStore {
    override suspend fun put(alias: CredentialAlias, value: SecretValue) = withContext(ioDispatcher) {
        try {
            validateAlias(alias)
            val plaintext = value.use(::encodeSecret)
            try {
                if (plaintext.isEmpty() || plaintext.size > MAX_SECRET_BYTES) {
                    throw CredentialStorageException()
                }
                val encrypted = cipher.encrypt(alias, plaintext)
                try {
                    if (encrypted.isEmpty() || encrypted.size > MAX_STORED_BYTES) {
                        throw CredentialStorageException()
                    }
                    records.write(alias, encrypted)
                } finally {
                    encrypted.fill(0)
                }
            } finally {
                plaintext.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CredentialStorageException) {
            throw error
        } catch (error: Exception) {
            throw CredentialStorageException(error)
        } finally {
            value.clear()
        }
    }

    override suspend fun get(alias: CredentialAlias): SecretValue? = withContext(ioDispatcher) {
        try {
            validateAlias(alias)
            val ciphertext = records.read(alias) ?: return@withContext null
            if (ciphertext.isEmpty() || ciphertext.size > MAX_STORED_BYTES) {
                ciphertext.fill(0)
                throw CredentialStorageException()
            }
            val plaintext = try {
                cipher.decrypt(alias, ciphertext)
            } finally {
                ciphertext.fill(0)
            }
            try {
                if (plaintext.isEmpty() || plaintext.size > MAX_SECRET_BYTES) {
                    throw CredentialStorageException()
                }
                val characters = decodeSecret(plaintext)
                try {
                    SecretValue.copyOf(characters)
                } finally {
                    characters.fill('\u0000')
                }
            } finally {
                plaintext.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CredentialStorageException) {
            throw error
        } catch (error: Exception) {
            throw CredentialStorageException(error)
        }
    }

    override suspend fun contains(alias: CredentialAlias): Boolean = withContext(ioDispatcher) {
        try {
            validateAlias(alias)
            records.read(alias)?.also { it.fill(0) } != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is CredentialStorageException) throw error
            throw CredentialStorageException(error)
        }
    }

    override suspend fun remove(alias: CredentialAlias) = withContext(ioDispatcher) {
        try {
            validateAlias(alias)
            records.remove(alias)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is CredentialStorageException) throw error
            throw CredentialStorageException(error)
        }
    }

    private fun validateAlias(alias: CredentialAlias) {
        if (alias.value.length !in 1..MAX_ALIAS_CHARACTERS ||
            !alias.value.matches(Regex("[A-Za-z0-9._-]+"))
        ) {
            throw CredentialStorageException()
        }
    }

    private fun encodeSecret(characters: CharArray): ByteArray {
        if (characters.isEmpty() || characters.size > MAX_SECRET_CHARACTERS) {
            throw CredentialStorageException()
        }
        val encoded: ByteBuffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(characters))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) encoded.array().fill(0)
        return bytes
    }

    private fun decodeSecret(bytes: ByteArray): CharArray {
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        val characters = CharArray(decoded.remaining())
        decoded.get(characters)
        if (decoded.hasArray()) decoded.array().fill('\u0000')
        return characters
    }

    companion object {
        const val MAX_SECRET_CHARACTERS = CredentialLimits.MAX_API_KEY_CHARACTERS
        const val MAX_ALIAS_CHARACTERS = 96
        const val MAX_SECRET_BYTES = MAX_SECRET_CHARACTERS * 4
        const val MAX_STORED_BYTES = 16 * 1024 + 64
    }
}
