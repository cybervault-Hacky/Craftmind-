package com.craftmind.app.data.ai.openai

import com.craftmind.app.domain.model.BuildLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface OpenAiCompletionContent {
    data class PlanJson(val content: String) : OpenAiCompletionContent
    data object Refused : OpenAiCompletionContent
    data object Truncated : OpenAiCompletionContent
    data object Invalid : OpenAiCompletionContent
}

internal object OpenAiResponseParser {
    private val json = Json { isLenient = false }

    fun completionContent(body: String): OpenAiCompletionContent {
        val root = body.parseObject() ?: return OpenAiCompletionContent.Invalid
        val choices = root["choices"] as? kotlinx.serialization.json.JsonArray
            ?: return OpenAiCompletionContent.Invalid
        val choice = choices.firstOrNull().asObjectOrNull() ?: return OpenAiCompletionContent.Invalid
        when (choice.string("finish_reason")) {
            "length" -> return OpenAiCompletionContent.Truncated
            "content_filter" -> return OpenAiCompletionContent.Refused
        }
        val message = choice["message"].asObjectOrNull() ?: return OpenAiCompletionContent.Invalid
        val refusal = message["refusal"]
        if (refusal != null && refusal != JsonNull && refusal.stringValueOrNull() != null) {
            return OpenAiCompletionContent.Refused
        }
        val content = message["content"].stringValueOrNull()
            ?: return OpenAiCompletionContent.Invalid
        return OpenAiCompletionContent.PlanJson(content)
    }

    fun errorCode(body: String): String? {
        val root = body.parseObject() ?: return null
        val error = root["error"].asObjectOrNull() ?: return null
        return error.string("code")?.takeIf { it.length <= BuildLimits.MAX_ERROR_CODE_CHARACTERS }
    }

    fun modelId(body: String): String? = body.parseObject()?.string("id")

    private fun String.parseObject(): JsonObject? = try {
        json.parseToJsonElement(this).asObjectOrNull()
    } catch (_: Exception) {
        null
    }

    private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.string(key: String): String? = this[key].stringValueOrNull()

    private fun JsonElement?.stringValueOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
