package com.craftmind.app.domain.account

/**
 * Test doubles for the account layer (Phase 16).
 *
 * The account domain has no Android, Compose, or coroutine dependency, so every transition is exercised here with plain
 * fakes. Real storage (Keystore) and the real backend are deliberately *not* simulated: a fake store is a test seam, not
 * a claim that a service exists.
 */

/** A clock the tests control, so session wording and expiry are deterministic. */
class FixedAccountClock(private val nowMillis: Long) : AccountClock {
    override fun nowMillis(): Long = nowMillis
}

/** In-memory session store with observable calls and injectable failures. */
class FakeAccountSessionStore(
    var record: StoredAccountSession? = null,
) : AccountSessionStore {
    var loadFailure: Exception? = null
    var saveFailure: Exception? = null
    var clearFailure: Exception? = null

    var loadCalls: Int = 0
        private set
    var saveCalls: Int = 0
        private set
    var clearCalls: Int = 0
        private set

    /** Records what the last successful save contained, for separation assertions. */
    var lastSavedSession: AccountSession? = null
        private set

    override fun load(): StoredAccountSession? {
        loadCalls++
        loadFailure?.let { throw it }
        return record
    }

    override fun save(session: AccountSession, credential: AccountSessionCredential) {
        saveCalls++
        saveFailure?.let { throw it }
        lastSavedSession = session
        record = StoredAccountSession(session, credential)
    }

    override fun clear() {
        clearCalls++
        clearFailure?.let { throw it }
        record = null
        lastSavedSession = null
    }
}

/** Authenticator whose every answer is controlled by the test. */
class FakeAccountAuthenticator : AccountAuthenticator {
    override val providerId: AccountProviderId = AccountProviderId.CRAFTMIND

    var availabilityValue: AccountAuthenticationAvailability = AccountAuthenticationAvailability.Available

    /** Returns exactly what the test asks for; the default is a rejection, so success can never happen by accident. */
    var signInBehavior: (AccountSignInRequest) -> AccountAuthOutcome =
        { AccountAuthOutcome.Failure(AccountAuthErrorCode.INVALID_CREDENTIALS) }

    var refreshBehavior: (AccountSession) -> AccountAuthOutcome =
        { AccountAuthOutcome.Failure(AccountAuthErrorCode.SERVICE_UNAVAILABLE) }

    var restoreBehavior: (AccountSession) -> AccountAuthOutcome =
        { AccountAuthOutcome.Failure(AccountAuthErrorCode.INVALID_CREDENTIALS) }

    var signOutResult: AccountSignOutResult = AccountSignOutResult(AccountSignOutMethod.LOCAL_ONLY)
    var beforeSignOut: (() -> Unit)? = null

    var deletionResult: AccountDeletionOutcome =
        AccountDeletionOutcome.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED)

    /** Thrown from every operation when set, to prove exceptions cannot escape the state machine. */
    var failureToThrow: Exception? = null

    var signInCalls: Int = 0
        private set
    var refreshCalls: Int = 0
        private set
    var restoreCalls: Int = 0
        private set
    var signOutCalls: Int = 0
        private set
    var deletionCalls: Int = 0
        private set

    /** Kept so a test can prove the request was not retained after the attempt. */
    var lastSignInRequest: AccountSignInRequest? = null
        private set

    override fun availability(): AccountAuthenticationAvailability = availabilityValue

    override fun signIn(request: AccountSignInRequest): AccountAuthOutcome {
        signInCalls++
        lastSignInRequest = request
        failureToThrow?.let { throw it }
        return signInBehavior(request)
    }

    override fun restore(credential: AccountSessionCredential, session: AccountSession): AccountAuthOutcome {
        restoreCalls++
        failureToThrow?.let { throw it }
        return restoreBehavior(session)
    }

    override fun refresh(credential: AccountSessionCredential, session: AccountSession): AccountAuthOutcome {
        refreshCalls++
        failureToThrow?.let { throw it }
        return refreshBehavior(session)
    }

    override fun signOut(session: AccountSession?, credential: AccountSessionCredential?): AccountSignOutResult {
        signOutCalls++
        beforeSignOut?.invoke()
        failureToThrow?.let { throw it }
        return signOutResult
    }

    override fun requestDeletion(session: AccountSession?, credential: AccountSessionCredential?): AccountDeletionOutcome {
        deletionCalls++
        failureToThrow?.let { throw it }
        return deletionResult
    }
}

fun testIdentity(
    accountId: String = "cm-account-0001",
    displayName: String = "Build Engineer",
    emailAddress: String? = "builder@example.com",
): AccountIdentity = AccountIdentity.of(accountId, displayName, emailAddress)

fun testSession(
    identity: AccountIdentity = testIdentity(),
    issuedAtEpochMillis: Long = 1_000L,
    expiresAtEpochMillis: Long? = 2_000L,
    refreshable: Boolean = true,
    source: AccountSessionSource = AccountSessionSource.LIVE_SIGN_IN,
    method: AccountSignInMethod = AccountSignInMethod.EMAIL_PASSWORD,
): AccountSession = AccountSession(
    identity = identity,
    providerId = AccountProviderId.CRAFTMIND,
    method = method,
    issuedAtEpochMillis = issuedAtEpochMillis,
    expiresAtEpochMillis = expiresAtEpochMillis,
    refreshable = refreshable,
    source = source,
)

fun testCredential(value: String = "session-secret-value"): AccountSessionCredential =
    AccountSessionCredential.fromCharacters(value.toCharArray())

fun testSuccess(
    session: AccountSession = testSession(),
    credential: AccountSessionCredential = testCredential(),
): AccountAuthOutcome.Success = AccountAuthOutcome.Success(session, credential)

fun testSignInRequest(
    emailAddress: String = "builder@example.com",
    password: String = "a-usable-password",
): AccountSignInRequest = AccountSignInRequest.emailPassword(emailAddress, password.toCharArray())
