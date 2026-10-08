package com.craftmind.app.domain.account

/**
 * Implemented by an authenticator that can *create* accounts as well as sign in to existing ones.
 *
 * Registration is a separate operation with a separate endpoint, so it is a separate capability rather than a parameter
 * every authenticator has to carry. An authenticator that cannot register simply does not implement this, and the state
 * machine reports that honestly instead of the UI hiding a button that would do nothing.
 */
interface AccountRegistrationCapable {
    fun signUp(emailAddress: String, password: CharArray, displayName: String): AccountAuthOutcome
}
