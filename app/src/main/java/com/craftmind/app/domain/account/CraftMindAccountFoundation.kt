package com.craftmind.app.domain.account

/**
 * The single place where the app decides which account service it is built against (Phase 16).
 *
 * Everything else in the account layer is provider-neutral. The app composition root supplies the real service-bound
 * authenticator to [sessionManager]; the default [authenticator] remains an explicit unavailable fallback for callers
 * that have no service configured. The fallback can never create a session, and the canonical Phase 16 state machine
 * remains the only account/session model.
 */
object CraftMindAccountFoundation {
    /** The explicit unavailable default for callers that do not inject a configured account service. */
    fun authenticator(): AccountAuthenticator = NoBackendAccountAuthenticator()

    /** Builds the session state machine for an encrypted session store. */
    fun sessionManager(
        sessionStore: AccountSessionStore,
        clock: AccountClock = AccountClock.System,
        authenticator: AccountAuthenticator = authenticator(),
    ): AccountSessionManager = AccountSessionManager(
        authenticator = authenticator,
        sessionStore = sessionStore,
        clock = clock,
    )
}
