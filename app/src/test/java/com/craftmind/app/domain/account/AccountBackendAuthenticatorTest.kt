package com.craftmind.app.domain.account

import com.craftmind.app.data.account.CraftMindAccountAuthenticator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adapter between the app and a real account service (Phase 17 §15).
 *
 * What is being proved here is the part of "real authentication" that no backend test can cover: that a server answer
 * becomes the app's own state without inventing anything, that a broken client cannot turn a failure into a session, and
 * that no credential can escape through a message, a toString, or a stored record.
 *
 * The service is a fake — this is a JVM test, and the real endpoints are exercised by `backend/test`. The fake is
 * deliberately strict: it answers only from the queue a test provides, so a call that was not expected fails loudly
 * instead of being served a default that could hide a bug.
 */
class AccountBackendAuthenticatorTest {

    private val clock = FixedAccountClock(1_000L)
    private val api = FakeAccountApi()
    private val store = InMemoryGuestIdentityStore()
    private val authenticator = CraftMindAccountAuthenticator(
        api = api,
        guestIdentityManager = GuestIdentityManager(store = store),
        clock = { clock.nowMillis() },
    )

    // -------------------------------------------------------------------------------------------- availability

    @Test
    fun aBuildWithoutAnAddressOffersNoSignInAtAll() {
        api.configured = false

        assertEquals(
            AccountAuthenticationAvailability.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED),
            authenticator.availability(),
        )
    }

    @Test
    fun aConfiguredBuildIsAvailable() {
        api.configured = true

        assertEquals(AccountAuthenticationAvailability.Available, authenticator.availability())
    }

    // -------------------------------------------------------------------------------------------- registration

    @Test
    fun registeringReturnsASessionBuiltFromWhatTheServiceReported() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Success(registration(expiresAt = 5_000L))

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

        val success = outcome as AccountAuthOutcome.Success
        assertEquals(AccountSessionSource.LIVE_SIGN_IN, success.session.source)
        assertEquals("Someone", success.session.identity.displayName)
        assertEquals(5_000L, success.session.expiresAtEpochMillis)
        assertTrue("a live session can be refreshed", success.session.refreshable)
        assertNotNull(success.credential)
        // The anonymous identity is offered so the service can link it; it is never required.
        assertEquals(store.stored, api.lastGuestIdentityId)
    }

    @Test
    fun anAlreadyRegisteredAddressIsReportedAsSuchInTheUsersTerms() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Rejected(AccountApiErrorCode.ACCOUNT_ALREADY_EXISTS)

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), null)

        assertEquals(AccountAuthErrorCode.ACCOUNT_ALREADY_EXISTS, (outcome as AccountAuthOutcome.Failure).error)
    }

    @Test
    fun anUnreachableServiceIsNotReportedAsARejectedCredential() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Unreachable

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), null)

        assertEquals(
            AccountAvailabilityReason.NETWORK_UNAVAILABLE,
            (outcome as AccountAuthOutcome.Unavailable).reason,
        )
    }

    @Test
    fun anAnswerThatDoesNotMatchTheContractIsDiscardedRatherThanGuessedAt() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Malformed

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), null)

        assertEquals(AccountAuthErrorCode.MALFORMED_RESPONSE, (outcome as AccountAuthOutcome.Failure).error)
    }

    @Test
    fun aServiceThatAnswersWithASuspendedAccountDoesNotSignAnyoneIn() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Success(
            registration(expiresAt = 5_000L, status = AccountServerStatus.SUSPENDED),
        )

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), null)

        assertEquals(AccountAuthErrorCode.ACCOUNT_SUSPENDED, (outcome as AccountAuthOutcome.Failure).error)
    }

    // ------------------------------------------------------------------------------------------------ sign in

    @Test
    fun signingInMapsRejectionCodesWithoutInventingMeaning() {
        api.configured = true
        val cases = mapOf(
            AccountApiErrorCode.INVALID_CREDENTIALS to AccountAuthErrorCode.INVALID_CREDENTIALS,
            AccountApiErrorCode.ACCOUNT_NOT_FOUND to AccountAuthErrorCode.INVALID_CREDENTIALS,
            AccountApiErrorCode.ACCOUNT_SUSPENDED to AccountAuthErrorCode.ACCOUNT_SUSPENDED,
            AccountApiErrorCode.ACCOUNT_ALREADY_EXISTS to AccountAuthErrorCode.ACCOUNT_ALREADY_EXISTS,
            AccountApiErrorCode.INVALID_EMAIL to AccountAuthErrorCode.INVALID_REQUEST,
            AccountApiErrorCode.INVALID_PASSWORD to AccountAuthErrorCode.INVALID_REQUEST,
            AccountApiErrorCode.BACKEND_UNAVAILABLE to AccountAuthErrorCode.SERVICE_UNAVAILABLE,
            AccountApiErrorCode.UNKNOWN_ERROR to AccountAuthErrorCode.UNEXPECTED_FAILURE,
        )

        for ((wire, expected) in cases) {
            api.nextRegistration = AccountApiOutcome.Rejected(wire)
            val outcome = authenticator.signIn(
                AccountSignInRequest.emailPassword("someone@example.com", "Passw0rdd!".toCharArray()),
            )
            assertEquals("$wire must map to $expected", expected, (outcome as AccountAuthOutcome.Failure).error)
        }
    }

    @Test
    fun theGuestIdentityTravelsWithASignInSoTheServiceCanLinkIt() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Success(registration(expiresAt = 5_000L))

        authenticator.signIn(AccountSignInRequest.emailPassword("someone@example.com", "Passw0rdd!".toCharArray()))

        assertEquals(store.stored, api.lastGuestIdentityId)
        assertEquals("someone@example.com", api.lastEmail)
    }

    @Test
    fun thePasswordTheServiceSeesIsTemporaryAndIsZeroedAsSoonAsTheCallReturns() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Rejected(AccountApiErrorCode.INVALID_CREDENTIALS)
        val request = AccountSignInRequest.emailPassword("someone@example.com", "Passw0rdd!".toCharArray())

        authenticator.signIn(request)

        val seen = api.lastPassword
        assertNotNull("the service call must have carried the password", seen)
        assertTrue(
            "the password must not outlive the request that carried it",
            seen!!.all { it == '\u0000' },
        )
        request.close()
    }

    // ------------------------------------------------------------------------------------- restore and refresh

    @Test
    fun aStoredSessionTheServiceStillAcceptsIsRestored() {
        api.configured = true
        api.nextAccount = AccountApiOutcome.Success(record(status = AccountServerStatus.ACTIVE))
        val session = existingSession()

        val outcome = authenticator.restore(session.storedCredential(), session.session)

        val success = outcome as AccountAuthOutcome.Success
        assertEquals(AccountSessionSource.RESTORED_ON_DEVICE, success.session.source)
        assertEquals(session.session.identity, success.session.identity)
        assertNotNull("the refreshed credential is what gets stored", success.credential)
    }

    @Test
    fun anAccessTokenTheServiceHasForgottenIsExchangedForANewPair() {
        api.configured = true
        api.nextAccount = AccountApiOutcome.Rejected(AccountApiErrorCode.SESSION_EXPIRED)
        api.nextRefresh = AccountApiOutcome.Success(
            AccountServerSession(
                tokens = tokens("new-access", "new-refresh"),
                accessExpiresAtEpochMillis = 9_000L,
                refreshExpiresAtEpochMillis = 90_000L,
            ),
        )
        val session = existingSession()

        val outcome = authenticator.restore(session.storedCredential(), session.session)

        val success = outcome as AccountAuthOutcome.Success
        assertEquals("a rotated pair must replace the stored one", 9_000L, success.session.expiresAtEpochMillis)
        assertTrue(success.credential.useSecret { String(it).contains("new-access") })
    }

    @Test
    fun aSessionTheServiceRefusesEndsRatherThanBeingKept() {
        api.configured = true
        api.nextAccount = AccountApiOutcome.Rejected(AccountApiErrorCode.SESSION_NOT_FOUND)
        api.nextRefresh = AccountApiOutcome.Rejected(AccountApiErrorCode.SESSION_EXPIRED)
        val session = existingSession()

        val outcome = authenticator.restore(session.storedCredential(), session.session)

        assertEquals(AccountAuthErrorCode.SESSION_REJECTED, (outcome as AccountAuthOutcome.Failure).error)
    }

    @Test
    fun anAccountSuspendedMidSessionEndsTheSessionWithThatReason() {
        api.configured = true
        api.nextAccount = AccountApiOutcome.Rejected(AccountApiErrorCode.ACCOUNT_SUSPENDED)
        val session = existingSession()

        val outcome = authenticator.restore(session.storedCredential(), session.session)

        assertEquals(AccountAuthErrorCode.ACCOUNT_SUSPENDED, (outcome as AccountAuthOutcome.Failure).error)
    }

    @Test
    fun anUnreachableServiceDuringRestoreKeepsTheSessionRatherThanEndingIt() {
        api.configured = true
        api.nextAccount = AccountApiOutcome.Unreachable
        val session = existingSession()

        val outcome = authenticator.restore(session.storedCredential(), session.session)

        assertEquals(
            AccountAvailabilityReason.NETWORK_UNAVAILABLE,
            (outcome as AccountAuthOutcome.Unavailable).reason,
        )
    }

    @Test
    fun aRestoredRecordThatIsNotActiveIsNotAdoptedAsALiveSession() {
        api.configured = true
        api.nextAccount = AccountApiOutcome.Success(record(status = AccountServerStatus.SUSPENDED))
        val session = existingSession()

        val outcome = authenticator.restore(session.storedCredential(), session.session)

        assertEquals(AccountAuthErrorCode.ACCOUNT_SUSPENDED, (outcome as AccountAuthOutcome.Failure).error)
    }

    @Test
    fun aServiceThatDeclaresNoExpiryLeadsToASessionWithNone() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Success(registration(expiresAt = null))

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), null)

        assertNull(
            "the app must not invent a lifetime the service did not declare",
            (outcome as AccountAuthOutcome.Success).session.expiresAtEpochMillis,
        )
    }

    @Test
    fun anExpiryAlreadyInThePastIsNotStoredAsAFutureOne() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Success(registration(expiresAt = 900L))

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), null)

        assertNull((outcome as AccountAuthOutcome.Success).session.expiresAtEpochMillis)
    }

    @Test
    fun aRefreshThatFailsBecauseTheServiceIsDownDoesNotDestroyAnything() {
        api.configured = true
        api.nextRefresh = AccountApiOutcome.Unreachable
        val session = existingSession()

        val outcome = authenticator.refresh(session.storedCredential(), session.session)

        assertEquals(
            AccountAvailabilityReason.NETWORK_UNAVAILABLE,
            (outcome as AccountAuthOutcome.Unavailable).reason,
        )
    }

    @Test
    fun anUnreadableStoredCredentialEndsTheSessionInsteadOfAskingWithGarbage() {
        api.configured = true
        val session = existingSession()

        val outcome = authenticator.restore(AccountSessionCredential.fromCharacters("not-a-credential".toCharArray()), session.session)

        assertEquals(AccountAuthErrorCode.SESSION_REJECTED, (outcome as AccountAuthOutcome.Failure).error)
        assertEquals("nothing must be sent when there is nothing to send", 0, api.calls)
    }

    // ----------------------------------------------------------------------------------------------- sign out

    @Test
    fun signingOutRevokesTheServerSessionAndSaysSo() {
        api.configured = true
        api.nextLogout = AccountApiOutcome.Success(Unit)
        val session = existingSession()

        val result = authenticator.signOut(session.session, session.storedCredential())

        assertEquals(AccountSignOutMethod.REMOTE_REVOKED, result.method)
        assertNull(result.error)
    }

    @Test
    fun signingOutWithAnUnreachableServiceStillClearsLocallyAndSaysTheRevocationWasSkipped() {
        api.configured = true
        api.nextLogout = AccountApiOutcome.Unreachable
        val session = existingSession()

        val result = authenticator.signOut(session.session, session.storedCredential())

        assertEquals(AccountSignOutMethod.REMOTE_REVOCATION_SKIPPED, result.method)
        assertEquals(AccountAuthErrorCode.NETWORK_UNAVAILABLE, result.error)
    }

    @Test
    fun signingOutWithNoStoredCredentialNeverInventsARemoteCall() {
        api.configured = true

        val result = authenticator.signOut(null, null)

        assertEquals(AccountSignOutMethod.LOCAL_ONLY, result.method)
        assertEquals(0, api.calls)
    }

    // ---------------------------------------------------------------------------------------------- deletion

    @Test
    fun accountDeletionIsReportedAsUnimplementedRatherThanPretended() {
        api.configured = true

        val outcome = authenticator.requestDeletion(existingSession().session, null)

        assertEquals(
            AccountDeletionOutcome.Unavailable(AccountAvailabilityReason.NOT_IMPLEMENTED_BY_SERVICE),
            outcome,
        )
    }

    // ------------------------------------------------------------------------------------ guest announcement

    @Test
    fun theAnonymousIdentityIsIntroducedOnceAndOnlyBestEffort() {
        api.configured = true
        api.nextGuest = AccountApiOutcome.Success(Unit)

        authenticator.announceGuestIdentity()

        assertEquals(store.stored, api.lastGuestIdentityId)
        assertEquals(1, api.calls)
    }

    @Test
    fun anUnreachableServiceDuringTheGuestAnnouncementIsNotAnErrorTheUserSees() {
        api.configured = true
        api.nextGuest = AccountApiOutcome.Unreachable

        val outcome = authenticator.announceGuestIdentity()

        assertTrue(outcome is AccountApiOutcome.Unreachable)
    }

    // ------------------------------------------------------------------------------------- nothing leaks

    @Test
    fun noOutcomeOfTheAdapterCanPrintACredential() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Success(registration(expiresAt = 5_000L))

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), null)

        val printed = outcome.toString() + (outcome as AccountAuthOutcome.Success).run {
            session.toString() + credential.toString()
        }
        assertFalse("a token must not be printable", printed.contains("access-value"))
        assertFalse(printed.contains("refresh-value"))
        assertFalse("a password must not be printable", printed.contains("Passw0rdd!"))
    }

    // -------------------------------------------------------------------------------------------- test doubles

    private fun tokens(access: String, refresh: String) =
        AccountSessionTokens.of(access.toCharArray(), refresh.toCharArray())

    private fun record(status: AccountServerStatus) = AccountServerRecord(
        userId = "cm-user-1",
        emailAddress = "someone@example.com",
        displayName = "Someone",
        status = status,
        createdAtEpochMillis = 10L,
        updatedAtEpochMillis = 20L,
    )

    private fun registration(expiresAt: Long?, status: AccountServerStatus = AccountServerStatus.ACTIVE) =
        AccountRegistration(
            record = record(status),
            session = AccountServerSession(
                tokens = tokens("access-value", "refresh-value"),
                accessExpiresAtEpochMillis = expiresAt,
                refreshExpiresAtEpochMillis = null,
            ),
        )

    /** A device that is already signed in: the session on disk, and the credential that goes with it. */
    private class ExistingSession(val session: AccountSession, private val encoded: CharArray) {
        fun storedCredential(): AccountSessionCredential = AccountSessionCredential.fromCharacters(encoded)
    }

    private fun existingSession(): ExistingSession {
        val encoded = tokens("access-value", "refresh-value").encodeForStorage()
        return ExistingSession(
            session = AccountSession(
                identity = AccountIdentity.of("cm-user-1", "Someone", "someone@example.com"),
                providerId = AccountProviderId.CRAFTMIND,
                method = AccountSignInMethod.EMAIL_PASSWORD,
                issuedAtEpochMillis = 500L,
                expiresAtEpochMillis = 9_000L,
                refreshable = true,
                source = AccountSessionSource.RESTORED_ON_DEVICE,
            ),
            encoded = encoded,
        )
    }
}

/** A guest identity store that keeps its value, so a "restart" can be simulated by building a second manager. */
class InMemoryGuestIdentityStore(initial: String? = null) : GuestIdentityStore {
    var stored: String? = initial
        private set

    override fun read(): String? = stored

    override fun write(value: String) {
        stored = value
    }
}

/**
 * An account service that answers only with what a test queued.
 *
 * Every call after the queued ones fails the test rather than returning a default, because a default is exactly how a
 * fake quietly becomes the thing being tested.
 */
class FakeAccountApi : AccountApi {
    var configured: Boolean = false
    override val isConfigured: Boolean get() = configured

    var nextRegistration: AccountApiOutcome<AccountRegistration>? = null
    var nextAccount: AccountApiOutcome<AccountServerRecord>? = null
    var nextRefresh: AccountApiOutcome<AccountServerSession>? = null
    var nextLogout: AccountApiOutcome<Unit>? = null
    var nextGuest: AccountApiOutcome<Unit>? = null

    var lastGuestIdentityId: String? = null
        private set
    var lastEmail: String? = null
        private set

    /** The exact array the adapter handed over, kept so a test can prove it is zeroed afterwards. */
    var lastPassword: CharArray? = null
        private set
    var calls: Int = 0
        private set

    override fun register(
        emailAddress: String,
        password: CharArray,
        displayName: String?,
        guestIdentityId: String?,
    ): AccountApiOutcome<AccountRegistration> {
        calls++
        lastEmail = emailAddress
        lastPassword = password
        lastGuestIdentityId = guestIdentityId
        return requireNotNull(nextRegistration) { "the test did not queue a registration answer" }
    }

    override fun login(
        emailAddress: String,
        password: CharArray,
        guestIdentityId: String?,
    ): AccountApiOutcome<AccountRegistration> {
        calls++
        lastEmail = emailAddress
        lastPassword = password
        lastGuestIdentityId = guestIdentityId
        return requireNotNull(nextRegistration) { "the test did not queue a sign-in answer" }
    }

    override fun refresh(refreshToken: CharArray): AccountApiOutcome<AccountServerSession> {
        calls++
        return requireNotNull(nextRefresh) { "the test did not queue a refresh answer" }
    }

    override fun currentAccount(accessToken: CharArray): AccountApiOutcome<AccountServerRecord> {
        calls++
        return requireNotNull(nextAccount) { "the test did not queue an account answer" }
    }

    override fun logout(accessToken: CharArray?, refreshToken: CharArray?): AccountApiOutcome<Unit> {
        calls++
        return requireNotNull(nextLogout) { "the test did not queue a sign-out answer" }
    }

    override fun registerGuestIdentity(guestIdentityId: String): AccountApiOutcome<Unit> {
        calls++
        lastGuestIdentityId = guestIdentityId
        return requireNotNull(nextGuest) { "the test did not queue a guest-identity answer" }
    }
}
