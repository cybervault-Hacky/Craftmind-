package com.craftmind.app.domain.account

/**
 * Account session credential (Phase 16).
 *
 * A deliberately separate type from the AI provider credential: an account session secret and a BYOK provider API key
 * are different security domains with different lifetimes and different owners, and nothing may convert one into the
 * other. Like the provider container, the value is only reachable through [useSecret], which hands out a temporary copy,
 * and [toString] is redacted so a stray log line cannot print it.
 */
class AccountSessionCredential private constructor(private val characters: CharArray) : AutoCloseable {
    private var closed: Boolean = false

    @Synchronized
    fun <T> useSecret(block: (CharArray) -> T): T {
        check(!closed) { "Session credential has already been cleared" }
        val temporaryCopy = characters.copyOf()
        return try {
            block(temporaryCopy)
        } finally {
            temporaryCopy.fill('\u0000')
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            characters.fill('\u0000')
            closed = true
        }
    }

    override fun toString(): String = "AccountSessionCredential([REDACTED])"

    companion object {
        /** Upper bound enforced by every store implementation. */
        const val MAXIMUM_CHARACTERS = 4096

        fun fromCharacters(value: CharArray): AccountSessionCredential {
            require(value.size in 1..MAXIMUM_CHARACTERS) { "session credential length is out of range" }
            return AccountSessionCredential(value.copyOf())
        }
    }
}

/** What can go wrong in the encrypted session store. Codes only: no storage path or crypto detail reaches the user. */
enum class AccountSessionStoreError {
    /** The stored record does not satisfy the format (truncated, tampered, or from another scheme). */
    CORRUPTED_SESSION,

    /** The Keystore key is missing or unusable. */
    KEY_UNAVAILABLE,

    /** The record is larger than the store accepts. */
    TOO_LARGE,

    /** Reading or writing failed. */
    STORAGE_FAILURE,
}

class AccountSessionStoreException(val error: AccountSessionStoreError) : Exception(error.name)

/** A stored session: metadata in the clear model, secret in the credential container. */
data class StoredAccountSession(
    val session: AccountSession,
    val credential: AccountSessionCredential,
)

/**
 * The session storage boundary (Phase 16).
 *
 * Contract for implementations:
 * * the credential is persisted **only** inside Keystore-backed encryption, in app-private storage — never in plain
 *   preferences, never in a file that participates in backup, never in the same namespace as provider credentials;
 * * at most one session is stored: [save] replaces the previous record, so the store is a single account slot and
 *   there is nothing to accumulate or to leak between accounts;
 * * [load] may throw [AccountSessionStoreException]; callers treat a failure as an unusable session and never as a
 *   crash;
 * * the credential passed to [save] belongs to the caller and is closed by it — an implementation must copy what it
 *   needs and retain nothing;
 * * [clear] must be idempotent and must not touch any other stored data.
 */
interface AccountSessionStore {
    /** Returns the stored session, or null when this device holds none. */
    fun load(): StoredAccountSession?

    /** Replaces the stored session. */
    fun save(session: AccountSession, credential: AccountSessionCredential)

    /** Removes the stored session. Safe to call when nothing is stored. */
    fun clear()
}
