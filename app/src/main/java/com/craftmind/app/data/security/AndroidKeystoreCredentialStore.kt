package com.craftmind.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.security.CredentialStore
import com.craftmind.app.domain.security.CredentialStoreError
import com.craftmind.app.domain.security.CredentialStoreException
import com.craftmind.app.domain.security.ProviderCredential
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** AES-GCM ciphertext is stored in app-private no-backup files; the AES key never leaves Keystore. */
class AndroidKeystoreCredentialStore(context: Context) : CredentialStore {
    private val appContext = context.applicationContext
    private val lock = Mutex()

    override suspend fun read(providerId: AiProviderId): ProviderCredential? = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = credentialFile(providerId)
            if (!file.baseFile.exists()) return@withLock null
            try {
                val payload = file.openRead().use { input -> input.readBounded(MAX_ENCRYPTED_BYTES) }
                val clearBytes = decrypt(payload, providerId)
                try {
                    val characters = decodeUtf8(clearBytes)
                    try {
                        ProviderCredential.fromCharacters(characters)
                    } finally {
                        characters.fill('\u0000')
                    }
                } finally {
                    clearBytes.fill(0)
                }
            } catch (error: CredentialStoreException) {
                throw error
            } catch (_: AEADBadTagException) {
                throw CredentialStoreException(CredentialStoreError.CORRUPTED_CREDENTIAL)
            } catch (_: GeneralSecurityException) {
                throw CredentialStoreException(CredentialStoreError.KEY_UNAVAILABLE)
            } catch (_: Exception) {
                throw CredentialStoreException(CredentialStoreError.CORRUPTED_CREDENTIAL)
            }
        }
    }

    override suspend fun write(providerId: AiProviderId, credential: ProviderCredential) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    val clearBytes = credential.useCharacters(::encodeUtf8)
                    try {
                        if (clearBytes.isEmpty() || clearBytes.size > MAX_CREDENTIAL_BYTES) {
                            throw CredentialStoreException(CredentialStoreError.TOO_LARGE)
                        }
                        val payload = encrypt(clearBytes, providerId)
                        val file = credentialFile(providerId)
                        file.baseFile.parentFile?.let { parent ->
                            if (!parent.exists() && !parent.mkdirs()) {
                                throw IOException("credential directory unavailable")
                            }
                        }
                        val output = file.startWrite()
                        try {
                            output.write(payload)
                            output.fd.sync()
                            file.finishWrite(output)
                        } catch (error: Exception) {
                            file.failWrite(output)
                            throw error
                        } finally {
                            payload.fill(0)
                        }
                    } finally {
                        clearBytes.fill(0)
                    }
                } catch (error: CredentialStoreException) {
                    throw error
                } catch (_: GeneralSecurityException) {
                    throw CredentialStoreException(CredentialStoreError.KEY_UNAVAILABLE)
                } catch (_: Exception) {
                    throw CredentialStoreException(CredentialStoreError.STORAGE_FAILURE)
                }
            }
        }
    }

    override suspend fun remove(providerId: AiProviderId) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    credentialFile(providerId).delete()
                } catch (_: Exception) {
                    throw CredentialStoreException(CredentialStoreError.STORAGE_FAILURE)
                }
            }
        }
    }

    private fun credentialFile(providerId: AiProviderId): AtomicFile {
        if (!PROVIDER_ID_PATTERN.matches(providerId.value)) {
            throw CredentialStoreException(CredentialStoreError.INVALID_PROVIDER_ID)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(providerId.value.toByteArray(StandardCharsets.UTF_8))
            .toHex()
        val base = File(File(appContext.noBackupFilesDir, DIRECTORY_NAME), "$digest.credential")
        return AtomicFile(base)
    }

    private fun encrypt(clearBytes: ByteArray, providerId: AiProviderId): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(providerId.value.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(clearBytes)
        val iv = cipher.iv
        return try {
            ByteArrayOutputStream(HEADER_BYTES + iv.size + encrypted.size).use { bytes ->
                DataOutputStream(bytes).use { data ->
                    data.writeInt(MAGIC)
                    data.writeByte(FORMAT_VERSION)
                    data.writeByte(iv.size)
                    data.writeInt(encrypted.size)
                    data.write(iv)
                    data.write(encrypted)
                    data.flush()
                }
                bytes.toByteArray()
            }
        } finally {
            encrypted.fill(0)
            iv.fill(0)
        }
    }

    private fun decrypt(payload: ByteArray, providerId: AiProviderId): ByteArray {
        if (payload.size !in HEADER_BYTES + MIN_IV_BYTES + GCM_TAG_BYTES..MAX_ENCRYPTED_BYTES) {
            throw CredentialStoreException(CredentialStoreError.CORRUPTED_CREDENTIAL)
        }
        val input = DataInputStream(ByteArrayInputStream(payload))
        if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT_VERSION) {
            throw CredentialStoreException(CredentialStoreError.CORRUPTED_CREDENTIAL)
        }
        val ivSize = input.readUnsignedByte()
        val cipherSize = input.readInt()
        if (ivSize !in MIN_IV_BYTES..MAX_IV_BYTES ||
            cipherSize !in GCM_TAG_BYTES..(MAX_CREDENTIAL_BYTES + GCM_TAG_BYTES) ||
            payload.size != HEADER_BYTES + ivSize + cipherSize
        ) {
            throw CredentialStoreException(CredentialStoreError.CORRUPTED_CREDENTIAL)
        }
        val iv = ByteArray(ivSize)
        val encrypted = ByteArray(cipherSize)
        try {
            input.readFully(iv)
            input.readFully(encrypted)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(providerId.value.toByteArray(StandardCharsets.UTF_8))
            return cipher.doFinal(encrypted)
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encodeUtf8(characters: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(characters))
        return try {
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            if (buffer.hasArray()) buffer.array().fill(0)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): CharArray {
        val buffer = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        return try {
            CharArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            if (buffer.hasArray()) buffer.array().fill('\u0000')
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw CredentialStoreException(CredentialStoreError.TOO_LARGE)
            output.write(buffer, 0, count)
        }
        buffer.fill(0)
        return output.toByteArray()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.craftmind.provider.credentials.aesgcm.v1"
        const val DIRECTORY_NAME = "provider_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAGIC = 0x434D4352
        const val FORMAT_VERSION = 1
        const val HEADER_BYTES = 10
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 16
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val MAX_CREDENTIAL_BYTES = 8 * 1024
        const val MAX_ENCRYPTED_BYTES = HEADER_BYTES + MAX_IV_BYTES + MAX_CREDENTIAL_BYTES + GCM_TAG_BYTES
        val PROVIDER_ID_PATTERN = Regex("[a-z0-9_-]{1,64}")
    }
}
