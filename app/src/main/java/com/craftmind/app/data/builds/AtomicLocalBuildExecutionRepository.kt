package com.craftmind.app.data.builds

import android.content.Context
import android.util.AtomicFile
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRecord
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRepository
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Separate bounded, atomic app-private execution history; it never edits or replaces a BuildPlan version. */
class AtomicLocalBuildExecutionRepository(
    context: Context,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) : LocalBuildExecutionRepository {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, DATABASE_NAME))
    private val mutex = Mutex()
    private val mutableRecords = MutableStateFlow<List<LocalBuildExecutionRecord>>(emptyList())
    private var loaded = false

    override val records: StateFlow<List<LocalBuildExecutionRecord>> = mutableRecords.asStateFlow()

    override suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                mutableRecords.value = readRecords()
                loaded = true
            }
        }
    }

    override suspend fun save(record: LocalBuildExecutionRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            validate(record)
            val current = mutableRecords.value
            val existing = current.firstOrNull { it.executionId == record.executionId }
            if (existing != null && record.eventSequence < existing.eventSequence) return@withLock
            if (existing != null && record.eventSequence == existing.eventSequence &&
                (record.phase != existing.phase || record.completedOperations != existing.completedOperations ||
                    record.reasonCode != existing.reasonCode)) return@withLock

            val updated = if (existing != null) {
                current.map { if (it.executionId == record.executionId) record else it }
            } else {
                val retained = if (current.size < MAX_RECORDS) current else {
                    val oldestTerminal = current.filter { it.phase in TERMINAL_PHASES }
                        .minByOrNull(LocalBuildExecutionRecord::updatedAtEpochMillis)
                        ?: throw LocalExecutionRepositoryException("EXECUTION_HISTORY_LIMIT_REACHED")
                    current - oldestTerminal
                }
                listOf(record) + retained
            }
            persist(updated)
            mutableRecords.value = updated.sortedByDescending(LocalBuildExecutionRecord::updatedAtEpochMillis)
        }
    }

    private fun ensureLoaded() {
        if (!loaded) {
            mutableRecords.value = readRecords()
            loaded = true
        }
    }

    private fun readRecords(): List<LocalBuildExecutionRecord> {
        if (!file.baseFile.exists()) return emptyList()
        return try {
            val bytes = file.openRead().use { it.readBounded(MAX_FILE_BYTES) }
            try {
                val records = json.decodeFromString(ListSerializer(LocalBuildExecutionRecord.serializer()),
                    String(bytes, StandardCharsets.UTF_8))
                if (records.size > MAX_RECORDS || records.map(LocalBuildExecutionRecord::executionId).toSet().size != records.size ||
                    records.any { !isValid(it) }) {
                    throw LocalExecutionRepositoryException("EXECUTION_RECORDS_INVALID")
                }
                records.sortedByDescending(LocalBuildExecutionRecord::updatedAtEpochMillis)
            } finally {
                bytes.fill(0)
            }
        } catch (error: LocalExecutionRepositoryException) {
            throw error
        } catch (_: IOException) {
            throw LocalExecutionRepositoryException("EXECUTION_STORAGE_FAILURE")
        } catch (_: SerializationException) {
            throw LocalExecutionRepositoryException("EXECUTION_RECORDS_INVALID")
        } catch (_: IllegalArgumentException) {
            throw LocalExecutionRepositoryException("EXECUTION_RECORDS_INVALID")
        }
    }

    private fun persist(records: List<LocalBuildExecutionRecord>) {
        val bytes = try {
            json.encodeToString(ListSerializer(LocalBuildExecutionRecord.serializer()), records)
                .toByteArray(StandardCharsets.UTF_8)
        } catch (_: Exception) {
            throw LocalExecutionRepositoryException("EXECUTION_STORAGE_FAILURE")
        }
        if (bytes.size > MAX_FILE_BYTES) {
            bytes.fill(0)
            throw LocalExecutionRepositoryException("EXECUTION_HISTORY_LIMIT_REACHED")
        }
        try {
            val output = file.startWrite()
            try {
                output.write(bytes)
                output.fd.sync()
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        } catch (error: Exception) {
            throw LocalExecutionRepositoryException("EXECUTION_STORAGE_FAILURE")
        } finally {
            bytes.fill(0)
        }
    }

    private fun validate(record: LocalBuildExecutionRecord) {
        if (!isValid(record)) throw LocalExecutionRepositoryException("EXECUTION_RECORDS_INVALID")
    }

    private fun isValid(record: LocalBuildExecutionRecord): Boolean {
        return EXECUTION_ID.matches(record.executionId) && BUILD_ID.matches(record.buildId) &&
            RECORD_ID.matches(record.planRecordId) && record.planVersion > 0 &&
            BRIDGE_ID.matches(record.bridgeId) && record.completedOperations in 0..record.totalOperations &&
            record.totalOperations in 1..MAX_OPERATIONS && record.eventSequence > 0 &&
            record.createdAtEpochMillis > 0 && record.updatedAtEpochMillis >= record.createdAtEpochMillis &&
            DIMENSION.matches(record.dimensionId) && WORLD_SESSION.matches(record.worldSessionId) &&
            record.resolvedOrigin.x in -MAX_COORDINATE..MAX_COORDINATE &&
            record.resolvedOrigin.z in -MAX_COORDINATE..MAX_COORDINATE && record.resolvedOrigin.y in -2048..2048 &&
            (record.reasonCode == null || ERROR_CODE.matches(record.reasonCode)) &&
            (record.failedOperationIndex == null || record.failedOperationIndex in 0 until record.totalOperations) &&
            (record.phase != MinecraftExecutionPhase.COMPLETED || record.completedOperations == record.totalOperations) &&
            (record.phase != MinecraftExecutionPhase.FAILED || record.reasonCode != null)
    }

    private fun java.io.InputStream.readBounded(maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        try {
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                total += count
                if (total > maximumBytes) throw LocalExecutionRepositoryException("EXECUTION_HISTORY_LIMIT_REACHED")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    class LocalExecutionRepositoryException(val reasonCode: String) : Exception(reasonCode)

    private companion object {
        const val DATABASE_NAME = "local-build-executions-v1.json"
        const val MAX_RECORDS = 100
        const val MAX_FILE_BYTES = 256 * 1024
        const val MAX_OPERATIONS = 4096
        const val MAX_COORDINATE = 30_000_000
        val TERMINAL_PHASES = setOf(MinecraftExecutionPhase.COMPLETED, MinecraftExecutionPhase.FAILED, MinecraftExecutionPhase.CANCELLED)
        val EXECUTION_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        val BUILD_ID = Regex("[A-Za-z0-9_-]{1,80}")
        val RECORD_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val BRIDGE_ID = Regex("bridge-[0-9a-f]{32}")
        val DIMENSION = Regex("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")
        val WORLD_SESSION = Regex("[A-Za-z0-9_-]{1,128}")
        val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}
