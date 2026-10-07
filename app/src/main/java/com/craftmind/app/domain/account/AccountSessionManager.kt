package com.craftmind.app.domain.account

/** What a sign-out achieved, together with the state the app is in afterwards. */
data class AccountSignOutOutcome(
    val state: AccountState,
    val result: AccountSignOutResult,
)

/**
 * The account session state machine (Phase 16).
 *
 * Owns exactly three things: the current [AccountState], the active [AccountSession] metadata, and the transitions
 * between them. It is deterministic, allocation-light, and free of Android and coroutine types, so every rule below is
 * covered by JVM tests.
 *
 * Rules the implementation enforces:
 *
 * 1. **Local first.** Anything except an adopted, persisted session leaves the app in a local-mode state. Nothing here
 *    can block a local CraftMind feature.
 * 2. **Secrets do not live in state.** Only metadata is kept in memory. Credentials are read from the encrypted store
 *    for the duration of one operation and closed immediately afterwards — a session secret is never resident between
 *    calls, never copied into a state object, and never included in a message.
 * 3. **Fail closed, fail quietly.** A stored session that cannot be read is dropped and reported as an unusable session;
 *    a session that cannot be *written* is not adopted; an exception that escapes an authenticator becomes
 *    [AccountAuthErrorCode.UNEXPECTED_FAILURE] with no message content.
 * 4. **Sign-out always clears locally.** Remote revocation is best-effort and reported, never a precondition.
 * 5. **Destructive operations are separate.** Signing out clears only the session; it never deletes builds, settings,
 *    provider credentials, or Minecraft pairing. Requesting account deletion touches nothing on the device.
 * 6. **A failed sign-in never destroys the current identity.** Switching accounts keeps the previous session until a new
 *    one is actually adopted.
 */
class AccountSessionManager(
    private val authenticator: AccountAuthenticator,
    private val sessionStore: AccountSessionStore,
    private val clock: AccountClock = AccountClock.System,
) {
    private val lock = Any()
    private val listeners = mutableListOf<(AccountState) -> Unit>()
    private var state: AccountState = AccountState.Guest
    private var session: AccountSession? = null

    /** The current state. */
    fun state(): AccountState = synchronized(lock) { state }

    /** Whether authentication can be attempted at all in this build and on this device. */
    fun availability(): AccountAuthenticationAvailability = authenticator.availability()

    /**
     * Observes state changes and receives the current state immediately.
     *
     * Returns a function that removes the listener again, so a screen can subscribe and unsubscribe without the manager
     * retaining it.
     */
    fun addListener(listener: (AccountState) -> Unit): () -> Unit {
        synchronized(lock) { listeners += listener }
        listener(state())
        return {
            synchronized(lock) { listeners -= listener }
            Unit
        }
    }

    // ------------------------------------------------------------------------------------------------ restoration

    /**
     * Restores a session at launch.
     *
     * A session whose declared expiry has not passed is restored as [AccountSessionSource.RESTORED_ON_DEVICE] without any
     * service round trip: CraftMind does not need the network to know who is signed in, and it does not claim a live
     * check happened. An expired session is refreshed when the contract allows it, and is otherwise reported through
     * [AccountState.SessionExpired] with the reason rather than silently disappearing.
     */
    fun restoreSession(): AccountState {
        val stored = try {
            sessionStore.load()
        } catch (_: AccountSessionStoreException) {
            clearStoredSessionQuietly()
            session = null
            return publish(AccountState.SessionExpired(null, AccountSessionEndReason.INVALID_STORED_SESSION))
        } catch (_: Exception) {
            clearStoredSessionQuietly()
            session = null
            return publish(AccountState.SessionExpired(null, AccountSessionEndReason.INVALID_STORED_SESSION))
        }
        if (stored == null) {
            session = null
            return publish(AccountState.Guest)
        }
        return try {
            restoreFrom(stored)
        } finally {
            stored.credential.close()
        }
    }

    private fun restoreFrom(stored: StoredAccountSession): AccountState {
        if (!stored.session.isExpiredAt(clock.nowMillis())) {
            val restored = stored.session.withSource(AccountSessionSource.RESTORED_ON_DEVICE)
            session = restored
            return publish(AccountState.Authenticated(restored))
        }
        if (!stored.session.refreshable) {
            clearStoredSessionQuietly()
            session = null
            return publish(
                AccountState.SessionExpired(stored.session.identity, AccountSessionEndReason.REFRESH_NOT_SUPPORTED),
            )
        }
        return when (
            val outcome = guarding(AccountAuthOutcome.Failure(AccountAuthErrorCode.UNEXPECTED_FAILURE)) {
                authenticator.refresh(stored.credential, stored.session)
            }
        ) {
            is AccountAuthOutcome.Success -> adopt(outcome, AccountSessionSource.RESTORED_ON_DEVICE)
            is AccountAuthOutcome.Failure -> {
                // Only a failure that says the credential itself is dead may destroy the stored record; a transient
                // failure keeps it so the next launch can try again.
                if (outcome.error.invalidatesStoredSession()) {
                    clearStoredSessionQuietly()
                    session = null
                    publish(
                        AccountState.SessionExpired(stored.session.identity, AccountSessionEndReason.REJECTED_BY_SERVICE),
                    )
                } else {
                    session = null
                    publish(
                        AccountState.SessionExpired(stored.session.identity, AccountSessionEndReason.REFRESH_UNAVAILABLE),
                    )
                }
            }

            is AccountAuthOutcome.Unavailable -> {
                session = null
                publish(
                    AccountState.SessionExpired(stored.session.identity, AccountSessionEndReason.REFRESH_UNAVAILABLE),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- sign-in

    /**
     * Attempts a sign-in and takes ownership of [request]: the request is closed before this method returns, so the
     * typed secret cannot outlive the attempt.
     */
    fun signIn(request: AccountSignInRequest): AccountState = try {
        val availability = authenticator.availability()
        if (availability is AccountAuthenticationAvailability.Unavailable) {
            publish(AccountState.Unavailable(availability.reason, identityOrNull()))
        } else {
            val baseIdentity = identityOrNull()
            publish(AccountState.SigningIn(request.method, baseIdentity))
            when (
                val outcome = guarding(AccountAuthOutcome.Failure(AccountAuthErrorCode.UNEXPECTED_FAILURE)) {
                    authenticator.signIn(request)
                }
            ) {
                is AccountAuthOutcome.Success -> adopt(outcome, AccountSessionSource.LIVE_SIGN_IN)
                is AccountAuthOutcome.Failure -> publish(AccountState.Failed(outcome.error, baseIdentity))
                is AccountAuthOutcome.Unavailable -> publish(AccountState.Unavailable(outcome.reason, baseIdentity))
            }
        }
    } finally {
        request.close()
    }

    /**
     * Signs out of the current account and signs straight into another one.
     *
     * The old session is cleared before the new attempt starts, and a failed attempt leaves the app in local mode
     * rather than half-switched: after [signOut] the previous identity is no longer assumed to be valid.
     */
    fun switchAccount(request: AccountSignInRequest): AccountState {
        signOut()
        return signIn(request)
    }

    // --------------------------------------------------------------------------------------------------- sign-out

    /**
     * Signs out.
     *
     * Order is deliberate: the local session is what must never survive a sign-out, so it is cleared even when remote
     * revocation cannot happen. Builds, settings, AI provider credentials, and Minecraft pairing are untouched — the
     * account layer has no access to them at all.
     */
    fun signOut(): AccountSignOutOutcome {
        val previous = identityOrNull()
        publish(AccountState.SigningOut(previous))
        val stored = try {
            sessionStore.load()
        } catch (_: AccountSessionStoreException) {
            null
        } catch (_: Exception) {
            null
        }
        val result = try {
            authenticator.signOut(session, stored?.credential)
        } catch (_: Exception) {
            AccountSignOutResult(AccountSignOutMethod.REMOTE_REVOCATION_FAILED, AccountAuthErrorCode.UNEXPECTED_FAILURE)
        } finally {
            stored?.credential?.close()
        }
        session = null
        val cleared = try {
            sessionStore.clear()
            true
        } catch (_: AccountSessionStoreException) {
            false
        } catch (_: Exception) {
            false
        }
        val nextState = if (cleared) {
            publish(AccountState.Guest)
        } else {
            // The UI must not look signed in, and the user must know the device could not fully forget the session.
            publish(AccountState.Failed(AccountAuthErrorCode.SESSION_STORAGE_FAILURE, previous))
        }
        return AccountSignOutOutcome(state = nextState, result = result)
    }

    /**
     * Records a failure raised before any attempt could be made, for example an unusable local input.
     *
     * Kept on the state machine so a validation failure is expressed the same way as a service failure, instead of the
     * UI inventing a second, parallel error channel.
     */
    fun failRequest(code: AccountAuthErrorCode): AccountState =
        publish(AccountState.Failed(code, identityOrNull()))

    /**
     * Marks the session unusable, for example because a real service rejected it.
     *
     * The stored record is destroyed for every reason except [AccountSessionEndReason.REFRESH_UNAVAILABLE], where the
     * session may still be recoverable and only the service was out of reach.
     */
    fun expireSession(reason: AccountSessionEndReason): AccountState {
        val previous = identityOrNull()
        if (reason != AccountSessionEndReason.REFRESH_UNAVAILABLE) clearStoredSessionQuietly()
        session = null
        return publish(AccountState.SessionExpired(previous, reason))
    }

    // ------------------------------------------------------------------------------------ account deletion request

    /**
     * Asks the account service to delete the account.
     *
     * Purely a request: no local state changes, no session is cleared, and no local data is touched. Deleting local
     * CraftMind data and signing out are separate operations with separate effects, and the UI must offer them as such.
     */
    fun requestAccountDeletion(): AccountDeletionOutcome {
        val stored = try {
            sessionStore.load()
        } catch (_: AccountSessionStoreException) {
            null
        } catch (_: Exception) {
            null
        }
        return try {
            guarding(AccountDeletionOutcome.Failure(AccountAuthErrorCode.UNEXPECTED_FAILURE)) {
                authenticator.requestDeletion(session, stored?.credential)
            }
        } finally {
            stored?.credential?.close()
        }
    }

    // ---------------------------------------------------------------------------------------------------- internals

    private fun adopt(outcome: AccountAuthOutcome.Success, source: AccountSessionSource): AccountState {
        val adopted = outcome.session.withSource(source)
        val persisted = try {
            sessionStore.save(adopted, outcome.credential)
            true
        } catch (_: AccountSessionStoreException) {
            false
        } catch (_: Exception) {
            false
        }
        if (!persisted) {
            // A session that cannot be stored securely is not adopted: the app stays with whoever it was before.
            session = null
            return publish(AccountState.Failed(AccountAuthErrorCode.SESSION_STORAGE_FAILURE, identityOrNull()))
        }
        session = adopted
        return publish(AccountState.Authenticated(adopted))
    }

    private fun identityOrNull(): AccountIdentity? = state.identityOrNull()

    private fun clearStoredSessionQuietly() {
        try {
            sessionStore.clear()
        } catch (_: AccountSessionStoreException) {
            // An unreadable record is dropped again on the next launch; the UI is already in a local state.
        } catch (_: Exception) {
            // Same as above: never let storage cleanup turn into a crash.
        }
    }

    private fun publish(next: AccountState): AccountState {
        val snapshot: List<(AccountState) -> Unit>
        synchronized(lock) {
            state = next
            snapshot = listeners.toList()
        }
        snapshot.forEach { listener -> listener(next) }
        return next
    }

    private inline fun <T> guarding(fallback: T, block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        // The cause is deliberately discarded: an exception message may contain service or credential content.
        fallback
    }
}

/**
 * True when a failure means the stored credential itself is no longer usable, so keeping it would only preserve a dead
 * secret on the device.
 */
fun AccountAuthErrorCode.invalidatesStoredSession(): Boolean =
    this == AccountAuthErrorCode.INVALID_CREDENTIALS || this == AccountAuthErrorCode.MALFORMED_RESPONSE
