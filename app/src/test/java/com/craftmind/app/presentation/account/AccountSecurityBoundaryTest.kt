package com.craftmind.app.presentation.account

import com.craftmind.app.domain.account.AccountAuthErrorCode
import com.craftmind.app.domain.account.AccountIdentity
import com.craftmind.app.domain.account.AccountSessionCredential
import com.craftmind.app.domain.account.AccountSignInRequest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 §9 and §10: the security boundary of the account feature, checked against the sources and against the types
 * themselves.
 *
 * The account layer must never become a second, informal path to secrets: no logging of credentials, no plaintext
 * storage, no reuse of the provider-credential store, no API key attached to an account, no account data inside a
 * BuildPlan or a Minecraft bridge payload, and no invented backend or endpoint anywhere.
 */
class AccountSecurityBoundaryTest {
    private val sourceRoot = File("src/main/java")
    private val appRoot = File(sourceRoot, "com/craftmind/app")

    private val accountSources: List<File> by lazy {
        listOf("domain/account", "data/account", "presentation/account")
            .map { File(appRoot, it) }
            .flatMap { directory ->
                directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
    }

    private fun accountSourceText(): String = accountSources.joinToString("\n") { it.readText() }

    private fun sourceText(relativePath: String): String = File(appRoot, relativePath).readText()

    @Test
    fun theAccountSourcesExistWhereTheArchitectureSaysTheyDo() {
        assertTrue("expected the app source root at ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        assertTrue("the account domain must exist", accountSources.size >= 10)
        assertTrue(accountSources.any { it.path.contains("/domain/account/") })
        assertTrue(accountSources.any { it.path.contains("/data/account/") })
        assertTrue(accountSources.any { it.path.contains("/presentation/account/") })
    }

    @Test
    fun nothingInTheAccountLayerWritesCredentialsToALog() {
        val forbidden = listOf(
            Regex("""android\.util\.Log"""),
            Regex("""\bLog\.[dview]\s*\("""),
            Regex("""\bprintln\s*\("""),
            Regex("""System\.out"""),
            Regex("""System\.err"""),
        )
        for (file in accountSources) {
            val text = file.readText()
            for (pattern in forbidden) {
                assertFalse(
                    "${file.path} must not log anything: credentials or session material could leak",
                    pattern.containsMatchIn(text),
                )
            }
        }
    }

    @Test
    fun signInCanReturnToGuestModeWithoutCreatingAnAccount() {
        val forms = sourceText("presentation/account/AccountForms.kt")

        assertTrue(forms.contains("text = \"Continue as Guest\""))
        assertTrue(forms.contains("onEvent(AccountUiEvent.FormDismissed)"))
        assertFalse("guest continuation must not submit sign-in credentials", forms.contains("AccountUiEvent.SignInRequested(guest"))
    }

    @Test
    fun noExceptionMessageFromTheAccountLayerIsEverSurfaced() {
        val text = sourceText("domain/account/AccountSessionManager.kt")

        assertTrue("the state machine must discard exception detail deliberately", text.contains("catch (_: Exception)"))
        assertFalse("an exception message must never become state or copy", text.contains("message}"))
        assertFalse(text.contains("localizedMessage"))
    }

    @Test
    fun sessionAndSignInSecretsRedactThemselvesAndFormSecretsAreCleared() {
        val credential = AccountSessionCredential.fromCharacters("sup3r-s3cret-session".toCharArray())
        val request = AccountSignInRequest.emailPassword("builder@example.com", "hunter2-please".toCharArray())
        val viewModel = sourceText("presentation/account/AccountViewModel.kt")
        val confirmationClear = viewModel.indexOf("event.confirmPassword.fill")
        val signUpValidation = viewModel.indexOf("AccountFormValidator.validateSignUp")

        assertTrue(
            "sign-up confirmation is temporary and must be erased before validation",
            confirmationClear >= 0 && confirmationClear < signUpValidation,
        )
        assertTrue("a locally rejected sign-in must erase its defensive request copy", viewModel.contains("request.close()"))

        try {
            for (rendered in listOf(credential.toString(), request.toString())) {
                assertFalse(rendered.contains("sup3r-s3cret-session"))
                assertFalse(rendered.contains("hunter2-please"))
                assertTrue(rendered.contains("[REDACTED]"))
            }
            assertTrue("an identifier may only be shown masked", request.toString().contains("•••"))
        } finally {
            credential.close()
            request.close()
        }
    }

    @Test
    fun signInValidatesThePasswordBeforeCreatingTheRequestAndWipingTheEventArray() {
        val viewModel = sourceText("presentation/account/AccountViewModel.kt")
        val start = viewModel.indexOf("private fun submitSignIn(")
        val end = viewModel.indexOf("\n    private fun background(", start)
        assertTrue("the sign-in handler must be present", start >= 0 && end > start)
        val handler = viewModel.substring(start, end)
        val validation = handler.indexOf("AccountFormValidator.validateSignIn")
        val request = handler.indexOf("AccountSignInRequest.emailPassword")
        val wipe = handler.indexOf("password.fill")

        assertTrue("the password must be validated while its characters are intact", validation >= 0 && validation < request)
        assertTrue("the event array is wiped only after the typed request owns a defensive copy", request < wipe)
        assertTrue("wiping must run from finally even on local validation failure", handler.contains("finally"))
    }

    @Test
    fun aClearedSecretCannotBeReadAgain() {
        val credential = AccountSessionCredential.fromCharacters("short-lived".toCharArray())
        credential.close()

        val readAfterClose = runCatching { credential.useSecret { it.size } }

        assertTrue("a cleared credential must be unusable", readAfterClose.isFailure)
    }

    @Test
    fun anIdentityNeverRendersItsRawAccountIdentifier() {
        val identity = AccountIdentity.of("cm-account-0001", "Build Engineer", "builder@example.com")

        assertFalse(identity.toString().contains("cm-account-0001"))
        assertTrue(identity.toString().contains("•••"))
    }

    @Test
    fun theAccountSessionStoreIsItsOwnKeystoreNamespaceAndNotTheProviderCredentialStore() {
        val accountStore = sourceText("data/account/AndroidKeystoreAccountSessionStore.kt")
        val providerStore = sourceText("data/security/AndroidKeystoreCredentialStore.kt")

        val accountAlias = Regex("""KEY_ALIAS\s*=\s*"([^"]+)"""").find(accountStore)?.groupValues?.get(1)
        val providerAlias = Regex("""KEY_ALIAS\s*=\s*"([^"]+)"""").find(providerStore)?.groupValues?.get(1)
        val accountDirectory = Regex("""DIRECTORY_NAME\s*=\s*"([^"]+)"""").find(accountStore)?.groupValues?.get(1)
        val providerDirectory = Regex("""DIRECTORY_NAME\s*=\s*"([^"]+)"""").find(providerStore)?.groupValues?.get(1)

        assertTrue("the account store must declare its own Keystore alias", accountAlias != null)
        assertTrue("the account store must declare its own directory", accountDirectory != null)
        assertFalse("an account session must not share the provider key alias", accountAlias == providerAlias)
        assertFalse("an account session must not share the provider key directory", accountDirectory == providerDirectory)
        assertTrue(
            "the account session store must be Keystore-backed",
            accountStore.contains("AndroidKeyStore") && accountStore.contains("AES/GCM/NoPadding"),
        )
        assertFalse(
            "the account layer must not depend on the provider credential store",
            accountSources
                .filterNot { it.name == "CraftMindDataOwnership.kt" }
                .joinToString(" ") { it.readText() }
                .contains("AndroidKeystoreCredentialStore"),
        )
        assertFalse(
            "no account file may import the provider credential layer",
            accountSources.any { file ->
                val text = file.readText()
                text.contains("import com.craftmind.app.domain.security") ||
                    text.contains("import com.craftmind.app.data.security")
            },
        )
    }

    @Test
    fun sessionMaterialIsStoredInTheNoBackupDirectorySoItCannotBeRestoredElsewhere() {
        val accountStore = sourceText("data/account/AndroidKeystoreAccountSessionStore.kt")

        assertTrue(accountStore.contains("noBackupFilesDir"))
        assertFalse(
            "session material must never be written to preferences",
            accountStore.contains("SharedPreferences") || accountStore.contains("getSharedPreferences"),
        )
    }

    @Test
    fun backupStaysDisabledSoASessionCannotBePulledOffTheDeviceByABackupAgent() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("""android:allowBackup="false""""))
    }

    @Test
    fun byokKeysAreNotReachableFromTheAccountLayer() {
        // The ownership registry names the provider key store on purpose: it is the written boundary, not a dependency.
        val text = accountSources
            .filterNot { it.name == "CraftMindDataOwnership.kt" }
            .joinToString("\n") { it.readText() }
        for (forbidden in listOf("CredentialStore", "ProviderCredential", "provider_credentials", "apiKey", "api_key")) {
            assertFalse(
                "the account layer must not touch AI provider credentials ($forbidden)",
                text.contains(forbidden),
            )
        }
    }

    @Test
    fun noAccountDataLeaksIntoBuildPlansOrMinecraftBridgePayloads() {
        val buildPlanAndBridge = listOf(
            "domain/buildplan",
            "domain/minecraft",
            "bridge-protocol",
        ).map { File(appRoot, it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }

        assertTrue("expected the build-plan and Minecraft sources to be scanned", buildPlanAndBridge.isNotEmpty())
        for (file in buildPlanAndBridge) {
            val text = file.readText()
            for (forbidden in listOf("AccountSession", "AccountIdentity", "accountId", "domain.account")) {
                assertFalse(
                    "${file.path} must stay free of account identity: a build plan and a bridge payload are not " +
                        "account surfaces ($forbidden)",
                    text.contains(forbidden),
                )
            }
        }
    }

    @Test
    fun accountIdentityIsNeverConfusedWithAMinecraftOrAiProviderAccount() {
        val text = accountSourceText()

        assertTrue("the boundary must be stated in the code", text.contains("never gives anyone control"))
        for (forbidden in listOf("MinecraftAccount", "aiProviderAccount", "providerAccountId")) {
            assertFalse(text.contains(forbidden))
        }
    }

    @Test
    fun noBackendIsInventedAndNoEndpointOrCredentialIsHardcoded() {
        val text = accountSourceText()

        for (forbidden in listOf(
            "http://",
            "https://",
            "firebase",
            "supabase",
            "auth0",
            "okta",
            "clerk",
            "google-services",
            "client_id",
            "clientId",
            "API_KEY",
        )) {
            assertFalse("the account layer must not invent a backend ($forbidden)", text.contains(forbidden))
        }
        // Phase 17 added a real client, which legitimately sends the bearer scheme — but never a token literal.
        assertFalse(
            "no authorization header value may be hardcoded",
            Regex("""Bearer [A-Za-z0-9._~+/-]{16,}""").containsMatchIn(text),
        )
        assertTrue(
            "the service address must come from the build, not from the source",
            text.contains("AccountServiceConfiguration") && sourceText("AppContainer.kt").contains("CRAFTMIND_ACCOUNT_BASE_URL"),
        )
        assertTrue(
            "the shipped build must state that no account service exists",
            sourceText("domain/account/NoBackendAccountAuthenticator.kt")
                .contains("AccountAvailabilityReason.NO_BACKEND_CONFIGURED"),
        )
    }

    @Test
    fun theBackendSwapPointIsExactlyOneObject() {
        val foundation = sourceText("domain/account/CraftMindAccountFoundation.kt")

        assertTrue(foundation.contains("fun authenticator(): AccountAuthenticator"))
        assertTrue(
            "the composition root must reach the authenticator through the foundation",
            sourceText("AppContainer.kt").let { it.contains("CraftMindAccountFoundation") },
        )
    }

    @Test
    fun theAccountLayerAddsNoWebViewAndNoAnalytics() {
        val text = accountSourceText()

        for (forbidden in listOf("WebView", "analytics", "Analytics", "mixpanel", "amplitude", "sentry", "firebase")) {
            assertFalse("the account layer must not add $forbidden", text.contains(forbidden))
        }
    }

    @Test
    fun anAuthenticationFailureCodeNeverCarriesFreeTextFromAService() {
        for (code in AccountAuthErrorCode.entries) {
            assertTrue(
                "$code must be a closed enum value, not a message",
                code.name == code.name.uppercase() && !code.name.contains(" "),
            )
        }
    }

    @Test
    fun theAccountLayerNeverSerialisesASession() {
        val text = accountSourceText()

        for (forbidden in listOf("Gson", "toJson", "Serializable", "@Serializable", "ObjectOutputStream")) {
            assertFalse("session material must not be serialised by the app ($forbidden)", text.contains(forbidden))
        }
    }

    @Test
    fun theFileMarkerUsedByTheMigrationCarriesMetadataOnly() {
        val store = sourceText("data/account/FileLocalOwnershipMarkerStore.kt")

        assertTrue(store.contains("local_ownership.marker"))
        assertTrue(store.contains("AtomicFile"))
        assertTrue("the marker must stay tiny", store.contains("MAXIMUM_BYTES"))
        assertEquals(
            "the marker must not be able to hold user content",
            false,
            store.contains("buildPlan") || store.contains("buildHistory"),
        )
    }
}
