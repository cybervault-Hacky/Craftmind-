package com.craftmind.app.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.craftmind.app.domain.account.AccountAuthErrorCode
import com.craftmind.app.domain.account.AccountClock
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

    private val mutableState = MutableStateFlow(currentUiState())
    val state: StateFlow<AccountUiState> = mutableState.asStateFlow()

    private val removeListener = manager.addListener { publish() }

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
        publish()
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
        val request = try {
            AccountSignInRequest.emailPassword(event.emailAddress, password)
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            // The event's copy is not needed once the request owns its own copy.
            password.fill('\u0000')
        }
        if (request == null) {
            // A field that is unusable is a form problem: it is reported on the field, without a request and without a
            // service-blame failure state, because nothing was ever sent anywhere.
            formIssues = listOf(AccountFormIssue(AccountFormField.PASSWORD, "Enter your password."))
            formMode = AccountFormMode.SIGN_IN
            publish()
            return
        }
        val validation = AccountFormValidator.validateSignIn(
            AccountSignInInput(emailAddress = event.emailAddress, password = String(password)),
        )
        if (validation is AccountFormValidation.Invalid) {
            // emailPassword created a defensive secret copy; close it because local validation prevented submission.
            request.close()
            formIssues = validation.issues
            formMode = AccountFormMode.SIGN_IN
            publish()
            return
        }
        formIssues = emptyList()
        deletionOutcome = null
        try {
            background { manager.signIn(request) }
        } finally {
            event.password.fill('\u0000')
        }
    }

    private fun background(operation: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            operation()
            publish()
        }
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
