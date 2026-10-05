package com.craftmind.app.presentation.settings

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.craftmind.app.designsystem.CraftMindTheme
import com.craftmind.app.domain.settings.ThemeMode
import org.junit.Rule
import org.junit.Test

class MinecraftBridgeSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pairingUiHasNoBuildSubmissionOrConstructionAction() {
        compose.setContent {
            CraftMindTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeSelected = {},
                    providerState = ProviderSettingsState(),
                    onProviderEvent = {},
                    bridgeState = BridgePairingState(),
                    onBridgeEvent = {},
                )
            }
        }

        compose.onNodeWithText("Pair device and verify bridge").assertExists()
        compose.onNodeWithText("This Android device is paired").assertDoesNotExist()
        compose.onNodeWithText("Send BuildPlan").assertDoesNotExist()
        compose.onNodeWithText("Place blocks").assertDoesNotExist()
        compose.onNodeWithText("Minecraft construction remains disabled.", substring = true).assertExists()
    }
}
