package com.craftmind.app.domain.account

/**
 * The account service contract, as the app understands it (Phase 17).
 *
 * These types are plain Kotlin on purpose: they describe what the app asks the service and what it gets back, with no
 * HTTP, JSON, or Android type in sight. The wire implementation lives in `data/account`, and every decision about what
 * an answer *means* is made here, which is what makes it testable without a socket.
 *
 * The server is authoritative. Nothing in this contract carries a client-supplied user id, status, or "authenticated"
 * flag: the app proves who it is with a session token, and the service decides the rest.
 */
interface AccountApi {
    /** True when the app was built with an account service address and can therefore attempt anything at all. */
    val isConfigured: Boolean

    fun register(
        emailAddress: String,
        password: CharArray,
        displayName: String?,
        guestIdentityId: String?,
    ): AccountApiOutcome<AccountRegistration>

    fun login(
        emailAddress: String,
        password: CharArray,
        guestIdentityId: String?,
    ): AccountApiOutcome<AccountRegistration>

    /** Exchanges a refresh token for a fresh pair. The old pair stops working. */
    fun refresh(refreshToken: CharArray): AccountApiOutcome<AccountServerSession>

    /** Asks the service who the presented access token belongs to. */
    fun currentAccount(accessToken: CharArray): AccountApiOutcome<AccountServerRecord>

    fun logout(accessToken: CharArray?, refreshToken: CharArray?): AccountApiOutcome<Unit>

    /** Introduces an anonymous guest identity to the service so it can be linked at registration. */
    fun registerGuestIdentity(guestIdentityId: String): AccountApiOutcome<Unit>
}

/** What an account-service call produced. */
sealed interface AccountApiOutcome<out T> {
    data class Success<T>(val value: T) : AccountApiOutcome<T>

    /** The service answered and refused. [code] is the service's own stable, typed reason. */
    data class Rejected(val code: AccountApiErrorCode) : AccountApiOutcome<Nothing>

    /** The service could not be reached at all: no network, DNS failure, timeout, connection refused. */
    data object Unreachable : AccountApiOutcome<Nothing>

    /** The service answered in a way that does not match the contract, so the answer is discarded, never guessed at. */
    data object Malformed : AccountApiOutcome<Nothing>
}

/**
 * Stable error codes of the account service.
 *
 * The wire strings are identical to the server's own codes, so a change on either side is caught by tests rather than
 * silently becoming UNKNOWN_ERROR at runtime.
 */
enum class AccountApiErrorCode(val wireValue: String, val retryable: Boolean) {
    MALFORMED_REQUEST("MALFORMED_REQUEST", retryable = false),
    REQUEST_TOO_LARGE("REQUEST_TOO_LARGE", retryable = false),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", retryable = false),
    INVALID_EMAIL("INVALID_EMAIL", retryable = false),
    INVALID_DISPLAY_NAME("INVALID_DISPLAY_NAME", retryable = false),
    INVALID_PASSWORD("INVALID_PASSWORD", retryable = false),
    ACCOUNT_ALREADY_EXISTS("ACCOUNT_ALREADY_EXISTS", retryable = false),
    ACCOUNT_NOT_FOUND("ACCOUNT_NOT_FOUND", retryable = false),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", retryable = false),
    ACCOUNT_SUSPENDED("ACCOUNT_SUSPENDED", retryable = false),
    SESSION_EXPIRED("SESSION_EXPIRED", retryable = false),
    SESSION_NOT_FOUND("SESSION_NOT_FOUND", retryable = false),
    AUTHENTICATION_REQUIRED("AUTHENTICATION_REQUIRED", retryable = false),
    INVALID_GUEST_IDENTITY("INVALID_GUEST_IDENTITY", retryable = false),
    GUEST_IDENTITY_ALREADY_LINKED("GUEST_IDENTITY_ALREADY_LINKED", retryable = false),
    PASSWORD_RESET_NOT_IMPLEMENTED("PASSWORD_RESET_NOT_IMPLEMENTED", retryable = false),
    BACKEND_UNAVAILABLE("BACKEND_UNAVAILABLE", retryable = true),
    UNKNOWN_ERROR("UNKNOWN_ERROR", retryable = true);

    /** True when a retry could plausibly succeed without the user changing anything. */
    fun isRetryable(): Boolean = retryable

    companion object {
        /** Maps a wire value to a code. An unrecognised value becomes [UNKNOWN_ERROR]; it is never guessed at. */
        fun fromWireValue(value: String?): AccountApiErrorCode =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN_ERROR
    }
}

/** An account as the service describes it. Never contains a password, a hash, or a token. */
data class AccountServerRecord(
    val userId: String,
    val emailAddress: String,
    val displayName: String,
    val status: AccountServerStatus,
    val createdAtEpochMillis: Long?,
    val updatedAtEpochMillis: Long?,
) {
    /**
     * The app's own identity model. The server's account identifier is carried through unchanged; the display name and
     * address are shown to the user exactly as the service reported them, never embellished.
     */
    fun toIdentity(): AccountIdentity = AccountIdentity.of(userId, displayName, emailAddress)

    /** Whether this record may sign in. A suspended or deleted account never may, whoever claims otherwise. */
    fun isUsableForSignIn(): Boolean = status == AccountServerStatus.ACTIVE
}

enum class AccountServerStatus(val wireValue: String) {
    ACTIVE("ACTIVE"),
    SUSPENDED("SUSPENDED"),
    DELETED("DELETED");

    companion object {
        fun fromWireValue(value: String?): AccountServerStatus =
            entries.firstOrNull { it.wireValue == value } ?: ACTIVE
    }
}

/** The tokens a session consists of, held as clearable characters and never as a rendered string. */
class AccountSessionTokens private constructor(
    private val accessToken: CharArray,
    private val refreshToken: CharArray,
) : AutoCloseable {
    private var closed: Boolean = false

    @Synchronized
    fun <T> useTokens(block: (accessToken: CharArray, refreshToken: CharArray) -> T): T {
        check(!closed) { "Session tokens have already been cleared" }
        val accessCopy = accessToken.copyOf()
        val refreshCopy = refreshToken.copyOf()
        return try {
            block(accessCopy, refreshCopy)
        } finally {
            accessCopy.fill('\u0000')
            refreshCopy.fill('\u0000')
        }
    }

    /**
     * The single representation stored on this device.
     *
     * A version tag plus the two tokens separated by a pipe. Both tokens are base64url, so a pipe can never appear
     * inside one, which makes the format unambiguous without escaping.
     */
    @Synchronized
    fun encodeForStorage(): CharArray {
        check(!closed) { "Session tokens have already been cleared" }
        val encoded = CharArray(FORMAT_VERSION.length + 1 + accessToken.size + 1 + refreshToken.size)
        FORMAT_VERSION.toCharArray().copyInto(encoded, 0)
        encoded[FORMAT_VERSION.length] = SEPARATOR
        accessToken.copyInto(encoded, FORMAT_VERSION.length + 1)
        encoded[FORMAT_VERSION.length + 1 + accessToken.size] = SEPARATOR
        refreshToken.copyInto(encoded, FORMAT_VERSION.length + 2 + accessToken.size)
        return encoded
    }

    val accessTokenCharacters: Int get() = accessToken.size

    @Synchronized
    override fun close() {
        if (!closed) {
            accessToken.fill('\u0000')
            refreshToken.fill('\u0000')
            closed = true
        }
    }

    override fun toString(): String = "AccountSessionTokens([REDACTED])"

    companion object {
        const val FORMAT_VERSION = "v1"
        const val SEPARATOR = '|'
        const val MAXIMUM_TOKEN_CHARACTERS = 512

        fun of(accessToken: CharArray, refreshToken: CharArray): AccountSessionTokens {
            require(accessToken.size in 1..MAXIMUM_TOKEN_CHARACTERS) { "access token length is out of range" }
            require(refreshToken.size in 1..MAXIMUM_TOKEN_CHARACTERS) { "refresh token length is out of range" }
            return AccountSessionTokens(accessToken.copyOf(), refreshToken.copyOf())
        }

        /** Reads back what [encodeForStorage] wrote. Returns null for anything that is not exactly that format. */
        fun decodeFromStorage(encoded: CharArray): AccountSessionTokens? {
            if (encoded.size < 8) return null
            val version = String(encoded, 0, FORMAT_VERSION.length)
            if (version != FORMAT_VERSION) return null
            val rest = encoded.size - FORMAT_VERSION.length - 1
            if (rest <= 0) return null
            val separatorIndex = indexOf(encoded, SEPARATOR, FORMAT_VERSION.length + 1)
            if (separatorIndex <= 0) return null
            val accessLength = separatorIndex - FORMAT_VERSION.length - 1
            val refreshLength = encoded.size - separatorIndex - 1
            if (accessLength !in 1..MAXIMUM_TOKEN_CHARACTERS) return null
            if (refreshLength !in 1..MAXIMUM_TOKEN_CHARACTERS) return null
            val access = CharArray(accessLength)
            val refresh = CharArray(refreshLength)
            encoded.copyInto(access, 0, FORMAT_VERSION.length + 1, separatorIndex)
            encoded.copyInto(refresh, 0, separatorIndex + 1, encoded.size)
            return AccountSessionTokens(access, refresh)
        }

        private fun indexOf(characters: CharArray, target: Char, from: Int): Int {
            for (index in from until characters.size) {
                if (characters[index] == target) return index
            }
            return -1
        }
    }
}

/** What a successful registration or sign-in produced. */
data class AccountRegistration(
    val record: AccountServerRecord,
    val session: AccountServerSession,
)

/** The session half of a refresh response, without an account record. */
data class AccountServerSession(
    val tokens: AccountSessionTokens,
    val accessExpiresAtEpochMillis: Long?,
    val refreshExpiresAtEpochMillis: Long?,
)
