package com.craftmind.app.domain.ai

import com.craftmind.app.domain.model.BuildLimits

object AiModelIdValidator {
    fun normalize(value: String): String? {
        val modelId = value.trim()
        if (modelId.length !in 1..BuildLimits.MAX_MODEL_ID_CHARACTERS) return null
        if (!modelId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) return null
        return modelId
    }
}
