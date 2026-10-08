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
    fun theCanonicalSessionManagerCanSeeAndUseTheRealRegistrationCapability() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Success(registration(expiresAt = 5_000L))
        val sessionStore = FakeAccountSessionStore()
        val manager = AccountSessionManager(
            authenticator = authenticator,
            sessionStore = sessionStore,
            clock = clock,
        )
        val password = "Passw0rdd!".toCharArray()

        val state = manager.signUp("someone@example.com", password, "Someone")

        assertTrue("the backend authenticator must expose the existing registration capability", state is AccountState.Authenticated)
        assertEquals("Someone", (state as AccountState.Authenticated).session.identity.displayName)
        assertEquals("the canonical manager persists only the server-created session", 1, sessionStore.saveCalls)
        assertTrue("registration takes and clears the password", password.all { it == '\u0000' })
    }

    @Test
    fun registrationRequiresEmailVerificationWithoutPersistingAnUnauthenticatedSession() {
        api.configured = true
        val response = AccountRegistration(
            record(AccountServerStatus.ACTIVE).copy(emailVerified = false),
            session = null,
            verificationRequired = true,
            deliveryStatus = AccountEmailDeliveryStatus.DEVELOPMENT_SINK,
        )
        api.nextRegistration = AccountApiOutcome.Success(response)
        val sessionStore = FakeAccountSessionStore()
        val manager = AccountSessionManager(authenticator, sessionStore, clock)

        val state = manager.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

        assertEquals(
            AccountState.VerificationRequired(
                response.record.toIdentity(),
                AccountEmailDeliveryStatus.DEVELOPMENT_SINK,
            ),
            state,
        )
        assertEquals("an unverified account must not create a device session", 0, sessionStore.saveCalls)
    }

    @Test
    fun successfulEmailVerificationCreatesNoSessionUntilTheUserSignsIn() {
        api.configured = true
        val unverifiedRecord = record(AccountServerStatus.ACTIVE).copy(emailVerified = false)
        api.nextRegistration = AccountApiOutcome.Success(
            AccountRegistration(unverifiedRecord, null, verificationRequired = true, deliveryStatus = AccountEmailDeliveryStatus.PROVIDER_ACCEPTED),
        )
        val store = FakeAccountSessionStore()
        val manager = AccountSessionManager(authenticator, store, clock)
        manager.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

        val token = CharArray(43) { 't' }
        api.nextVerification = AccountApiOutcome.Success(AccountEmailVerificationResult(record(AccountServerStatus.ACTIVE)))
        assertTrue(manager.verifyEmail(token) is AccountApiOutcome.Success)

        assertTrue(token.all { it == '\u0000' })
        assertEquals(AccountState.EmailVerified(unverifiedRecord.toIdentity()), manager.state())
        assertEquals("verification alone cannot persist an authenticated session", 0, store.saveCalls)
    }

    @Test
    fun theCanonicalManagerOwnsSecurityTokensAndDelegatesSessionOperations() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Success(registration(expiresAt = 5_000L))
        val sessionStore = FakeAccountSessionStore()
        val manager = AccountSessionManager(authenticator, sessionStore, clock)
        manager.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

        api.nextPasswordChange = AccountApiOutcome.Success(AccountPasswordChangeResult(true, 2))
        val currentPassword = "CurrentPass1!".toCharArray()
        val newPassword = "NewPassw0rd!".toCharArray()
        assertEquals(AccountPasswordChangeResult(true, 2), (manager.changePassword(currentPassword, newPassword) as AccountApiOutcome.Success).value)
        assertTrue(currentPassword.all { it == '\u0000' })
        assertTrue(newPassword.all { it == '\u0000' })

        val remote = AccountRemoteSession("ses-other", 100L, 200L, 300L, "Android device", false)
        api.nextSessions = AccountApiOutcome.Success(AccountSessionList(listOf(remote)))
        assertEquals(listOf(remote), (manager.listSessions() as AccountApiOutcome.Success).value.sessions)
        api.nextSessionRevocation = AccountApiOutcome.Success(Unit)
        assertTrue(manager.revokeSession(remote.sessionId) is AccountApiOutcome.Success)
        assertEquals(remote.sessionId, api.lastSessionId)
        api.nextRevokeSessions = AccountApiOutcome.Success(AccountRevokeSessionsResult(1, true))
        assertEquals(1, (manager.revokeOtherSessions() as AccountApiOutcome.Success).value.revokedSessions)
    }

    @Test
    fun registeringReturnsASessionBuiltFromWhatTheServiceReported() {
        api.configured = true
        val serviceResponse = registration(expiresAt = 5_000L)
        api.nextRegistration = AccountApiOutcome.Success(serviceResponse)

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

        val success = outcome as AccountAuthOutcome.Success
        assertEquals(AccountSessionSource.LIVE_SIGN_IN, success.session.source)
        assertEquals("Someone", success.session.identity.displayName)
        assertEquals(5_000L, success.session.expiresAtEpochMillis)
        assertTrue("a live session can be refreshed", success.session.refreshable)
        assertNotNull(success.credential)
        assertFalse(
            "the raw tokens in the API response must be destroyed after the encrypted credential is made",
            runCatching { requireNotNull(serviceResponse.session).tokens.useTokens { _, _ -> Unit } }.isSuccess,
        )
        // The anonymous identity is offered so the service can link it; it is never required.
        assertEquals(store.stored, api.lastGuestIdentityId)
    }

    @Test
    fun anAlreadyRegisteredAddressIsReportedAsSuchInTheUsersTerms() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Rejected(AccountApiErrorCode.ACCOUNT_ALREADY_EXISTS)

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

        assertEquals(AccountAuthErrorCode.ACCOUNT_ALREADY_EXISTS, (outcome as AccountAuthOutcome.Failure).error)
    }

    @Test
    fun anUnreachableServiceIsNotReportedAsARejectedCredential() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Unreachable

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

        assertEquals(
            AccountAvailabilityReason.NETWORK_UNAVAILABLE,
            (outcome as AccountAuthOutcome.Unavailable).reason,
        )
    }

    @Test
    fun anAnswerThatDoesNotMatchTheContractIsDiscardedRatherThanGuessedAt() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Malformed

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

        assertEquals(AccountAuthErrorCode.MALFORMED_RESPONSE, (outcome as AccountAuthOutcome.Failure).error)
    }

    @Test
    fun aServiceThatAnswersWithASuspendedAccountDoesNotSignAnyoneInOrRetainItsTokens() {
        api.configured = true
        val response = registration(expiresAt = 5_000L, status = AccountServerStatus.SUSPENDED)
        api.nextRegistration = AccountApiOutcome.Success(response)

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

        assertEquals(AccountAuthErrorCode.ACCOUNT_SUSPENDED, (outcome as AccountAuthOutcome.Failure).error)
        assertFalse(
            "tokens attached to an unusable suspended-account response must be destroyed",
            runCatching { requireNotNull(response.session).tokens.useTokens { _, _ -> Unit } }.isSuccess,
        )
    }

    // ------------------------------------------------------------------------------------------------ sign in

    @Test
    fun signingInMapsRejectionCodesWithoutInventingMeaning() {
        api.configured = true
        val cases = mapOf(
            AccountApiErrorCode.INVALID_CREDENTIALS to AccountAuthErrorCode.INVALID_CREDENTIALS,
            AccountApiErrorCode.ACCOUNT_NOT_FOUND to AccountAuthErrorCode.INVALID_CREDENTIALS,
            AccountApiErrorCode.ACCOUNT_SUSPENDED to AccountAuthErrorCode.ACCOUNT_SUSPENDED,
            AccountApiErrorCode.ACCOUNT_DELETED to AccountAuthErrorCode.SESSION_REJECTED,
            AccountApiErrorCode.SESSION_INVALID to AccountAuthErrorCode.SESSION_REJECTED,
            AccountApiErrorCode.REFRESH_FAILED to AccountAuthErrorCode.SESSION_REJECTED,
            AccountApiErrorCode.ACCOUNT_ALREADY_EXISTS to AccountAuthErrorCode.ACCOUNT_ALREADY_EXISTS,
            AccountApiErrorCode.INVALID_EMAIL to AccountAuthErrorCode.INVALID_REQUEST,
            AccountApiErrorCode.INVALID_PASSWORD to AccountAuthErrorCode.INVALID_REQUEST,
            AccountApiErrorCode.INVALID_REQUEST to AccountAuthErrorCode.INVALID_REQUEST,
            AccountApiErrorCode.NETWORK_ERROR to AccountAuthErrorCode.NETWORK_UNAVAILABLE,
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
        val rotated = AccountServerSession(
            tokens = tokens("new-access", "new-refresh"),
            accessExpiresAtEpochMillis = 9_000L,
            refreshExpiresAtEpochMillis = 90_000L,
        )
        api.nextRefresh = AccountApiOutcome.Success(rotated)
        val session = existingSession()

        val outcome = authenticator.restore(session.storedCredential(), session.session)

        val success = outcome as AccountAuthOutcome.Success
        assertEquals("a rotated pair must replace the stored one", 9_000L, success.session.expiresAtEpochMillis)
        assertTrue(success.credential.useSecret { String(it).contains("new-access") })
        assertFalse(
            "raw rotated response tokens must be destroyed after credential copy",
            runCatching { rotated.tokens.useTokens { _, _ -> Unit } }.isSuccess,
        )
    }

    @Test
    fun aSessionTheServiceRefusesEndsRatherThanBeingKept() {
        api.configured = true
        api.nextAccount = AccountApiOutcome.Rejected(AccountApiErrorCode.SESSION_INVALID)
        api.nextRefresh = AccountApiOutcome.Rejected(AccountApiErrorCode.SESSION_EXPIRED)
        val session = existingSession()

        val outcome = authenticator.restore(session.storedCredential(), session.session)

        assertEquals(AccountAuthErrorCode.SESSION_REJECTED, (outcome as AccountAuthOutcome.Failure).error)
    }

    @Test
    fun temporaryServiceErrorCodesDuringRestoreKeepTheSavedSession() {
        val cases = listOf(
            AccountApiErrorCode.NETWORK_ERROR to AccountAvailabilityReason.NETWORK_UNAVAILABLE,
            AccountApiErrorCode.BACKEND_UNAVAILABLE to AccountAvailabilityReason.MAINTENANCE,
            AccountApiErrorCode.UNKNOWN_ERROR to AccountAvailabilityReason.MAINTENANCE,
        )
        for ((code, reason) in cases) {
            api.nextAccount = AccountApiOutcome.Rejected(code)
            val existing = existingSession()

            val outcome = authenticator.restore(existing.storedCredential(), existing.session)

            assertEquals("$code is a service problem, not proof of a dead credential", AccountAuthOutcome.Unavailable(reason), outcome)
        }
    }

    @Test
    fun aTemporaryServiceFailureDuringRefreshDoesNotRejectTheSavedCredential() {
        api.nextAccount = AccountApiOutcome.Rejected(AccountApiErrorCode.SESSION_EXPIRED)
        api.nextRefresh = AccountApiOutcome.Rejected(AccountApiErrorCode.BACKEND_UNAVAILABLE)
        val existing = existingSession()

        val outcome = authenticator.restore(existing.storedCredential(), existing.session)

        assertEquals(
            AccountAuthOutcome.Unavailable(AccountAvailabilityReason.MAINTENANCE),
            outcome,
        )
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

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

        assertNull(
            "the app must not invent a lifetime the service did not declare",
            (outcome as AccountAuthOutcome.Success).session.expiresAtEpochMillis,
        )
    }

    @Test
    fun anExpiryAlreadyInThePastIsNotStoredAsAFutureOne() {
        api.configured = true
        api.nextRegistration = AccountApiOutcome.Success(registration(expiresAt = 900L))

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

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

        val outcome = authenticator.signUp("someone@example.com", "Passw0rdd!".toCharArray(), "Someone")

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
    var nextVerification: AccountApiOutcome<AccountEmailVerificationResult>? = null
    var nextOperationReceipt: AccountApiOutcome<AccountOperationReceipt>? = null
    var nextPasswordChange: AccountApiOutcome<AccountPasswordChangeResult>? = null
    var nextSessions: AccountApiOutcome<AccountSessionList>? = null
    var nextRevokeSessions: AccountApiOutcome<AccountRevokeSessionsResult>? = null
    var nextSessionRevocation: AccountApiOutcome<Unit>? = null

    var lastSecurityToken: CharArray? = null
        private set
    var lastSessionId: String? = null
        private set
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
        displayName: String,
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

    override fun verifyEmail(token: CharArray): AccountApiOutcome<AccountEmailVerificationResult> {
        calls++
        lastSecurityToken = token
        return requireNotNull(nextVerification) { "the test did not queue a verification answer" }
    }
    override fun resendVerification(emailAddress: String): AccountApiOutcome<AccountOperationReceipt> {
        calls++
        lastEmail = emailAddress
        return requireNotNull(nextOperationReceipt) { "the test did not queue a resend answer" }
    }
    override fun requestPasswordReset(emailAddress: String): AccountApiOutcome<AccountOperationReceipt> {
        calls++
        lastEmail = emailAddress
        return requireNotNull(nextOperationReceipt) { "the test did not queue a recovery answer" }
    }
    override fun confirmPasswordReset(token: CharArray, newPassword: CharArray): AccountApiOutcome<AccountOperationReceipt> {
        calls++
        lastSecurityToken = token
        lastPassword = newPassword
        return requireNotNull(nextOperationReceipt) { "the test did not queue a reset answer" }
    }
    override fun changePassword(accessToken: CharArray, currentPassword: CharArray, newPassword: CharArray): AccountApiOutcome<AccountPasswordChangeResult> {
        calls++
        lastPassword = newPassword
        return requireNotNull(nextPasswordChange) { "the test did not queue a password-change answer" }
    }
    override fun listSessions(accessToken: CharArray): AccountApiOutcome<AccountSessionList> {
        calls++
        return requireNotNull(nextSessions) { "the test did not queue a session-list answer" }
    }
    override fun revokeSession(accessToken: CharArray, sessionId: String): AccountApiOutcome<Unit> {
        calls++
        lastSessionId = sessionId
        return requireNotNull(nextSessionRevocation) { "the test did not queue a session-revocation answer" }
    }
    override fun revokeOtherSessions(accessToken: CharArray): AccountApiOutcome<AccountRevokeSessionsResult> {
        calls++
        return requireNotNull(nextRevokeSessions) { "the test did not queue a revoke-other-sessions answer" }
    }
}
