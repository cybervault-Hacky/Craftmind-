package com.craftmind.app.data.minecraft

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.craftmind.app.domain.minecraft.MinecraftBridgeProfileRepository
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.minecraftBridgePreferences: DataStore<Preferences> by preferencesDataStore(
    name = "craftmind_minecraft_bridge",
)

/** Stores only the explicitly confirmed private-LAN endpoint, public pin, and paired client IDs. */
class DataStoreMinecraftBridgeProfileRepository(context: Context) : MinecraftBridgeProfileRepository {
    private val dataStore = context.applicationContext.minecraftBridgePreferences

    override val profile: Flow<TrustedMinecraftBridge?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val host = preferences[HOST]?.takeIf { it.length in 7..15 } ?: return@map null
            val port = preferences[PORT] ?: return@map null
            val fingerprint = preferences[FINGERPRINT]?.takeIf(FINGERPRINT_PATTERN::matches) ?: return@map null
            val bridgeId = preferences[BRIDGE_ID]?.takeIf(BRIDGE_ID_PATTERN::matches) ?: return@map null
            val clientId = preferences[CLIENT_ID]?.takeIf(CLIENT_ID_PATTERN::matches) ?: return@map null
            val displayName = preferences[DISPLAY_NAME]?.takeIf { it.length in 1..48 } ?: return@map null
            val pairedAt = preferences[PAIRED_AT] ?: return@map null
            if (port !in 1024..65535 || pairedAt <= 0L) return@map null
            TrustedMinecraftBridge(host, port, fingerprint, bridgeId, clientId, displayName, pairedAt)
        }

    override suspend fun save(profile: TrustedMinecraftBridge) {
        require(profile.host.length in 7..15 && profile.port in 1024..65535)
        require(FINGERPRINT_PATTERN.matches(profile.tlsFingerprint))
        require(BRIDGE_ID_PATTERN.matches(profile.bridgeId))
        require(CLIENT_ID_PATTERN.matches(profile.clientId))
        require(profile.displayName.length in 1..48 && profile.pairedAtEpochMillis > 0L)
        dataStore.edit { preferences ->
            preferences[HOST] = profile.host
            preferences[PORT] = profile.port
            preferences[FINGERPRINT] = profile.tlsFingerprint
            preferences[BRIDGE_ID] = profile.bridgeId
            preferences[CLIENT_ID] = profile.clientId
            preferences[DISPLAY_NAME] = profile.displayName
            preferences[PAIRED_AT] = profile.pairedAtEpochMillis
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(HOST)
            preferences.remove(PORT)
            preferences.remove(FINGERPRINT)
            preferences.remove(BRIDGE_ID)
            preferences.remove(CLIENT_ID)
            preferences.remove(DISPLAY_NAME)
            preferences.remove(PAIRED_AT)
        }
    }

    private companion object {
        val HOST = stringPreferencesKey("bridge_host_ipv4")
        val PORT = intPreferencesKey("bridge_port")
        val FINGERPRINT = stringPreferencesKey("bridge_tls_sha256")
        val BRIDGE_ID = stringPreferencesKey("bridge_id")
        val CLIENT_ID = stringPreferencesKey("client_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val PAIRED_AT = longPreferencesKey("paired_at_epoch_ms")
        val FINGERPRINT_PATTERN = Regex("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}")
        val BRIDGE_ID_PATTERN = Regex("bridge-[0-9a-f]{32}")
        val CLIENT_ID_PATTERN = Regex("cm-[0-9a-f]{24}")
    }
}
