package com.craftmind.app.domain.model

/** Central safety limits shared by request validation, parsing, validation, and transport. */
object BuildLimits {
    const val PLAN_SCHEMA_VERSION = 1
    const val MAX_PROMPT_CHARACTERS = 2_000
    const val MAX_REQUEST_UTF8_BYTES = 16 * 1024
    const val MAX_MODEL_ID_CHARACTERS = 100
    const val MAX_PROVIDER_ID_CHARACTERS = 40
    const val MAX_PLAN_ID_CHARACTERS = 80
    const val MAX_ERROR_CODE_CHARACTERS = 80
    const val MAX_BLOCK_PROPERTY_NAME_CHARACTERS = 32
    const val MAX_DEPENDENCIES_PER_OPERATION = 64
    const val MAX_PLAN_TITLE_CHARACTERS = 120
    const val MAX_STYLE_CHARACTERS = 80
    const val MAX_WIDTH = 128
    const val MAX_LENGTH = 128
    const val MAX_HEIGHT = 96
    const val MAX_BOUNDS_VOLUME = 1_000_000L
    const val MAX_OPERATION_COUNT = 20_000
    const val MAX_MATERIAL_TYPES = 32
    const val MAX_PLAN_STEPS = 64
    const val MAX_STEP_ID_CHARACTERS = 40
    const val MAX_STEP_TITLE_CHARACTERS = 100
    const val MAX_STEP_DESCRIPTION_CHARACTERS = 500
    const val MAX_BLOCK_PROPERTIES = 8
    const val MAX_PROPERTY_VALUE_CHARACTERS = 40
    const val MAX_PLAN_RESPONSE_BYTES = 8 * 1024 * 1024
    const val MAX_PROVIDER_REQUEST_BYTES = 32 * 1024
    const val MAX_COMPLETION_TOKENS = 16_384
}
