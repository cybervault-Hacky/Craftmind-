package com.craftmind.app.domain.account

/**
 * Time source for the account layer (Phase 16).
 *
 * Session expiry is a security-relevant decision, so it is taken against an injected clock rather than a direct
 * `System.currentTimeMillis()` call inside the state machine: tests can then prove that an expired session is detected,
 * that a session is *not* expired when it should not be, and that a boundary second behaves as documented.
 */
interface AccountClock {
    fun nowMillis(): Long

    companion object {
        /** Wall-clock time in UTC milliseconds. */
        val System: AccountClock = object : AccountClock {
            override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
        }
    }
}
