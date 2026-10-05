package com.craftmind.app.domain.buildplan

/**
 * Conservative, versioned-in-code allowlist of common vanilla construction blocks. This is a
 * validation catalog, not a build template. Unsupported IDs are rejected rather than passed to a
 * later world bridge. The catalog can be replaced by a version-matched registry in a later phase.
 */
object MinecraftBlockCatalog {
    private val treeWoods = setOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry")
    private val netherWoods = setOf("crimson", "warped")
    private val bambooWood = "bamboo"

    private val colors = setOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    )

    private val explicitBlocks = setOf(
        "stone", "cobblestone", "mossy_cobblestone", "stone_bricks", "mossy_stone_bricks",
        "cracked_stone_bricks", "chiseled_stone_bricks", "smooth_stone", "granite",
        "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite",
        "deepslate", "cobbled_deepslate", "polished_deepslate", "deepslate_bricks",
        "cracked_deepslate_bricks", "deepslate_tiles", "cracked_deepslate_tiles", "tuff",
        "polished_tuff", "tuff_bricks", "chiseled_tuff", "calcite", "dripstone_block",
        "sandstone", "cut_sandstone", "chiseled_sandstone", "smooth_sandstone", "red_sandstone",
        "cut_red_sandstone", "chiseled_red_sandstone", "smooth_red_sandstone", "bricks",
        "mud_bricks", "packed_mud", "mud", "clay", "terracotta", "glass", "glass_pane",
        "quartz_block", "smooth_quartz", "quartz_bricks", "chiseled_quartz_block", "quartz_pillar",
        "sea_lantern", "glowstone", "lantern", "soul_lantern", "iron_bars", "chain",
        "iron_block", "gold_block", "diamond_block", "emerald_block", "copper_block",
        "exposed_copper", "weathered_copper", "oxidized_copper", "waxed_copper_block",
        "moss_block", "moss_carpet", "grass_block", "dirt", "coarse_dirt", "rooted_dirt",
        "podzol", "mycelium", "farmland", "snow_block", "packed_ice", "blue_ice", "ice",
        "water", "torch", "soul_torch", "bookshelf", "chiseled_bookshelf", "barrel", "crafting_table",
        "white_bed", "stonecutter", "flower_pot", "oak_sign", "oak_hanging_sign", "oak_wall_sign",
        "wall_torch", "soul_wall_torch",
    )

    private val stairBases = setOf(
        "stone", "cobblestone", "mossy_cobblestone", "stone_brick", "mossy_stone_brick",
        "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite",
        "cobbled_deepslate", "polished_deepslate", "deepslate_brick", "deepslate_tile", "tuff",
        "polished_tuff", "tuff_brick", "brick", "mud_brick", "sandstone", "smooth_sandstone",
        "red_sandstone", "smooth_red_sandstone", "quartz",
        "smooth_quartz", "purpur", "prismarine", "prismarine_brick", "dark_prismarine",
    )

    private val supported: Set<String> = buildSet {
        addAll(explicitBlocks)
        colors.forEach { color ->
            add("${color}_wool")
            add("${color}_terracotta")
            add("${color}_concrete")
            add("${color}_concrete_powder")
            add("${color}_stained_glass")
            add("${color}_stained_glass_pane")
        }
        treeWoods.forEach { wood -> addWoodFamily(wood, includeLog = true) }
        addWoodFamily(bambooWood, includeLog = false)
        netherWoods.forEach { wood -> addWoodFamily(wood, includeLog = false) }
        stairBases.forEach { base ->
            add("${base}_stairs")
            add("${base}_slab")
        }
        setOf("smooth_stone", "cut_sandstone", "cut_red_sandstone").forEach { base ->
            add("${base}_slab")
        }
        addAll(
            setOf(
                "cobblestone_wall", "mossy_cobblestone_wall", "stone_brick_wall", "mossy_stone_brick_wall",
                "brick_wall", "mud_brick_wall", "sandstone_wall", "red_sandstone_wall", "nether_brick_wall",
                "red_nether_brick_wall", "end_stone_brick_wall", "cobbled_deepslate_wall", "deepslate_brick_wall",
                "deepslate_tile_wall", "polished_deepslate_wall", "tuff_wall", "polished_tuff_wall", "tuff_brick_wall",
                "granite_wall", "diorite_wall", "andesite_wall", "blackstone_wall", "polished_blackstone_wall",
                "polished_blackstone_brick_wall",
            ),
        )
    }

    private val waterloggedBlocks = supported.filterTo(mutableSetOf()) { id ->
        id.endsWith("_stairs") || id.endsWith("_slab") || id.endsWith("_fence") ||
            id.endsWith("_fence_gate") || id.endsWith("_door") || id.endsWith("_trapdoor") ||
            id.endsWith("_pane") || id == "iron_bars" || id.endsWith("_wall")
    }

    fun supports(blockId: String): Boolean = blockId.startsWith("minecraft:") &&
        blockId.substringAfter(':') in supported

    fun supportedBlockIds(): Set<String> = supported.mapTo(sortedSetOf()) { "minecraft:$it" }

    fun validState(blockId: String, state: Map<String, String>): Boolean {
        if (!supports(blockId) || state.size > BuildPlanLimits.MAX_BLOCK_STATE_PROPERTIES) return false
        if (state.any { (key, value) ->
                key.length > BuildPlanLimits.MAX_BLOCK_STATE_VALUE_LENGTH ||
                    value.length > BuildPlanLimits.MAX_BLOCK_STATE_VALUE_LENGTH
            }
        ) return false

        val id = blockId.substringAfter(':')
        val schema = stateSchema(id)
        return state.all { (key, value) -> schema[key]?.contains(value) == true }
    }

    private fun stateSchema(id: String): Map<String, Set<String>> {
        val yesNo = setOf("true", "false")
        val directions = setOf("north", "south", "east", "west")
        val waterlogged = if (id in waterloggedBlocks) mapOf("waterlogged" to yesNo) else emptyMap()
        return when {
            id.endsWith("_stairs") -> mapOf(
                "facing" to directions,
                "half" to setOf("top", "bottom"),
                "shape" to setOf("straight", "inner_left", "inner_right", "outer_left", "outer_right"),
            ) + waterlogged

            id.endsWith("_slab") -> mapOf("type" to setOf("top", "bottom", "double")) + waterlogged

            id.endsWith("_door") -> mapOf(
                "facing" to directions,
                "half" to setOf("upper", "lower"),
                "hinge" to setOf("left", "right"),
                "open" to yesNo,
                "powered" to yesNo,
            )

            id.endsWith("_trapdoor") -> mapOf(
                "facing" to directions,
                "half" to setOf("top", "bottom"),
                "open" to yesNo,
                "powered" to yesNo,
            ) + waterlogged

            id.endsWith("_fence_gate") -> mapOf(
                "facing" to directions,
                "open" to yesNo,
                "powered" to yesNo,
                "in_wall" to yesNo,
            )

            id.endsWith("_fence") || id.endsWith("_pane") || id == "iron_bars" ->
                directions.associateWith { yesNo } + waterlogged

            id.endsWith("_wall") -> mapOf(
                "north" to setOf("none", "low", "tall"),
                "south" to setOf("none", "low", "tall"),
                "east" to setOf("none", "low", "tall"),
                "west" to setOf("none", "low", "tall"),
                "up" to yesNo,
            ) + waterlogged

            id.endsWith("_button") -> mapOf(
                "face" to setOf("floor", "wall", "ceiling"),
                "facing" to directions,
                "powered" to yesNo,
            )

            id.endsWith("_pressure_plate") -> mapOf("powered" to yesNo)

            id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") ||
                id.endsWith("_hyphae") || id.endsWith("_pillar") -> mapOf("axis" to setOf("x", "y", "z"))

            id.endsWith("_leaves") -> mapOf(
                "distance" to (1..7).map(Int::toString).toSet(),
                "persistent" to yesNo,
                "waterlogged" to yesNo,
            )

            id == "water" -> mapOf("level" to setOf("0"))
            id == "lantern" || id == "soul_lantern" -> mapOf("hanging" to yesNo)
            id == "wall_torch" || id == "soul_wall_torch" -> mapOf("facing" to directions)
            id == "bamboo_block" || id == "stripped_bamboo_block" -> mapOf("axis" to setOf("x", "y", "z"))
            else -> emptyMap()
        }
    }

    private fun MutableSet<String>.addWoodFamily(wood: String, includeLog: Boolean) {
        add("${wood}_planks")
        add("${wood}_stairs")
        add("${wood}_slab")
        add("${wood}_fence")
        add("${wood}_fence_gate")
        add("${wood}_door")
        add("${wood}_trapdoor")
        add("${wood}_pressure_plate")
        add("${wood}_button")
        if (wood in treeWoods) {
            add("${wood}_leaves")
        }
        when {
            includeLog -> {
                add("${wood}_log")
                add("stripped_${wood}_log")
                add("${wood}_wood")
                add("stripped_${wood}_wood")
            }

            wood == bambooWood -> {
                add("bamboo_block")
                add("stripped_bamboo_block")
            }

            wood in netherWoods -> {
                add("${wood}_stem")
                add("stripped_${wood}_stem")
                add("${wood}_hyphae")
                add("stripped_${wood}_hyphae")
                add("${wood}_wart_block")
            }
        }
    }
}
