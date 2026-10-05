package com.craftmind.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class CraftMindSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsComposerAndDirectProviderDisclosure() {
        composeRule.onNodeWithText("What do you want to build?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Generate with AI").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Your prompt is sent directly to the selected AI provider. URL references stay local and are never fetched.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
