package com.craftmind.app

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CraftMindNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeRendersAndBuildsAndProviderSettingsAreReachable() {
        composeRule.onNodeWithText("What would you like to build?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Builds").onFirst().performClick()
        composeRule.onNodeWithText("Your builds will appear here.").assertIsDisplayed()

        composeRule.onAllNodesWithText("Settings").onFirst().performClick()
        composeRule.onNodeWithText("AI provider configuration").assertIsDisplayed()
        composeRule.onNodeWithText("API key configuration").assertIsDisplayed()
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Select a provider").performClick()
        composeRule.onNodeWithText("OpenAI").assertIsDisplayed()
        composeRule.onNodeWithText("Provider model identifier").assertIsDisplayed()
        composeRule.onNodeWithText("API key").assertIsDisplayed()
    }

    @Test
    fun textRequestShowsRealConfigurationFailureInsteadOfFakeProgressOrPlan() {
        composeRule.onNodeWithTag("builder-prompt").performTextInput("A quiet garden pavilion")
        composeRule.onNodeWithContentDescription("Generate and validate a text-only build plan")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Choose a provider in Settings.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Requesting a structured plan…").assertDoesNotExist()

        composeRule.onAllNodesWithText("Builds").onFirst().performClick()
        composeRule.onNodeWithText("No plan has been generated in this app session. Configure OpenAI in Settings and request a text-only plan. No Minecraft blocks are placed in Phase 2.")
            .assertIsDisplayed()
    }
}
