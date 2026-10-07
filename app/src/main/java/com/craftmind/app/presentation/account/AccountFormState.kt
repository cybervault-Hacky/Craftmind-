package com.craftmind.app.presentation.account

/**
 * Client-side validation for the account forms (Phase 17 §5, §6).
 *
 * Plain Kotlin, no Compose and no Android, so the rules are tested directly. The rules mirror the account service's own
 * checks **exactly** — the same password shape, the same address shape — because a client that accepts what the service
 * refuses produces a confusing round trip, and a client that refuses what the service accepts produces a support burden.
 * The service always re-validates; this exists to give an answer before a request is made, not instead of one.
 */
enum class AccountFormField {
    DISPLAY_NAME,
    EMAIL,
    PASSWORD,
    CONFIRM_PASSWORD,
}

/** A single validation complaint, attached to the field it belongs to. */
data class AccountFormIssue(val field: AccountFormField, val message: String)

/** Everything a sign-in attempt needs, as typed by the user. */
data class AccountSignInInput(
    val emailAddress: String,
    val password: String,
)

/** Everything a sign-up attempt needs, as typed by the user. */
data class AccountSignUpInput(
    val emailAddress: String,
    val password: String,
    val confirmPassword: String,
    val displayName: String = "",
)

sealed interface AccountFormValidation {
    data object Valid : AccountFormValidation

    /** One or more fields are unusable. Every complaint is returned, so the whole form can be corrected at once. */
    data class Invalid(val issues: List<AccountFormIssue>) : AccountFormValidation

    val isValid: Boolean get() = this is Valid

    /** The first complaint for a field, or null when that field is fine. */
    fun issueFor(field: AccountFormField): String? =
        (this as? Invalid)?.issues?.firstOrNull { it.field == field }?.message
}

object AccountFormValidator {
    /** Identical to the service's rule: 10 characters minimum, at least two character classes. */
    const val PASSWORD_MINIMUM_LENGTH = 10
    const val PASSWORD_MAXIMUM_LENGTH = 256

    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$")

    fun validateSignIn(input: AccountSignInInput): AccountFormValidation {
        val issues = buildList {
            emailViolation(input.emailAddress)?.let { add(AccountFormIssue(AccountFormField.EMAIL, it)) }
            if (input.password.isEmpty()) {
                add(AccountFormIssue(AccountFormField.PASSWORD, "Enter your password."))
            }
        }
        return if (issues.isEmpty()) AccountFormValidation.Valid else AccountFormValidation.Invalid(issues)
    }

    fun validateSignUp(input: AccountSignUpInput): AccountFormValidation {
        val issues = buildList {
            emailViolation(input.emailAddress)?.let { add(AccountFormIssue(AccountFormField.EMAIL, it)) }
            passwordViolation(input.password)?.let { add(AccountFormIssue(AccountFormField.PASSWORD, it)) }
            when {
                input.confirmPassword.isEmpty() ->
                    add(AccountFormIssue(AccountFormField.CONFIRM_PASSWORD, "Repeat your password to confirm it."))

                input.confirmPassword != input.password ->
                    add(AccountFormIssue(AccountFormField.CONFIRM_PASSWORD, "The two passwords do not match."))

                else -> Unit
            }
            if (input.displayName.length > MAXIMUM_DISPLAY_NAME_LENGTH) {
                add(
                    AccountFormIssue(
                        AccountFormField.DISPLAY_NAME,
                        "Display names can be at most $MAXIMUM_DISPLAY_NAME_LENGTH characters.",
                    ),
                )
            }
            if (input.displayName.any { it < ' ' || it == '\u007f' }) {
                add(AccountFormIssue(AccountFormField.DISPLAY_NAME, "Display names cannot contain control characters."))
            }
        }
        return if (issues.isEmpty()) AccountFormValidation.Valid else AccountFormValidation.Invalid(issues)
    }

    /** The address rule, stated in the user's terms. Null when the address is usable. */
    fun emailViolation(emailAddress: String): String? {
        val trimmed = emailAddress.trim()
        return when {
            trimmed.isEmpty() -> "Enter your email address."
            trimmed.length > MAXIMUM_EMAIL_LENGTH -> "That email address is too long."
            !EMAIL_PATTERN.matches(trimmed) -> "That does not look like an email address yet."
            else -> null
        }
    }

    /** The password rule, stated in the user's terms. Null when the password is usable. */
    fun passwordViolation(password: String): String? {
        val classes = listOf(
            password.any { it.isLowerCase() },
            password.any { it.isUpperCase() },
            password.any { it.isDigit() },
            password.any { !it.isLetterOrDigit() },
        ).count { it }
        return when {
            password.isEmpty() -> "Choose a password."
            password.length < PASSWORD_MINIMUM_LENGTH ->
                "Use at least $PASSWORD_MINIMUM_LENGTH characters."

            password.length > PASSWORD_MAXIMUM_LENGTH -> "That password is too long."
            password.isBlank() -> "That password is only whitespace."
            classes < 2 -> "Mix at least two of: lowercase letters, uppercase letters, digits, symbols."
            else -> null
        }
    }

    const val MAXIMUM_EMAIL_LENGTH = 254
    const val MAXIMUM_DISPLAY_NAME_LENGTH = 80
}
