package com.craftmind.app.reference

import com.craftmind.app.domain.model.ReferenceInput

/** Future processors may prepare local images or supported public URLs for a planner. */
interface ReferenceProcessor {
    suspend fun process(input: ReferenceInput): ProcessedReference
}

data class ProcessedReference(
    val source: ReferenceInput,
    val mediaType: String,
    val dimensions: ReferenceDimensions? = null,
    val contentFingerprint: String? = null,
)

data class ReferenceDimensions(val width: Int, val height: Int)
