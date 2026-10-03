package com.craftmind.app.data.credentials

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.craftmind.app.domain.ai.CredentialAlias
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

private val Context.encryptedCredentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "craftmind_encrypted_provider_credentials",
)

/** Persists only AES-GCM ciphertext; preference names reveal only a one-way alias digest. */
internal class DataStoreCredentialRecordStore(context: Context) : CredentialRecordStore {
    private val dataStore = context.applicationContext.encryptedCredentialDataStore

    override suspend fun read(alias: CredentialAlias): ByteArray? {
        val encoded = dataStore.data.first()[preferenceKey(alias)] ?: return null
        if (encoded.length > MAX_BASE64_LENGTH) throw CredentialStorageException()
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw CredentialStorageException(error)
        }
    }

    override suspend fun write(alias: CredentialAlias, ciphertext: ByteArray) {
        if (ciphertext.isEmpty() || ciphertext.size > EncryptedCredentialStore.MAX_STORED_BYTES) {
            throw CredentialStorageException()
        }
        val encoded = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        dataStore.edit { preferences -> preferences[preferenceKey(alias)] = encoded }
    }

    override suspend fun remove(alias: CredentialAlias) {
        dataStore.edit { preferences -> preferences.remove(preferenceKey(alias)) }
    }

    private fun preferenceKey(alias: CredentialAlias) =
        stringPreferencesKey("credential_${alias.value.toSha256Hex()}")

    private fun String.toSha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val MAX_BASE64_LENGTH = ((EncryptedCredentialStore.MAX_STORED_BYTES + 2) / 3) * 4
    }
}
