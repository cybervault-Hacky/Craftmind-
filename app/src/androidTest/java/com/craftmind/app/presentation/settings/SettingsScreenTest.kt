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
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelCapabilities
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

    @Test
    fun modelMenuAndProviderNoticeDistinguishMultiImageVideoCapability() {
        val multiImageProvider = provider.copy(
            capabilities = provider.capabilities.copy(vision = true, multipleImages = true),
        )
        val multiImageModel = AiModel(
            id = "gemini-3.5-flash",
            providerId = provider.id,
            displayName = "Gemini 3.5 Flash",
            capabilities = AiModelCapabilities(
                textGeneration = true,
                vision = true,
                publicUrlReferences = false,
                structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
                maximumContextTokens = 64_000,
                maximumOutputTokens = 8_000,
                multipleImages = true,
            ),
        )
        val oneImageModel = multiImageModel.copy(
            id = "gemini-vision-only",
            displayName = "Gemini Vision Only",
            capabilities = multiImageModel.capabilities.copy(multipleImages = false),
        )
        val textOnlyModel = multiImageModel.copy(
            id = "gemini-text-only",
            displayName = "Gemini Text Only",
            capabilities = multiImageModel.capabilities.copy(vision = false, multipleImages = false),
        )
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeSelected = {},
                    providerState = ProviderSettingsState(
                        providers = listOf(multiImageProvider),
                        activeProviderId = multiImageProvider.id,
                        savedCredentialExists = true,
                        models = listOf(multiImageModel, oneImageModel, textOnlyModel),
                        selectedModelId = multiImageModel.id,
                        connection = ProviderConnectionState.Verified(1),
                    ),
                    onProviderEvent = {},
                )
            }
        }

        compose.onNodeWithText("Gemini 3.5 Flash").performClick()
        compose.onNodeWithText("Text + one-image and multi-frame video vision").assertExists()
        compose.onNodeWithText("Text + one-image vision · multi-frame video unavailable").assertExists()
        compose.onNodeWithText("Text only · image/video analysis unavailable").assertExists()
        compose.onNodeWithText(
            "Selected model is verified for one-image input and bounded multi-image video-frame analysis. Video bytes stay on-device except for bounded HTTPS range reads; sampled images are sent directly to this provider. Refinement uses saved text notes only and never re-downloads the video. Provider retention is governed by its terms.",
        ).assertExists()
        compose.onNodeWithText(
            "Image requests use one locally prepared JPEG, PNG, or WebP. Video requests are separately limited to direct HTTPS MP4/WebM files on raw.githubusercontent.com: byte ranges only, no redirects, and at most five sampled frames. A model must be individually labeled Multi-image Vision; frames go directly to that selected provider.",
        ).assertExists()
    }
}
