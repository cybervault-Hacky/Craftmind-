package com.craftmind.app.domain.validation

import com.craftmind.app.domain.media.ImageReferenceValidator
import com.craftmind.app.domain.media.ImageValidationResult
import com.craftmind.app.domain.model.BuildInput
import com.craftmind.app.domain.model.BuildRequest
import com.craftmind.app.domain.model.ImageReference
import com.craftmind.app.domain.model.TextInput
import com.craftmind.app.domain.model.UrlReference
import com.craftmind.app.domain.model.BuildLimits

sealed interface BuildRequestValidationResult {
    data class Valid(val request: BuildRequest) : BuildRequestValidationResult
    data class Invalid(val issue: BuildRequestValidationIssue) : BuildRequestValidationResult
}

enum class BuildRequestValidationIssue {
    EMPTY,
    TOO_MANY_INPUTS,
    DUPLICATE_INPUT_TYPE,
    PROMPT_TOO_LONG,
    REQUEST_TOO_LARGE,
    INVALID_IMAGE_REFERENCE,
    INVALID_URL_REFERENCE,
}

class BuildRequestValidator {
    fun validate(request: BuildRequest): BuildRequestValidationResult {
        if (request.inputs.isEmpty()) return invalid(BuildRequestValidationIssue.EMPTY)
        if (request.inputs.size > 3) return invalid(BuildRequestValidationIssue.TOO_MANY_INPUTS)

        val normalizedInputs = ArrayList<BuildInput>(request.inputs.size)
        var hasText = false
        var hasImage = false
        var hasUrl = false
        var estimatedUtf8Bytes = 0L

        for (input in request.inputs) {
            when (input) {
                is TextInput -> {
                    if (hasText) return invalid(BuildRequestValidationIssue.DUPLICATE_INPUT_TYPE)
                    hasText = true
                    val text = input.text.trim()
                    if (text.isEmpty()) continue
                    if (text.length > BuildLimits.MAX_PROMPT_CHARACTERS) {
                        return invalid(BuildRequestValidationIssue.PROMPT_TOO_LONG)
                    }
                    estimatedUtf8Bytes += text.length.toLong() * 4L
                    normalizedInputs += TextInput(text)
                }
                is ImageReference -> {
                    if (hasImage) return invalid(BuildRequestValidationIssue.DUPLICATE_INPUT_TYPE)
                    hasImage = true
                    val checked = ImageReferenceValidator.validate(
                        uri = input.uri,
                        mimeType = input.mimeType,
                        displayName = input.displayName,
                        sizeBytes = input.sizeBytes,
                    )
                    if (checked !is ImageValidationResult.Accepted) {
                        return invalid(BuildRequestValidationIssue.INVALID_IMAGE_REFERENCE)
                    }
                    estimatedUtf8Bytes += (input.uri.length + input.displayName.length + input.mimeType.length).toLong() * 4L
                    normalizedInputs += checked.image
                }
                is UrlReference -> {
                    if (hasUrl) return invalid(BuildRequestValidationIssue.DUPLICATE_INPUT_TYPE)
                    hasUrl = true
                    val checked = ReferenceUrlValidator.validate(input.normalizedUrl)
                    if (checked !is UrlValidationResult.Valid) {
                        return invalid(BuildRequestValidationIssue.INVALID_URL_REFERENCE)
                    }
                    estimatedUtf8Bytes += checked.normalizedUrl.length.toLong() * 4L
                    normalizedInputs += UrlReference(checked.normalizedUrl)
                }
                else -> return invalid(BuildRequestValidationIssue.INVALID_IMAGE_REFERENCE)
            }
        }

        if (normalizedInputs.isEmpty()) return invalid(BuildRequestValidationIssue.EMPTY)
        if (estimatedUtf8Bytes > BuildLimits.MAX_REQUEST_UTF8_BYTES) {
            return invalid(BuildRequestValidationIssue.REQUEST_TOO_LARGE)
        }
        return BuildRequestValidationResult.Valid(BuildRequest(normalizedInputs.toList()))
    }

    private fun invalid(issue: BuildRequestValidationIssue) = BuildRequestValidationResult.Invalid(issue)
}
