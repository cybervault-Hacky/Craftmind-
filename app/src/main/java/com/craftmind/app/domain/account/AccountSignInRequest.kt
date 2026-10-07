package com.craftmind.app.domain.account

/**
 * How a user is trying to authenticate.
 *
 * Both values are contract vocabulary for a real account service: [EMAIL_PASSWORD] for the CraftMind account service
 * and [OAUTH_AUTHORIZATION_CODE] for a future social flow. Neither is implemented in Phase 16 — listing them here
 * only means the boundary does not have to be redesigned when one is.
 */
enum class AccountSignInMethod {
    EMAIL_PASSWORD,
    OAUTH_AUTHORIZATION_CODE,
}

/**
 * One sign-in attempt's input.
 *
 * The only copy of the secret lives in a private [CharArray] that is zeroed by [close], and [toString] is redacted, so
 * a request can be passed around and logged without leaking what the user typed. This mirrors the provider credential
 * container, but it is a distinct type on purpose: a CraftMind account password is not an AI provider API key, and the
 * two must never share storage or code paths.
 */
class AccountSignInRequest private constructor(
    val method: AccountSignInMethod,
    /** Email identifier for an email/password attempt; null when the method does not use one. */
    val identifier: String?,
    private val secret: CharArray,
) : AutoCloseable {
    private var closed: Boolean = false

    /** Runs [block] with a temporary copy of the secret and zeroes the copy afterwards. */
    @Synchronized
    fun <T> useSecret(block: (CharArray) -> T): T {
        check(!closed) { "Sign-in request has already been cleared" }
        val temporaryCopy = secret.copyOf()
        return try {
            block(temporaryCopy)
        } finally {
            temporaryCopy.fill('\u0000')
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            secret.fill('\u0000')
            closed = true
        }
    }

    /** Redacted: the identifier is masked and the secret is never printed. */
    override fun toString(): String =
        "AccountSignInRequest(method=$method, identifier=${identifier?.let(::maskEmailAddress) ?: "none"}, secret=[REDACTED])"

    companion object {
        /** Bounded so a paste accident cannot turn into unbounded memory or storage use. */
        const val MAXIMUM_SECRET_CHARACTERS = 512

        /**
         * Builds an email/password attempt.
         *
         * Throws only for malformed input (blank identifier or an out-of-range secret length); a *rejected* credential
         * is an [AccountAuthOutcome.Failure], never an exception.
         */
        fun emailPassword(emailAddress: String, password: CharArray): AccountSignInRequest {
            val identifier = emailAddress.trim()
            require(identifier.isNotEmpty()) { "an email identifier is required" }
            require(password.size in 1..MAXIMUM_SECRET_CHARACTERS) { "the password length is not usable" }
            return AccountSignInRequest(AccountSignInMethod.EMAIL_PASSWORD, identifier, password.copyOf())
        }

        /** Builds an authorization-code attempt for a future OAuth flow. The code is treated as a secret. */
        fun oauthAuthorizationCode(code: CharArray): AccountSignInRequest {
            require(code.size in 1..MAXIMUM_SECRET_CHARACTERS) { "the authorization code length is not usable" }
            return AccountSignInRequest(AccountSignInMethod.OAUTH_AUTHORIZATION_CODE, null, code.copyOf())
        }
    }
}
