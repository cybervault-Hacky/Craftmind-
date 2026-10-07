package com.craftmind.app.domain.account

/**
 * The single place where the app decides which account service it is built against (Phase 16).
 *
 * Everything else in the account layer is provider-neutral; this object is the one line a future phase changes when a
 * real CraftMind account service exists. Until then it returns [NoBackendAccountAuthenticator], so the shipped app
 * answers "authentication is not available in this build" instead of pretending otherwise, and the composition root
 * cannot accidentally wire a fake or an unreviewed service.
 */
object CraftMindAccountFoundation {
    /** The authenticator CraftMind ships with. Replace here — and only here — when a real service exists. */
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
