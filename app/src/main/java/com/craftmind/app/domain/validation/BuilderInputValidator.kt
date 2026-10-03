package com.craftmind.app.domain.validation

import com.craftmind.app.domain.model.BuildRequest
import com.craftmind.app.domain.model.ReferenceInput

sealed interface BuilderInputValidation {
    data class Valid(val request: BuildRequest) : BuilderInputValidation
    data object MissingInput : BuilderInputValidation
    data object PromptTooLong : BuilderInputValidation
}

object BuilderInputValidator {
    const val MAX_PROMPT_LENGTH = 2_000

    fun validate(
        prompt: String,
        image: ReferenceInput.Image?,
        url: ReferenceInput.Url?,
    ): BuilderInputValidation {
        if (prompt.length > MAX_PROMPT_LENGTH) return BuilderInputValidation.PromptTooLong

        val cleanedPrompt = prompt.trim().ifEmpty { null }
        val references = listOfNotNull(image, url)
        if (cleanedPrompt == null && references.isEmpty()) {
            return BuilderInputValidation.MissingInput
        }

        return BuilderInputValidation.Valid(
            BuildRequest(
                prompt = cleanedPrompt,
                references = references,
            ),
        )
    }
}
