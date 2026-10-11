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
        displayName: String,
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

    fun verifyEmail(token: CharArray): AccountApiOutcome<AccountEmailVerificationResult>
    fun resendVerification(emailAddress: String): AccountApiOutcome<AccountOperationReceipt>
    fun requestPasswordReset(emailAddress: String): AccountApiOutcome<AccountOperationReceipt>
    fun confirmPasswordReset(token: CharArray, newPassword: CharArray): AccountApiOutcome<AccountOperationReceipt>
    fun changePassword(accessToken: CharArray, currentPassword: CharArray, newPassword: CharArray): AccountApiOutcome<AccountPasswordChangeResult>
    fun listSessions(accessToken: CharArray): AccountApiOutcome<AccountSessionList>
    fun revokeSession(accessToken: CharArray, sessionId: String): AccountApiOutcome<Unit>
    fun revokeOtherSessions(accessToken: CharArray): AccountApiOutcome<AccountRevokeSessionsResult>
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
 * The wire strings are identical to the server's own codes (`backend/src/errors.js`), so a change on either side is
 * caught by tests rather than silently becoming UNKNOWN_ERROR at runtime.
 */
enum class AccountApiErrorCode(val wireValue: String, val retryable: Boolean) {
    INVALID_REQUEST("INVALID_REQUEST", retryable = false),
    REQUEST_TOO_LARGE("REQUEST_TOO_LARGE", retryable = false),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", retryable = false),
    INVALID_EMAIL("INVALID_EMAIL", retryable = false),
    INVALID_DISPLAY_NAME("INVALID_DISPLAY_NAME", retryable = false),
    INVALID_PASSWORD("INVALID_PASSWORD", retryable = false),
    ACCOUNT_ALREADY_EXISTS("ACCOUNT_ALREADY_EXISTS", retryable = false),
    ACCOUNT_NOT_FOUND("ACCOUNT_NOT_FOUND", retryable = false),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", retryable = false),
    ACCOUNT_SUSPENDED("ACCOUNT_SUSPENDED", retryable = false),
    ACCOUNT_DELETED("ACCOUNT_DELETED", retryable = false),
    SESSION_EXPIRED("SESSION_EXPIRED", retryable = false),
    SESSION_INVALID("SESSION_INVALID", retryable = false),
    REFRESH_FAILED("REFRESH_FAILED", retryable = false),
    AUTHENTICATION_REQUIRED("AUTHENTICATION_REQUIRED", retryable = false),
    INVALID_GUEST_IDENTITY("INVALID_GUEST_IDENTITY", retryable = false),
    GUEST_IDENTITY_ALREADY_LINKED("GUEST_IDENTITY_ALREADY_LINKED", retryable = false),
    EMAIL_NOT_VERIFIED("EMAIL_NOT_VERIFIED", retryable = false),
    EMAIL_VERIFICATION_TOKEN_INVALID("EMAIL_VERIFICATION_TOKEN_INVALID", retryable = false),
    EMAIL_VERIFICATION_TOKEN_EXPIRED("EMAIL_VERIFICATION_TOKEN_EXPIRED", retryable = false),
    EMAIL_VERIFICATION_TOKEN_USED("EMAIL_VERIFICATION_TOKEN_USED", retryable = false),
    PASSWORD_RESET_TOKEN_INVALID("PASSWORD_RESET_TOKEN_INVALID", retryable = false),
    PASSWORD_RESET_TOKEN_EXPIRED("PASSWORD_RESET_TOKEN_EXPIRED", retryable = false),
    PASSWORD_RESET_TOKEN_USED("PASSWORD_RESET_TOKEN_USED", retryable = false),
    CURRENT_PASSWORD_INVALID("CURRENT_PASSWORD_INVALID", retryable = false),
    INVALID_DEVICE_LABEL("INVALID_DEVICE_LABEL", retryable = false),
    SESSION_NOT_FOUND("SESSION_NOT_FOUND", retryable = false),
    CURRENT_SESSION_REVOKE_NOT_ALLOWED("CURRENT_SESSION_REVOKE_NOT_ALLOWED", retryable = false),
    INVALID_CONTENT_TYPE("INVALID_CONTENT_TYPE", retryable = false),
    CORS_ORIGIN_NOT_ALLOWED("CORS_ORIGIN_NOT_ALLOWED", retryable = false),
    HTTPS_REQUIRED("HTTPS_REQUIRED", retryable = false),
    RATE_LIMITED("RATE_LIMITED", retryable = true),
    EMAIL_DELIVERY_UNAVAILABLE("EMAIL_DELIVERY_UNAVAILABLE", retryable = true),
    PASSWORD_RESET_NOT_IMPLEMENTED("PASSWORD_RESET_NOT_IMPLEMENTED", retryable = false),

    // The account service also reports the developer, entitlement, marketplace, and security domains added after the
    // account foundation. They are mirrored here with their exact wire values because the parity test treats an
    // unknown code, not a missing one, as the thing that must never happen silently: a client that cannot name a code
    // the service really sends would report UNKNOWN_ERROR and hide a real, actionable outcome.
    // Developer access (Phase 30): bootstrap, credential, session, tool, confirmation, and grant outcomes.
    DEVELOPER_BOOTSTRAP_DISABLED("DEVELOPER_BOOTSTRAP_DISABLED", retryable = false),
    DEVELOPER_BOOTSTRAP_INVALID("DEVELOPER_BOOTSTRAP_INVALID", retryable = false),
    DEVELOPER_BOOTSTRAP_CONSUMED("DEVELOPER_BOOTSTRAP_CONSUMED", retryable = false),
    DEVELOPER_INVALID_CREDENTIALS("DEVELOPER_INVALID_CREDENTIALS", retryable = false),
    DEVELOPER_AUTHENTICATION_REQUIRED("DEVELOPER_AUTHENTICATION_REQUIRED", retryable = false),
    DEVELOPER_SESSION_EXPIRED("DEVELOPER_SESSION_EXPIRED", retryable = false),
    DEVELOPER_REFRESH_FAILED("DEVELOPER_REFRESH_FAILED", retryable = false),
    DEVELOPER_ACCESS_DENIED("DEVELOPER_ACCESS_DENIED", retryable = false),
    DEVELOPER_TOOL_UNKNOWN("DEVELOPER_TOOL_UNKNOWN", retryable = false),
    DEVELOPER_TOOL_INPUT_INVALID("DEVELOPER_TOOL_INPUT_INVALID", retryable = false),
    DEVELOPER_CONFIRMATION_INVALID("DEVELOPER_CONFIRMATION_INVALID", retryable = false),
    DEVELOPER_CONFIRMATION_EXPIRED("DEVELOPER_CONFIRMATION_EXPIRED", retryable = false),
    DEVELOPER_GRANT_ALREADY_ACTIVE("DEVELOPER_GRANT_ALREADY_ACTIVE", retryable = false),
    DEVELOPER_GRANT_NOT_FOUND("DEVELOPER_GRANT_NOT_FOUND", retryable = false),
    DEVELOPER_AI_UNAVAILABLE("DEVELOPER_AI_UNAVAILABLE", retryable = true),
    DEVELOPER_AI_RESPONSE_INVALID("DEVELOPER_AI_RESPONSE_INVALID", retryable = false),

    // Entitlement and the credit ledger.
    INSUFFICIENT_CREDITS("INSUFFICIENT_CREDITS", retryable = false),
    ENTITLEMENT_REQUIRED("ENTITLEMENT_REQUIRED", retryable = false),
    MEMBERSHIP_UNAVAILABLE("MEMBERSHIP_UNAVAILABLE", retryable = true),
    CREDIT_OPERATION_INVALID("CREDIT_OPERATION_INVALID", retryable = false),
    CREDIT_OPERATION_DUPLICATE("CREDIT_OPERATION_DUPLICATE", retryable = false),
    CREDIT_OPERATION_CONFLICT("CREDIT_OPERATION_CONFLICT", retryable = false),
    CREDIT_LEDGER_BUSY("CREDIT_LEDGER_BUSY", retryable = true),

    // Creator profiles and the accounts they may operate on.
    CREATOR_ENTITLEMENT_REQUIRED("CREATOR_ENTITLEMENT_REQUIRED", retryable = false),
    CREATOR_PROFILE_EXISTS("CREATOR_PROFILE_EXISTS", retryable = false),
    CREATOR_PROFILE_NOT_FOUND("CREATOR_PROFILE_NOT_FOUND", retryable = false),
    CREATOR_PROFILE_SUSPENDED("CREATOR_PROFILE_SUSPENDED", retryable = false),
    CREATOR_HANDLE_UNAVAILABLE("CREATOR_HANDLE_UNAVAILABLE", retryable = false),
    CREATOR_HANDLE_RESERVED("CREATOR_HANDLE_RESERVED", retryable = false),
    CREATOR_VERIFICATION_REQUIRED("CREATOR_VERIFICATION_REQUIRED", retryable = false),
    SERVER_ENTITLEMENT_REQUIRED("SERVER_ENTITLEMENT_REQUIRED", retryable = false),
    SERVER_NOT_FOUND("SERVER_NOT_FOUND", retryable = false),
    SERVER_ACCESS_DENIED("SERVER_ACCESS_DENIED", retryable = false),
    SERVER_SUSPENDED("SERVER_SUSPENDED", retryable = false),
    SERVER_LIMIT_REACHED("SERVER_LIMIT_REACHED", retryable = false),
    SERVER_SLUG_RESERVED("SERVER_SLUG_RESERVED", retryable = false),
    SERVER_SLUG_UNAVAILABLE("SERVER_SLUG_UNAVAILABLE", retryable = false),
    OWNERSHIP_REQUIRED("OWNERSHIP_REQUIRED", retryable = false),
    FEATURE_NOT_IMPLEMENTED("FEATURE_NOT_IMPLEMENTED", retryable = false),
    STORAGE_BUSY("STORAGE_BUSY", retryable = true),
    PHONE_VERIFICATION_UNAVAILABLE("PHONE_VERIFICATION_UNAVAILABLE", retryable = true),

    // Marketplace listings, jobs, proposals, orders, milestones, revisions, reports, and disputes.
    SELLER_AGREEMENT_REQUIRED("SELLER_AGREEMENT_REQUIRED", retryable = false),
    LISTING_NOT_FOUND("LISTING_NOT_FOUND", retryable = false),
    LISTING_STATE_CONFLICT("LISTING_STATE_CONFLICT", retryable = false),
    LISTING_PUBLISH_BLOCKED("LISTING_PUBLISH_BLOCKED", retryable = false),
    JOB_NOT_FOUND("JOB_NOT_FOUND", retryable = false),
    JOB_STATE_CONFLICT("JOB_STATE_CONFLICT", retryable = false),
    PROPOSAL_NOT_FOUND("PROPOSAL_NOT_FOUND", retryable = false),
    PROPOSAL_STATE_CONFLICT("PROPOSAL_STATE_CONFLICT", retryable = false),
    PROPOSAL_BLOCKED("PROPOSAL_BLOCKED", retryable = false),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", retryable = false),
    ORDER_STATE_CONFLICT("ORDER_STATE_CONFLICT", retryable = false),
    ORDER_ROLE_DENIED("ORDER_ROLE_DENIED", retryable = false),
    ORDER_COMPLETION_BLOCKED("ORDER_COMPLETION_BLOCKED", retryable = false),
    MILESTONE_NOT_FOUND("MILESTONE_NOT_FOUND", retryable = false),
    MILESTONE_STATE_CONFLICT("MILESTONE_STATE_CONFLICT", retryable = false),
    REVISION_LIMIT_REACHED("REVISION_LIMIT_REACHED", retryable = false),
    REPORT_NOT_FOUND("REPORT_NOT_FOUND", retryable = false),
    REPORT_STATE_CONFLICT("REPORT_STATE_CONFLICT", retryable = false),
    DISPUTE_NOT_FOUND("DISPUTE_NOT_FOUND", retryable = false),
    DISPUTE_STATE_CONFLICT("DISPUTE_STATE_CONFLICT", retryable = false),
    ORDER_DISPUTED("ORDER_DISPUTED", retryable = false),

    // Marketplace blocklist and referrals.
    MARKETPLACE_BLOCKED("MARKETPLACE_BLOCKED", retryable = false),
    MARKETPLACE_BLOCK_NOT_FOUND("MARKETPLACE_BLOCK_NOT_FOUND", retryable = false),
    REFERRAL_CLAIM_CONFLICT("REFERRAL_CLAIM_CONFLICT", retryable = false),

    // Security Guardian (Phase 38).
    SECURITY_EVENT_INVALID("SECURITY_EVENT_INVALID", retryable = false),
    SECURITY_ACTION_UNKNOWN("SECURITY_ACTION_UNKNOWN", retryable = false),
    SECURITY_ACTION_NOT_AUTHORIZED("SECURITY_ACTION_NOT_AUTHORIZED", retryable = false),
    SECURITY_ACTION_ARGUMENTS_INVALID("SECURITY_ACTION_ARGUMENTS_INVALID", retryable = false),
    SECURITY_INCIDENT_NOT_FOUND("SECURITY_INCIDENT_NOT_FOUND", retryable = false),
    SECURITY_ENGINE_UNAVAILABLE("SECURITY_ENGINE_UNAVAILABLE", retryable = true),
    NETWORK_ERROR("NETWORK_ERROR", retryable = true),
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
    val emailVerified: Boolean = true,
    val emailVerifiedAtEpochMillis: Long? = null,
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
        fun fromWireValue(value: String?): AccountServerStatus? =
            entries.firstOrNull { it.wireValue == value }
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
    val session: AccountServerSession?,
    val verificationRequired: Boolean = false,
    val deliveryStatus: AccountEmailDeliveryStatus = AccountEmailDeliveryStatus.NOT_APPLICABLE,
)

enum class AccountEmailDeliveryStatus { SENT, PROVIDER_ACCEPTED, DEVELOPMENT_SINK, PROVIDER_CONFIGURED, UNAVAILABLE, NOT_APPLICABLE }

data class AccountEmailVerificationResult(val record: AccountServerRecord)
data class AccountOperationReceipt(val accepted: Boolean, val deliveryMode: AccountEmailDeliveryStatus)
data class AccountPasswordChangeResult(val currentSessionRetained: Boolean, val revokedOtherSessions: Int)
data class AccountRevokeSessionsResult(val revokedSessions: Int, val currentSessionRetained: Boolean)

data class AccountRemoteSession(
    val sessionId: String,
    val createdAtEpochMillis: Long?,
    val lastUsedAtEpochMillis: Long?,
    val expiresAtEpochMillis: Long?,
    val deviceLabel: String,
    val isCurrent: Boolean,
)
data class AccountSessionList(val sessions: List<AccountRemoteSession>)

/** The session half of a refresh response, without an account record. */
data class AccountServerSession(
    val tokens: AccountSessionTokens,
    val accessExpiresAtEpochMillis: Long?,
    val refreshExpiresAtEpochMillis: Long?,
)
