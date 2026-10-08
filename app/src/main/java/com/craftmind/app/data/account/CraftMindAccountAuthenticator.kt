package com.craftmind.app.data.account

import com.craftmind.app.domain.account.AccountApi
import com.craftmind.app.domain.account.AccountApiErrorCode
import com.craftmind.app.domain.account.AccountApiOutcome
import com.craftmind.app.domain.account.AccountAuthErrorCode
import com.craftmind.app.domain.account.AccountAuthOutcome
import com.craftmind.app.domain.account.AccountAuthenticationAvailability
import com.craftmind.app.domain.account.AccountAuthenticator
import com.craftmind.app.domain.account.AccountAvailabilityReason
import com.craftmind.app.domain.account.AccountDeletionOutcome
import com.craftmind.app.domain.account.AccountEmailVerificationResult
import com.craftmind.app.domain.account.AccountOperationReceipt
import com.craftmind.app.domain.account.AccountPasswordChangeResult
import com.craftmind.app.domain.account.AccountProviderId
import com.craftmind.app.domain.account.AccountRemoteSession
import com.craftmind.app.domain.account.AccountRevokeSessionsResult
import com.craftmind.app.domain.account.AccountSecurityCapable
import com.craftmind.app.domain.account.AccountSessionList
import com.craftmind.app.domain.account.AccountRegistrationCapable
import com.craftmind.app.domain.account.AccountRegistration
import com.craftmind.app.domain.account.AccountServerRecord
import com.craftmind.app.domain.account.AccountServerStatus
import com.craftmind.app.domain.account.AccountSession
import com.craftmind.app.domain.account.AccountSessionCredential
import com.craftmind.app.domain.account.AccountSessionSource
import com.craftmind.app.domain.account.AccountSessionTokens
import com.craftmind.app.domain.account.AccountSignInMethod
import com.craftmind.app.domain.account.AccountSignInRequest
import com.craftmind.app.domain.account.AccountSignOutMethod
import com.craftmind.app.domain.account.AccountSignOutResult
import com.craftmind.app.domain.account.GuestIdentityManager

/**
 * The real CraftMind account authenticator (Phase 17 §15).
 *
 * It is the single implementation of Phase 16's [AccountAuthenticator] contract that talks to a server, and it changes
 * nothing about how the app models accounts: the same [AccountSession] metadata, the same typed outcomes, the same
 * one-slot encrypted credential. Only the source of truth changed — the service decides validity — and this class is the
 * adaptor between that decision and the domain.
 *
 * What it never does:
 *
 * * it never invents a session, an identity, or a success: every success is a server answer that was actually received
 *   and matched the contract;
 * * it never keeps a token in state, in a message, or in a log: tokens live in [AccountSessionTokens] and are cleared;
 * * it never reports an unreachable service as a credential problem, so the app can say "offline" rather than "wrong
 *   password";
 * * it never retries a credential submission by itself.
 */
class CraftMindAccountAuthenticator(
    private val api: AccountApi,
    private val guestIdentityManager: GuestIdentityManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AccountAuthenticator, AccountRegistrationCapable, AccountSecurityCapable {
    override val providerId: AccountProviderId = AccountProviderId.CRAFTMIND

    override fun availability(): AccountAuthenticationAvailability =
        if (api.isConfigured) {
            AccountAuthenticationAvailability.Available
        } else {
            // No address is configured for this build: CraftMind says so instead of offering a sign-in that cannot work.
            AccountAuthenticationAvailability.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED)
        }

    override fun signIn(request: AccountSignInRequest): AccountAuthOutcome {
        val guestIdentityId = currentGuestIdentityId()
        val outcome = request.useSecret { password -> api.login(request.identifier.orEmpty(), password, guestIdentityId) }
        return mapRegistration(outcome)
    }

    /**
     * Creates an account.
     *
     * A separate entry point because it is a separate endpoint, but the mapping is shared with [signIn] so registration
     * and sign-in cannot diverge in what they store or how they fail.
     */
    override fun signUp(emailAddress: String, password: CharArray, displayName: String): AccountAuthOutcome =
        mapRegistration(api.register(emailAddress, password, displayName, currentGuestIdentityId()))

    /**
     * Confirms a stored session with the service.
     *
     * The access token is tried first. If the service says it is expired or unknown, the refresh token is exchanged for a
     * fresh pair — the only path that keeps a session alive across a long absence. A rejected session ends; an
     * unreachable service does not, so a flight or a tunnel does not sign anyone out.
     */
    override fun restore(credential: AccountSessionCredential, session: AccountSession): AccountAuthOutcome {
        val tokens = decodeTokens(credential) ?: return AccountAuthOutcome.Failure(AccountAuthErrorCode.SESSION_REJECTED)
        val access = tokenOf(tokens, access = true)
        return try {
            when (val current = api.currentAccount(access)) {
                is AccountApiOutcome.Success -> {
                    val record = current.value
                    if (record.status != AccountServerStatus.ACTIVE) {
                        AccountAuthOutcome.Failure(AccountAuthErrorCode.ACCOUNT_SUSPENDED)
                    } else {
                        AccountAuthOutcome.Success(
                            session = sessionFrom(record = record, source = AccountSessionSource.RESTORED_ON_DEVICE),
                            credential = credentialOf(tokens),
                        )
                    }
                }

                is AccountApiOutcome.Unreachable -> AccountAuthOutcome.Unavailable(AccountAvailabilityReason.NETWORK_UNAVAILABLE)
                is AccountApiOutcome.Malformed -> AccountAuthOutcome.Failure(AccountAuthErrorCode.MALFORMED_RESPONSE)
                is AccountApiOutcome.Rejected -> when (current.code) {
                    AccountApiErrorCode.SESSION_EXPIRED,
                    AccountApiErrorCode.SESSION_INVALID,
                    AccountApiErrorCode.AUTHENTICATION_REQUIRED,
                    -> refreshInto(tokens, session)

                    AccountApiErrorCode.ACCOUNT_SUSPENDED -> AccountAuthOutcome.Failure(AccountAuthErrorCode.ACCOUNT_SUSPENDED)
                    AccountApiErrorCode.NETWORK_ERROR ->
                        AccountAuthOutcome.Unavailable(AccountAvailabilityReason.NETWORK_UNAVAILABLE)
                    AccountApiErrorCode.BACKEND_UNAVAILABLE,
                    AccountApiErrorCode.UNKNOWN_ERROR,
                    -> AccountAuthOutcome.Unavailable(AccountAvailabilityReason.MAINTENANCE)
                    else -> AccountAuthOutcome.Failure(AccountAuthErrorCode.SESSION_REJECTED)
                }
            }
        } finally {
            access.fill('\u0000')
            tokens.close()
        }
    }

    override fun refresh(credential: AccountSessionCredential, session: AccountSession): AccountAuthOutcome {
        val tokens = decodeTokens(credential) ?: return AccountAuthOutcome.Failure(AccountAuthErrorCode.SESSION_REJECTED)
        return try {
            refreshInto(tokens, session)
        } finally {
            tokens.close()
        }
    }

    /**
     * Revokes the server session, and reports honestly whether that happened. The state machine clears the local session
     * regardless, because a device must not stay signed in merely because a server was unreachable.
     */
    override fun signOut(session: AccountSession?, credential: AccountSessionCredential?): AccountSignOutResult {
        val tokens = credential?.let(::decodeTokens) ?: return AccountSignOutResult(AccountSignOutMethod.LOCAL_ONLY)
        return try {
            when (revoke(tokens)) {
                is AccountApiOutcome.Success -> AccountSignOutResult(AccountSignOutMethod.REMOTE_REVOKED)
                is AccountApiOutcome.Rejected -> AccountSignOutResult(
                    AccountSignOutMethod.REMOTE_REVOCATION_FAILED,
                    AccountAuthErrorCode.SERVICE_UNAVAILABLE,
                )

                is AccountApiOutcome.Unreachable, is AccountApiOutcome.Malformed -> AccountSignOutResult(
                    AccountSignOutMethod.REMOTE_REVOCATION_SKIPPED,
                    AccountAuthErrorCode.NETWORK_UNAVAILABLE,
                )
            }
        } finally {
            tokens.close()
        }
    }

    /**
     * Account deletion is **not implemented** by this phase, and this method does not pretend otherwise.
     *
     * There is no deletion endpoint, nothing is deleted anywhere, and the answer says the service does not implement it.
     * The contract exists so a later phase can implement it without changing the state machine or the UI vocabulary.
     */
    override fun requestDeletion(session: AccountSession?, credential: AccountSessionCredential?): AccountDeletionOutcome =
        AccountDeletionOutcome.Unavailable(AccountAvailabilityReason.NOT_IMPLEMENTED_BY_SERVICE)

    /** Introduces this device's anonymous identity to the service. Best effort: failure never blocks anything. */
    fun announceGuestIdentity(): AccountApiOutcome<Unit> = api.registerGuestIdentity(currentGuestIdentityId())

    override fun verifyEmail(token: CharArray): AccountApiOutcome<AccountEmailVerificationResult> = api.verifyEmail(token)
    override fun resendVerification(emailAddress: String): AccountApiOutcome<AccountOperationReceipt> = api.resendVerification(emailAddress)
    override fun requestPasswordReset(emailAddress: String): AccountApiOutcome<AccountOperationReceipt> = api.requestPasswordReset(emailAddress)
    override fun confirmPasswordReset(token: CharArray, newPassword: CharArray): AccountApiOutcome<AccountOperationReceipt> =
        api.confirmPasswordReset(token, newPassword)

    override fun changePassword(
        credential: AccountSessionCredential,
        currentPassword: CharArray,
        newPassword: CharArray,
    ): AccountApiOutcome<AccountPasswordChangeResult> = withAccessToken(credential) { access ->
        api.changePassword(access, currentPassword, newPassword)
    }

    override fun listSessions(credential: AccountSessionCredential): AccountApiOutcome<AccountSessionList> =
        withAccessToken(credential, api::listSessions)

    override fun revokeSession(credential: AccountSessionCredential, sessionId: String): AccountApiOutcome<Unit> =
        withAccessToken(credential) { access -> api.revokeSession(access, sessionId) }

    override fun revokeOtherSessions(credential: AccountSessionCredential): AccountApiOutcome<AccountRevokeSessionsResult> =
        withAccessToken(credential, api::revokeOtherSessions)

    private inline fun <T> withAccessToken(
        credential: AccountSessionCredential,
        operation: (CharArray) -> AccountApiOutcome<T>,
    ): AccountApiOutcome<T> {
        val tokens = decodeTokens(credential)
            ?: return AccountApiOutcome.Rejected(AccountApiErrorCode.SESSION_INVALID)
        val access = tokenOf(tokens, access = true)
        return try {
            operation(access)
        } finally {
            access.fill('\u0000')
            tokens.close()
        }
    }

    // ---------------------------------------------------------------------------------------------------- internals

    private fun currentGuestIdentityId(): String = guestIdentityManager.current().value

    private fun mapRegistration(outcome: AccountApiOutcome<AccountRegistration>): AccountAuthOutcome = when (outcome) {
        is AccountApiOutcome.Success -> {
            val registration = outcome.value
            val record = registration.record
            when (record.status) {
                AccountServerStatus.SUSPENDED -> {
                    registration.session?.tokens?.close()
                    AccountAuthOutcome.Failure(AccountAuthErrorCode.ACCOUNT_SUSPENDED)
                }
                AccountServerStatus.DELETED -> {
                    registration.session?.tokens?.close()
                    AccountAuthOutcome.Failure(AccountAuthErrorCode.INVALID_CREDENTIALS)
                }
                AccountServerStatus.ACTIVE -> {
                    val sessionResponse = registration.session
                    if (sessionResponse == null) {
                        if (registration.verificationRequired && !record.emailVerified) {
                            AccountAuthOutcome.VerificationRequired(record.toIdentity(), registration.deliveryStatus)
                        } else {
                            AccountAuthOutcome.Failure(AccountAuthErrorCode.MALFORMED_RESPONSE)
                        }
                    } else if (!record.emailVerified) {
                        sessionResponse.tokens.close()
                        AccountAuthOutcome.Failure(AccountAuthErrorCode.MALFORMED_RESPONSE)
                    } else {
                        val tokens = sessionResponse.tokens
                        try {
                            AccountAuthOutcome.Success(
                                session = sessionFrom(
                                    record = record,
                                    source = AccountSessionSource.LIVE_SIGN_IN,
                                    expiresAtEpochMillis = sessionResponse.accessExpiresAtEpochMillis,
                                ),
                                credential = credentialOf(tokens),
                            )
                        } finally {
                            // The credential now owns an encrypted-storage representation. Destroy clear response tokens.
                            tokens.close()
                        }
                    }
                }
            }
        }

        is AccountApiOutcome.Rejected -> AccountAuthOutcome.Failure(outcome.code.toAuthErrorCode())
        is AccountApiOutcome.Unreachable -> AccountAuthOutcome.Unavailable(AccountAvailabilityReason.NETWORK_UNAVAILABLE)
        is AccountApiOutcome.Malformed -> AccountAuthOutcome.Failure(AccountAuthErrorCode.MALFORMED_RESPONSE)
    }

    private fun refreshInto(tokens: AccountSessionTokens, session: AccountSession): AccountAuthOutcome {
        val refreshToken = tokenOf(tokens, access = false)
        return try {
            when (val outcome = api.refresh(refreshToken)) {
                is AccountApiOutcome.Success -> {
                    val rotatedTokens = outcome.value.tokens
                    try {
                        AccountAuthOutcome.Success(
                            session = sessionFrom(
                                record = null,
                                source = AccountSessionSource.RESTORED_ON_DEVICE,
                                expiresAtEpochMillis = outcome.value.accessExpiresAtEpochMillis,
                                existing = session,
                            ),
                            credential = credentialOf(rotatedTokens),
                        )
                    } finally {
                        rotatedTokens.close()
                    }
                }

                is AccountApiOutcome.Rejected -> when (outcome.code) {
                    AccountApiErrorCode.SESSION_EXPIRED,
                    AccountApiErrorCode.SESSION_INVALID,
                    AccountApiErrorCode.REFRESH_FAILED,
                    AccountApiErrorCode.ACCOUNT_DELETED,
                    AccountApiErrorCode.AUTHENTICATION_REQUIRED,
                    -> AccountAuthOutcome.Failure(AccountAuthErrorCode.SESSION_REJECTED)

                    AccountApiErrorCode.ACCOUNT_SUSPENDED -> AccountAuthOutcome.Failure(AccountAuthErrorCode.ACCOUNT_SUSPENDED)
                    AccountApiErrorCode.NETWORK_ERROR ->
                        AccountAuthOutcome.Unavailable(AccountAvailabilityReason.NETWORK_UNAVAILABLE)
                    AccountApiErrorCode.BACKEND_UNAVAILABLE,
                    AccountApiErrorCode.UNKNOWN_ERROR,
                    -> AccountAuthOutcome.Unavailable(AccountAvailabilityReason.MAINTENANCE)
                    else -> AccountAuthOutcome.Failure(AccountAuthErrorCode.SESSION_REJECTED)
                }

                is AccountApiOutcome.Unreachable -> AccountAuthOutcome.Unavailable(AccountAvailabilityReason.NETWORK_UNAVAILABLE)
                is AccountApiOutcome.Malformed -> AccountAuthOutcome.Failure(AccountAuthErrorCode.MALFORMED_RESPONSE)
            }
        } finally {
            refreshToken.fill('\u0000')
        }
    }

    private fun revoke(tokens: AccountSessionTokens): AccountApiOutcome<Unit> {
        val access = tokenOf(tokens, access = true)
        val refresh = tokenOf(tokens, access = false)
        return try {
            api.logout(access, refresh)
        } finally {
            access.fill('\u0000')
            refresh.fill('\u0000')
        }
    }

    private fun decodeTokens(credential: AccountSessionCredential): AccountSessionTokens? =
        credential.useSecret { characters -> AccountSessionTokens.decodeFromStorage(characters) }

    /** Copies one token out of the holder for the duration of a single call; the caller clears the copy. */
    private fun tokenOf(tokens: AccountSessionTokens, access: Boolean): CharArray {
        var copy: CharArray? = null
        tokens.useTokens { accessToken, refreshToken ->
            copy = if (access) accessToken.copyOf() else refreshToken.copyOf()
        }
        return requireNotNull(copy) { "session tokens are no longer readable" }
    }

    /**
     * Builds the app's session metadata from what the service actually reported.
     *
     * When the service declared no expiry, the session carries none and the app never invents one. [existing] supplies
     * the identity and method for a refresh, where the answer contains a session but no account record.
     */
    private fun sessionFrom(
        record: AccountServerRecord?,
        source: AccountSessionSource,
        expiresAtEpochMillis: Long? = null,
        existing: AccountSession? = null,
    ): AccountSession {
        val identity = record?.toIdentity() ?: requireNotNull(existing?.identity) {
            "a refresh answer needs the identity the session already had"
        }
        val issuedAt = clock()
        return AccountSession(
            identity = identity,
            providerId = AccountProviderId.CRAFTMIND,
            method = existing?.method ?: AccountSignInMethod.EMAIL_PASSWORD,
            issuedAtEpochMillis = issuedAt,
            expiresAtEpochMillis = expiresAtEpochMillis?.takeIf { it > issuedAt },
            refreshable = true,
            source = source,
        )
    }

    private fun credentialOf(tokens: AccountSessionTokens): AccountSessionCredential {
        val encoded = tokens.encodeForStorage()
        return try {
            AccountSessionCredential.fromCharacters(encoded)
        } finally {
            encoded.fill('\u0000')
        }
    }

    private fun AccountApiErrorCode.toAuthErrorCode(): AccountAuthErrorCode = when (this) {
        AccountApiErrorCode.INVALID_CREDENTIALS, AccountApiErrorCode.ACCOUNT_NOT_FOUND ->
            AccountAuthErrorCode.INVALID_CREDENTIALS

        AccountApiErrorCode.ACCOUNT_ALREADY_EXISTS -> AccountAuthErrorCode.ACCOUNT_ALREADY_EXISTS
        AccountApiErrorCode.ACCOUNT_SUSPENDED -> AccountAuthErrorCode.ACCOUNT_SUSPENDED
        AccountApiErrorCode.EMAIL_NOT_VERIFIED -> AccountAuthErrorCode.EMAIL_NOT_VERIFIED
        AccountApiErrorCode.RATE_LIMITED -> AccountAuthErrorCode.RATE_LIMITED

        AccountApiErrorCode.SESSION_EXPIRED,
        AccountApiErrorCode.SESSION_INVALID,
        AccountApiErrorCode.REFRESH_FAILED,
        AccountApiErrorCode.ACCOUNT_DELETED,
        AccountApiErrorCode.AUTHENTICATION_REQUIRED,
        -> AccountAuthErrorCode.SESSION_REJECTED

        AccountApiErrorCode.INVALID_EMAIL,
        AccountApiErrorCode.INVALID_DISPLAY_NAME,
        AccountApiErrorCode.INVALID_PASSWORD,
        AccountApiErrorCode.INVALID_REQUEST,
        AccountApiErrorCode.REQUEST_TOO_LARGE,
        AccountApiErrorCode.METHOD_NOT_ALLOWED,
        AccountApiErrorCode.INVALID_GUEST_IDENTITY,
        AccountApiErrorCode.GUEST_IDENTITY_ALREADY_LINKED,
        AccountApiErrorCode.INVALID_DEVICE_LABEL,
        AccountApiErrorCode.INVALID_CONTENT_TYPE,
        AccountApiErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID,
        AccountApiErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED,
        AccountApiErrorCode.EMAIL_VERIFICATION_TOKEN_USED,
        AccountApiErrorCode.PASSWORD_RESET_TOKEN_INVALID,
        AccountApiErrorCode.PASSWORD_RESET_TOKEN_EXPIRED,
        AccountApiErrorCode.PASSWORD_RESET_TOKEN_USED,
        -> AccountAuthErrorCode.INVALID_REQUEST

        AccountApiErrorCode.NETWORK_ERROR -> AccountAuthErrorCode.NETWORK_UNAVAILABLE
        AccountApiErrorCode.BACKEND_UNAVAILABLE,
        AccountApiErrorCode.EMAIL_DELIVERY_UNAVAILABLE,
        AccountApiErrorCode.HTTPS_REQUIRED,
        AccountApiErrorCode.CORS_ORIGIN_NOT_ALLOWED,
        -> AccountAuthErrorCode.SERVICE_UNAVAILABLE
        AccountApiErrorCode.PASSWORD_RESET_NOT_IMPLEMENTED,
        AccountApiErrorCode.CURRENT_PASSWORD_INVALID,
        AccountApiErrorCode.SESSION_NOT_FOUND,
        AccountApiErrorCode.CURRENT_SESSION_REVOKE_NOT_ALLOWED,
        AccountApiErrorCode.UNKNOWN_ERROR,
        -> AccountAuthErrorCode.UNEXPECTED_FAILURE
    }
}
