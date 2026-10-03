package com.craftmind.app.data.ai.openai

import com.craftmind.app.domain.model.BuildRequest
import com.craftmind.app.domain.model.TextInput
import com.craftmind.app.domain.model.BuildLimits
import com.craftmind.app.domain.planning.SupportedMaterials
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal object OpenAiRequestFactory {
    private val systemInstructions: String by lazy {
        """
        Create a deterministic Minecraft block-placement plan from the user's text idea.
        Return only the requested JSON structure. The server enforces BuildPlan schema version ${BuildLimits.PLAN_SCHEMA_VERSION}; never omit required fields.
        Use only these supported vanilla block identifiers: ${SupportedMaterials.blockIdentifiers.sorted().joinToString(", ")}.
        Coordinates are local to the plan origin: x from 0 to width-1, y from 0 to height-1, z from 0 to length-1.
        Keep width <= ${BuildLimits.MAX_WIDTH}, length <= ${BuildLimits.MAX_LENGTH}, height <= ${BuildLimits.MAX_HEIGHT}, and operations <= ${BuildLimits.MAX_OPERATION_COUNT}. Use a compact useful build that fits the output budget.
        Operation sequence values must be contiguous from 0. Do not place two blocks at the same coordinate. List each material with the exact count used by operations. Every operation must reference a declared step. Dependencies may reference only earlier operations. Use no image or URL references.
        Never claim the plan has been placed inside Minecraft. Do not include explanations outside the JSON output.
        """.trimIndent()
    }

    fun createCompletionRequest(request: BuildRequest, modelId: String): String? {
        val text = request.inputs.singleOrNull() as? TextInput ?: return null
        if (text.text.isBlank() || text.text.length > BuildLimits.MAX_PROMPT_CHARACTERS) return null
        return buildJsonObject {
            put("model", modelId)
            put("max_completion_tokens", BuildLimits.MAX_COMPLETION_TOKENS)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemInstructions)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", text.text)
                })
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", OpenAiBuildPlanSchema.NAME)
                    put("strict", true)
                    put("schema", OpenAiBuildPlanSchema.jsonSchema())
                }
            }
        }.toString()
    }
}
