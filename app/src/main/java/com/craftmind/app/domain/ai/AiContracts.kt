package com.craftmind.app.domain.ai

import com.craftmind.app.domain.model.BuildPlanDraft
import com.craftmind.app.domain.model.BuildRequest

@JvmInline
value class ProviderId(val value: String) {
    companion object {
        val OPENAI = ProviderId("openai")
    }
}

data class AiModel(val id: String)

data class AiProviderDescriptor(
    val id: ProviderId,
    val displayName: String,
    val description: String,
)

sealed interface AiProviderResult<out T> {
    data class Success<T>(val value: T) : AiProviderResult<T>
    data class Failure(val error: AiProviderError) : AiProviderResult<Nothing>
}

enum class AiProviderErrorCode {
    INVALID_API_KEY,
    UNSUPPORTED_MODEL,
    PROVIDER_UNAVAILABLE,
    NO_INTERNET,
    REQUEST_TIMED_OUT,
    RATE_LIMITED,
    REQUEST_REJECTED,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE,
    UNKNOWN,
}

data class AiProviderError(
    val code: AiProviderErrorCode,
    val retryable: Boolean = false,
)

/** Provider implementations own transport and parsing; presentation never depends on an SDK. */
interface AiProvider {
    val descriptor: AiProviderDescriptor

    suspend fun generateBuildPlan(
        request: BuildRequest,
        model: AiModel,
        credential: SecretValue,
    ): AiProviderResult<BuildPlanDraft>

    /** A small, non-generative check that the credential can access the configured model. */
    suspend fun testConnection(
        model: AiModel,
        credential: SecretValue,
    ): AiProviderResult<Unit>
}

interface AiProviderRegistry {
    fun availableProviders(): List<AiProviderDescriptor>
    fun find(providerId: ProviderId): AiProvider?
}

object CredentialLimits {
    const val MAX_API_KEY_CHARACTERS = 4_096
}

@JvmInline
value class CredentialAlias(val value: String)

/** Secret material is short-lived, never part of UI state, and redacted from string rendering. */
class SecretValue private constructor(private val characters: CharArray) {
    companion object {
        fun copyOf(value: CharArray): SecretValue = SecretValue(value.copyOf())
    }

    internal fun <T> use(block: (CharArray) -> T): T = block(characters)

    internal fun clear() {
        characters.fill('\u0000')
    }

    override fun toString(): String = "SecretValue([REDACTED])"
}

/** Only encrypted implementations may be wired into the production composition root. */
interface CredentialStore {
    suspend fun put(alias: CredentialAlias, value: SecretValue)
    suspend fun get(alias: CredentialAlias): SecretValue?
    suspend fun contains(alias: CredentialAlias): Boolean
    suspend fun remove(alias: CredentialAlias)
}
