package com.craftmind.app.data.account

import android.content.Context
import android.util.AtomicFile
import com.craftmind.app.domain.account.LocalOwnershipMarker
import com.craftmind.app.domain.account.LocalOwnershipMarkerStore
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Stores the local ownership marker as a two-line record (Phase 16 §12).
 *
 * The marker says "this device held CraftMind data before accounts existed" and contains nothing else: no account ID, no
 * identifier, no secret and no user content, so it is not encrypted and needs no Keystore key. It is written through
 * [AtomicFile], so a crash mid-write leaves the previous record intact rather than a truncated one — the property the
 * migration relies on to be safe if it is interrupted.
 */
class FileLocalOwnershipMarkerStore(context: Context) : LocalOwnershipMarkerStore {
    private val appContext = context.applicationContext

    @Synchronized
    override fun read(): LocalOwnershipMarker? {
        val file = markerFile()
        if (!file.baseFile.exists()) return null
        return try {
            val text = file.openRead().use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAXIMUM_BYTES) return null
                String(bytes, StandardCharsets.UTF_8)
            }
            parse(text)
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun write(marker: LocalOwnershipMarker) {
        val file = markerFile()
        file.baseFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("ownership marker directory unavailable")
            }
        }
        val record = "schema=${marker.schemaVersion}\nmarked_at=${marker.markedAtEpochMillis}\n"
            .toByteArray(StandardCharsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(record)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        } finally {
            record.fill(0)
        }
    }

    private fun markerFile(): AtomicFile = AtomicFile(File(appContext.filesDir, FILE_NAME))

    private fun parse(text: String): LocalOwnershipMarker? {
        var schema: Int? = null
        var markedAt: Long? = null
        text.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            when (key) {
                "schema" -> schema = value.toIntOrNull()
                "marked_at" -> markedAt = value.toLongOrNull()
            }
        }
        val resolvedSchema = schema ?: return null
        val resolvedMarkedAt = markedAt ?: return null
        if (resolvedSchema < 1 || resolvedMarkedAt < 0) return null
        return LocalOwnershipMarker(schemaVersion = resolvedSchema, markedAtEpochMillis = resolvedMarkedAt)
    }

    private companion object {
        const val FILE_NAME = "local_ownership.marker"
        const val MAXIMUM_BYTES = 512
    }
}
