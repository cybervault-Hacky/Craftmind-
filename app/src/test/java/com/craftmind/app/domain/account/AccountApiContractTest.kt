package com.craftmind.app.domain.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.function.ThrowingRunnable
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract the app and the service share (Phase 17).
 *
 * These are the details that would silently rot if only the happy path were tested: what a stored credential looks
 * like, what a rejected answer maps to, what a guest identity may contain, and what the client refuses to send.
 */
class AccountApiContractTest {

    // -------------------------------------------------------------------------------- stored session credentials

    @Test
    fun aStoredCredentialRoundTripsAndIsVersioned() {
        val tokens = AccountSessionTokens.of("access-value".toCharArray(), "refresh-value".toCharArray())

        val encoded = tokens.encodeForStorage()
        val restored = AccountSessionTokens.decodeFromStorage(encoded)

        assertNotNull("a stored credential must be readable again", restored)
        restored!!.useTokens { access, refresh ->
            assertEquals("access-value", String(access))
            assertEquals("refresh-value", String(refresh))
        }
        assertTrue("the format is versioned so it can change without guessing", String(encoded).startsWith("v1|"))
        tokens.close()
        restored.close()
    }

    @Test
    fun aCredentialThatIsNotInTheKnownFormatIsRejectedRatherThanGuessed() {
        assertNull(AccountSessionTokens.decodeFromStorage("garbage".toCharArray()))
        assertNull(AccountSessionTokens.decodeFromStorage("v2|a|b".toCharArray()))
        assertNull(AccountSessionTokens.decodeFromStorage("v1|only-one-field".toCharArray()))
        assertNull(AccountSessionTokens.decodeFromStorage("v1||".toCharArray()))
    }

    @Test
    fun closingACredentialDestroysItAndLaterReadsFailClosed() {
        val tokens = AccountSessionTokens.of("access-value".toCharArray(), "refresh-value".toCharArray())

        tokens.close()

        var failedClosed = false
        try {
            tokens.useTokens { _, _ -> }
        } catch (_: IllegalStateException) {
            failedClosed = true
        }
        assertTrue("a cleared credential must not be readable", failedClosed)
        assertTrue(tokens.toString().contains("REDACTED"))
        assertFalse(tokens.toString().contains("access-value"))
    }

    @Test
    fun noApiOutcomeEverPrintsItsValue() {
        val tokens = AccountSessionTokens.of("access-value".toCharArray(), "refresh-value".toCharArray())
        val session = AccountServerSession(
            tokens = tokens,
            accessExpiresAtEpochMillis = 5_000L,
            refreshExpiresAtEpochMillis = 10_000L,
        )

        val printed = listOf(
            session.toString(),
            AccountApiOutcome.Success(session).toString(),
            AccountApiOutcome.Success(tokens).toString(),
        ).joinToString(" ")

        assertFalse("a token must not be printable", printed.contains("access-value"))
        assertFalse(printed.contains("refresh-value"))
        tokens.close()
    }

    // -------------------------------------------------------------------------------------------- wire codes

    @Test
    fun everyServiceErrorCodeMapsToAKnownClientCode() {
        val wireValues = listOf(
            "INVALID_EMAIL",
            "INVALID_PASSWORD",
            "INVALID_DISPLAY_NAME",
            "INVALID_CREDENTIALS",
            "ACCOUNT_NOT_FOUND",
            "ACCOUNT_ALREADY_EXISTS",
            "ACCOUNT_SUSPENDED",
            "ACCOUNT_DELETED",
            "SESSION_EXPIRED",
            "SESSION_INVALID",
            "REFRESH_FAILED",
            "AUTHENTICATION_REQUIRED",
            "NETWORK_ERROR",
            "INVALID_GUEST_IDENTITY",
            "GUEST_IDENTITY_ALREADY_LINKED",
            "INVALID_REQUEST",
            "REQUEST_TOO_LARGE",
            "METHOD_NOT_ALLOWED",
            "PASSWORD_RESET_NOT_IMPLEMENTED",
            "BACKEND_UNAVAILABLE",
            "UNKNOWN_ERROR",
        )

        for (value in wireValues) {
            assertTrue("$value must map to a typed client code", AccountApiErrorCode.fromWireValue(value) != AccountApiErrorCode.UNKNOWN_ERROR || value == "UNKNOWN_ERROR")
        }
    }

    @Test
    fun anUnknownServerStatusIsRejectedRatherThanDefaultedToActive() {
        assertNull(AccountServerStatus.fromWireValue("PENDING"))
        assertNull(AccountServerStatus.fromWireValue(null))
        assertEquals(AccountServerStatus.ACTIVE, AccountServerStatus.fromWireValue("ACTIVE"))
    }

    @Test
    fun anUnrecognisedCodeDoesNotInventMeaning() {
        assertEquals(AccountApiErrorCode.UNKNOWN_ERROR, AccountApiErrorCode.fromWireValue("SOME_FUTURE_CODE"))
        assertEquals(AccountApiErrorCode.UNKNOWN_ERROR, AccountApiErrorCode.fromWireValue(null))
    }

    @Test
    fun networkAndServiceFailuresAreRetryableAndCredentialsAreNot() {
        assertTrue("a service that is down is worth trying again", AccountApiErrorCode.BACKEND_UNAVAILABLE.retryable)
        assertTrue(AccountApiErrorCode.UNKNOWN_ERROR.retryable)
        assertFalse("retrying a wrong password just burns attempts", AccountApiErrorCode.INVALID_CREDENTIALS.retryable)
        assertFalse(AccountApiErrorCode.INVALID_EMAIL.retryable)
        assertFalse(AccountApiErrorCode.ACCOUNT_SUSPENDED.retryable)
        assertFalse(AccountApiErrorCode.REFRESH_FAILED.retryable)
        assertTrue(AccountApiErrorCode.NETWORK_ERROR.retryable)
    }

    @Test
    fun aServerRecordBecomesAnIdentityWithoutCarryingASession() {
        val record = AccountServerRecord(
            userId = "cm-user-1",
            emailAddress = "someone@example.com",
            displayName = "Someone",
            status = AccountServerStatus.ACTIVE,
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 2_000L,
        )

        val identity = record.toIdentity()

        assertEquals("cm-user-1", identity.accountId)
        assertEquals("someone@example.com", identity.emailAddress)
        assertEquals("only the masked form may be rendered", "s•••••e@example.com", identity.maskedEmailAddress)
        assertTrue(record.isUsableForSignIn())
    }

    @Test
    fun aSuspendedRecordIsNotUsableForSignIn() {
        val record = AccountServerRecord(
            userId = "cm-user-1",
            emailAddress = "someone@example.com",
            displayName = "Someone",
            status = AccountServerStatus.SUSPENDED,
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 2_000L,
        )

        assertFalse(record.isUsableForSignIn())
    }

    // ---------------------------------------------------------------------------------------- guest identity

    @Test
    fun aGuestIdentityIsGeneratedFromRandomnessAndLooksLikeNothingElse() {
        val first = GuestIdentity.generate()
        val second = GuestIdentity.generate()

        assertFalse("two devices must not share an identity", first.value == second.value)
        assertTrue("the identity must match the contract the service validates", GuestIdentity.isWellFormed(first.value))
        assertTrue(first.value.length in 22..64)
        assertEquals("an identity is not a secret and says so", "GuestIdentity([OPAQUE])", first.toString())
    }

    @Test
    fun aGuestIdentityContainsNoIdentifierOfTheDeviceOrUser() {
        val identity = GuestIdentity.generate().value

        // base64url only: an opaque tag, with no packaging that could carry a device id, a model name, or a build id.
        assertTrue(identity.all { character -> character.isLetterOrDigit() || character == '-' || character == '_' })
        assertFalse(identity.contains("android"))
        assertFalse(identity.contains("sdk"))
        assertFalse(identity.contains("com.craftmind.app"))
    }

    @Test
    fun theGuestIdentitySurvivesRestartsAndIsWrittenOnce() {
        val store = CountingGuestIdentityStore()
        val first = GuestIdentityManager(store = store)

        val original = first.current()

        val afterRestart = GuestIdentityManager(store = store)
        assertEquals("a restart must not create a new identity", original, afterRestart.current())
        assertEquals("the identity is persisted exactly once", 1, store.writes)
    }

    @Test
    fun aStoredIdentityThatIsNotWellFormedIsReplacedRatherThanTrusted() {
        val store = CountingGuestIdentityStore(initial = "not a valid identity")
        val manager = GuestIdentityManager(store = store)

        val identity = manager.current()

        assertTrue(GuestIdentity.isWellFormed(identity.value))
        assertTrue(store.writes >= 1)
    }

    @Test
    fun aStoreThatCannotBeWrittenStillYieldsAnIdentityForThisRun() {
        val store = CountingGuestIdentityStore(failWrites = true)
        val manager = GuestIdentityManager(store = store)

        val identity = manager.current()

        assertTrue("a device without writable storage is still a guest", GuestIdentity.isWellFormed(identity.value))
    }

    // ---------------------------------------------------------------------------------- service configuration

    @Test
    fun anAccountServiceAddressMustBeAbsoluteHttps() {
        assertNotNull(AccountServiceConfiguration.of("https://accounts.craftmind.example"))
        assertThrows(IllegalArgumentException::class.java, ThrowingRunnable {
            AccountServiceConfiguration.of("http://accounts.craftmind.example")
        })
        assertThrows(IllegalArgumentException::class.java, ThrowingRunnable {
            AccountServiceConfiguration.of("accounts.craftmind.example")
        })
        assertThrows(IllegalArgumentException::class.java, ThrowingRunnable {
            AccountServiceConfiguration.of("ftp://accounts.craftmind.example")
        })
    }

    @Test
    fun anAccountServiceAddressMustNotCarryAPathQueryOrFragment() {
        assertThrows(IllegalArgumentException::class.java, ThrowingRunnable {
            AccountServiceConfiguration.of("https://accounts.craftmind.example/v1")
        })
        assertThrows(IllegalArgumentException::class.java, ThrowingRunnable {
            AccountServiceConfiguration.of("https://accounts.craftmind.example?next=1")
        })
        assertThrows(IllegalArgumentException::class.java, ThrowingRunnable {
            AccountServiceConfiguration.of("https://accounts.craftmind.example#fragment")
        })
        // A trailing slash is the one normalisation that is accepted, because it means nothing.
        assertEquals(
            "https://accounts.craftmind.example",
            AccountServiceConfiguration.of("https://accounts.craftmind.example/").baseUrl,
        )
    }

    @Test
    fun aBlankAddressIsALegitimateBuildWithoutAServiceNotAnError() {
        val none = AccountServiceConfiguration.fromBuildValue("")
        assertFalse("a build without a service is a valid build", none.isConfigured)
        assertNull(none.baseUrl)
        assertFalse(AccountServiceConfiguration.fromBuildValue(null).isConfigured)
        assertFalse("whitespace is not an address", AccountServiceConfiguration.fromBuildValue("   ").isConfigured)

        val configured = AccountServiceConfiguration.fromBuildValue("https://accounts.craftmind.example")
        assertTrue(configured.isConfigured)
        assertEquals(
            "https://accounts.craftmind.example/auth/me",
            configured.endpointUrl("/auth/me"),
        )
        assertFalse(
            "a configured address is never printed where a user could mistake it for an identity",
            configured.toString().contains("accounts.craftmind.example"),
        )
    }

    @Test
    fun askingForAnEndpointWithoutAServiceFailsLoudlyInsteadOfDereferencingNothing() {
        val none = AccountServiceConfiguration.fromBuildValue("")

        assertThrows(IllegalArgumentException::class.java, ThrowingRunnable { none.endpointUrl("/auth/me") })
    }

    // ------------------------------------------------------------------------------------- request payloads

    @Test
    fun aRequestPayloadEscapesEverythingThatCouldBreakTheJson() {
        val body = AccountApiRequests.register(
            emailAddress = "someone@example.com",
            password = "a\"b\\c\nd\u0000e".toCharArray(),
            displayName = "Na\"me\\with\nnewline",
            guestIdentityId = "identity-1",
        )

        assertTrue(body.startsWith("{"))
        assertTrue(body.endsWith("}"))
        assertTrue("quotes and backslashes must be escaped", body.contains("\\\""))
        assertTrue(body.contains("\\\\"))
        assertTrue(body.contains("\\n"))
        assertFalse("control characters must never reach the wire raw", body.contains('\u0000'))
        assertTrue("no field is skipped", body.contains("\"password\":\""))
        assertTrue("the required display name is sent", body.contains("\"displayName\":\""))
    }

    @Test
    fun aSignInPayloadOmitsNothingTheServiceNeedsAndIncludesTheGuestIdentityWhenThereIsOne() {
        val withGuest = AccountApiRequests.login("someone@example.com", "secret".toCharArray(), "identity-1")
        val withoutGuest = AccountApiRequests.login("someone@example.com", "secret".toCharArray(), null)

        assertTrue(withGuest.contains("\"guestIdentityId\":\"identity-1\""))
        assertFalse(
            "a field with nothing to say is omitted rather than sent as null",
            withoutGuest.contains("guestIdentityId"),
        )
        assertTrue(withGuest.contains("\"email\":\"someone@example.com\""))
        assertTrue(withGuest.contains("\"password\":\"secret\""))
    }

}

/** In-memory guest identity store, with the write count a test needs to prove "written once". */
private class CountingGuestIdentityStore(
    private val initial: String? = null,
    private val failWrites: Boolean = false,
) : GuestIdentityStore {
    private var value: String? = initial
    var writes: Int = 0
        private set

    override fun read(): String? = value

    override fun write(value: String) {
        if (failWrites) throw java.io.IOException("read-only storage")
        this.value = value
        writes++
    }
}
