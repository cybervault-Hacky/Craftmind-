package com.craftmind.app.domain.account

/**
 * The authenticator this build ships with (Phase 16).
 *
 * CraftMind has no account service yet, so this implementation exists to make that fact explicit and impossible to
 * mistake for success:
 * * [availability] always reports [AccountAvailabilityReason.NO_BACKEND_CONFIGURED];
 * * every authentication operation returns [AccountAuthOutcome.Unavailable] — it can never produce a session, a
 *   credential, or an identity, because it has no service to produce one from;
 * * [signOut] reports [AccountSignOutMethod.LOCAL_ONLY]: clearing this device needs no service;
 * * [requestDeletion] reports [AccountDeletionOutcome.Unavailable]: a deletion request cannot be made, and nothing is
 *   deleted.
 *
 * It is not a stub to be replaced by a fake later. When a real account service exists, the replacement is a new
 * implementation of [AccountAuthenticator]; [NoBackendAccountAuthenticator] then simply stops being the one CraftMind
 * chooses in [CraftMindAccountFoundation], and the rest of the app — state machine, storage boundary, UI states,
 * tests — is unchanged.
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
