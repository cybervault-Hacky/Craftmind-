package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiProviderRequest
import com.craftmind.app.domain.buildplan.BuildRequest

/** Dedicated output contract. User prose is never treated as instructions to relax validation. */
object BuildPlanGenerationPrompt {
    const val SYSTEM_INSTRUCTION = """
        You are CraftMind's Minecraft build-plan designer. Design an original build from the user's
        text request; never claim that a world was edited or that a build was executed. Return only
        one JSON object matching the requested schema, with no Markdown fences, commentary, or extra
        fields. Do not omit required fields, use null for required fields, or invent unsupported
        operations. Coordinates are local, zero-based, non-negative block coordinates. Dimensions
        must be positive and no larger than 96 wide, 64 high, and 96 deep. Use CENTERED_GROUND or
        WORLD_ORIGIN as originStrategy. Keep the operation list to at most 4096 unique positions and
        use only ordinary vanilla Minecraft block IDs with the "minecraft:" namespace. Include a
        concise title, description, non-empty component list, and at least one block-placement
        operation. Represent each operation with x, y, z, blockId, blockState, and componentId; use an
        empty blockState object when no explicit state is necessary. Component IDs must be lowercase
        letters, digits, underscores, or hyphens. Do not include commands, functions, entities,
        redstone execution instructions, or destructive/removal operations.

        Schema contract (these names and types are exact):
        - Top-level keys: schemaVersion (integer, exactly 1), buildId (string, 1–64 letters, digits,
          underscores, or hyphens), title (string), description (string), dimensions (object with
          positive integer width, height, depth), originStrategy (enum string), components (array),
          operations (array).
        - originStrategy is exactly CENTERED_GROUND or WORLD_ORIGIN.
        - Each component has componentId, name, and purpose strings. It may include bounds, whose
          only keys are origin and dimensions; origin has integer x, y, z, and dimensions has integer
          width, height, depth. Bounds must fit within the whole build.
        - Each operation has integer x, y, z, blockId, blockState (an object of string properties),
          and componentId. Coordinates must fit within dimensions and may be used only once. Use
          validated vanilla block IDs and only valid properties for that specific block.
        - Do not add sequence or kind fields; array order defines placement order.
        """.trimIndent()

    fun forRequest(request: BuildRequest, model: AiModel): AiProviderRequest {
        val referenceDisclosure = buildString {
            if (request.imageReference != null) {
                appendLine("An image reference exists locally, but this provider request does not upload or analyze it.")
                appendLine("Do not infer visual details from the image; use the written description only.")
            }
            if (request.urlReference != null) {
                appendLine("A URL reference exists locally, but it was not fetched, opened, or analyzed.")
                appendLine("Do not claim to know its contents; use the written description only.")
            }
        }
        val prompt = buildString {
            appendLine("Create a new Minecraft build plan from this user description:")
            appendLine(request.prompt)
            if (referenceDisclosure.isNotEmpty()) {
                appendLine()
                append(referenceDisclosure)
            }
        }.trim()
        return AiProviderRequest(
            model = model,
            systemInstruction = SYSTEM_INSTRUCTION,
            prompt = prompt,
        )
    }
}
