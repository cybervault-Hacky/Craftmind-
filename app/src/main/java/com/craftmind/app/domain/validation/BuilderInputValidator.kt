package com.craftmind.app.domain.validation

import com.craftmind.app.domain.model.BuildRequest
import com.craftmind.app.domain.model.ImageReference
import com.craftmind.app.domain.model.TextInput
import com.craftmind.app.domain.model.UrlReference
import com.craftmind.app.domain.model.BuildLimits

sealed interface BuilderInputValidation {
    data class Valid(val request: BuildRequest) : BuilderInputValidation
    data object MissingInput : BuilderInputValidation
    data object PromptTooLong : BuilderInputValidation
}

/** UI-level validation that preserves all supplied references in a typed request. */
object BuilderInputValidator {
    const val MAX_PROMPT_LENGTH = BuildLimits.MAX_PROMPT_CHARACTERS

    fun validate(
        prompt: String,
        image: ImageReference?,
        url: UrlReference?,
    ): BuilderInputValidation {
        if (prompt.length > MAX_PROMPT_LENGTH) return BuilderInputValidation.PromptTooLong

        val cleanedPrompt = prompt.trim().ifEmpty { null }
        val inputs = buildList {
            cleanedPrompt?.let { add(TextInput(it)) }
            image?.let { add(it) }
            url?.let { add(it) }
        }
        if (inputs.isEmpty()) return BuilderInputValidation.MissingInput
        return BuilderInputValidation.Valid(BuildRequest(inputs))
    }
}
