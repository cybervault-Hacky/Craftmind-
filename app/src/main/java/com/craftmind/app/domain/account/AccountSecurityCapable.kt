package com.craftmind.app.domain.account

/**
 * Optional capability implemented by the real account-service authenticator. Keeping recovery/security operations behind
 * the Phase 16 manager means the UI never receives an access token or opens a second session store.
 */
interface AccountSecurityCapable {
    fun verifyEmail(token: CharArray): AccountApiOutcome<AccountEmailVerificationResult>
    fun resendVerification(emailAddress: String): AccountApiOutcome<AccountOperationReceipt>
    fun requestPasswordReset(emailAddress: String): AccountApiOutcome<AccountOperationReceipt>
    fun confirmPasswordReset(token: CharArray, newPassword: CharArray): AccountApiOutcome<AccountOperationReceipt>
    fun changePassword(credential: AccountSessionCredential, currentPassword: CharArray, newPassword: CharArray): AccountApiOutcome<AccountPasswordChangeResult>
    fun listSessions(credential: AccountSessionCredential): AccountApiOutcome<AccountSessionList>
    fun revokeSession(credential: AccountSessionCredential, sessionId: String): AccountApiOutcome<Unit>
    fun revokeOtherSessions(credential: AccountSessionCredential): AccountApiOutcome<AccountRevokeSessionsResult>
}
