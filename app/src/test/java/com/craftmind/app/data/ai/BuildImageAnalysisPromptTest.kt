package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiImageInput
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelCapabilities
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.StructuredOutputMode
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildImageAnalysisPromptTest {
    private val model = AiModel(
        id = "gemini-3.5-flash",
        providerId = AiProviderId("google_gemini"),
        displayName = "Gemini 3.5 Flash",
        capabilities = AiModelCapabilities(
            textGeneration = true,
            vision = true,
            publicUrlReferences = false,
            structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
            maximumContextTokens = 1_000_000,
            maximumOutputTokens = 65_536,
        ),
    )

    @Test
    fun imageBytesGoOnlyInTheImagePartAndPrivateUriFilenameAndUrlStayOutOfPrompt() {
        val image = AiImageInput("image/jpeg", 16, 12, byteArrayOf(1, 2, 3))
        try {
            val request = BuildImageAnalysisPrompt.forRequest(
                BuildRequest(
                    requestId = "request-private-test",
                    prompt = "A small garden pavilion",
                    imageReference = BuildInput.ImageReference(
                        contentUri = "content://private/photo/secret",
                        mediaType = "image/jpeg",
                        sizeBytes = 123L,
                        displayName = "private-name.jpg",
                    ),
                    urlReference = BuildInput.UrlReference("https://private.example/image-instructions"),
                    createdAtEpochMillis = 1_700_000_000_000,
                ),
                model,
                image,
            )

            assertEquals(listOf(image), request.imageInputs)
            assertTrue(request.prompt.contains("A small garden pavilion"))
            assertTrue(request.prompt.contains("not fetched"))
            assertFalse(request.prompt.contains("content://private"))
            assertFalse(request.prompt.contains("private-name.jpg"))
            assertFalse(request.prompt.contains("https://private.example"))
            assertTrue(request.systemInstruction.contains("observedDetails"))
            assertTrue(request.systemInstruction.contains("inferredDetails"))
        } finally {
            image.close()
        }
    }
}
