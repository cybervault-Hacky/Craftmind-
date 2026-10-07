package com.craftmind.app.data.account

import android.content.Context
import android.util.AtomicFile
import com.craftmind.app.domain.account.GuestIdentityStore
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Stores this device's anonymous guest identity (Phase 17 §7).
 *
 * A small file, read and written atomically:
 *
 * * it is **not** a preference, so nothing about it can be swept into a settings backup or read by a preference screen;
 * * it is **not** a credential, so it is not encrypted and does not belong in the Keystore — encrypting a public,
 *   device-generated tag would only make the privacy claim harder to verify;
 * * it is **not** in the account session store, which holds the one thing that must never be confused with it.
 *
 * The identity contains nothing derived from the device, so this file describes an anonymous browser-like tag and no
 * more. Deleting app data deletes it, which is expected and stated in the app.
 */
class FileGuestIdentityStore(context: Context) : GuestIdentityStore {
    private val appContext = context.applicationContext

    @Synchronized
    override fun read(): String? {
        val file = identityFile()
        if (!file.baseFile.exists()) return null
        return try {
            val bytes = file.openRead().use { input -> input.readBytes() }
            if (bytes.size > MAXIMUM_BYTES) return null
            String(bytes, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun write(value: String) {
        val file = identityFile()
        file.baseFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) throw IOException("guest identity directory unavailable")
        }
        val record = value.toByteArray(StandardCharsets.UTF_8)
        if (record.size > MAXIMUM_BYTES) throw IOException("guest identity is larger than expected")
        val output = file.startWrite()
        try {
            output.write(record)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun identityFile(): AtomicFile = AtomicFile(File(File(appContext.filesDir, DIRECTORY_NAME), FILE_NAME))

    private companion object {
        const val DIRECTORY_NAME = "account_identity"
        const val FILE_NAME = "guest_identity.tag"
        const val MAXIMUM_BYTES = 128
    }
}
