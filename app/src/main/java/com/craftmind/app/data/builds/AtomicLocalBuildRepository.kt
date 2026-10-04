package com.craftmind.app.data.builds

import android.content.Context
import android.util.AtomicFile
import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.BuildDiff
import com.craftmind.app.domain.buildplan.BuildHistoryPolicy
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildRepositoryError
import com.craftmind.app.domain.buildplan.BuildRepositoryException
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.LocalBuildRepository
import com.craftmind.app.domain.buildplan.ValidatedBuildPlan
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
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

/** Atomic app-private history. Every accepted revision is retained; failed writes leave state intact. */
class AtomicLocalBuildRepository(
    context: Context,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
    private val historyPolicy: BuildHistoryPolicy = BuildHistoryPolicy(),
) : LocalBuildRepository {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, DATABASE_NAME))
    private val mutex = Mutex()
    private val mutableRecords = MutableStateFlow<List<LocalBuildRecord>>(emptyList())
    private var loaded = false

    override val records: StateFlow<List<LocalBuildRecord>> = mutableRecords.asStateFlow()

    override suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                mutableRecords.value = readRecords()
                loaded = true
            }
        }
    }

    override suspend fun save(plan: ValidatedBuildPlan, request: BuildRequest): LocalBuildRecord =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                val change = historyPolicy.appendInitial(mutableRecords.value, plan, request, nowEpochMillis())
                persist(change.records)
                mutableRecords.value = change.records
                change.appended
            }
        }

    override suspend fun appendRefinement(
        baseRecordId: String,
        plan: ValidatedBuildPlan,
        request: BuildEditRequest,
        diff: BuildDiff,
    ): LocalBuildRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val change = historyPolicy.appendRefinement(
                records = mutableRecords.value,
                baseRecordId = baseRecordId,
                plan = plan,
                request = request,
                diff = diff,
                savedAtEpochMillis = nowEpochMillis(),
            )
            persist(change.records)
            mutableRecords.value = change.records
            change.appended
        }
    }

    override suspend fun revertTo(
        buildId: String,
        targetVersion: Int,
        expectedCurrentRecordId: String,
    ): LocalBuildRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val change = historyPolicy.appendRevert(
                records = mutableRecords.value,
                buildId = buildId,
                targetVersion = targetVersion,
                expectedCurrentRecordId = expectedCurrentRecordId,
                savedAtEpochMillis = nowEpochMillis(),
            )
            persist(change.records)
            mutableRecords.value = change.records
            change.appended
        }
    }

    private fun ensureLoaded() {
        if (!loaded) {
            mutableRecords.value = readRecords()
            loaded = true
        }
    }

    private fun readRecords(): List<LocalBuildRecord> {
        if (!file.baseFile.exists()) return emptyList()
        return try {
            val bytes = file.openRead().use { it.readBounded(BuildPlanLimits.MAX_HISTORY_FILE_BYTES) }
            try {
                val records = json.decodeFromString(ListSerializer(LocalBuildRecord.serializer()), String(bytes, StandardCharsets.UTF_8))
                if (!historyPolicy.isValidHistory(records)) throw BuildRepositoryException(BuildRepositoryError.INVALID_RECORD)
                records.sortedWith(compareByDescending<LocalBuildRecord> { it.savedAtEpochMillis }.thenByDescending { it.version })
            } finally {
                bytes.fill(0)
            }
        } catch (error: BuildRepositoryException) {
            throw error
        } catch (_: IOException) {
            throw BuildRepositoryException(BuildRepositoryError.STORAGE_FAILURE)
        } catch (_: SerializationException) {
            throw BuildRepositoryException(BuildRepositoryError.INVALID_RECORD)
        } catch (_: IllegalArgumentException) {
            throw BuildRepositoryException(BuildRepositoryError.INVALID_RECORD)
        }
    }

    private fun persist(records: List<LocalBuildRecord>) {
        val serialized = try {
            json.encodeToString(ListSerializer(LocalBuildRecord.serializer()), records)
                .toByteArray(StandardCharsets.UTF_8)
        } catch (_: Exception) {
            throw BuildRepositoryException(BuildRepositoryError.STORAGE_FAILURE)
        }
        if (serialized.size > BuildPlanLimits.MAX_HISTORY_FILE_BYTES) {
            serialized.fill(0)
            throw BuildRepositoryException(BuildRepositoryError.HISTORY_LIMIT_REACHED)
        }
        try {
            writeAtomically(serialized)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw BuildRepositoryException(BuildRepositoryError.STORAGE_FAILURE)
        } finally {
            serialized.fill(0)
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        try {
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw BuildRepositoryException(BuildRepositoryError.HISTORY_LIMIT_REACHED)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    private companion object {
        // Keeping the v1 file name lets kotlinx serialization apply additive defaults to Phase 2 records.
        const val DATABASE_NAME = "local-build-records-v1.json"
    }
}
