package com.craftmind.app.domain.planning

import com.craftmind.app.domain.model.BuildLimits
import com.craftmind.app.domain.model.BlockCoordinate
import com.craftmind.app.domain.model.BlockOperation
import com.craftmind.app.domain.model.BlockState
import com.craftmind.app.domain.model.BuildDimensions
import com.craftmind.app.domain.model.BuildPlan
import com.craftmind.app.domain.model.BuildPlanDraft
import com.craftmind.app.domain.model.BuildPlanStep
import com.craftmind.app.domain.model.MaterialRequirement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

sealed interface BuildPlanParseResult {
    data class Parsed(val draft: BuildPlanDraft) : BuildPlanParseResult
    data class Rejected(val issue: BuildPlanParseIssue) : BuildPlanParseResult
}

enum class BuildPlanParseIssue {
    RESPONSE_TOO_LARGE,
    JSON_NOT_FOUND,
    MALFORMED_JSON,
    INVALID_STRUCTURE,
    UNSUPPORTED_SCHEMA_VERSION,
    TOO_MANY_ITEMS,
}

/** Strict parser for the exact, versioned plan contract; unknown fields are rejected. */
class BuildPlanJsonParser {
    private val json = Json {
        isLenient = false
        allowTrailingComma = false
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun parse(responseText: String): BuildPlanParseResult {
        if (responseText.toByteArray(Charsets.UTF_8).size > BuildLimits.MAX_PLAN_RESPONSE_BYTES) {
            return BuildPlanParseResult.Rejected(BuildPlanParseIssue.RESPONSE_TOO_LARGE)
        }
        val trimmed = responseText.trim()
        if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) {
            return BuildPlanParseResult.Rejected(
                if (trimmed.contains('{')) BuildPlanParseIssue.MALFORMED_JSON else BuildPlanParseIssue.JSON_NOT_FOUND,
            )
        }
        val root = try {
            json.parseToJsonElement(trimmed) as? JsonObject
                ?: return BuildPlanParseResult.Rejected(BuildPlanParseIssue.INVALID_STRUCTURE)
        } catch (_: Exception) {
            return BuildPlanParseResult.Rejected(BuildPlanParseIssue.MALFORMED_JSON)
        }

        try {
            root.requireKeys(
                "schemaVersion",
                "title",
                "style",
                "dimensions",
                "origin",
                "materials",
                "steps",
                "operations",
                "estimatedOperationCount",
            )
        } catch (_: InvalidPlanJson) {
            return BuildPlanParseResult.Rejected(BuildPlanParseIssue.INVALID_STRUCTURE)
        }

        val version = root.int("schemaVersion")
            ?: return BuildPlanParseResult.Rejected(BuildPlanParseIssue.INVALID_STRUCTURE)
        if (version != BuildLimits.PLAN_SCHEMA_VERSION) {
            return BuildPlanParseResult.Rejected(BuildPlanParseIssue.UNSUPPORTED_SCHEMA_VERSION)
        }

        return try {
            val dimensions = root.obj("dimensions").also { it.requireKeys("width", "length", "height") }
            val origin = root.obj("origin").also { it.requireKeys("x", "y", "z") }
            val materialArray = root.array("materials")
            val stepArray = root.array("steps")
            val operationArray = root.array("operations")
            if (materialArray.size > BuildLimits.MAX_MATERIAL_TYPES ||
                stepArray.size > BuildLimits.MAX_PLAN_STEPS ||
                operationArray.size > BuildLimits.MAX_OPERATION_COUNT
            ) {
                return BuildPlanParseResult.Rejected(BuildPlanParseIssue.TOO_MANY_ITEMS)
            }

            val draft = BuildPlanDraft(
                schemaVersion = version,
                title = root.string("title"),
                style = root.string("style"),
                dimensions = BuildDimensions(
                    width = dimensions.int("width") ?: return invalidStructure(),
                    length = dimensions.int("length") ?: return invalidStructure(),
                    height = dimensions.int("height") ?: return invalidStructure(),
                ),
                origin = origin.coordinate(),
                materials = materialArray.map { element ->
                    val material = element.asObject().also { it.requireKeys("blockIdentifier", "count") }
                    MaterialRequirement(
                        blockIdentifier = material.string("blockIdentifier"),
                        count = material.int("count") ?: return invalidStructure(),
                    )
                },
                steps = stepArray.map { element ->
                    val step = element.asObject().also { it.requireKeys("id", "title", "description") }
                    BuildPlanStep(
                        id = step.string("id"),
                        title = step.string("title"),
                        description = step.string("description"),
                    )
                },
                operations = operationArray.map { element ->
                    val operation = element.asObject().also {
                        it.requireKeys(
                            "sequence",
                            "position",
                            "block",
                            "stepId",
                            "rotationDegrees",
                            "dependsOnSequences",
                        )
                    }
                    val block = operation.obj("block").also { it.requireKeys("identifier", "properties") }
                    val properties = block.array("properties").map { propertyElement ->
                        val property = propertyElement.asObject().also { it.requireKeys("key", "value") }
                        property.string("key") to property.string("value")
                    }
                    val propertyMap = LinkedHashMap<String, String>(properties.size)
                    for ((key, value) in properties) {
                        if (propertyMap.put(key, value) != null) return invalidStructure()
                    }
                    val rotationElement = operation.required("rotationDegrees")
                    val rotation = if (rotationElement == JsonNull) {
                        null
                    } else {
                        rotationElement.intOrNull() ?: return invalidStructure()
                    }
                    val dependencies = operation.array("dependsOnSequences").map { dependency ->
                        dependency.intOrNull() ?: return invalidStructure()
                    }
                    BlockOperation(
                        sequence = operation.int("sequence") ?: return invalidStructure(),
                        position = operation.obj("position").coordinate(),
                        block = BlockState(
                            identifier = block.string("identifier"),
                            properties = propertyMap,
                        ),
                        stepId = operation.string("stepId"),
                        rotationDegrees = rotation,
                        dependsOnSequences = dependencies,
                    )
                },
                estimatedOperationCount = root.int("estimatedOperationCount")
                    ?: return BuildPlanParseResult.Rejected(BuildPlanParseIssue.INVALID_STRUCTURE),
            )
            BuildPlanParseResult.Parsed(draft)
        } catch (_: InvalidPlanJson) {
            BuildPlanParseResult.Rejected(BuildPlanParseIssue.INVALID_STRUCTURE)
        } catch (_: Exception) {
            BuildPlanParseResult.Rejected(BuildPlanParseIssue.INVALID_STRUCTURE)
        }
    }

    private fun JsonObject.requireKeys(vararg expected: String) {
        if (keys != expected.toSet()) throw InvalidPlanJson()
    }

    private fun JsonObject.required(name: String): JsonElement =
        this[name] ?: throw InvalidPlanJson()

    private fun JsonObject.string(name: String): String =
        (required(name) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: throw InvalidPlanJson()

    private fun JsonObject.int(name: String): Int? = this[name]?.intOrNull()

    private fun JsonObject.obj(name: String): JsonObject =
        required(name) as? JsonObject ?: throw InvalidPlanJson()

    private fun JsonObject.array(name: String): JsonArray =
        required(name) as? JsonArray ?: throw InvalidPlanJson()

    private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: throw InvalidPlanJson()

    private fun JsonElement.intOrNull(): Int? =
        (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun JsonObject.coordinate(): BlockCoordinate {
        requireKeys("x", "y", "z")
        return BlockCoordinate(
            x = int("x") ?: throw InvalidPlanJson(),
            y = int("y") ?: throw InvalidPlanJson(),
            z = int("z") ?: throw InvalidPlanJson(),
        )
    }

    private fun invalidStructure() = BuildPlanParseResult.Rejected(BuildPlanParseIssue.INVALID_STRUCTURE)

    private class InvalidPlanJson : RuntimeException()
}

/** Deterministic JSON representation for plan export and future local history persistence. */
object BuildPlanJsonCodec {
    fun encode(plan: BuildPlan): String = buildJsonObject {
        put("schemaVersion", plan.schemaVersion)
        put("planId", plan.id)
        put("title", plan.title)
        put("style", plan.style)
        put("dimensions", buildJsonObject {
            put("width", plan.dimensions.width)
            put("length", plan.dimensions.length)
            put("height", plan.dimensions.height)
        })
        put("origin", plan.origin.toJson())
        putJsonArray("materials") {
            plan.materials.sortedBy { it.blockIdentifier }.forEach { material ->
                addJsonObject {
                    put("blockIdentifier", material.blockIdentifier)
                    put("count", material.count)
                }
            }
        }
        putJsonArray("steps") {
            plan.steps.forEach { step ->
                addJsonObject {
                    put("id", step.id)
                    put("title", step.title)
                    put("description", step.description)
                }
            }
        }
        putJsonArray("operations") {
            plan.operations.sortedBy { it.sequence }.forEach { operation ->
                addJsonObject {
                    put("sequence", operation.sequence)
                    put("position", operation.position.toJson())
                    put("block", buildJsonObject {
                        put("identifier", operation.block.identifier)
                        putJsonArray("properties") {
                            operation.block.properties.toSortedMap().forEach { (key, value) ->
                                addJsonObject {
                                    put("key", key)
                                    put("value", value)
                                }
                            }
                        }
                    })
                    put("stepId", operation.stepId)
                    put("rotationDegrees", operation.rotationDegrees?.let(::JsonPrimitive) ?: JsonNull)
                    putJsonArray("dependsOnSequences") {
                        operation.dependsOnSequences.sorted().forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
        }
        put("estimatedOperationCount", plan.estimatedOperationCount)
        put("generatedAtEpochMillis", plan.generatedAtEpochMillis)
        put("providerId", plan.generator.providerId)
        put("modelId", plan.generator.modelId)
    }.toString()

    private fun BlockCoordinate.toJson() = buildJsonObject {
        put("x", x)
        put("y", y)
        put("z", z)
    }
}
