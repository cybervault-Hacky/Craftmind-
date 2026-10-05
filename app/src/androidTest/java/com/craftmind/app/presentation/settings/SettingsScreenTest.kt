package com.craftmind.app.presentation.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.craftmind.app.designsystem.CraftMindTheme
import com.craftmind.app.domain.ai.AiProviderCapabilities
import com.craftmind.app.domain.ai.AiProviderDefinition
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.CredentialType
import com.craftmind.app.domain.ai.StructuredOutputMode
import com.craftmind.app.domain.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val provider = AiProviderDefinition(
        id = AiProviderId("google_gemini"),
        displayName = "Google Gemini",
        credentialType = CredentialType.API_KEY,
        baseEndpoint = "https://generativelanguage.googleapis.com/",
        capabilities = AiProviderCapabilities(
            textGeneration = true,
            vision = false,
            publicUrlReferences = false,
            structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
            cancellation = true,
            streaming = false,
        ),
    )

    @Test
    fun savedKeyIsNeverPrefilledIntoThePasswordFieldAndConnectionRequiresLiveTest() {
        var lastEvent: ProviderSettingsEvent? = null
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeSelected = {},
                    providerState = ProviderSettingsState(
                        providers = listOf(provider),
                        activeProviderId = provider.id,
                        savedCredentialExists = true,
                        keyDraft = "",
                        connection = ProviderConnectionState.Unverified,
                    ),
                    onProviderEvent = { lastEvent = it },
                )
            }
        }

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(5)
        compose.onAllNodes(hasSetTextAction()).onFirst().assertTextEquals("")
        compose.onNodeWithText("An API key is saved encrypted on this device. Its value is never displayed.").assertExists()
        compose.onNodeWithText("Test connection").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ProviderSettingsEvent.TestConnection, lastEvent) }
    }

    @Test
    fun unconfiguredProviderCannotBeReportedVerifiedOrTested() {
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeSelected = {},
                    providerState = ProviderSettingsState(
                        providers = listOf(provider),
                        activeProviderId = provider.id,
                        savedCredentialExists = false,
                    ),
                    onProviderEvent = {},
                )
            }
        }

        compose.onNodeWithText("Not verified. Run a live test to check the saved key and model list.").assertExists()
        compose.onNodeWithText("Test connection").assertExists().assertIsNotEnabled()
    }
}
