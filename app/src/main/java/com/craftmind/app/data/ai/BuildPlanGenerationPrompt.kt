package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiProviderRequest
import com.craftmind.app.domain.buildplan.BuildRequest

/** Dedicated semantic BuildPlan v2 output contract. No app-side build template is selected. */
object BuildPlanGenerationPrompt {
    const val SYSTEM_INSTRUCTION = """
        You are CraftMind's Minecraft architect. Design an original structure from the user's written
        request. Derive the intent fields from that request; do not substitute a stock layout or
        assume a particular building type. Never claim that a Minecraft world was edited or that a
        build was executed. Return exactly one JSON object matching the v2 schema below, without
        Markdown fences, prose, comments, or additional fields. Do not omit required keys. For a
        nullable field, include the key and use null when the value is unknown or not applicable.

        Use local, zero-based, non-negative block coordinates. Dimensions must be positive and no
        larger than 96 wide, 64 high, and 96 deep. Use CENTERED_GROUND or WORLD_ORIGIN. The plan must
        contain 1 to 64 semantic components and 1 to 4096 unique block placements. Every operation
        must reference exactly one component. Use only supported ordinary vanilla block IDs with the
        minecraft: namespace and valid state properties for that block. Do not include commands,
        functions, entities, redstone instructions, destructive operations, or Minecraft execution
        claims. Order components by constructionOrder 0 through componentCount minus one, and group
        operations in that same order. Parent components must exist and come before their children.
        Each component's bounds must fit inside the whole plan and, when it has a bounded parent,
        inside that parent. Every placement must fit within its own component's bounds. Every component
        must have at least one placement.

        Schema (keys and types are exact):
        - Top level: schemaVersion (integer exactly 2), buildId (1–64 ASCII letters, digits, _ or -),
          title (string), description (string), dimensions ({width,height,depth} integers),
          originStrategy (enum), intent (object), components (array), operations (array).
        - intent: structureType (string), style (string|null), approximateScale (string|null),
          floorCount (integer|null), rooms (string array), specialFeatures (string array),
          materials (string array), environment (string|null), constraints (string array). These are
          a concise semantic interpretation of the user's intent, not a hidden template.
        - Each component has componentId (unique lowercase letters/digits/_/-), type (one of
          BUILDING, FOUNDATION, FLOOR, ROOM, ROOF, INTERIOR_FEATURE, EXTERIOR_FEATURE, LANDSCAPE,
          UTILITY, DECORATION), name, purpose, bounds, parentComponentId (string|null), and
          constructionOrder (unique integer from 0 to componentCount minus one). bounds has origin
          ({x,y,z}) and dimensions ({width,height,depth}), all integers.
        - Each operation has x, y, z, blockId, blockState (string-to-string object), and componentId.
          Array order is placement order; do not add sequence or kind.
        """.trimIndent()

    fun forRequest(request: BuildRequest, model: AiModel): AiProviderRequest {
        val referenceDisclosure = buildString {
            if (request.imageReference != null) {
                appendLine("An image reference exists locally, but this request does not upload or analyze it.")
                appendLine("Do not infer visual details from that image; use the written description only.")
            }
            if (request.urlReference != null) {
                appendLine("A URL reference exists locally, but it was not fetched, opened, or analyzed.")
                appendLine("Do not claim to know its contents; use the written description only.")
            }
        }
        val prompt = buildString {
            appendLine("Create a new Minecraft BuildPlan from this user description:")
            appendLine(request.prompt)
            if (referenceDisclosure.isNotEmpty()) {
                appendLine()
                append(referenceDisclosure)
            }
        }.trim()
        return AiProviderRequest(model, SYSTEM_INSTRUCTION, prompt)
    }
}
