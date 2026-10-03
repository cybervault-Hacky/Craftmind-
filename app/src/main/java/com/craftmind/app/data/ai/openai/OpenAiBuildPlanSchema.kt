package com.craftmind.app.data.ai.openai

import com.craftmind.app.domain.model.BuildLimits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** OpenAI strict Structured Outputs schema for the versioned, untrusted plan payload. */
internal object OpenAiBuildPlanSchema {
    const val NAME = "craftmind_build_plan_v1"

    fun jsonSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            put("schemaVersion", buildJsonObject {
                put("type", "integer")
                putJsonArray("enum") { add(JsonPrimitive(BuildLimits.PLAN_SCHEMA_VERSION)) }
            })
            put("title", stringSchema())
            put("style", stringSchema())
            put("dimensions", coordinateDimensionsSchema())
            put("origin", coordinateSchema())
            put("materials", arraySchema(materialSchema(), 1, BuildLimits.MAX_MATERIAL_TYPES))
            put("steps", arraySchema(stepSchema(), 1, BuildLimits.MAX_PLAN_STEPS))
            put("operations", arraySchema(operationSchema(), 1, BuildLimits.MAX_OPERATION_COUNT))
            put("estimatedOperationCount", buildJsonObject { put("type", "integer") })
        }
        putJsonArray("required") {
            listOf(
                "schemaVersion",
                "title",
                "style",
                "dimensions",
                "origin",
                "materials",
                "steps",
                "operations",
                "estimatedOperationCount",
            ).forEach { add(JsonPrimitive(it)) }
        }
    }

    private fun stringSchema() = buildJsonObject { put("type", "string") }

    private fun coordinateSchema() = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            put("x", integerSchema())
            put("y", integerSchema())
            put("z", integerSchema())
        }
        putRequired("x", "y", "z")
    }

    private fun coordinateDimensionsSchema() = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            put("width", integerSchema())
            put("length", integerSchema())
            put("height", integerSchema())
        }
        putRequired("width", "length", "height")
    }

    private fun materialSchema() = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            put("blockIdentifier", stringSchema())
            put("count", integerSchema())
        }
        putRequired("blockIdentifier", "count")
    }

    private fun stepSchema() = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            put("id", stringSchema())
            put("title", stringSchema())
            put("description", stringSchema())
        }
        putRequired("id", "title", "description")
    }

    private fun operationSchema() = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            put("sequence", integerSchema())
            put("position", coordinateSchema())
            put("block", blockSchema())
            put("stepId", stringSchema())
            put("rotationDegrees", buildJsonObject {
                putJsonArray("type") {
                    add(JsonPrimitive("integer"))
                    add(JsonPrimitive("null"))
                }
            })
            put("dependsOnSequences", arraySchema(integerSchema(), 0, BuildLimits.MAX_DEPENDENCIES_PER_OPERATION))
        }
        putRequired("sequence", "position", "block", "stepId", "rotationDegrees", "dependsOnSequences")
    }

    private fun blockSchema() = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            put("identifier", stringSchema())
            put("properties", arraySchema(propertySchema(), 0, BuildLimits.MAX_BLOCK_PROPERTIES))
        }
        putRequired("identifier", "properties")
    }

    private fun propertySchema() = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            put("key", stringSchema())
            put("value", stringSchema())
        }
        putRequired("key", "value")
    }

    private fun integerSchema() = buildJsonObject { put("type", "integer") }

    private fun arraySchema(itemSchema: JsonObject, minItems: Int, maxItems: Int) = buildJsonObject {
        put("type", "array")
        put("minItems", minItems)
        put("maxItems", maxItems)
        put("items", itemSchema)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putRequired(vararg names: String) {
        putJsonArray("required") { names.forEach { add(JsonPrimitive(it)) } }
    }
}
