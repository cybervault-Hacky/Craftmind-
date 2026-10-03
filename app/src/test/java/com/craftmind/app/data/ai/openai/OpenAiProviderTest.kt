package com.craftmind.app.data.ai.openai

import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiProviderErrorCode
import com.craftmind.app.domain.ai.AiProviderResult
import com.craftmind.app.domain.ai.SecretValue
import com.craftmind.app.domain.model.BuildRequest
import com.craftmind.app.domain.model.TextInput
import com.craftmind.app.domain.model.UrlReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderTest {
    @Test
    fun completionUsesStrictStructuredOutputAndReturnsOnlyParsedPlan() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport { call, _ ->
            assertTrue(call is OpenAiHttpCall.CreateCompletion)
            OpenAiHttpResult.Response(200, completionResponse(validPlanJson()))
        }
        val provider = OpenAiProvider(transport)
        val secret = SecretValue.copyOf("test-credential".toCharArray())

        val result = provider.generateBuildPlan(
            BuildRequest(listOf(TextInput("A small stone hut"))),
            AiModel("gpt-4o-mini"),
            secret,
        )

        assertTrue(result is AiProviderResult.Success)
        val draft = (result as AiProviderResult.Success).value
        assertEquals("Stone hut", draft.title)
        assertEquals(1, draft.operations.size)
        assertEquals("\u0000".repeat("test-credential".length), secret.use { it.concatToString() })
        val body = (transport.calls.single() as OpenAiHttpCall.CreateCompletion).jsonBody
        val root = Json.parseToJsonElement(body).toString()
        assertTrue(root.contains("json_schema"))
        assertTrue(root.contains("\"strict\":true"))
        assertTrue(root.contains("A small stone hut"))
        assertFalse(root.contains("https://"))
    }

    @Test
    fun requestFactoryRefusesImagesAndUrlsRatherThanDroppingThem() {
        val withUrl = BuildRequest(listOf(TextInput("A hut"), UrlReference("https://example.com")))

        assertNull(OpenAiRequestFactory.createCompletionRequest(withUrl, "gpt-4o-mini"))
    }

    @Test
    fun mapsCredentialModelAndRateLimitErrorsWithoutReturningProviderBody() = kotlinx.coroutines.runBlocking {
        val secretText = "do-not-leak-this-secret"
        val provider = OpenAiProvider(RecordingTransport { _, _ ->
            OpenAiHttpResult.Response(401, "{\"error\":{\"message\":\"$secretText\",\"code\":\"invalid_api_key\"}}")
        })
        val unauthorized = provider.generateBuildPlan(
            BuildRequest(listOf(TextInput("A tower"))), AiModel("gpt-4o-mini"), SecretValue.copyOf("key".toCharArray()),
        )
        assertTrue(unauthorized is AiProviderResult.Failure)
        assertEquals(AiProviderErrorCode.INVALID_API_KEY, (unauthorized as AiProviderResult.Failure).error.code)
        assertFalse(unauthorized.toString().contains(secretText))

        val limitedProvider = OpenAiProvider(RecordingTransport { _, _ ->
            OpenAiHttpResult.Response(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}")
        })
        val limited = limitedProvider.generateBuildPlan(
            BuildRequest(listOf(TextInput("A tower"))), AiModel("gpt-4o-mini"), SecretValue.copyOf("key".toCharArray()),
        ) as AiProviderResult.Failure
        assertEquals(AiProviderErrorCode.RATE_LIMITED, limited.error.code)
        assertTrue(limited.error.retryable)
    }

    @Test
    fun connectionCheckUsesModelLookupAndRetryableTransportFailuresStayTyped() = kotlinx.coroutines.runBlocking {
        val modelTransport = RecordingTransport { call, _ ->
            assertTrue(call is OpenAiHttpCall.RetrieveModel)
            OpenAiHttpResult.Response(200, "{\"id\":\"gpt-4o-mini\"}")
        }
        val result = OpenAiProvider(modelTransport).testConnection(
            AiModel("gpt-4o-mini"), SecretValue.copyOf("test-key".toCharArray()),
        )
        assertTrue(result is AiProviderResult.Success)
        assertTrue(modelTransport.calls.single() is OpenAiHttpCall.RetrieveModel)

        val unavailable = OpenAiProvider(RecordingTransport { _, _ ->
            OpenAiHttpResult.Failed(OpenAiTransportFailure.REQUEST_TIMED_OUT)
        }).testConnection(AiModel("gpt-4o-mini"), SecretValue.copyOf("test-key".toCharArray()))
        assertTrue(unavailable is AiProviderResult.Failure)
        val unavailableFailure = unavailable as AiProviderResult.Failure
        assertEquals(AiProviderErrorCode.REQUEST_TIMED_OUT, unavailableFailure.error.code)
        assertTrue(unavailableFailure.error.retryable)
    }

    @Test
    fun rejectsMalformedStructuredContentAndUnsupportedModels() = kotlinx.coroutines.runBlocking {
        val malformed = OpenAiProvider(RecordingTransport { _, _ ->
            OpenAiHttpResult.Response(200, completionResponse("not json"))
        }).generateBuildPlan(
            BuildRequest(listOf(TextInput("A tower"))), AiModel("gpt-4o-mini"), SecretValue.copyOf("test-key".toCharArray()),
        ) as AiProviderResult.Failure
        assertEquals(AiProviderErrorCode.MALFORMED_RESPONSE, malformed.error.code)

        val unsupported = OpenAiProvider(RecordingTransport { _, _ ->
            error("Transport should not run for invalid model ids")
        }).generateBuildPlan(
            BuildRequest(listOf(TextInput("A tower"))), AiModel("model/with/path"), SecretValue.copyOf("test-key".toCharArray()),
        ) as AiProviderResult.Failure
        assertEquals(AiProviderErrorCode.UNSUPPORTED_MODEL, unsupported.error.code)
    }

    private fun completionResponse(content: String) = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                put("finish_reason", "stop")
                putJsonObject("message") {
                    put("refusal", JsonNull)
                    put("content", content)
                }
            }
        }
    }.toString()

    private fun validPlanJson() = """{"schemaVersion":1,"title":"Stone hut","style":"rustic","dimensions":{"width":1,"length":1,"height":1},"origin":{"x":0,"y":64,"z":0},"materials":[{"blockIdentifier":"minecraft:stone","count":1}],"steps":[{"id":"foundation","title":"Foundation","description":"Place the base"}],"operations":[{"sequence":0,"position":{"x":0,"y":0,"z":0},"block":{"identifier":"minecraft:stone","properties":[]},"stepId":"foundation","rotationDegrees":null,"dependsOnSequences":[]}],"estimatedOperationCount":1}"""

    private class RecordingTransport(
        private val response: suspend (OpenAiHttpCall, SecretValue) -> OpenAiHttpResult,
    ) : OpenAiHttpTransport {
        val calls = mutableListOf<OpenAiHttpCall>()
        override suspend fun execute(call: OpenAiHttpCall, credential: SecretValue): OpenAiHttpResult {
            calls += call
            return response(call, credential)
        }
    }
}
