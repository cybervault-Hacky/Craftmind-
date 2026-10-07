package com.craftmind.app.domain.account

/** The account service a session belongs to. Today CraftMind ships one; the type exists so it stays explicit. */
enum class AccountProviderId(val storageValue: String) {
    /** The CraftMind account service. */
    CRAFTMIND("craftmind");

    companion object {
        fun fromStorageValue(value: String?): AccountProviderId? =
            entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * How a session was obtained.
 *
 * The distinction exists because it is a real difference the user must be able to see: a session that was restored from
 * this device has not been re-verified against the account service in this app run, and CraftMind says so instead of
 * implying a live check happened. Phase 16 has no backend, so neither value is reachable in a shipped build; the
 * vocabulary is part of the contract a real implementation fills in.
 */
enum class AccountSessionSource {
    /** Obtained from the account service during this app run. */
    LIVE_SIGN_IN,

    /** Restored from encrypted on-device storage without a service round trip. */
    RESTORED_ON_DEVICE,
}

/**
 * A CraftMind account session: **metadata only**.
 *
 * The session deliberately contains no access token, no refresh token, no password, and no provider API key. Secrets
 * live in [AccountSessionCredential] inside the encrypted [AccountSessionStore]; everything the UI, the state machine,
 * and any future synchronisation layer needs is here, and none of it is sensitive. That separation is what makes it safe
 * for a session object to appear in a state object, a log line, or a test fixture.
 */
data class AccountSession(
    val identity: AccountIdentity,
    val providerId: AccountProviderId,
    /** How this session was obtained, so the UI can be exact about what was verified. */
    val method: AccountSignInMethod,
    val issuedAtEpochMillis: Long,
    /** Null when the account service did not declare an expiry. Never invented locally. */
    val expiresAtEpochMillis: Long?,
    /** Whether the account service contract allows this session to be refreshed. */
    val refreshable: Boolean,
    val source: AccountSessionSource,
) {
    init {
        require(issuedAtEpochMillis >= 0) { "issuedAt must be a real timestamp" }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > issuedAtEpochMillis) {
            "an expiry that is not after the issue time is a malformed session"
        }
    }

    /** True when the service-declared expiry has passed. A session with no declared expiry never expires locally. */
    fun isExpiredAt(nowMillis: Long): Boolean = expiresAtEpochMillis?.let { nowMillis >= it } ?: false

    /** Total declared validity, or null when the service declared no expiry. */
    fun declaredValidityMillis(): Long? = expiresAtEpochMillis?.let { it - issuedAtEpochMillis }

    fun withSource(source: AccountSessionSource): AccountSession = copy(source = source)
}
