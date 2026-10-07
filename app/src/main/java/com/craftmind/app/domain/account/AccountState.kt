package com.craftmind.app.domain.account

/**
 * Why authentication cannot be attempted at all (Phase 16).
 *
 * This is a *service* condition, not a credential condition: it answers "can sign-in be tried right now?" and is
 * always kept separate from "the credentials were rejected".
 */
enum class AccountAvailabilityReason {
    /** This build ships no account service, so no authentication can be attempted. */
    NO_BACKEND_CONFIGURED,

    /** The device has no usable network path to the account service. */
    NETWORK_UNAVAILABLE,

    /** The account service exists but could not be reached. */
    SERVICE_UNREACHABLE,

    /** The account service is reachable but temporarily not accepting authentication. */
    MAINTENANCE,

    /** Sign-in is switched off by policy for this build or this device. */
    DISABLED_BY_POLICY,

    /** The service exists but does not implement this operation yet, and says so instead of pretending. */
    NOT_IMPLEMENTED_BY_SERVICE,
}

/** Whether authentication can be attempted, expressed as a value rather than a boolean plus reason. */
sealed interface AccountAuthenticationAvailability {
    /** The account service can be used. */
    data object Available : AccountAuthenticationAvailability

    /** No attempt is possible; [reason] says exactly why, and is surfaced to the user verbatim. */
    data class Unavailable(val reason: AccountAvailabilityReason) : AccountAuthenticationAvailability
}

/** Why a session that once existed is no longer usable. */
enum class AccountSessionEndReason {
    /** The service-declared expiry passed. */
    EXPIRED,

    /** Expired, and the service contract does not allow refreshing. */
    REFRESH_NOT_SUPPORTED,

    /** Expired or unverifiable, and the account service could not be reached to refresh it. */
    REFRESH_UNAVAILABLE,

    /** The account service rejected the stored session. */
    REJECTED_BY_SERVICE,

    /** The service reports the account as suspended, so the session cannot continue. */
    ACCOUNT_SUSPENDED,

    /** The stored session could not be read back (corrupted or unreadable storage). */
    INVALID_STORED_SESSION,
}

/**
 * The account state machine (Phase 16).
 *
 * One sealed model replaces scattered `isLoggedIn` / `hasToken` / `isGuest` booleans, so a combination such as
 * "signed in but no session" cannot be expressed. Every state except [Authenticated] means the app runs locally,
 * which is the product rule of this phase: an account is never required for local CraftMind features.
 *
 * Secrets are absent from every state by construction: states carry [AccountSession] metadata (which has no token
 * fields) or nothing at all.
 */
sealed interface AccountState {
    /** Local mode. The default state of the app, and a complete product state rather than a degraded one. */
    data object Guest : AccountState

    /** A sign-in is in flight. [previousIdentity] stays valid until a new session is actually adopted. */
    data class SigningIn(
        val method: AccountSignInMethod,
        val previousIdentity: AccountIdentity?,
    ) : AccountState

    /** A session is active. */
    data class Authenticated(val session: AccountSession) : AccountState

    /** A sign-out is in flight. Sign-out is local-first, so this is a brief transition, never a stuck state. */
    data class SigningOut(val previousIdentity: AccountIdentity?) : AccountState

    /** A previous session is no longer usable; the reason is stated rather than hidden. */
    data class SessionExpired(
        val lastIdentity: AccountIdentity?,
        val reason: AccountSessionEndReason,
    ) : AccountState

    /** Authentication could not be attempted. Distinct from [Failed]: nothing was rejected. */
    data class Unavailable(
        val reason: AccountAvailabilityReason,
        val previousIdentity: AccountIdentity?,
    ) : AccountState

    /** An attempt was made and failed. Distinct from [Unavailable]: the service answered, or the operation broke. */
    data class Failed(
        val error: AccountAuthErrorCode,
        val previousIdentity: AccountIdentity?,
    ) : AccountState
}

/** The identity the app currently considers itself to be, if any. Local mode has none. */
fun AccountState.identityOrNull(): AccountIdentity? = when (this) {
    AccountState.Guest -> null
    is AccountState.SigningIn -> previousIdentity
    is AccountState.Authenticated -> session.identity
    is AccountState.SigningOut -> previousIdentity
    is AccountState.SessionExpired -> lastIdentity
    is AccountState.Unavailable -> previousIdentity
    is AccountState.Failed -> previousIdentity
}

/** The active session, if any. Only [AccountState.Authenticated] has one. */
fun AccountState.sessionOrNull(): AccountSession? =
    (this as? AccountState.Authenticated)?.session

/**
 * True when the app is operating on local data alone.
 *
 * Every state except [AccountState.Authenticated] behaves this way, including [AccountState.SigningIn] and
 * [AccountState.Failed]: CraftMind never blocks local features because an account operation is pending or failed.
 */
val AccountState.runsLocally: Boolean
    get() = this !is AccountState.Authenticated
