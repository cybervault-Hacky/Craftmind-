package com.craftmind.app.domain.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 §5: the account session state machine.
 *
 * These tests pin the rules that make the account layer trustworthy without a backend: local-first clearing, fail-closed
 * behaviour, no session adopted unless it can be stored, no local data touched by anything except the operations that
 * are supposed to touch it, and no secret ever escaping into state.
 */
class AccountSessionManagerTest {
    private val store = FakeAccountSessionStore()
    private val authenticator = FakeAccountAuthenticator()

    private fun manager(): AccountSessionManager = managerAt(1_500L)

    /**
     * Seeds a stored session that the service still accepts.
     *
     * Phase 17 made restoration server-authoritative for a configured service, so "there is a live session on this
     * device" now has two halves: an encrypted record, and a service that confirms it. This helper makes that explicit
     * instead of hiding it behind a default.
     */
    private fun stashLiveSession(identity: AccountIdentity = testIdentity()) {
        authenticator.availabilityValue = AccountAuthenticationAvailability.Available
        authenticator.restoreBehavior = { testSuccess(session = testSession(identity = identity)) }
        store.record = StoredAccountSession(testSession(identity = identity), testCredential())
    }

    /** A manager whose clock is past the fixture session's declared expiry. */
    private fun managerAt(nowMillis: Long): AccountSessionManager = AccountSessionManager(
        authenticator = authenticator,
        sessionStore = store,
        clock = FixedAccountClock(nowMillis),
    )

    // ------------------------------------------------------------------------------------------------ local mode

    @Test
    fun startsInGuestStateWhichIsAFirstClassLocalMode() {
        val manager = manager()

        assertEquals(AccountState.Guest, manager.state())
        assertTrue("guest mode must not be treated as a lesser state", manager.state().runsLocally)
        assertNull(manager.state().sessionOrNull())
    }

    @Test
    fun aBuildWithoutAServiceReportsUnavailableRatherThanFailingDifferentlyEverywhere() {
        val manager = CraftMindAccountFoundation.sessionManager(
            sessionStore = store,
            clock = FixedAccountClock(1_500L),
            authenticator = NoBackendAccountAuthenticator(),
        )

        val state = manager.signIn(testSignInRequest())

        assertEquals(
            AccountState.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED, null),
            state,
        )
        assertEquals("nothing may be written when no service exists", 0, store.saveCalls)
        assertNull("the shipped build must not be able to create a session", manager.state().sessionOrNull())
        assertTrue("the app stays fully usable in local mode", manager.state().runsLocally)
    }

    @Test
    fun signInTakesOwnershipOfTheRequestAndClosesItEvenWhenItFails() {
        val request = testSignInRequest()
        authenticator.availabilityValue =
            AccountAuthenticationAvailability.Unavailable(AccountAvailabilityReason.SERVICE_UNREACHABLE)

        manager().signIn(request)

        assertTrue("the typed secret must not outlive the attempt", request.isClosed())
    }

    @Test
    fun aRejectedSignInDoesNotDestroyTheIdentityAlreadyOnTheDevice() {
        val identity = testIdentity()
        stashLiveSession(identity)
        authenticator.signInBehavior = { AccountAuthOutcome.Failure(AccountAuthErrorCode.INVALID_CREDENTIALS) }
        val manager = manager()
        manager.restoreSession()

        val savesAfterRestore = store.saveCalls
        val clearsAfterRestore = store.clearCalls

        val state = manager.signIn(testSignInRequest())

        assertTrue(state is AccountState.Failed)
        assertEquals(identity, state.identityOrNull())
        assertEquals("a failed attempt must not clear the stored session", clearsAfterRestore, store.clearCalls)
        assertEquals("a failed attempt must not overwrite the stored session", savesAfterRestore, store.saveCalls)
        assertEquals(identity, manager.state().identityOrNull())
    }

    @Test
    fun aSessionThatCannotBeStoredSecurelyIsNeverAdopted() {
        authenticator.availabilityValue = AccountAuthenticationAvailability.Available
        authenticator.signInBehavior = { testSuccess() }
        store.saveFailure = AccountSessionStoreException(AccountSessionStoreError.STORAGE_FAILURE)
        val manager = manager()

        val state = manager.signIn(testSignInRequest())

        assertEquals(
            AccountState.Failed(AccountAuthErrorCode.SESSION_STORAGE_FAILURE, null),
            state,
        )
        assertFalse("an unstorable session must not be treated as signed in", state is AccountState.Authenticated)
        assertNull(manager.state().sessionOrNull())
    }

    @Test
    fun anAuthenticatorExceptionIsReportedAsAnUnexpectedFailureAndLeaksNothing() {
        authenticator.availabilityValue = AccountAuthenticationAvailability.Available
        authenticator.failureToThrow = IllegalStateException("token=super-secret-value")
        val manager = manager()

        val state = manager.signIn(testSignInRequest())

        assertEquals(AccountState.Failed(AccountAuthErrorCode.UNEXPECTED_FAILURE, null), state)
        assertFalse(
            "the exception message must never reach state that is rendered or logged",
            state.toString().contains("super-secret-value"),
        )
    }

    // -------------------------------------------------------------------------------------------- restore / expiry

    @Test
    fun restoreWithNothingStoredStaysInLocalModeWithoutCallingTheService() {
        val state = manager().restoreSession()

        assertEquals(AccountState.Guest, state)
        assertEquals("a restore with nothing stored must not contact anything", 0, authenticator.restoreCalls)
        assertEquals(0, authenticator.refreshCalls)
    }

    @Test
    fun aStoredSessionIsConfirmedWithTheServiceWhenOneIsConfigured() {
        stashLiveSession()
        val manager = manager()

        val state = manager.restoreSession()

        val session = (state as AccountState.Authenticated).session
        assertEquals("a configured service is the authority on whether the session is still valid", 1, authenticator.restoreCalls)
        assertEquals(AccountSessionSource.RESTORED_ON_DEVICE, session.source)
        assertEquals("a confirmation must not re-issue anything on the device", 1, store.saveCalls)
    }

    @Test
    fun aStoredSessionIsUsedLocallyWhenNoServiceCanAnswer() {
        store.record = StoredAccountSession(testSession(), testCredential())
        authenticator.availabilityValue = AccountAuthenticationAvailability.Unavailable(
            AccountAvailabilityReason.SERVICE_UNREACHABLE,
        )
        val manager = manager()

        val state = manager.restoreSession()

        val session = (state as AccountState.Authenticated).session
        assertEquals(AccountSessionSource.RESTORED_ON_DEVICE, session.source)
        assertEquals("with no service there is nothing to ask, and nothing is invented", 0, authenticator.restoreCalls)
        assertEquals(0, authenticator.refreshCalls)
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun aServiceThatCannotBeReachedDoesNotEndTheSession() {
        store.record = StoredAccountSession(testSession(), testCredential())
        authenticator.availabilityValue = AccountAuthenticationAvailability.Available
        authenticator.restoreBehavior = {
            AccountAuthOutcome.Unavailable(AccountAvailabilityReason.NETWORK_UNAVAILABLE)
        }
        val manager = manager()

        val state = manager.restoreSession()

        assertTrue("an unreachable service must not sign anyone out", state is AccountState.Authenticated)
        assertEquals("the record is kept for the next attempt", 0, store.clearCalls)
        assertNotNull(store.record)
    }

    @Test
    fun aSessionTheServiceRejectsIsEndedAndRemovedLocally() {
        store.record = StoredAccountSession(testSession(), testCredential())
        authenticator.availabilityValue = AccountAuthenticationAvailability.Available
        authenticator.restoreBehavior = { AccountAuthOutcome.Failure(AccountAuthErrorCode.SESSION_REJECTED) }
        val manager = manager()

        val state = manager.restoreSession()

        assertEquals(
            AccountState.SessionExpired(testIdentity(), AccountSessionEndReason.REJECTED_BY_SERVICE),
            state,
        )
        assertEquals("a session the service refuses must not stay on the device", 1, store.clearCalls)
    }

    @Test
    fun aSuspendedAccountEndsTheSessionAndSaysWhy() {
        store.record = StoredAccountSession(testSession(), testCredential())
        authenticator.availabilityValue = AccountAuthenticationAvailability.Available
        authenticator.restoreBehavior = { AccountAuthOutcome.Failure(AccountAuthErrorCode.ACCOUNT_SUSPENDED) }
        val manager = manager()

        val state = manager.restoreSession()

        assertEquals(
            AccountState.SessionExpired(testIdentity(), AccountSessionEndReason.ACCOUNT_SUSPENDED),
            state,
        )
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun anExpiredSessionThatCannotBeRefreshedEndsAndIsRemovedFromTheDevice() {
        store.record = StoredAccountSession(testSession(refreshable = false), testCredential())

        val state = managerAt(3_000L).restoreSession()

        assertEquals(
            AccountState.SessionExpired(testIdentity(), AccountSessionEndReason.REFRESH_NOT_SUPPORTED),
            state,
        )
        assertEquals("a dead session must not stay on the device", 1, store.clearCalls)
    }

    @Test
    fun anExpiredRefreshableSessionIsRefreshedAndTheFreshSessionReplacesTheStaleOne() {
        val identity = testIdentity()
        store.record = StoredAccountSession(testSession(identity = identity), testCredential())
        val refreshed = testSession(identity = identity, issuedAtEpochMillis = 1_500L, expiresAtEpochMillis = 9_000L)
        authenticator.refreshBehavior = { testSuccess(session = refreshed) }

        val state = managerAt(3_000L).restoreSession()

        assertEquals(1, authenticator.refreshCalls)
        val session = (state as AccountState.Authenticated).session
        assertEquals(refreshed.expiresAtEpochMillis, session.expiresAtEpochMillis)
        assertEquals(AccountSessionSource.RESTORED_ON_DEVICE, session.source)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun aRefreshThatOnlyFailedBecauseTheServiceWasOutOfReachKeepsTheStoredRecord() {
        store.record = StoredAccountSession(testSession(), testCredential())
        authenticator.refreshBehavior = { AccountAuthOutcome.Failure(AccountAuthErrorCode.NETWORK_UNAVAILABLE) }

        val state = managerAt(3_000L).restoreSession()

        assertEquals(
            AccountState.SessionExpired(testIdentity(), AccountSessionEndReason.REFRESH_UNAVAILABLE),
            state,
        )
        assertEquals("a recoverable session must not be destroyed", 0, store.clearCalls)
        assertNotNull("the record must still be there for the next launch", store.record)
    }

    @Test
    fun aRefreshRejectedByTheServiceDestroysTheStoredRecord() {
        store.record = StoredAccountSession(testSession(), testCredential())
        authenticator.refreshBehavior = { AccountAuthOutcome.Failure(AccountAuthErrorCode.INVALID_CREDENTIALS) }

        val state = managerAt(3_000L).restoreSession()

        assertEquals(
            AccountState.SessionExpired(testIdentity(), AccountSessionEndReason.REJECTED_BY_SERVICE),
            state,
        )
        assertEquals(1, store.clearCalls)
        assertNull(store.record)
    }

    @Test
    fun anUnreachableServiceDuringRefreshIsAlsoRecoverable() {
        store.record = StoredAccountSession(testSession(), testCredential())
        authenticator.refreshBehavior = {
            AccountAuthOutcome.Unavailable(AccountAvailabilityReason.SERVICE_UNREACHABLE)
        }

        val state = managerAt(3_000L).restoreSession()

        assertEquals(
            AccountState.SessionExpired(testIdentity(), AccountSessionEndReason.REFRESH_UNAVAILABLE),
            state,
        )
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun anUnreadableStoredSessionIsDroppedAndReportedAsSuch() {
        store.record = StoredAccountSession(testSession(), testCredential())
        store.loadFailure = AccountSessionStoreException(AccountSessionStoreError.CORRUPTED_SESSION)

        val state = manager().restoreSession()

        assertEquals(AccountState.SessionExpired(null, AccountSessionEndReason.INVALID_STORED_SESSION), state)
        assertEquals("a corrupted record must not be kept", 1, store.clearCalls)
    }

    @Test
    fun anUnexpectedStorageExceptionIsHandledTheSameWayAndNeverCrashes() {
        store.loadFailure = RuntimeException("keystore exploded")

        val state = manager().restoreSession()

        assertEquals(AccountState.SessionExpired(null, AccountSessionEndReason.INVALID_STORED_SESSION), state)
    }

    // --------------------------------------------------------------------------------------------------- sign-out

    @Test
    fun signOutAlwaysClearsLocallyAndReturnsToLocalMode() {
        stashLiveSession()
        authenticator.beforeSignOut = { assertNull("local session is cleared before any remote revoke", store.record) }
        val manager = manager()
        manager.restoreSession()

        val outcome = manager.signOut()

        assertEquals(AccountState.Guest, outcome.state)
        assertNull("a local-only sign-out has no remote error to report", outcome.result.error)
        assertEquals(AccountSignOutMethod.LOCAL_ONLY, outcome.result.method)
        assertEquals(1, store.clearCalls)
        assertNull(store.record)
        assertTrue("signing out must leave a fully usable local app", manager.state().runsLocally)
    }

    @Test
    fun signOutReportsHonestlyWhenRemoteRevocationCouldNotHappen() {
        stashLiveSession()
        authenticator.signOutResult =
            AccountSignOutResult(AccountSignOutMethod.REMOTE_REVOCATION_SKIPPED, AccountAuthErrorCode.NETWORK_UNAVAILABLE)
        val manager = manager()
        manager.restoreSession()

        val outcome = manager.signOut()

        assertEquals(AccountSignOutMethod.REMOTE_REVOCATION_SKIPPED, outcome.result.method)
        assertEquals("the device must still forget the session", AccountState.Guest, outcome.state)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun signOutNeverLeavesTheUiLookingSignedInWhenTheDeviceCouldNotForgetTheSession() {
        store.record = StoredAccountSession(testSession(), testCredential())
        store.clearFailure = AccountSessionStoreException(AccountSessionStoreError.STORAGE_FAILURE)
        val manager = manager()
        manager.restoreSession()

        val state = manager.signOut().state

        assertEquals(AccountState.Failed(AccountAuthErrorCode.SESSION_STORAGE_FAILURE, testIdentity()), state)
        assertFalse("a failed clear must not be presented as a clean sign-out", state is AccountState.Authenticated)
    }

    @Test
    fun signOutSurvivesAnAuthenticatorThatThrows() {
        store.record = StoredAccountSession(testSession(), testCredential())
        authenticator.failureToThrow = RuntimeException("boom")
        val manager = manager()
        manager.restoreSession()

        val outcome = manager.signOut()

        assertEquals(AccountState.Guest, outcome.state)
        assertEquals(AccountSignOutMethod.REMOTE_REVOCATION_FAILED, outcome.result.method)
        assertEquals(1, store.clearCalls)
    }

    // --------------------------------------------------------------------------------------------- account switching

    @Test
    fun switchingAccountsSignsOutFirstSoAHalfSwitchCannotHappen() {
        stashLiveSession()
        authenticator.signInBehavior = { AccountAuthOutcome.Failure(AccountAuthErrorCode.INVALID_CREDENTIALS) }
        val manager = manager()
        manager.restoreSession()

        val state = manager.switchAccount(testSignInRequest(emailAddress = "other@example.com"))

        assertEquals(AccountState.Failed(AccountAuthErrorCode.INVALID_CREDENTIALS, null), state)
        assertEquals("the previous session must be cleared before a switch", 1, store.clearCalls)
        assertNull("no identity may survive a failed switch", manager.state().identityOrNull())
    }

    @Test
    fun switchingAccountsAdoptsTheNewIdentityOnlyWhenTheSignInSucceeds() {
        val nextIdentity = testIdentity(accountId = "cm-account-0002", emailAddress = "next@example.com")
        stashLiveSession()
        authenticator.signInBehavior = { testSuccess(session = testSession(identity = nextIdentity)) }
        val manager = manager()
        manager.restoreSession()

        val state = manager.switchAccount(testSignInRequest(emailAddress = "next@example.com"))

        assertEquals(nextIdentity, state.identityOrNull())
        assertEquals(1, store.clearCalls)
        assertEquals(nextIdentity, store.lastSavedSession?.identity)
    }

    // --------------------------------------------------------------------------------------- expiry and deletion

    @Test
    fun expiringASessionSharpensTheStateAndRemovesTheRecord() {
        stashLiveSession()
        val manager = manager()
        manager.restoreSession()

        val state = manager.expireSession(AccountSessionEndReason.EXPIRED)

        assertEquals(
            AccountState.SessionExpired(testIdentity(), AccountSessionEndReason.EXPIRED),
            state,
        )
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun expiringBecauseRefreshIsMerelyUnavailableKeepsTheRecord() {
        stashLiveSession()
        val manager = manager()
        manager.restoreSession()

        manager.expireSession(AccountSessionEndReason.REFRESH_UNAVAILABLE)

        assertEquals(0, store.clearCalls)
    }

    @Test
    fun anAccountDeletionRequestTouchesNothingLocal() {
        stashLiveSession()
        authenticator.deletionResult =
            AccountDeletionOutcome.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED)
        val manager = manager()
        manager.restoreSession()

        // The confirmation during restore is the baseline; the deletion request itself must add nothing to it.
        val savesAfterRestore = store.saveCalls
        val clearsAfterRestore = store.clearCalls

        val outcome = manager.requestAccountDeletion()

        assertEquals(
            AccountDeletionOutcome.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED),
            outcome,
        )
        assertEquals("a deletion request is not a sign-out", clearsAfterRestore, store.clearCalls)
        assertEquals("a deletion request changes no session record", savesAfterRestore, store.saveCalls)
        assertNotNull("an account request must never delete local data", store.record)
        assertTrue(manager.state() is AccountState.Authenticated)
    }

    @Test
    fun aFailedDeletionRequestIsReportedWithoutChangingAnythingEither() {
        stashLiveSession()
        authenticator.deletionResult = AccountDeletionOutcome.Failure(AccountAuthErrorCode.SERVICE_UNAVAILABLE)
        val manager = manager()
        manager.restoreSession()

        val outcome = manager.requestAccountDeletion()

        assertEquals(AccountDeletionOutcome.Failure(AccountAuthErrorCode.SERVICE_UNAVAILABLE), outcome)
        assertEquals(0, store.clearCalls)
        assertTrue(manager.state() is AccountState.Authenticated)
    }

    @Test
    fun aDeletionRequestThatThrowsIsReportedAsAFailureRatherThanACrash() {
        authenticator.failureToThrow = RuntimeException("network down")

        val outcome = manager().requestAccountDeletion()

        assertEquals(AccountDeletionOutcome.Failure(AccountAuthErrorCode.UNEXPECTED_FAILURE), outcome)
    }

    @Test
    fun aLocallyUnusableRequestIsRecordedAsAFailureWithoutSigningAnyoneOut() {
        stashLiveSession()
        val manager = manager()
        manager.restoreSession()

        val state = manager.failRequest(AccountAuthErrorCode.INVALID_REQUEST)

        assertEquals(AccountState.Failed(AccountAuthErrorCode.INVALID_REQUEST, testIdentity()), state)
        assertEquals("local validation failures never touch storage", 0, store.clearCalls)
        assertEquals(0, authenticator.signInCalls)
    }

    // ---------------------------------------------------------------------------------------------------- listeners

    @Test
    fun listenersSeeEveryTransitionAndCanStopListening() {
        val seen = mutableListOf<AccountState>()
        val manager = manager()
        val unsubscribe = manager.addListener { seen += it }

        assertEquals("a new listener is told the current state immediately", listOf(AccountState.Guest), seen)
        stashLiveSession()
        manager.restoreSession()
        manager.signOut()
        unsubscribe()
        manager.signIn(testSignInRequest())

        assertEquals(4, seen.size)
        assertTrue(seen[1] is AccountState.Authenticated)
        assertTrue("signing out is observable while it happens", seen[2] is AccountState.SigningOut)
        assertEquals(AccountState.Guest, seen[3])
        assertTrue("an unsubscribed listener must not be called again", seen.size == 4)
    }

    @Test
    fun everyTransitionIsPublishedSoNoViewHasToPollTheMachine() {
        val seen = mutableListOf<AccountState>()
        authenticator.availabilityValue = AccountAuthenticationAvailability.Available
        authenticator.signInBehavior = { testSuccess() }
        val manager = manager()
        manager.addListener { seen += it }

        manager.signIn(testSignInRequest())

        assertEquals(3, seen.size)
        assertEquals("the listener is told the current state when it subscribes", AccountState.Guest, seen[0])
        assertTrue("the in-flight state must be visible", seen[1] is AccountState.SigningIn)
        assertTrue(seen[2] is AccountState.Authenticated)
    }

    @Test
    fun sessionExpiryIsDetectedFromTheDeclaredExpiryOnly() {
        val session = testSession(expiresAtEpochMillis = 2_000L)

        assertFalse(session.isExpiredAt(1_999L))
        assertTrue(session.isExpiredAt(2_000L))
        assertFalse(
            "a session without a declared expiry must never be expired locally",
            testSession(expiresAtEpochMillis = null).isExpiredAt(Long.MAX_VALUE),
        )
    }

    @Test
    fun aMalformedSessionIsRejectedAtConstruction() {
        val malformed = runCatching {
            AccountSession(
                identity = testIdentity(),
                providerId = AccountProviderId.CRAFTMIND,
                method = AccountSignInMethod.EMAIL_PASSWORD,
                issuedAtEpochMillis = 5_000L,
                expiresAtEpochMillis = 5_000L,
                refreshable = true,
                source = AccountSessionSource.LIVE_SIGN_IN,
            )
        }

        assertTrue(malformed.isFailure)
    }

    @Test
    fun noStateEverCarriesASessionSecret() {
        store.record = StoredAccountSession(testSession(), testCredential("do-not-leak-me"))
        val manager = manager()

        val restored = manager.restoreSession()
        val signedOut = manager.signOut().state

        for (state in listOf(restored, signedOut, manager.state())) {
            assertFalse(
                "session state must be metadata only",
                state.toString().contains("do-not-leak-me"),
            )
        }
    }
}

/** `AccountSignInRequest` has no public `closed` flag; the secret is simply unusable once it is. */
private fun AccountSignInRequest.isClosed(): Boolean = runCatching {
    useSecret { it.size }
}.isFailure
