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
) : ViewModel() {
    @Volatile
    private var ownershipRecorded: Boolean = false

    @Volatile
    private var deletionOutcome: AccountDeletionOutcome? = null

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
    }

    fun dispatch(event: AccountUiEvent) {
        when (event) {
            is AccountUiEvent.SignInRequested -> submitSignIn(event)
            AccountUiEvent.SignOutRequested -> background { manager.signOut() }
            AccountUiEvent.RetryRequested -> background { manager.restoreSession() }
            AccountUiEvent.AccountDeletionRequested -> background {
                deletionOutcome = manager.requestAccountDeletion()
            }
        }
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
            // Nothing leaves the device for malformed local input: the state machine records it like any other failure,
            // and the screen says so instead of blaming a service that was never contacted.
            background { manager.failRequest(AccountAuthErrorCode.INVALID_REQUEST) }
            return
        }
        deletionOutcome = null
        background { manager.signIn(request) }
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
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AccountViewModel(manager, ownershipMigration, clock) as T
}
