package com.craftmind.app.domain.buildplan

import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Bounded text-only notes from the initial image request; raw image bytes are never stored here. */
@Serializable
data class BuildImageAnalysis(
    val summary: String,
    val observedDetails: List<String>,
    val inferredDetails: List<String>,
    val uncertainties: List<String>,
) {
    fun isWellFormed(): Boolean =
        summary.isNotBlank() && summary.length <= BuildImageAnalysisLimits.MAX_SUMMARY_CHARACTERS &&
            observedDetails.isValidDetails(BuildImageAnalysisLimits.MAX_OBSERVED_DETAILS) &&
            inferredDetails.isValidDetails(BuildImageAnalysisLimits.MAX_INFERRED_DETAILS) &&
            uncertainties.isValidDetails(BuildImageAnalysisLimits.MAX_UNCERTAINTIES)

    fun isWithinSavedByteLimit(): Boolean {
        val encoded = ANALYSIS_JSON.encodeToString(BuildImageAnalysis.serializer(), this).toByteArray(StandardCharsets.UTF_8)
        val withinLimit = encoded.size <= BuildImageAnalysisLimits.MAX_SERIALIZED_BYTES
        encoded.fill(0)
        return withinLimit
    }
}

/** Records which selected model received the initial image and the separate, uncertain text notes. */
@Serializable
data class BuildImageAnalysisSource(
    val providerId: String,
    val modelId: String,
    val analysis: BuildImageAnalysis,
) {
    fun isWellFormed(): Boolean =
        providerId.isNotBlank() && providerId.length <= BuildImageAnalysisLimits.MAX_SOURCE_ID_LENGTH &&
            modelId.isNotBlank() && modelId.length <= BuildImageAnalysisLimits.MAX_SOURCE_ID_LENGTH &&
            analysis.isWellFormed() && analysis.isWithinSavedByteLimit() && isWithinSerializedByteLimit()

    private fun isWithinSerializedByteLimit(): Boolean {
        val encoded = SOURCE_JSON.encodeToString(BuildImageAnalysisSource.serializer(), this).toByteArray(StandardCharsets.UTF_8)
        val withinLimit = encoded.size <= BuildImageAnalysisLimits.MAX_SOURCE_SERIALIZED_BYTES
        encoded.fill(0)
        return withinLimit
    }
}

object BuildImageAnalysisLimits {
    const val MAX_SUMMARY_CHARACTERS = 800
    const val MAX_DETAIL_CHARACTERS = 240
    const val MAX_OBSERVED_DETAILS = 16
    const val MAX_INFERRED_DETAILS = 12
    const val MAX_UNCERTAINTIES = 12
    const val MAX_SOURCE_ID_LENGTH = 128
    const val MAX_SERIALIZED_BYTES = 8 * 1024
    const val MAX_SOURCE_SERIALIZED_BYTES = 9 * 1024
    const val MAX_RESPONSE_BYTES = 32 * 1024
}

private val ANALYSIS_JSON = Json { encodeDefaults = true }
private val SOURCE_JSON = Json { encodeDefaults = true }

private fun List<String>.isValidDetails(maxCount: Int): Boolean =
    size <= maxCount && all { it.isNotBlank() && it.length <= BuildImageAnalysisLimits.MAX_DETAIL_CHARACTERS }
