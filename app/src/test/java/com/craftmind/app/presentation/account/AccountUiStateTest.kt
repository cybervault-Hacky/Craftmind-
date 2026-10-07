package com.craftmind.app.presentation.account

import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.domain.account.AccountAuthErrorCode
import com.craftmind.app.domain.account.AccountAvailabilityReason
import com.craftmind.app.domain.account.AccountDeletionOutcome
import com.craftmind.app.domain.account.AccountSession
import com.craftmind.app.domain.account.AccountSessionSource
import com.craftmind.app.domain.account.AccountState
import com.craftmind.app.domain.account.AccountAuthenticationAvailability
import com.craftmind.app.domain.account.AccountSignInMethod
import com.craftmind.app.domain.account.AccountSessionEndReason
import com.craftmind.app.domain.account.CraftMindDataDomain
import com.craftmind.app.domain.account.maskEmailAddress
import com.craftmind.app.domain.account.testIdentity
import com.craftmind.app.domain.account.testSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 §8: the account surfaces are a pure projection of account state.
 *
 * What is asserted here is mostly the honesty of the copy: local mode is complete, "unavailable" is never dressed up as
 * a credential problem, nothing promises a cloud feature that does not exist, and no secret, key, or internal account
 * identifier is ever rendered.
 */
class AccountUiStateTest {
    private val unavailable = AccountAuthenticationAvailability.Unavailable(
        AccountAvailabilityReason.NO_BACKEND_CONFIGURED,
    )
    private val nowMillis = 1_500L

    private fun state(
        accountState: AccountState,
        availability: AccountAuthenticationAvailability = unavailable,
        ownershipRecorded: Boolean = false,
        deletionOutcome: AccountDeletionOutcome? = null,
    ): AccountUiState = accountUiState(
        state = accountState,
        availability = availability,
        nowMillis = nowMillis,
        ownershipRecorded = ownershipRecorded,
        deletionOutcome = deletionOutcome,
    )

    @Test
    fun theShippedBuildDescribesLocalModeAsACompleteStateAndOffersNoDeadSignIn() {
        val ui = state(AccountState.Guest)

        assertEquals("Local mode", ui.modeLabel)
        assertEquals(CraftMindTone.NEUTRAL, ui.tone)
        assertNull("a build without a service must not offer a sign-in it cannot perform", ui.primaryAction)
        assertNotNull("an action that is not offered still needs its reason", ui.actionUnavailableReason)
        assertFalse(ui.showsSignInForm)
        assertTrue(ui.detail.contains("without an account"))
    }

    @Test
    fun authenticationBeingUnavailableIsNotStatedAsAWrongPassword() {
        val unavailableCopy = state(AccountState.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED, null))
        val rejected = state(
            AccountState.Failed(AccountAuthErrorCode.INVALID_CREDENTIALS, null),
            availability = AccountAuthenticationAvailability.Available,
        )

        assertNotEquals(unavailableCopy.headline, rejected.headline)
        assertTrue(unavailableCopy.detail.contains("not available") || unavailableCopy.detail.contains("no account"))
        assertFalse(
            "an unavailable service must never be blamed on the user's credentials",
            unavailableCopy.detail.contains("credential") || unavailableCopy.detail.contains("password"),
        )
        assertEquals(CraftMindTone.INFORMATIVE, unavailableCopy.tone)
        assertEquals(CraftMindTone.NEGATIVE, rejected.tone)
    }

    @Test
    fun everyTypedErrorCodeHasCopyAndNeverNamesASecret() {
        val session = testSession()
        for (code in AccountAuthErrorCode.entries) {
            val ui = state(AccountState.Failed(code, session.identity))

            assertTrue("$code has no headline", ui.headline.isNotBlank())
            assertTrue("$code has no detail", ui.detail.isNotBlank())
            assertTrue("$code has no accessibility label", ui.accessibilityLabel.isNotBlank())
            for (secretWord in listOf("token", "bearer", "api key", "apikey", "private key", "client secret")) {
                assertFalse(
                    "$code leaks the word '$secretWord' into user-visible copy",
                    ui.detail.lowercase().contains(secretWord) || ui.headline.lowercase().contains(secretWord),
                )
            }
        }
    }

    @Test
    fun aRetryableFailureOffersARetryAndANonRetryableOneDoesNotPretendTo() {
        val retryable = state(
            AccountState.Failed(AccountAuthErrorCode.NETWORK_UNAVAILABLE, null),
            availability = AccountAuthenticationAvailability.Available,
        )
        val permanent = state(
            AccountState.Failed(AccountAuthErrorCode.INVALID_CREDENTIALS, null),
            availability = AccountAuthenticationAvailability.Available,
        )

        assertEquals(AccountAction.TryAgain, retryable.primaryAction)
        assertEquals(AccountAction.SignIn, permanent.primaryAction)
        assertTrue(retryable.showsSignInForm)
    }

    @Test
    fun aSignedInIdentityIsShownMaskedAndNeverByItsInternalIdentifier() {
        val identity = testIdentity(accountId = "cm-account-9f3a", emailAddress = "builder@example.com")
        val ui = state(AccountState.Authenticated(testSession(identity = identity)))

        assertEquals("Build Engineer", ui.identityName)
        assertEquals(maskEmailAddress("builder@example.com"), ui.identityReference)
        assertNotEquals(
            "the rendered identifier must be masked, not the stored address",
            "builder@example.com",
            ui.identityReference,
        )
        assertFalse(
            "the internal account id must never be rendered",
            ui.identityReference!!.contains(identity.accountId) ||
                ui.headline.contains(identity.accountId) ||
                ui.accessibilityLabel.contains(identity.accountId),
        )
        assertEquals(AccountAction.SignOut, ui.primaryAction)
    }

    @Test
    fun aSignedInScreenStillStatesThatLocalDataIsLocal() {
        val ui = state(AccountState.Authenticated(testSession()))

        assertTrue(ui.localDataLine.contains("Signing out only clears the account session"))
        assertTrue(ui.securityLine.contains("never uploads an API key"))
        assertTrue(ui.detail.contains("local features still work"))
    }

    @Test
    fun aRestoredSessionSaysItWasNotRecheckedInsteadOfImplyingALiveCheck() {
        val ui = state(
            AccountState.Authenticated(testSession(source = AccountSessionSource.RESTORED_ON_DEVICE)),
        )

        assertTrue(ui.sessionLine!!.contains("Restored from this device"))
        assertTrue(ui.sessionLine!!.contains("not re-checked"))
        assertFalse(ui.sessionLine!!.contains("Verified with the account service"))
    }

    @Test
    fun aLiveSessionSaysItWasVerifiedInThisRun() {
        val ui = state(AccountState.Authenticated(testSession(source = AccountSessionSource.LIVE_SIGN_IN)))

        assertTrue(ui.sessionLine!!.contains("Verified with the account service in this app run"))
    }

    @Test
    fun sessionWordingIsDeterministicAgainstTheSuppliedClock() {
        val ui = state(
            AccountState.Authenticated(
                testSession(issuedAtEpochMillis = 0L, expiresAtEpochMillis = 1_500L + 1_800_000L),
            ),
        )

        assertTrue("wording is computed from the injected clock", ui.sessionLine!!.contains("30 minute(s)"))
    }

    @Test
    fun aSessionWithoutADeclaredExpiryIsNotGivenAnInventedOne() {
        val ui = state(AccountState.Authenticated(testSession(expiresAtEpochMillis = null)))

        assertTrue(ui.sessionLine!!.contains("declared no expiry"))
    }

    @Test
    fun everyEndReasonExplainsItselfWithoutBlamingTheUser() {
        for (reason in AccountSessionEndReason.entries) {
            val ui = state(AccountState.SessionExpired(testIdentity(), reason))

            assertEquals("Session ended", ui.modeLabel)
            assertEquals(CraftMindTone.CAUTION, ui.tone)
            assertTrue("$reason has no explanation", ui.detail.isNotBlank())
            assertTrue("$reason has no accessibility label", ui.accessibilityLabel.isNotBlank())
            assertTrue(
                "a session ending must never sound like the user's fault",
                !ui.detail.contains("you failed") && !ui.detail.contains("incorrect"),
            )
        }
    }

    @Test
    fun inFlightStatesOfferNoActionAndClaimNoProgress() {
        val signingIn = state(AccountState.SigningIn(AccountSignInMethod.EMAIL_PASSWORD, null))
        val signingOut = state(AccountState.SigningOut(testIdentity()))

        assertNull(signingIn.primaryAction)
        assertNull(signingOut.primaryAction)
        for (ui in listOf(signingIn, signingOut)) {
            assertFalse("no fake progress may be shown", ui.detail.contains("%"))
        }
    }

    @Test
    fun theDeletionRequestStatesAreDistinctFromSigningOutAndFromLocalDeletion() {
        val requested = deletionOutcomeLine(AccountDeletionOutcome.Requested("req-42"))
        val unavailableLine = deletionOutcomeLine(
            AccountDeletionOutcome.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED),
        )
        val failed = deletionOutcomeLine(AccountDeletionOutcome.Failure(AccountAuthErrorCode.SERVICE_UNAVAILABLE))

        assertTrue(requested.contains("req-42"))
        assertTrue(requested.contains("Local CraftMind data on this device is not deleted"))
        assertTrue(unavailableLine.contains("No deletion request could be made"))
        assertTrue(unavailableLine.contains("Nothing was deleted"))
        assertTrue(failed.contains("did not complete"))
        assertTrue(failed.contains("session was left untouched"))
    }

    @Test
    fun aDeletionOutcomeAppearsInTheProjectedStateWithoutChangingTheSession() {
        val ui = state(
            AccountState.Authenticated(testSession()),
            deletionOutcome = AccountDeletionOutcome.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED),
        )

        assertNotNull(ui.deletionLine)
        assertEquals(AccountAction.SignOut, ui.primaryAction)
    }

    @Test
    fun theSettingsSummaryIsHonestForTheDefaultCaseAndNeverBlank() {
        val summary = defaultAccountSummary()

        assertEquals("Local mode", summary.badgeLabel)
        assertEquals(CraftMindTone.NEUTRAL, summary.badgeTone)
        assertTrue(summary.detail.isNotBlank())
        assertNull("the default summary must not offer a sign-in that cannot work", summary.primaryAction)
        assertNotNull(summary.actionUnavailableReason)
    }

    @Test
    fun theSettingsSummaryMirrorsTheScreenInsteadOfInventingASecondStory() {
        val ui = state(AccountState.Authenticated(testSession()))
        val summary = accountSettingsSummary(ui)

        assertEquals(ui.modeLabel, summary.badgeLabel)
        assertEquals(ui.identityName, summary.identityName)
        assertEquals(ui.primaryAction, summary.primaryAction)
        assertTrue(summary.detail.isNotBlank())
    }

    @Test
    fun theDataSectionListsOwnershipForEveryDomainAndStaysFreeOfCloudPromises() {
        val lines = accountDataOwnershipLines()

        assertEquals(CraftMindDataDomain.entries.size, lines.size)
        for (domain in CraftMindDataDomain.entries) {
            assertTrue("${domain.name} is missing from the data section", lines.any { it.contains(domain.storedBy) })
        }
        for (line in lines) {
            assertFalse(line.lowercase().contains("synced"))
            assertFalse(line.lowercase().contains("in the cloud"))
        }
    }

    @Test
    fun theShortDataCardMentionsBuildsKeysAndPairing() {
        val lines = accountLocalDataLines().joinToString(" ")

        assertTrue(lines.contains("build"))
        assertTrue(lines.contains("API keys"))
        assertTrue("the Minecraft pairing boundary must be stated", lines.contains("bridge"))
    }

    @Test
    fun everyStateCarriesAScreenReaderDescriptionAndNeverReliesOnColourAlone() {
        val states = listOf(
            AccountState.Guest,
            AccountState.SigningIn(AccountSignInMethod.EMAIL_PASSWORD, null),
            AccountState.Authenticated(testSession()),
            AccountState.SigningOut(null),
            AccountState.SessionExpired(testIdentity(), AccountSessionEndReason.EXPIRED),
            AccountState.Unavailable(AccountAvailabilityReason.SERVICE_UNREACHABLE, null),
            AccountState.Failed(AccountAuthErrorCode.MALFORMED_RESPONSE, null),
        )

        for (accountState in states) {
            val ui = state(accountState)
            assertTrue("$accountState has no mode label", ui.modeLabel.isNotBlank())
            assertTrue(
                "$accountState needs a spoken status, not just a colour",
                ui.accessibilityLabel.length >= 20 && ui.accessibilityLabel.startsWith("Account status:"),
            )
        }
    }

    @Test
    fun everyUnavailableReasonExplainsItself() {
        for (reason in AccountAvailabilityReason.entries) {
            val ui = state(AccountState.Guest, availability = AccountAuthenticationAvailability.Unavailable(reason))

            assertNotNull("$reason must be explained", ui.availabilityLine)
            assertNotNull("$reason must say why the action is unavailable", ui.actionUnavailableReason)
            assertTrue(ui.availabilityLine!!.isNotBlank())
        }
    }

    @Test
    fun anAvailableServiceIsTheOnlyCaseThatOffersSignIn() {
        val ui = state(AccountState.Guest, availability = AccountAuthenticationAvailability.Available)

        assertEquals(AccountAction.SignIn, ui.primaryAction)
        assertTrue(ui.showsSignInForm)
        assertNull(ui.availabilityLine)
        assertNull(ui.actionUnavailableReason)
    }

    @Test
    fun ownershipRecordedIsReportedWithoutClaimingAnythingElse() {
        val recorded = state(AccountState.Guest, ownershipRecorded = true)

        assertTrue(recorded.ownershipRecorded)
        assertFalse(recorded.localDataLine.contains("ownership marker"))
    }

    @Test
    fun noProjectedFieldCanHoldASessionSecret() {
        val secretish = AccountUiState::class.java.declaredFields
            .map { it.name.lowercase() }
            .filter { name ->
                listOf("token", "secret", "password", "apikey", "authorization", "cookie").any(name::contains)
            }

        assertEquals("the projection must have no field capable of carrying a credential", emptyList<String>(), secretish)
    }

    @Test
    fun aSessionObjectIsNotReachableFromTheProjectedState() {
        val stringFields = AccountUiState::class.java.declaredFields
            .filter { it.type == String::class.java || it.type == AccountSession::class.java }
            .map { it.type.simpleName }

        assertFalse("no raw session may be projected", stringFields.contains("AccountSession"))
    }
}
