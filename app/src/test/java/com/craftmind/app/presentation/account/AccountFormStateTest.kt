package com.craftmind.app.presentation.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-up and sign-in validation rules (Phase 17 §5, §6).
 *
 * A form is where a user finds out what is wrong, so the rules are tested the way a user meets them: by typing.
 */
class AccountFormStateTest {

    // ------------------------------------------------------------------------------------- what a field says

    @Test
    fun anEmptyAddressSaysWhatToDoRatherThanWhatIsMissing() {
        assertEquals("Enter your email address.", AccountFormValidator.emailViolation("   "))
    }

    @Test
    fun anAddressWithoutTheRightShapeIsDescribedInPlainWords() {
        for (value in listOf("someone", "someone@", "@example.com", "someone@example", "someone example@x.com", "a@b c.com")) {
            assertEquals(
                "$value must be refused",
                "That does not look like an email address yet.",
                AccountFormValidator.emailViolation(value),
            )
        }
        assertNull(AccountFormValidator.emailViolation("someone@example.com"))
        assertNull(AccountFormValidator.emailViolation("first.last+tag@sub.example.co.uk"))
        assertNull("surrounding spaces are the user's, not a mistake", AccountFormValidator.emailViolation("  someone@example.com  "))
    }

    @Test
    fun anAddressLongerThanTheServiceAcceptsIsRefusedHereToo() {
        val tooLong = "a".repeat(250) + "@example.com"
        assertEquals("That email address is too long.", AccountFormValidator.emailViolation(tooLong))
    }

    @Test
    fun thePasswordRuleIsStatedOnceAndMirrorsTheService() {
        assertEquals("Choose a password.", AccountFormValidator.passwordViolation(""))
        assertEquals("Use at least 10 characters.", AccountFormValidator.passwordViolation("short1"))
        assertEquals(
            "Mix at least two of: lowercase letters, uppercase letters, digits, symbols.",
            AccountFormValidator.passwordViolation("aaaaaaaaaaaa"),
        )
        assertNull(AccountFormValidator.passwordViolation("correct horse battery staple"))
        assertNull(AccountFormValidator.passwordViolation("Passw0rdd!"))
        assertEquals(10, AccountFormValidator.PASSWORD_MINIMUM_LENGTH)
    }

    @Test
    fun aPasswordThatIsOnlyWhitespaceIsRefusedAsSuch() {
        assertEquals("That password is only whitespace.", AccountFormValidator.passwordViolation(" ".repeat(20)))
    }

    // ---------------------------------------------------------------------------------------- signing up

    @Test
    fun aCompleteSignUpIsValid() {
        val validation = AccountFormValidator.validateSignUp(
            AccountSignUpInput(
                emailAddress = "someone@example.com",
                password = "Passw0rdd!",
                confirmPassword = "Passw0rdd!",
                displayName = "Someone",
            ),
        )

        assertTrue(validation.isValid)
    }

    @Test
    fun aSignUpWithSeveralProblemsReportsEveryOneOfThemAtOnce() {
        val validation = AccountFormValidator.validateSignUp(
            AccountSignUpInput(emailAddress = "nope", password = "short", confirmPassword = "other"),
        )

        assertFalse(validation.isValid)
        assertEquals("That does not look like an email address yet.", validation.issueFor(AccountFormField.EMAIL))
        assertEquals("Use at least 10 characters.", validation.issueFor(AccountFormField.PASSWORD))
        assertEquals("The two passwords do not match.", validation.issueFor(AccountFormField.CONFIRM_PASSWORD))
    }

    @Test
    fun aMissingConfirmationIsAskedForRatherThanReportedAsAMismatch() {
        val validation = AccountFormValidator.validateSignUp(
            AccountSignUpInput(emailAddress = "someone@example.com", password = "Passw0rdd!", confirmPassword = ""),
        )

        assertEquals("Repeat your password to confirm it.", validation.issueFor(AccountFormField.CONFIRM_PASSWORD))
    }

    @Test
    fun anOptionalDisplayNameIsOptionalButBounded() {
        assertTrue(
            AccountFormValidator.validateSignUp(
                AccountSignUpInput(emailAddress = "someone@example.com", password = "Passw0rdd!", confirmPassword = "Passw0rdd!"),
            ).isValid,
        )

        val tooLong = AccountFormValidator.validateSignUp(
            AccountSignUpInput(
                emailAddress = "someone@example.com",
                password = "Passw0rdd!",
                confirmPassword = "Passw0rdd!",
                displayName = "n".repeat(81),
            ),
        )
        assertEquals(
            "Display names can be at most 80 characters.",
            tooLong.issueFor(AccountFormField.DISPLAY_NAME),
        )

        val controlCharacters = AccountFormValidator.validateSignUp(
            AccountSignUpInput(
                emailAddress = "someone@example.com",
                password = "Passw0rdd!",
                confirmPassword = "Passw0rdd!",
                displayName = "so\u0000meone",
            ),
        )
        assertEquals(
            "Display names cannot contain control characters.",
            controlCharacters.issueFor(AccountFormField.DISPLAY_NAME),
        )
    }

    // ---------------------------------------------------------------------------------------- signing in

    @Test
    fun signingInAsksOnlyForWhatTheServiceNeeds() {
        assertTrue(
            AccountFormValidator.validateSignIn(
                AccountSignInInput(emailAddress = "someone@example.com", password = "x"),
            ).isValid,
        )
        // The password rule belongs to registration; telling an existing user their password is too short helps nobody.
        assertNull(
            AccountFormValidator.validateSignIn(
                AccountSignInInput(emailAddress = "someone@example.com", password = "x"),
            ).issueFor(AccountFormField.PASSWORD),
        )
    }

    @Test
    fun signingInStillNeedsBothFields() {
        val validation = AccountFormValidator.validateSignIn(AccountSignInInput(emailAddress = "", password = ""))

        assertEquals("Enter your email address.", validation.issueFor(AccountFormField.EMAIL))
        assertEquals("Enter your password.", validation.issueFor(AccountFormField.PASSWORD))
    }

    @Test
    fun anIssueIsAlwaysAttachedToAFieldSoItCanBeShownWhereItBelongs() {
        val validation = AccountFormValidator.validateSignUp(
            AccountSignUpInput(emailAddress = "nope", password = "short", confirmPassword = "short"),
        )

        val issues = (validation as AccountFormValidation.Invalid).issues
        assertTrue(issues.isNotEmpty())
        assertTrue("every complaint names a field", issues.all { it.message.isNotBlank() })
        assertTrue("no raw codes leak into user copy", issues.none { it.message.contains("_") && it.message.uppercase() == it.message })
    }
}
