package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelCapabilities
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.AiProviderRequest
import com.craftmind.app.domain.ai.StructuredOutputMode
import com.craftmind.app.domain.security.ProviderCredential
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleGeminiProviderAdapterTest {
    private lateinit var server: MockWebServer
    private lateinit var adapter: GoogleGeminiProviderAdapter
    private val testKey = "unit-test-provider-key"
    private val model = AiModel(
        id = "gemini-test-model",
        providerId = AiProviderId("google_gemini"),
        displayName = "Gemini test model",
        capabilities = AiModelCapabilities(
            textGeneration = true,
            vision = false,
            publicUrlReferences = false,
            structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
            maximumContextTokens = 32_000,
            maximumOutputTokens = 8_192,
        ),
    )

    @Before
    fun setUp() {
        val serverCertificate = HeldCertificate.Builder().commonName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(serverCertificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
        val httpsUrl = server.url("/").newBuilder().scheme("https").build()
        adapter = GoogleGeminiProviderAdapter(client, httpsUrl, kotlinx.serialization.json.Json { ignoreUnknownKeys = false })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun listsOnlyModelsThatActuallyAdvertiseGenerateContent() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {
                  "models": [
                    {"name":"models/gemini-test-model","displayName":"Gemini test model",
                     "supportedGenerationMethods":["generateContent"],"inputTokenLimit":32000,"outputTokenLimit":8192},
                    {"name":"models/embedding-test","displayName":"Embedding model",
                     "supportedGenerationMethods":["embedContent"]}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        try {
            val models = adapter.listModels(credential)
            val sent = server.takeRequest()
            assertEquals(listOf(model.id), models.map(AiModel::id))
            assertEquals("GET", sent.method)
            assertEquals("$testKey", sent.getHeader("x-goog-api-key"))
            assertNull(sent.requestUrl?.queryParameter("key"))
            assertTrue(sent.path.orEmpty().startsWith("/v1beta/models?"))
        } finally {
            credential.close()
        }
    }

    @Test
    fun postsStrictJsonGenerationRequestOverHttpsAndReturnsProviderText() = runBlocking {
        val generatedJson = """{"schemaVersion":1,"buildId":"test","title":"Test","description":"Test plan","dimensions":{"width":1,"height":1,"depth":1},"originStrategy":"CENTERED_GROUND","components":[],"operations":[]}"""
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {
                  "candidates":[{"content":{"role":"model","parts":[{"text":${kotlinx.serialization.json.JsonPrimitive(generatedJson)}}]},"finishReason":"STOP"}],
                  "usageMetadata":{"promptTokenCount":22,"candidatesTokenCount":44}
                }
                """.trimIndent(),
            ),
        )
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        try {
            val response = adapter.generateContent(
                AiProviderRequest(model, "system instruction", "user prompt"),
                credential,
            )
            val sent = server.takeRequest()
            assertEquals(generatedJson, response.content)
            assertEquals("POST", sent.method)
            assertEquals("$testKey", sent.getHeader("x-goog-api-key"))
            assertNull(sent.requestUrl?.queryParameter("key"))
            assertTrue(sent.path.orEmpty().contains("/v1beta/models/${model.id}:generateContent"))
            val body = sent.body.readUtf8()
            assertTrue(body.contains("\"responseMimeType\":\"application/json\""))
            assertTrue(body.contains("system instruction"))
            assertTrue(body.contains("user prompt"))
            assertEquals(22L, response.usage?.inputTokens)
            assertEquals(44L, response.usage?.outputTokens)
        } finally {
            credential.close()
        }
    }

    @Test
    fun mapsProviderStatusesWithoutExposingRawResponseBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive provider diagnostic"))
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        try {
            val failure = try {
                adapter.listModels(credential)
                throw AssertionError("Expected invalid key response")
            } catch (expected: AiProviderException) {
                expected
            }
            assertEquals(AiErrorCode.INVALID_API_KEY, failure.failure.code)
            assertFalse(failure.message.orEmpty().contains("sensitive"))
            assertFalse(failure.failure.retryable)
        } finally {
            credential.close()
        }
    }

    @Test
    fun rejectsOversizedModelListResponse() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody(" ".repeat(1024 * 1024 + 1)),
        )
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        try {
            val failure = try {
                adapter.listModels(credential)
                throw AssertionError("Expected oversized provider body")
            } catch (expected: AiProviderException) {
                expected
            }
            assertEquals(AiErrorCode.RESPONSE_TOO_LARGE, failure.failure.code)
        } finally {
            credential.close()
        }
    }

    @Test
    fun cancellationCancelsPendingOkHttpCall() = runBlocking {
        server.enqueue(
            MockResponse().setBodyDelay(30, TimeUnit.SECONDS)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"models\":[]}"),
        )
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        val job = launch(Dispatchers.IO) { adapter.listModels(credential) }
        try {
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        } finally {
            credential.close()
            job.cancel()
        }
    }
}
