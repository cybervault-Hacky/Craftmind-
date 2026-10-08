package com.craftmind.app.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.craftmind.app.domain.account.AccountApiErrorCode
import com.craftmind.app.domain.account.AccountApiOutcome
import com.craftmind.app.domain.account.AccountAuthErrorCode
import com.craftmind.app.domain.account.AccountClock
import com.craftmind.app.domain.account.AccountRemoteSession
import com.craftmind.app.domain.account.AccountState
import com.craftmind.app.domain.account.AccountDeletionOutcome
import com.craftmind.app.domain.account.AccountSessionManager
import com.craftmind.app.domain.account.AccountSignInRequest
import com.craftmind.app.domain.account.LocalOwnershipMigration
import com.craftmind.app.domain.account.OwnershipMarkerOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Input the account surfaces can produce. */
sealed interface AccountUiEvent {
    /** A sign-in attempt with typed credentials. Only reachable in a build that has an account service. */
    data class SignInRequested(val emailAddress: String, val password: CharArray) : AccountUiEvent

    /** A registration attempt. Validated on the device first; the service validates again. */
    data class SignUpRequested(
        val emailAddress: String,
        val password: CharArray,
        val confirmPassword: CharArray,
        val displayName: String,
    ) : AccountUiEvent

    data class SecurityPanelRequested(val panel: AccountSecurityPanel) : AccountUiEvent
    data object SecurityPanelDismissed : AccountUiEvent
    data class VerifyEmailSubmitted(val token: CharArray) : AccountUiEvent
    data class ResendVerificationSubmitted(val emailAddress: String) : AccountUiEvent
    data class PasswordResetRequested(val emailAddress: String) : AccountUiEvent
    data class PasswordResetConfirmed(val token: CharArray, val newPassword: CharArray, val confirmation: CharArray) : AccountUiEvent
    data class PasswordChangeRequested(val currentPassword: CharArray, val newPassword: CharArray, val confirmation: CharArray) : AccountUiEvent
    data object SessionsRefreshRequested : AccountUiEvent
    data class SessionRevocationRequested(val sessionId: String) : AccountUiEvent
    data object OtherSessionsRevocationRequested : AccountUiEvent

    /** Shows the sign-in form. */
    data object SignInFormRequested : AccountUiEvent

    /** Shows the registration form. */
    data object SignUpFormRequested : AccountUiEvent

    /** Leaves whichever form is open and returns to the account summary. Never signs anyone out. */
    data object FormDismissed : AccountUiEvent

    data object SignOutRequested : AccountUiEvent

    /** Repeats the last authentication attempt, for a failure the contract marks as retryable. */
    data object RetryRequested : AccountUiEvent

    /** Asks the account service to delete the account. Never deletes local data, never signs out. */
    data object AccountDeletionRequested : AccountUiEvent
}

/**
 * Bridges the deterministic account state machine to Compose (Phase 16 §8).
 *
 * Deliberately thin: it holds no authentication logic of its own. Every decision — whether an attempt is possible, what
 * a failure means, what may be destroyed — lives in [AccountSessionManager] and in the pure [accountUiState] projection,
 * both of which are covered by JVM tests. This class only moves calls off the main thread (a real authenticator performs
 * network work), closes typed secrets it no longer needs, and publishes the projected state.
 */
class AccountViewModel(
    private val manager: AccountSessionManager,
    private val ownershipMigration: LocalOwnershipMigration,
    private val clock: AccountClock = AccountClock.System,
    private val guestAnnouncement: (() -> Unit)? = null,
) : ViewModel() {
    @Volatile
    private var ownershipRecorded: Boolean = false

    @Volatile
    private var deletionOutcome: AccountDeletionOutcome? = null

    @Volatile
    private var formMode: AccountFormMode = AccountFormMode.NONE

    @Volatile
    private var formIssues: List<AccountFormIssue> = emptyList()

    @Volatile
    private var securityPanel: AccountSecurityPanel = AccountSecurityPanel.NONE

    @Volatile
    private var securityOperationState: AccountSecurityOperationState = AccountSecurityOperationState.IDLE

    @Volatile
    private var securityMessage: String? = null

    @Volatile
    private var securitySessions: List<AccountRemoteSession> = emptyList()

    @Volatile
    private var securityIssues: List<AccountSecurityIssue> = emptyList()

    private val mutableState = MutableStateFlow(currentUiState())
    val state: StateFlow<AccountUiState> = mutableState.asStateFlow()

    private val removeListener = manager.addListener { onManagerStateChanged() }

    init {
        // Restore first (a signed-in device must look signed in immediately), then record the ownership marker once.
        viewModelScope.launch(Dispatchers.IO) {
            manager.restoreSession()
            val outcome = ownershipMigration.ensureMarker()
            ownershipRecorded = outcome !is OwnershipMarkerOutcome.StorageUnavailable
            publish()
        }
        // Introducing the anonymous identity is best effort: if the service is unreachable, the device is still simply a
        // guest, and nothing is blocked or retried behind the user's back.
        viewModelScope.launch(Dispatchers.IO) { guestAnnouncement?.invoke() }
    }

    fun dispatch(event: AccountUiEvent) {
        when (event) {
            is AccountUiEvent.SignInRequested -> submitSignIn(event)
            is AccountUiEvent.SignUpRequested -> submitSignUp(event)
            is AccountUiEvent.SecurityPanelRequested -> showSecurityPanel(event.panel)
            AccountUiEvent.SecurityPanelDismissed -> showSecurityPanel(AccountSecurityPanel.NONE)
            is AccountUiEvent.VerifyEmailSubmitted -> verifyEmail(event.token)
            is AccountUiEvent.ResendVerificationSubmitted -> resendVerification(event.emailAddress)
            is AccountUiEvent.PasswordResetRequested -> requestPasswordReset(event.emailAddress)
            is AccountUiEvent.PasswordResetConfirmed -> confirmPasswordReset(event)
            is AccountUiEvent.PasswordChangeRequested -> changePassword(event)
            AccountUiEvent.SessionsRefreshRequested -> refreshSessions()
            is AccountUiEvent.SessionRevocationRequested -> revokeSession(event.sessionId)
            AccountUiEvent.OtherSessionsRevocationRequested -> revokeOtherSessions()
            AccountUiEvent.SignInFormRequested -> showForm(AccountFormMode.SIGN_IN)
            AccountUiEvent.SignUpFormRequested -> showForm(AccountFormMode.SIGN_UP)
            AccountUiEvent.FormDismissed -> showForm(AccountFormMode.NONE)
            AccountUiEvent.SignOutRequested -> background { manager.signOut() }
            AccountUiEvent.RetryRequested -> background { manager.restoreSession() }
            AccountUiEvent.AccountDeletionRequested -> background {
                deletionOutcome = manager.requestAccountDeletion()
            }
        }
    }

    /**
     * Validates on the device before anything leaves it, and reports every problem at once.
     *
     * A local validation failure never reaches the state machine and never reaches the network: it is a form problem,
     * and the form says so.
     */
    private fun showForm(mode: AccountFormMode) {
        formIssues = emptyList()
        formMode = mode
        if (mode != AccountFormMode.NONE) showSecurityPanel(AccountSecurityPanel.NONE)
        publish()
    }

    private fun showSecurityPanel(panel: AccountSecurityPanel) {
        if (panel != AccountSecurityPanel.NONE) {
            formMode = AccountFormMode.NONE
            formIssues = emptyList()
        }
        securityPanel = panel
        securityOperationState = AccountSecurityOperationState.IDLE
        securityMessage = null
        securityIssues = emptyList()
        publish()
        if (panel == AccountSecurityPanel.SESSIONS) refreshSessions()
    }

    private fun verifyEmail(token: CharArray) {
        if (token.size !in 32..128) {
            token.fill('\u0000')
            securityOperationState = AccountSecurityOperationState.INVALID_INPUT
            securityMessage = "Enter the one-time verification code from the email message."
            publish()
            return
        }
        performSecurityOperation({ manager.verifyEmail(token) }) { "Email verified. Sign in to establish a session." }
    }

    private fun resendVerification(emailAddress: String) {
        val issue = AccountFormValidator.emailViolation(emailAddress)
        if (issue != null) {
            securityOperationState = AccountSecurityOperationState.INVALID_INPUT
            securityIssues = listOf(AccountSecurityIssue(AccountSecurityField.EMAIL, issue))
            securityMessage = issue
            publish()
            return
        }
        performSecurityOperation({ manager.resendVerification(emailAddress) }) { receipt ->
            if (receipt.deliveryMode == com.craftmind.app.domain.account.AccountEmailDeliveryStatus.DEVELOPMENT_SINK) {
                "No email was sent by the development sink. Configure a real mail provider before production use."
            } else {
                "If verification is needed, the service accepted your request for processing. This response does not confirm that an email was delivered."
            }
        }
    }

    private fun requestPasswordReset(emailAddress: String) {
        val issue = AccountFormValidator.emailViolation(emailAddress)
        if (issue != null) {
            securityOperationState = AccountSecurityOperationState.INVALID_INPUT
            securityIssues = listOf(AccountSecurityIssue(AccountSecurityField.EMAIL, issue))
            securityMessage = issue
            publish()
            return
        }
        performSecurityOperation({ manager.requestPasswordReset(emailAddress) }) { receipt ->
            if (receipt.deliveryMode == com.craftmind.app.domain.account.AccountEmailDeliveryStatus.DEVELOPMENT_SINK) {
                "No email was sent: this service stores recovery messages only in its private development test sink, which is not exposed in the app. Configure a real provider to receive codes."
            } else {
                "If the account exists, the service accepted the recovery request for processing. This response neither confirms the account nor confirms email delivery."
            }
        }
    }

    private fun confirmPasswordReset(event: AccountUiEvent.PasswordResetConfirmed) {
        val password = String(event.newPassword)
        val confirmation = String(event.confirmation)
        val issue = AccountFormValidator.passwordViolation(password)
        val issues = buildList {
            if (event.token.size !in 32..128) add(AccountSecurityIssue(AccountSecurityField.TOKEN, "Enter the recovery code."))
            if (issue != null) add(AccountSecurityIssue(AccountSecurityField.NEW_PASSWORD, issue))
            if (confirmation != password) add(AccountSecurityIssue(AccountSecurityField.CONFIRM_PASSWORD, "The two passwords do not match."))
        }
        event.confirmation.fill('\u0000')
        if (issues.isNotEmpty()) {
            event.token.fill('\u0000')
            event.newPassword.fill('\u0000')
            securityIssues = issues
            securityOperationState = AccountSecurityOperationState.INVALID_INPUT
            securityMessage = "Check the recovery code and password fields."
            publish()
            return
        }
        performSecurityOperation({ manager.confirmPasswordReset(event.token, event.newPassword) }) {
            "Password reset. Existing sessions were revoked; sign in again."
        }
    }

    private fun changePassword(event: AccountUiEvent.PasswordChangeRequested) {
        val current = String(event.currentPassword)
        val newPassword = String(event.newPassword)
        val confirmation = String(event.confirmation)
        val issues = buildList {
            if (current.isEmpty()) add(AccountSecurityIssue(AccountSecurityField.CURRENT_PASSWORD, "Enter your current password."))
            AccountFormValidator.passwordViolation(newPassword)?.let {
                add(AccountSecurityIssue(AccountSecurityField.NEW_PASSWORD, it))
            }
            if (confirmation != newPassword) {
                add(AccountSecurityIssue(AccountSecurityField.CONFIRM_PASSWORD, "The two new passwords do not match."))
            }
        }
        event.confirmation.fill('\u0000')
        if (issues.isNotEmpty()) {
            event.currentPassword.fill('\u0000')
            event.newPassword.fill('\u0000')
            securityIssues = issues
            securityOperationState = AccountSecurityOperationState.INVALID_INPUT
            securityMessage = "Check the password fields."
            publish()
            return
        }
        performSecurityOperation({ manager.changePassword(event.currentPassword, event.newPassword) }) {
            "Password changed. This session remains active; other sessions were revoked."
        }
    }

    private fun refreshSessions() {
        securityPanel = AccountSecurityPanel.SESSIONS
        performSecurityOperation({ manager.listSessions() }, onSuccess = { result ->
            securitySessions = result.sessions
            "Active sessions loaded. Session details contain no credentials."
        })
    }

    private fun revokeSession(sessionId: String) {
        performSecurityOperation({ manager.revokeSession(sessionId) }, onSuccess = {
            val refreshed = manager.listSessions()
            if (refreshed is AccountApiOutcome.Success) securitySessions = refreshed.value.sessions
            "The selected session was revoked."
        })
    }

    private fun revokeOtherSessions() {
        performSecurityOperation({ manager.revokeOtherSessions() }, onSuccess = { result ->
            val refreshed = manager.listSessions()
            if (refreshed is AccountApiOutcome.Success) securitySessions = refreshed.value.sessions
            "${result.revokedSessions} other session(s) were revoked. This device remains signed in."
        })
    }

    private fun <T> performSecurityOperation(
        operation: () -> AccountApiOutcome<T>,
        onSuccess: (T) -> String,
    ) {
        securityOperationState = AccountSecurityOperationState.SUBMITTING
        securityMessage = null
        securityIssues = emptyList()
        publish()
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = try { operation() } catch (_: Exception) { AccountApiOutcome.Unreachable }
            when (outcome) {
                is AccountApiOutcome.Success -> {
                    securityOperationState = AccountSecurityOperationState.SUCCESS
                    securityMessage = onSuccess(outcome.value)
                }
                is AccountApiOutcome.Unreachable -> {
                    securityOperationState = AccountSecurityOperationState.NETWORK_UNAVAILABLE
                    securityMessage = "The account service could not be reached. Try again when the network is available."
                }
                is AccountApiOutcome.Malformed -> {
                    securityOperationState = AccountSecurityOperationState.UNKNOWN_ERROR
                    securityMessage = "The account service response could not be verified. No raw server detail is shown."
                }
                is AccountApiOutcome.Rejected -> {
                    val mapped = securityFailure(outcome.code)
                    securityOperationState = mapped.first
                    securityMessage = mapped.second
                }
            }
            publish()
        }
    }

    private fun securityFailure(code: AccountApiErrorCode): Pair<AccountSecurityOperationState, String> = when (code) {
        AccountApiErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED,
        AccountApiErrorCode.PASSWORD_RESET_TOKEN_EXPIRED -> AccountSecurityOperationState.EXPIRED_TOKEN to "That one-time code expired. Request a new code."
        AccountApiErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID,
        AccountApiErrorCode.EMAIL_VERIFICATION_TOKEN_USED,
        AccountApiErrorCode.PASSWORD_RESET_TOKEN_INVALID,
        AccountApiErrorCode.PASSWORD_RESET_TOKEN_USED -> AccountSecurityOperationState.INVALID_TOKEN to "That one-time code is invalid or has already been used."
        AccountApiErrorCode.INVALID_EMAIL,
        AccountApiErrorCode.INVALID_PASSWORD,
        AccountApiErrorCode.CURRENT_PASSWORD_INVALID -> AccountSecurityOperationState.INVALID_INPUT to "The account service did not accept one of the entered fields."
        AccountApiErrorCode.RATE_LIMITED -> AccountSecurityOperationState.RATE_LIMITED to "Too many attempts. Wait before trying again."
        AccountApiErrorCode.BACKEND_UNAVAILABLE,
        AccountApiErrorCode.EMAIL_DELIVERY_UNAVAILABLE,
        AccountApiErrorCode.HTTPS_REQUIRED -> AccountSecurityOperationState.BACKEND_UNAVAILABLE to "The account service or its email delivery is unavailable."
        AccountApiErrorCode.NETWORK_ERROR -> AccountSecurityOperationState.NETWORK_UNAVAILABLE to "The account service could not be reached."
        else -> AccountSecurityOperationState.UNKNOWN_ERROR to "The request did not complete. No raw server detail is shown."
    }

    private fun submitSignUp(event: AccountUiEvent.SignUpRequested) {
        val candidate = try {
            AccountSignUpInput(
                emailAddress = event.emailAddress,
                password = String(event.password),
                confirmPassword = String(event.confirmPassword),
                displayName = event.displayName,
            )
        } finally {
            // Confirmation is only for local matching and must not survive the validation step.
            event.confirmPassword.fill('\u0000')
        }
        val validation = AccountFormValidator.validateSignUp(candidate)
        if (validation is AccountFormValidation.Invalid) {
            formIssues = validation.issues
            formMode = AccountFormMode.SIGN_UP
            event.password.fill('\u0000')
            event.confirmPassword.fill('\u0000')
            publish()
            return
        }
        formIssues = emptyList()
        deletionOutcome = null
        background { manager.signUp(event.emailAddress, event.password, event.displayName.trim()) }
    }

    private fun submitSignIn(event: AccountUiEvent.SignInRequested) {
        val password = event.password
        try {
            val validation = AccountFormValidator.validateSignIn(
                AccountSignInInput(emailAddress = event.emailAddress, password = String(password)),
            )
            if (validation is AccountFormValidation.Invalid) {
                formIssues = validation.issues
                formMode = AccountFormMode.SIGN_IN
                publish()
                return
            }
            val request = try {
                AccountSignInRequest.emailPassword(event.emailAddress, password)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (request == null) {
                // A field that is unusable is a form problem, without a request or service-blame failure state.
                formIssues = listOf(AccountFormIssue(AccountFormField.PASSWORD, "Enter your password."))
                formMode = AccountFormMode.SIGN_IN
                publish()
                return
            }
            formIssues = emptyList()
            deletionOutcome = null
            background { manager.signIn(request) }
        } finally {
            // Validation runs before wiping; the typed request owns its own copy before network work starts.
            password.fill('\u0000')
        }
    }

    private fun background(operation: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            operation()
            publish()
        }
    }

    private fun onManagerStateChanged() {
        when (manager.state()) {
            AccountState.Guest,
            is AccountState.VerificationRequired,
            is AccountState.EmailVerified,
            is AccountState.Authenticated,
            is AccountState.SigningOut,
            is AccountState.SessionExpired,
            is AccountState.Unavailable -> {
                formMode = AccountFormMode.NONE
                formIssues = emptyList()
            }
            is AccountState.SigningIn, is AccountState.Failed -> Unit
        }
        publish()
    }

    private fun publish() {
        mutableState.value = currentUiState()
    }

    private fun currentUiState(): AccountUiState = accountUiState(
        state = manager.state(),
        availability = manager.availability(),
        nowMillis = clock.nowMillis(),
        ownershipRecorded = ownershipRecorded,
        deletionOutcome = deletionOutcome,
        formMode = formMode,
        formIssues = formIssues,
        securityPanel = securityPanel,
        securityOperationState = securityOperationState,
        securityMessage = securityMessage,
        securitySessions = securitySessions,
        securityIssues = securityIssues,
    )

    override fun onCleared() {
        removeListener()
        super.onCleared()
    }
}

/** Builds the account view model from the composition root. */
class AccountViewModelFactory(
    private val manager: AccountSessionManager,
    private val ownershipMigration: LocalOwnershipMigration,
    private val clock: AccountClock = AccountClock.System,
    private val guestAnnouncement: (() -> Unit)? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AccountViewModel(manager, ownershipMigration, clock, guestAnnouncement) as T
}
