package com.craftmind.app.domain.buildplan

import com.craftmind.app.domain.reference.PublicVideoReferenceLimits
import com.craftmind.app.domain.reference.PublicVideoReferenceUrlPolicy
import com.craftmind.app.domain.reference.PublicVideoUrlValidation
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class BuildReferenceSourceType {
    RAW_GITHUB_VIDEO,
}

/** Persisted text-only provenance. It intentionally stores no video, frame bytes, title, or full URL. */
@Serializable
data class BuildReferenceAnalysisSource(
    val sourceType: BuildReferenceSourceType,
    val sourceDomain: String,
    val mediaType: String,
    val durationMillis: Long,
    /** Deterministic target timestamps for the distinct frames actually sent to the selected model. */
    val sampledTimestampsMillis: List<Long>,
    val providerId: String,
    val modelId: String,
    val analysis: BuildImageAnalysis,
) {
    val frameCount: Int get() = sampledTimestampsMillis.size

    fun isWellFormed(urlReference: String?): Boolean {
        val url = urlReference?.let(PublicVideoReferenceUrlPolicy::validate) as? PublicVideoUrlValidation.Valid
            ?: return false
        val expected = url.value
        return sourceType == BuildReferenceSourceType.RAW_GITHUB_VIDEO &&
            sourceDomain == expected.sourceDomain &&
            mediaType == expected.mediaType &&
            durationMillis in PublicVideoReferenceLimits.MIN_VIDEO_DURATION_MS..PublicVideoReferenceLimits.MAX_VIDEO_DURATION_MS &&
            sampledTimestampsMillis.size in PublicVideoReferenceLimits.MIN_FRAME_COUNT..PublicVideoReferenceLimits.MAX_FRAME_COUNT &&
            sampledTimestampsMillis.all { it in 0L until durationMillis } &&
            sampledTimestampsMillis.zipWithNext().all { (first, second) -> first < second } &&
            providerId.isNotBlank() && providerId.length <= BuildImageAnalysisLimits.MAX_SOURCE_ID_LENGTH &&
            modelId.isNotBlank() && modelId.length <= BuildImageAnalysisLimits.MAX_SOURCE_ID_LENGTH &&
            analysis.isWellFormed() && analysis.isWithinSavedByteLimit() && isWithinSerializedByteLimit()
    }

    private fun isWithinSerializedByteLimit(): Boolean {
        val encoded = SOURCE_JSON.encodeToString(BuildReferenceAnalysisSource.serializer(), this)
            .toByteArray(StandardCharsets.UTF_8)
        val withinLimit = encoded.size <= MAX_SOURCE_SERIALIZED_BYTES
        encoded.fill(0)
        return withinLimit
    }

    companion object {
        const val MAX_SOURCE_SERIALIZED_BYTES = BuildImageAnalysisLimits.MAX_SOURCE_SERIALIZED_BYTES + 512
    }
}

private val SOURCE_JSON = Json { encodeDefaults = true }
