package com.craftmind.app.domain.account

/**
 * The authenticator this build ships with (Phase 16).
 *
 * This is the explicit fallback for a build with no account service configured; the app's configured composition root
 * uses the real HTTP-backed authenticator instead. The fallback makes unavailability impossible to mistake for success:
 * * [availability] always reports [AccountAvailabilityReason.NO_BACKEND_CONFIGURED];
 * * every authentication operation returns [AccountAuthOutcome.Unavailable] — it can never produce a session, a
 *   credential, or an identity, because it has no service to produce one from;
 * * [signOut] reports [AccountSignOutMethod.LOCAL_ONLY]: clearing this device needs no service;
 * * [requestDeletion] reports [AccountDeletionOutcome.Unavailable]: a deletion request cannot be made, and nothing is
 *   deleted.
 *
 * It is not an authentication bypass and cannot be used to create a session. The configured Android composition now
 * injects the real HTTP-backed implementation; this remains the explicit local-only option for tests and other callers
 * that deliberately do not supply one. The session state machine, storage boundary, and UI state model stay the same.
 */
class NoBackendAccountAuthenticator : AccountAuthenticator {
    override val providerId: AccountProviderId = AccountProviderId.CRAFTMIND

    override fun availability(): AccountAuthenticationAvailability =
        AccountAuthenticationAvailability.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED)

    override fun signIn(request: AccountSignInRequest): AccountAuthOutcome =
        AccountAuthOutcome.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED)

    override fun restore(credential: AccountSessionCredential, session: AccountSession): AccountAuthOutcome =
        AccountAuthOutcome.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED)

    override fun refresh(credential: AccountSessionCredential, session: AccountSession): AccountAuthOutcome =
        AccountAuthOutcome.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED)

    override fun signOut(session: AccountSession?, credential: AccountSessionCredential?): AccountSignOutResult =
        AccountSignOutResult(AccountSignOutMethod.LOCAL_ONLY)

    override fun requestDeletion(session: AccountSession?, credential: AccountSessionCredential?): AccountDeletionOutcome =
        AccountDeletionOutcome.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED)
}
