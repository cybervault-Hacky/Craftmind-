package com.craftmind.app.presentation.home

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiGenerationStage
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelCapabilities
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.StructuredOutputMode
import com.craftmind.app.domain.buildplan.BuildRequestValidationError
import com.craftmind.app.domain.buildplan.BuildRequestValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wording contract for the composer (Phase 15 §6, §17).
 *
 * Every typed stage, error code, and validation error a user can hit must have a sentence that a person can act on,
 * and no sentence may fabricate progress, leak a secret, or claim compatibility that was not resolved.
 */
class HomeMessagesTest {

    @Test
    fun everyGenerationStageHasAnActionableSentence() {
        AiGenerationStage.entries.forEach { stage ->
            val message = generationStageMessage(stage)
            assertTrue("$stage has no message", message.isNotBlank())
            assertTrue("$stage message is too long to read beside a spinner", message.length <= 160)
            assertFalse("$stage message must not name the enum", message.contains(stage.name))
        }
    }

    @Test
    fun everyProviderErrorCodeHasAnActionableSentence() {
        AiErrorCode.entries.forEach { code ->
            val message = generationMessage(code)
            assertTrue("$code has no message", message.isNotBlank())
            assertTrue("$code message is too long for an inline notice", message.length <= 320)
            assertFalse("$code message must not dump the enum name", message.contains(code.name))
            // A user-facing sentence never looks like a crash report or a raw payload.
            listOf("Exception", "at com.craftmind", "stackTrace", "AIza", "apiKey=", "Bearer ").forEach { forbidden ->
                assertFalse("$code message leaks '$forbidden'", message.contains(forbidden))
            }
        }
    }

    @Test
    fun everyValidationErrorHasAnActionableSentence() {
        BuildRequestValidationError.entries.forEach { error ->
            val message = validationMessage(error)
            assertTrue("$error has no message", message.isNotBlank())
            assertFalse("$error message must not dump the enum name", message.contains(error.name))
        }
        assertTrue(
            validationMessage(BuildRequestValidationError.PROMPT_TOO_LONG)
                .contains(BuildRequestValidator.MAX_PROMPT_LENGTH.toString()),
        )
    }

    @Test
    fun noMessageInventsProgressOrOverstatesCompatibility() {
        val messages = AiGenerationStage.entries.map { generationStageMessage(it) } +
            AiErrorCode.entries.map { generationMessage(it) } +
            BuildRequestValidationError.entries.map { validationMessage(it) }
        messages.forEach { message ->
            val lowered = message.lowercase()
            assertFalse("no fabricated percentage: $message", message.contains("%"))
            listOf("almost done", "estimated time", "fully compatible", "full compatibility", "perfect", "guaranteed")
                .forEach { phrase ->
                    assertFalse("'$phrase' is not a claim CraftMind makes: $message", lowered.contains(phrase))
                }
        }
    }

    @Test
    fun missingCredentialAndVisionErrorsTellTheUserWhereToGo() {
        assertTrue(generationMessage(AiErrorCode.MISSING_CREDENTIAL).contains("Settings"))
        assertTrue(generationMessage(AiErrorCode.VISION_UNSUPPORTED).contains("Vision"))
        assertTrue(generationMessage(AiErrorCode.CANCELLED).contains("No plan was created"))
        assertTrue(
            generationMessage(AiErrorCode.MULTIPLE_VISUAL_REFERENCES_UNSUPPORTED).contains("one visual reference"),
        )
    }

    @Test
    fun sizeFormattingStaysHonestWhenTheValueIsMissing() {
        assertEquals("Size unavailable", formatSize(null))
        assertEquals("512 B", formatSize(512))
        assertEquals("5.0 MiB", formatSize(5L * 1024 * 1024))
        assertEquals("12.0 MiB", formatSize(12L * 1024 * 1024))
    }

    @Test
    fun referenceUrlDisplayHidesCredentialsQueryAndFragments() {
        assertEquals(
            "raw.githubusercontent.com/user/repo/main/video.mp4",
            hostAndPath("https://raw.githubusercontent.com/user/repo/main/video.mp4"),
        )
        val withSecrets = hostAndPath(
            "https://user:token@raw.githubusercontent.com/user/repo/main/video.mp4?sig=abc#fragment",
        )
        assertFalse("credentials must never be echoed", withSecrets.contains("token"))
        assertFalse("query parameters must never be echoed", withSecrets.contains("sig=abc"))
        assertFalse("fragments must never be echoed", withSecrets.contains("fragment"))
        assertEquals("Video URL (details hidden)", hostAndPath("not a url"))
    }

    @Test
    fun setupStatusNamesTheOneMissingPrecondition() {
        val loading = homeSetupStatus(
            providerSettingsLoaded = false,
            providerCredentialSaved = false,
            selectedModelId = null,
            selectedModel = null,
        )
        assertEquals("Checking your AI setup", loading?.title)

        val noKey = homeSetupStatus(
            providerSettingsLoaded = true,
            providerCredentialSaved = false,
            selectedModelId = null,
            selectedModel = null,
        )
        assertEquals("Connect an AI provider in Settings to generate builds", noKey?.title)
        assertEquals("Set up AI provider", noKey?.actionLabel)

        val noModel = homeSetupStatus(
            providerSettingsLoaded = true,
            providerCredentialSaved = true,
            selectedModelId = null,
            selectedModel = null,
        )
        assertEquals("No model selected", noModel?.title)

        val unverifiedModel = homeSetupStatus(
            providerSettingsLoaded = true,
            providerCredentialSaved = true,
            selectedModelId = "gemini-test",
            selectedModel = null,
        )
        assertEquals("Model not verified in this session", unverifiedModel?.title)

        assertNull(
            "a fully configured provider shows no setup banner at all",
            homeSetupStatus(
                providerSettingsLoaded = true,
                providerCredentialSaved = true,
                selectedModelId = "gemini-test",
                selectedModel = visionModel(),
            ),
        )
    }

    @Test
    fun everySetupStatusOffersARealWayForward() {
        listOf(
            homeSetupStatus(false, false, null, null),
            homeSetupStatus(true, false, null, null),
            homeSetupStatus(true, true, null, null),
            homeSetupStatus(true, true, "gemini-test", null),
        ).forEach { status ->
            val value = requireNotNull(status)
            assertTrue(value.title.isNotBlank())
            assertTrue(value.message.isNotBlank())
            assertTrue(value.actionLabel.isNotBlank())
            assertTrue(value.message.length <= 260)
        }
    }

    private fun visionModel() = AiModel(
        id = "gemini-test",
        providerId = AiProviderId("google_gemini"),
        displayName = "Gemini test model",
        capabilities = AiModelCapabilities(
            textGeneration = true,
            vision = true,
            publicUrlReferences = true,
            structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
            maximumContextTokens = null,
            maximumOutputTokens = null,
            multipleImages = true,
        ),
    )
}
