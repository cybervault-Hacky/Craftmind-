package com.craftmind.app.data.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.craftmind.app.domain.account.AccountIdentity
import com.craftmind.app.domain.account.AccountProviderId
import com.craftmind.app.domain.account.AccountSession
import com.craftmind.app.domain.account.AccountSessionCredential
import com.craftmind.app.domain.account.AccountSessionSource
import com.craftmind.app.domain.account.AccountSessionStore
import com.craftmind.app.domain.account.AccountSessionStoreError
import com.craftmind.app.domain.account.AccountSessionStoreException
import com.craftmind.app.domain.account.AccountSignInMethod
import com.craftmind.app.domain.account.StoredAccountSession
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
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted, single-slot store for the CraftMind account session secret (Phase 16 §4).
 *
 * Security properties, all deliberate:
 * * **Its own Keystore key.** The AES-256-GCM key lives under [KEY_ALIAS], which is a different key from the AI provider
 *   credential key. Compromise or deletion of one namespace cannot decrypt the other, and rotating provider credentials
 *   never touches an account session.
 * * **Its own directory.** Records live in app-private `noBackupFilesDir`, so they are excluded from cloud backup and
 *   device transfer. A session secret that cannot be read on another device is a session secret that cannot leak there.
 * * **Single slot.** [save] replaces the previous record atomically: there is no accumulation of stale sessions and no
 *   cross-account bleed if a user switches accounts.
 * * **Authenticated encryption.** GCM authenticates the payload, and the fixed AAD binds the ciphertext to this purpose,
 *   so a record from another CraftMind store cannot be replayed here.
 * * **Nothing is logged.** No branch of this class writes a session value, an account ID, or a crypto error detail to a
 *   log or to an exception message.
 */
class AndroidKeystoreAccountSessionStore(context: Context) : AccountSessionStore {
    private val appContext = context.applicationContext

    @Synchronized
    override fun load(): StoredAccountSession? {
        val file = sessionFile()
        if (!file.baseFile.exists()) return null
        try {
            val payload = file.openRead().use { input -> input.readBounded(MAX_ENCRYPTED_BYTES) }
            val clearBytes = decrypt(payload)
            try {
                return decodeRecord(clearBytes)
            } finally {
                clearBytes.fill(0)
            }
        } catch (error: AccountSessionStoreException) {
            throw error
        } catch (_: AEADBadTagException) {
            throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
        } catch (_: GeneralSecurityException) {
            throw AccountSessionStoreException(AccountSessionStoreError.KEY_UNAVAILABLE)
        } catch (_: IOException) {
            throw AccountSessionStoreException(AccountSessionStoreError.STORAGE_FAILURE)
        } catch (_: Exception) {
            throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
        }
    }

    @Synchronized
    override fun save(session: AccountSession, credential: AccountSessionCredential) {
        val clearBytes = credential.useSecret { characters -> encodeRecord(session, characters) }
        try {
            if (clearBytes.size > MAX_RECORD_BYTES) {
                throw AccountSessionStoreException(AccountSessionStoreError.TOO_LARGE)
            }
            val payload = encrypt(clearBytes)
            val file = sessionFile()
            file.baseFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw AccountSessionStoreException(AccountSessionStoreError.STORAGE_FAILURE)
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
        } catch (error: AccountSessionStoreException) {
            throw error
        } catch (_: GeneralSecurityException) {
            throw AccountSessionStoreException(AccountSessionStoreError.KEY_UNAVAILABLE)
        } catch (_: Exception) {
            throw AccountSessionStoreException(AccountSessionStoreError.STORAGE_FAILURE)
        } finally {
            clearBytes.fill(0)
        }
    }

    @Synchronized
    override fun clear() {
        try {
            sessionFile().delete()
        } catch (_: Exception) {
            throw AccountSessionStoreException(AccountSessionStoreError.STORAGE_FAILURE)
        }
    }

    private fun sessionFile(): AtomicFile {
        val base = File(File(appContext.noBackupFilesDir, DIRECTORY_NAME), FILE_NAME)
        return AtomicFile(base)
    }

    // ------------------------------------------------------------------------------------------------ record codec

    private fun encodeRecord(session: AccountSession, credentialCharacters: CharArray): ByteArray {
        val credentialBytes = encodeUtf8(credentialCharacters)
        try {
            return ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { data ->
                    data.writeInt(MAGIC)
                    data.writeByte(FORMAT_VERSION)
                    data.writeUTF(session.identity.accountId)
                    data.writeUTF(session.identity.displayName)
                    data.writeBoolean(session.identity.emailAddress != null)
                    session.identity.emailAddress?.let { email -> data.writeUTF(email) }
                    data.writeUTF(session.providerId.storageValue)
                    data.writeUTF(session.method.name)
                    data.writeLong(session.issuedAtEpochMillis)
                    data.writeBoolean(session.expiresAtEpochMillis != null)
                    session.expiresAtEpochMillis?.let { expiry -> data.writeLong(expiry) }
                    data.writeBoolean(session.refreshable)
                    data.writeUTF(session.source.name)
                    data.writeInt(credentialBytes.size)
                    data.write(credentialBytes)
                    data.flush()
                }
                bytes.toByteArray()
            }
        } finally {
            credentialBytes.fill(0)
        }
    }

    private fun decodeRecord(clearBytes: ByteArray): StoredAccountSession {
        if (clearBytes.size !in MIN_RECORD_BYTES..MAX_RECORD_BYTES) {
            throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
        }
        val data = DataInputStream(ByteArrayInputStream(clearBytes))
        if (data.readInt() != MAGIC || data.readUnsignedByte() != FORMAT_VERSION) {
            throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
        }
        val accountId = data.readUTF()
        val displayName = data.readUTF()
        val emailAddress = if (data.readBoolean()) data.readUTF() else null
        val providerId = AccountProviderId.fromStorageValue(data.readUTF())
            ?: throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
        val method = runCatching { AccountSignInMethod.valueOf(data.readUTF()) }.getOrNull()
            ?: throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
        val issuedAt = data.readLong()
        val expiresAt = if (data.readBoolean()) data.readLong() else null
        val refreshable = data.readBoolean()
        val source = runCatching { AccountSessionSource.valueOf(data.readUTF()) }.getOrNull()
            ?: throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
        val credentialLength = data.readInt()
        if (credentialLength !in 1..AccountSessionCredential.MAXIMUM_CHARACTERS || data.available() != credentialLength) {
            throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
        }
        val credentialBytes = ByteArray(credentialLength)
        data.readFully(credentialBytes)
        try {
            val characters = decodeUtf8(credentialBytes)
            val credential = try {
                AccountSessionCredential.fromCharacters(characters)
            } finally {
                characters.fill('\u0000')
            }
            val identity = try {
                AccountIdentity.of(accountId, displayName, emailAddress)
            } catch (_: IllegalArgumentException) {
                credential.close()
                throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
            }
            val session = try {
                AccountSession(
                    identity = identity,
                    providerId = providerId,
                    method = method,
                    issuedAtEpochMillis = issuedAt,
                    expiresAtEpochMillis = expiresAt,
                    refreshable = refreshable,
                    source = source,
                )
            } catch (_: IllegalArgumentException) {
                credential.close()
                throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
            }
            return StoredAccountSession(session = session, credential = credential)
        } finally {
            credentialBytes.fill(0)
        }
    }

    // ------------------------------------------------------------------------------------------------------- crypto

    private fun encrypt(clearBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(ADDITIONAL_DATA)
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

    private fun decrypt(payload: ByteArray): ByteArray {
        if (payload.size !in HEADER_BYTES + MIN_IV_BYTES + GCM_TAG_BYTES..MAX_ENCRYPTED_BYTES) {
            throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
        }
        val input = DataInputStream(ByteArrayInputStream(payload))
        if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT_VERSION) {
            throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
        }
        val ivSize = input.readUnsignedByte()
        val cipherSize = input.readInt()
        if (ivSize !in MIN_IV_BYTES..MAX_IV_BYTES ||
            cipherSize !in GCM_TAG_BYTES..MAX_RECORD_BYTES + GCM_TAG_BYTES ||
            payload.size != HEADER_BYTES + ivSize + cipherSize
        ) {
            throw AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)
        }
        val iv = ByteArray(ivSize)
        val encrypted = ByteArray(cipherSize)
        try {
            input.readFully(iv)
            input.readFully(encrypted)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(ADDITIONAL_DATA)
            return cipher.doFinal(encrypted)
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { key -> return key }
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
            ByteArray(buffer.remaining()).also { target -> buffer.get(target) }
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
            CharArray(buffer.remaining()).also { target -> buffer.get(target) }
        } finally {
            if (buffer.hasArray()) buffer.array().fill('\u0000')
        }
    }

    private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw AccountSessionStoreException(AccountSessionStoreError.TOO_LARGE)
            output.write(buffer, 0, count)
        }
        buffer.fill(0)
        return output.toByteArray()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /**
         * Distinct from the provider credential key on purpose: separate namespace, separate key material. The name is
         * part of the on-disk contract and must not change once a build has shipped.
         */
        const val KEY_ALIAS = "com.craftmind.account.session.aesgcm.v1"
        const val DIRECTORY_NAME = "account_session"
        const val FILE_NAME = "session.account"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAGIC = 0x434D4153
        const val FORMAT_VERSION = 1
        const val HEADER_BYTES = 10
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 16
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8

        /** Metadata plus a credential of at most [AccountSessionCredential.MAXIMUM_CHARACTERS] characters. */
        const val MAX_RECORD_BYTES = 32 * 1024
        const val MIN_RECORD_BYTES = 24
        const val MAX_ENCRYPTED_BYTES = HEADER_BYTES + MAX_IV_BYTES + MAX_RECORD_BYTES + GCM_TAG_BYTES

        /** Binds a ciphertext to this store, so a record from another CraftMind store cannot be replayed here. */
        val ADDITIONAL_DATA = "craftmind.account.session.v1".toByteArray(StandardCharsets.UTF_8)
    }
}
