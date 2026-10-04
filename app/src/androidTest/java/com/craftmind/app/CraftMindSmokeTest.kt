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
    fun homeShowsComposerAndHonestPhaseOneAction() {
        composeRule.onNodeWithText("What do you want to build?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Generate with AI").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("No AI request is sent in Phase 1. Image and URL references are not analyzed.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
