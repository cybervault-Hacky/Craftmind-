package com.craftmind.app.domain.account

/**
 * The authentication boundary (Phase 16).
 *
 * This is the single seam through which CraftMind can ever talk to an account service. It is provider-neutral: nothing
 * here names a vendor, an SDK, an endpoint, or a token format, so a real backend (or a different one) can be dropped in
 * by implementing this interface — and nothing else in the app has to change.
 *
 * The contract is **synchronous and deterministic** on purpose. A real implementation performs its network work inside
 * the call and is executed by the platform layer off the main thread; keeping the boundary free of coroutine and
 * framework types means the whole account state machine stays JVM-testable, and it makes "what happens when the service
 * is slow or unreachable" a property of one implementation instead of a property of every caller.
 *
 * Rules every implementation must follow:
 * * **Never invent a session.** A session may only be produced from a real service response.
 * * **Never throw for an expected condition.** Unreachable service, rejected credentials, malformed payloads, and
 *   cancellations are outcomes ([AccountAuthOutcome.Failure], [AccountAuthOutcome.Unavailable]), not exceptions. An
 *   exception that does escape is treated as [AccountAuthErrorCode.UNEXPECTED_FAILURE] and its message is discarded.
 * * **Never leak secrets.** Returned credentials live in [AccountSessionCredential]; errors carry codes only. No
 *   implementation may put a token, a password, or a raw service response into a state object, a message, or a log.
 * * **Keep account identity separate.** An account service response must never be used to satisfy an AI provider
 *   request, and provider credentials must never be sent to an account service.
 * * **Report availability honestly.** [availability] is how the UI knows whether sign-in can be attempted at all, so it
 *   must answer for this build and this device now — never optimistically.
 */
interface AccountAuthenticator {
    /** Which account service this boundary talks to. */
    val providerId: AccountProviderId

    /** Whether authentication can be attempted right now. */
    fun availability(): AccountAuthenticationAvailability

    /**
     * Attempts to establish a session.
     *
     * The implementation reads the secret through [AccountSignInRequest.useSecret] and must not retain it; the caller
     * closes the request after this call returns.
     */
    fun signIn(request: AccountSignInRequest): AccountAuthOutcome

    /**
     * Validates a stored session against the service and returns it (possibly renewed) on success.
     *
     * Used when a restored session must be re-verified or refreshed.
     */
    fun restore(credential: AccountSessionCredential, session: AccountSession): AccountAuthOutcome

    /** Renews an expired session when the service contract allows it. */
    fun refresh(credential: AccountSessionCredential, session: AccountSession): AccountAuthOutcome

    /**
     * Best-effort remote revocation.
     *
     * This is called *after* the decision to sign out has been taken: a backend that cannot revoke must not prevent the
     * user from leaving, so the caller clears local state regardless of what this returns.
     */
    fun signOut(session: AccountSession?, credential: AccountSessionCredential?): AccountSignOutResult

    /**
     * Asks the account service to delete the account.
     *
     * This is a *request*, not a local deletion: it never deletes local CraftMind data, and local data deletion is not
     * this layer's responsibility.
     */
    fun requestDeletion(session: AccountSession?, credential: AccountSessionCredential?): AccountDeletionOutcome
}
