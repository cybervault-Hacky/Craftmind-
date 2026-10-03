package com.craftmind.app

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
    fun homeRendersAndBuildsAndSettingsAreReachable() {
        composeRule.onNodeWithText("What would you like to build?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Builds").onFirst().performClick()
        composeRule.onNodeWithText("Your builds will appear here.").assertIsDisplayed()

        composeRule.onAllNodesWithText("Settings").onFirst().performClick()
        composeRule.onNodeWithText("AI provider").assertIsDisplayed()
        composeRule.onNodeWithText("API key configuration").assertIsDisplayed()
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
    }

    @Test
    fun pressingBuildDoesNotPretendToGenerateAPlan() {
        composeRule.onNodeWithContentDescription("Check this input for a future AI build request")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Add a description, image, or reference URL to continue.")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("builder-prompt").performTextInput("A quiet garden pavilion")
        composeRule.onNodeWithContentDescription("Check this input for a future AI build request")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("AI builder isn’t connected yet")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Nothing was sent and no build was generated. AI provider setup will arrive in a later phase.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
