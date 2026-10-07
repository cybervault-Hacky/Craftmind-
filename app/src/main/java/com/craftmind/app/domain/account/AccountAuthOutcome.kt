package com.craftmind.app.domain.account

/**
 * Typed authentication failures (Phase 16).
 *
 * Errors are codes, never messages. A code cannot smuggle a token, a password, or a raw service response into the UI
 * or a log, and it keeps the wording of an error in the presentation layer where it can be reviewed. [retryable]
 * states whether offering the same action again is meaningful, so the UI can avoid pointless retry affordances.
 */
enum class AccountAuthErrorCode(val retryable: Boolean) {
    /** The account service rejected the credentials. Distinct from [AUTHENTICATION_UNAVAILABLE]. */
    INVALID_CREDENTIALS(retryable = false),

    /** The user cancelled. */
    CANCELLED(retryable = true),

    /** No network path to the account service. */
    NETWORK_UNAVAILABLE(retryable = true),

    /** The account service answered with an error. */
    SERVICE_UNAVAILABLE(retryable = true),

    /** Authentication is not offered by this build. */
    AUTHENTICATION_UNAVAILABLE(retryable = false),

    /** The service answered with data that does not satisfy the contract. */
    MALFORMED_RESPONSE(retryable = false),

    /** The attempt could not be built from what was entered, so nothing was ever sent anywhere. */
    INVALID_REQUEST(retryable = false),

    /** The session could not be persisted or removed securely on this device. */
    SESSION_STORAGE_FAILURE(retryable = true),

    /** Anything else, deliberately opaque: the cause is never surfaced. */
    UNEXPECTED_FAILURE(retryable = true),
}

/** The result of one authentication operation (sign-in, restore, or refresh). */
sealed interface AccountAuthOutcome {
    /**
     * A session was established. The credential is handed to the caller, which is responsible for closing it; it is
     * never stored in a state object and never rendered.
     */
    data class Success(
        val session: AccountSession,
        val credential: AccountSessionCredential,
    ) : AccountAuthOutcome

    /** The attempt was made and failed. */
    data class Failure(val error: AccountAuthErrorCode) : AccountAuthOutcome

    /** The attempt could not be made at all. */
    data class Unavailable(val reason: AccountAvailabilityReason) : AccountAuthOutcome
}

/** How far a sign-out actually reached: local clearing is never conditional, remote revocation is best-effort. */
enum class AccountSignOutMethod {
    /** The session was cleared on this device; there is no remote session to revoke. */
    LOCAL_ONLY,

    /** The account service confirmed revocation, then the session was cleared locally. */
    REMOTE_REVOKED,

    /** The account service could not be reached; the session was still cleared locally. */
    REMOTE_REVOCATION_SKIPPED,

    /** The account service refused revocation; the session was still cleared locally. */
    REMOTE_REVOCATION_FAILED,
}

/** What a sign-out achieved. Local clearing happens in every case; only the remote half can be unavailable. */
data class AccountSignOutResult(
    val method: AccountSignOutMethod,
    val error: AccountAuthErrorCode? = null,
)

/**
 * The result of an *account deletion request*.
 *
 * Deletion is a remote operation against an account service. It is a different operation from signing out (which only
 * clears this device) and from deleting local CraftMind data (which the account layer never performs). With no account
 * service in this build, [Unavailable] is the honest answer, and no local state is touched.
 */
sealed interface AccountDeletionOutcome {
    /** The account service accepted a deletion request. Only a real service can produce this. */
    data class Requested(val requestReference: String?) : AccountDeletionOutcome

    /** No request could be made; [reason] says why. Nothing was deleted, locally or remotely. */
    data class Unavailable(val reason: AccountAvailabilityReason) : AccountDeletionOutcome

    /** The request was made and failed. */
    data class Failure(val error: AccountAuthErrorCode) : AccountDeletionOutcome
}
