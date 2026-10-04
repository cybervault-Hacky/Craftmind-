package com.craftmind.app.data.builds

import android.content.Context
import android.util.AtomicFile
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.BuildPlanValidator
import com.craftmind.app.domain.buildplan.BuildRepositoryException
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.LocalBuildRepository
import com.craftmind.app.domain.buildplan.ValidatedBuildPlan
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Stores only successful plans in an app-private no-backup atomic file. */
class AtomicLocalBuildRepository(
    context: Context,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
    private val validator: BuildPlanValidator = DefaultBuildPlanValidator(),
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

    override suspend fun save(plan: ValidatedBuildPlan, request: BuildRequest) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                val domainPlan = plan.plan
                if (domainPlan.status != BuildStatus.READY ||
                    domainPlan.metadata.sourceRequestId != request.requestId ||
                    domainPlan.metadata.providerId.isBlank() || domainPlan.metadata.modelId.isBlank()
                ) {
                    throw BuildRepositoryException()
                }
                val record = LocalBuildRecord(
                    recordId = "${request.requestId}:${domainPlan.planId}",
                    plan = domainPlan,
                    request = BuildRequestSnapshot(
                        prompt = request.prompt,
                        imageContentUri = request.imageReference?.contentUri,
                        imageMediaType = request.imageReference?.mediaType,
                        imageDisplayName = request.imageReference?.displayName,
                        imageSizeBytes = request.imageReference?.sizeBytes,
                        urlReference = request.urlReference?.url,
                    ),
                    savedAtEpochMillis = nowEpochMillis(),
                )
                val candidates = (listOf(record) + mutableRecords.value.filterNot { it.recordId == record.recordId })
                    .take(MAX_RECORDS)
                    .toMutableList()

                var serialized = encode(candidates)
                while (serialized.size > MAX_DATABASE_BYTES && candidates.size > 1) {
                    candidates.removeAt(candidates.lastIndex)
                    serialized.fill(0)
                    serialized = encode(candidates)
                }
                if (serialized.size > MAX_DATABASE_BYTES) {
                    serialized.fill(0)
                    throw BuildRepositoryException()
                }
                try {
                    writeAtomically(serialized)
                    mutableRecords.value = candidates.toList()
                } catch (_: Exception) {
                    throw BuildRepositoryException()
                } finally {
                    serialized.fill(0)
                }
            }
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
            val bytes = file.openRead().use { it.readBounded(MAX_DATABASE_BYTES) }
            try {
                val records = json.decodeFromString(ListSerializer(LocalBuildRecord.serializer()), String(bytes, StandardCharsets.UTF_8))
                if (records.size > MAX_RECORDS || records.any(::isInvalidRecord)) throw BuildRepositoryException()
                records
            } finally {
                bytes.fill(0)
            }
        } catch (_: IOException) {
            throw BuildRepositoryException()
        } catch (_: SerializationException) {
            throw BuildRepositoryException()
        } catch (_: IllegalArgumentException) {
            throw BuildRepositoryException()
        }
    }

    private fun isInvalidRecord(record: LocalBuildRecord): Boolean {
        val plan = record.plan
        if (plan.status != BuildStatus.READY ||
            plan.metadata.sourceRequestId.isBlank() ||
            record.recordId != "${plan.metadata.sourceRequestId}:${plan.planId}" ||
            record.request.prompt.isBlank() ||
            plan.metadata.providerId.isBlank() || plan.metadata.modelId.isBlank()
        ) return true
        return validator.validate(plan) !is BuildPlanValidationResult.Valid
    }

    private fun encode(records: List<LocalBuildRecord>): ByteArray =
        json.encodeToString(ListSerializer(LocalBuildRecord.serializer()), records)
            .toByteArray(StandardCharsets.UTF_8)

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
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw BuildRepositoryException()
            output.write(buffer, 0, count)
        }
        buffer.fill(0)
        return output.toByteArray()
    }

    private companion object {
        const val DATABASE_NAME = "local-build-records-v1.json"
        const val MAX_RECORDS = 20
        const val MAX_DATABASE_BYTES = 20 * 1024 * 1024
    }
}
