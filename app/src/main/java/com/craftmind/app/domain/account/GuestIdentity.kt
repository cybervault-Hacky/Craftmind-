package com.craftmind.app.domain.account

import java.security.SecureRandom

/**
 * The anonymous identity a device uses before it has an account (Phase 17 §7).
 *
 * It is generated on the device from a cryptographic random source, and it is *only* an opaque tag: it contains no
 * advertising id, no Android id, no hardware serial, no device model, no phone number, and no location. The service uses
 * it for exactly two things — distinguishing an anonymous session from a registered account, and linking the anonymous
 * identity to the account that is created from it — and it is never an authentication credential.
 *
 * Clearing app data or reinstalling removes it, and the next launch gets a new one. That is stated in the app rather
 * than hidden, because a fresh anonymous identity is *not* a way to reset an account.
 */
class GuestIdentity private constructor(val value: String) {
    override fun toString(): String = "GuestIdentity([OPAQUE])"

    override fun equals(other: Any?): Boolean = other is GuestIdentity && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** 24 random bytes in base64url form: 32 characters, all URL-safe. */
        const val RANDOM_BYTES = 24
        const val EXPECTED_CHARACTERS = 32
        private val PATTERN = Regex("^[A-Za-z0-9_-]{22,64}$")

        fun of(value: String): GuestIdentity {
            require(PATTERN.matches(value)) { "a guest identity must be an opaque URL-safe token" }
            return GuestIdentity(value)
        }

        fun isWellFormed(value: String?): Boolean = value != null && PATTERN.matches(value)

        /**
         * Generates a new identity. The random source is injectable so tests are deterministic; production always uses
         * [SecureRandom].
         */
        fun generate(random: SecureRandom = SecureRandom()): GuestIdentity {
            val bytes = ByteArray(RANDOM_BYTES)
            random.nextBytes(bytes)
            val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return GuestIdentity(encoded)
        }
    }
}

/** Where a device keeps its guest identity. A file, deliberately: it is not a preference and not a credential. */
interface GuestIdentityStore {
    fun read(): String?

    fun write(value: String)
}

/**
 * Keeps one stable guest identity per device.
 *
 * [current] is idempotent: the first call generates and persists an identity, and every later call returns the same one.
 * A store that cannot be written still yields a usable identity for this run, so an unwritable file can never block the
 * app; the next launch simply produces another anonymous identity.
 */
class GuestIdentityManager(
    private val store: GuestIdentityStore,
    private val random: SecureRandom = SecureRandom(),
) {
    @Volatile
    private var cached: GuestIdentity? = null

    fun current(): GuestIdentity {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val stored = runCatching { store.read() }.getOrNull()
            if (GuestIdentity.isWellFormed(stored)) {
                val identity = GuestIdentity.of(stored!!)
                cached = identity
                return identity
            }
            val generated = GuestIdentity.generate(random)
            runCatching { store.write(generated.value) }
            cached = generated
            return generated
        }
    }
}
