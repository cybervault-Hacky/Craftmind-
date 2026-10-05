package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelCapabilities
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.StructuredOutputMode
import com.craftmind.app.domain.buildplan.BuildImageAnalysis
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPlanGenerationPromptTest {
    private val model = AiModel(
        id = "gemini-3.5-flash",
        providerId = AiProviderId("google_gemini"),
        displayName = "Gemini 3.5 Flash",
        capabilities = AiModelCapabilities(
            textGeneration = true,
            vision = true,
            publicUrlReferences = false,
            structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
            maximumContextTokens = 1_048_576,
            maximumOutputTokens = 65_536,
        ),
    )

    @Test
    fun imageAnalysisIsTextOnlyAndKeepsObservedAndInferredDetailsSeparate() {
        val request = request(prompt = "A stone garden pavilion")
        val analysis = BuildImageAnalysis(
            summary = "An open pavilion with a low roof.",
            observedDetails = listOf("Four vertical supports appear visible."),
            inferredDetails = listOf("The pale support surfaces may be stone."),
            uncertainties = listOf("The rear side is hidden."),
        )

        val providerRequest = BuildPlanGenerationPrompt.forRequest(request, model, analysis)

        assertTrue(providerRequest.prompt.contains("observedDetails"))
        assertTrue(providerRequest.prompt.contains("inferredDetails"))
        assertTrue(providerRequest.prompt.contains("uncertainties"))
        assertTrue(providerRequest.prompt.contains("image bytes are not attached"))
        assertTrue(providerRequest.systemInstruction.contains("no accuracy"))
        assertTrue(providerRequest.imageInputs.isEmpty())
        assertTrue(!providerRequest.prompt.contains("content://"))
    }

    @Test
    fun imageOnlyRequestNeedsNoInventedWrittenPromptAndUrlIsStillNotFetched() {
        val providerRequest = BuildPlanGenerationPrompt.forRequest(
            request(prompt = "", urlReference = "https://example.org/reference"),
            model,
            BuildImageAnalysis("A tower.", listOf("A tall shape is visible."), emptyList(), emptyList()),
        )

        assertTrue(providerRequest.prompt.contains("user provided no written description"))
        assertTrue(providerRequest.prompt.contains("was not fetched"))
        assertFalse(providerRequest.prompt.contains("https://example.org/reference"))
        assertEquals(emptyList<com.craftmind.app.domain.ai.AiImageInput>(), providerRequest.imageInputs)
    }

    private fun request(prompt: String, urlReference: String? = null) = BuildRequest(
        requestId = "request-prompt-test",
        prompt = prompt,
        imageReference = BuildInput.ImageReference("content://picker/image", "image/jpeg", 32L),
        urlReference = urlReference?.let { BuildInput.UrlReference(it) },
        createdAtEpochMillis = 1_700_000_000_000,
    )
}
