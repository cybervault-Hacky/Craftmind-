package com.craftmind.app.domain.account

/**
 * Identity of a CraftMind account (Phase 16).
 *
 * A CraftMind account is an identity for *CraftMind* cloud features. It is deliberately **not** an AI provider
 * credential and **not** a Minecraft identity: those are separate security domains with separate storage, and the
 * account layer never accepts, holds, or forwards either of them.
 *
 * The model is intentionally small and cannot be built from nonsense: the account ID is an opaque bounded token
 * issued by the account service, the display name is bounded printable text, and the optional email identifier must
 * look like an address. Nothing here is inferred or invented locally — if the account service does not provide a
 * field, the field is absent.
 *
 * Privacy notes:
 * * [toString] never prints the raw email address and never prints the account ID, so an accidental log line cannot
 *   leak an identifier.
 * * [maskedEmailAddress] is the only form the UI renders.
 */
class AccountIdentity private constructor(
    /** Opaque, stable identifier issued by the account service. Never rendered in the UI. */
    val accountId: String,
    /** Name shown in the account surfaces. */
    val displayName: String,
    /** Email identifier when the account service reports one; null for providers that do not. */
    val emailAddress: String?,
) {
    /** The only form of the email identifier that may be displayed. */
    val maskedEmailAddress: String? get() = emailAddress?.let(::maskEmailAddress)

    override fun equals(other: Any?): Boolean = other is AccountIdentity &&
        other.accountId == accountId &&
        other.displayName == displayName &&
        other.emailAddress == emailAddress

    override fun hashCode(): Int {
        var result = accountId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + (emailAddress?.hashCode() ?: 0)
        return result
    }

    /** Redacted: no account ID, no raw email address. */
    override fun toString(): String =
        "AccountIdentity(displayName=$displayName, emailAddress=${maskedEmailAddress ?: "none"})"

    companion object {
        /** Bounded so a hostile or malformed service response cannot blow up storage or UI. */
        const val MAXIMUM_IDENTIFIER_CHARACTERS = 128
        const val MAXIMUM_DISPLAY_NAME_CHARACTERS = 80
        const val MAXIMUM_EMAIL_CHARACTERS = 254

        /**
         * Account IDs are compared and stored as opaque tokens: alphanumerics plus `.`, `_`, `:`, `-`.
         * This is deliberately a *shape* check, not a scheme check — CraftMind does not invent ID formats.
         */
        private val ID_PATTERN = Regex("^[A-Za-z0-9._:-]{1,$MAXIMUM_IDENTIFIER_CHARACTERS}$")
        private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$")

        /**
         * Builds an identity from account-service data.
         *
         * Callers that handle untrusted responses (an authenticator implementation) must treat a throw here as a
         * malformed response and surface [AccountAuthErrorCode.MALFORMED_RESPONSE] rather than crashing.
         */
        fun of(accountId: String, displayName: String, emailAddress: String? = null): AccountIdentity {
            require(ID_PATTERN.matches(accountId)) { "accountId must be an opaque bounded token" }
            val trimmedName = displayName.trim()
            require(trimmedName.isNotEmpty()) { "displayName must not be blank" }
            require(trimmedName.length <= MAXIMUM_DISPLAY_NAME_CHARACTERS) { "displayName is too long" }
            require(trimmedName.none { it.isISOControl() }) { "displayName must not contain control characters" }
            val normalizedEmail = emailAddress?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedEmail != null) {
                require(normalizedEmail.length <= MAXIMUM_EMAIL_CHARACTERS) { "emailAddress is too long" }
                require(EMAIL_PATTERN.matches(normalizedEmail)) { "emailAddress must look like an address" }
            }
            return AccountIdentity(accountId, trimmedName, normalizedEmail)
        }
    }
}

/**
 * Masks an email identifier for display: the first and last character of the local part stay, the middle is hidden
 * and the domain is kept so the user can still recognise the address.
 *
 * The masked form never contains the full local part, and a value that is not an address at all becomes `•••`.
 */
fun maskEmailAddress(emailAddress: String): String {
    val at = emailAddress.indexOf('@')
    if (at <= 0 || at == emailAddress.length - 1) return "•••"
    val local = emailAddress.substring(0, at)
    val domain = emailAddress.substring(at)
    val maskedLocal = when {
        local.length == 1 -> "${local.first()}•••"
        local.length == 2 -> "${local.first()}•••${local.last()}"
        else -> {
            val hidden = "•".repeat(minOf(local.length - 2, 6))
            "${local.first()}$hidden${local.last()}"
        }
    }
    return maskedLocal + domain
}
