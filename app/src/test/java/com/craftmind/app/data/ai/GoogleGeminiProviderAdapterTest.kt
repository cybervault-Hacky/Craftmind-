package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiImageInput
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
    fun liveDiscoveryLabelsOnlyTheDocumentedExactGeminiImageModelsAsVision() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"models":[
                  {"name":"models/gemini-3.5-flash","displayName":"Gemini 3.5 Flash","supportedGenerationMethods":["generateContent"]},
                  {"name":"models/gemini-3.5-flash-lite","displayName":"Gemini 3.5 Flash-Lite","supportedGenerationMethods":["generateContent"]},
                  {"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","supportedGenerationMethods":["generateContent"]}
                ]}
                """.trimIndent(),
            ),
        )
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        try {
            val models = adapter.listModels(credential).associateBy(AiModel::id)
            assertTrue(adapter.definition.capabilities.vision)
            assertEquals(true, models["gemini-3.5-flash"]?.capabilities?.vision)
            assertEquals(true, models["gemini-3.5-flash"]?.capabilities?.multipleImages)
            assertEquals(true, models["gemini-3.5-flash-lite"]?.capabilities?.vision)
            assertEquals(true, models["gemini-3.5-flash-lite"]?.capabilities?.multipleImages)
            assertEquals(false, models["gemini-2.5-flash"]?.capabilities?.vision)
            assertEquals(false, models["gemini-2.5-flash"]?.capabilities?.multipleImages)
        } finally {
            credential.close()
        }
    }

    @Test
    fun postsImageBytesAsGeminiInlineDataUsingActualMimeAndNoCredentialInBody() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"{}"}]},"finishReason":"STOP"}]}""",
            ),
        )
        val imageBytes = byteArrayOf(9, 8, 7, 6, 5)
        val image = AiImageInput("image/webp", 2, 1, imageBytes)
        val visionModel = model.copy(id = "gemini-3.5-flash", capabilities = model.capabilities.copy(vision = true))
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        try {
            adapter.generateContent(
                AiProviderRequest(visionModel, "vision system", "analyze this", listOf(image)),
                credential,
            )
            val sent = server.takeRequest()
            val body = sent.body.readUtf8()
            val encoded = java.util.Base64.getEncoder().encodeToString(imageBytes)
            val root = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
            val content = (root["contents"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
            val parts = content["parts"] as kotlinx.serialization.json.JsonArray
            val inlineData = (parts[1] as kotlinx.serialization.json.JsonObject)["inline_data"] as kotlinx.serialization.json.JsonObject
            assertEquals("image/webp", (inlineData["mime_type"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals(encoded, (inlineData["data"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals(2, parts.size)
            assertTrue(body.contains("\"inline_data\""))
            assertTrue(body.contains("\"mime_type\":\"image/webp\""))
            assertTrue(body.contains("\"data\":\"$encoded\""))
            assertTrue(body.contains("analyze this"))
            assertEquals("$testKey", sent.getHeader("x-goog-api-key"))
            assertFalse(body.contains(testKey))
            assertNull(sent.requestUrl?.queryParameter("key"))
            assertTrue(sent.path.orEmpty().contains("/v1beta/models/${visionModel.id}:generateContent"))
        } finally {
            image.close()
            imageBytes.fill(0)
            credential.close()
        }
    }

    @Test
    fun postsMultipleFramesAsOrderedInlineImagePartsForTheVerifiedSelectedModel() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"{}"}]},"finishReason":"STOP"}]}""",
            ),
        )
        val payloads = listOf(byteArrayOf(1, 3, 5), byteArrayOf(2, 4, 6), byteArrayOf(7, 8, 9))
        val images = payloads.map { AiImageInput("image/jpeg", 2, 2, it) }
        val multiImageModel = model.copy(
            id = "gemini-3.5-flash",
            capabilities = model.capabilities.copy(vision = true, multipleImages = true),
        )
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        try {
            adapter.generateContent(
                AiProviderRequest(multiImageModel, "video system", "analyze these chronological frames", images),
                credential,
            )
            val sent = server.takeRequest()
            val body = sent.body.readUtf8()
            val root = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
            val contents = root["contents"] as kotlinx.serialization.json.JsonArray
            val parts = (contents.single() as kotlinx.serialization.json.JsonObject)["parts"] as kotlinx.serialization.json.JsonArray
            assertEquals(4, parts.size)
            assertEquals("text", (parts[0] as kotlinx.serialization.json.JsonObject).keys.single())
            val encodedImages = parts.drop(1).map { part ->
                val inlineData = (part as kotlinx.serialization.json.JsonObject)["inline_data"] as kotlinx.serialization.json.JsonObject
                assertEquals("image/jpeg", (inlineData["mime_type"] as kotlinx.serialization.json.JsonPrimitive).content)
                (inlineData["data"] as kotlinx.serialization.json.JsonPrimitive).content
            }
            assertEquals(payloads.map { java.util.Base64.getEncoder().encodeToString(it) }, encodedImages)
            assertTrue(body.contains("analyze these chronological frames"))
            assertFalse(body.contains(testKey))
            assertEquals("$testKey", sent.getHeader("x-goog-api-key"))
            assertNull(sent.requestUrl?.queryParameter("key"))
        } finally {
            images.forEach(AiImageInput::close)
            payloads.forEach { it.fill(0) }
            credential.close()
        }
    }

    @Test
    fun refusesAggregateMultiImagePayloadOverThreeMiBBeforeNetworkRequest() = runBlocking {
        val firstBytes = ByteArray(1_600 * 1024) { 1 }
        val secondBytes = ByteArray(1_600 * 1024) { 2 }
        val images = listOf(
            AiImageInput("image/jpeg", 1, 1, firstBytes),
            AiImageInput("image/jpeg", 1, 1, secondBytes),
        )
        val multiImageModel = model.copy(
            id = "gemini-3.5-flash",
            capabilities = model.capabilities.copy(vision = true, multipleImages = true),
        )
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        try {
            val failure = try {
                adapter.generateContent(AiProviderRequest(multiImageModel, "system", "prompt", images), credential)
                throw AssertionError("Expected multi-image payload limit rejection")
            } catch (expected: AiProviderException) {
                expected
            }
            assertEquals(AiErrorCode.IMAGE_TOO_LARGE, failure.failure.code)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally {
            images.forEach(AiImageInput::close)
            firstBytes.fill(0)
            secondBytes.fill(0)
            credential.close()
        }
    }

    @Test
    fun rejectsMultipleFramesWhenExactModelDoesNotAdvertiseMultiImageSupport() = runBlocking {
        val images = listOf(
            AiImageInput("image/jpeg", 1, 1, byteArrayOf(1)),
            AiImageInput("image/jpeg", 1, 1, byteArrayOf(2)),
        )
        val visionOnlyModel = model.copy(
            id = "gemini-3.5-flash",
            capabilities = model.capabilities.copy(vision = true, multipleImages = false),
        )
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        try {
            val failure = try {
                adapter.generateContent(AiProviderRequest(visionOnlyModel, "system", "prompt", images), credential)
                throw AssertionError("Expected multi-image capability rejection")
            } catch (expected: AiProviderException) {
                expected
            }
            assertEquals(AiErrorCode.MULTI_IMAGE_UNSUPPORTED, failure.failure.code)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally {
            images.forEach(AiImageInput::close)
            credential.close()
        }
    }

    @Test
    fun rejectsImageForNonVisionModelBeforeMakingANetworkRequest() = runBlocking {
        val image = AiImageInput("image/jpeg", 1, 1, byteArrayOf(1, 2, 3))
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        try {
            val failure = try {
                adapter.generateContent(AiProviderRequest(model, "system", "prompt", listOf(image)), credential)
                throw AssertionError("Expected vision capability rejection")
            } catch (expected: AiProviderException) {
                expected
            }
            assertEquals(AiErrorCode.VISION_UNSUPPORTED, failure.failure.code)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally {
            image.close()
            credential.close()
        }
    }

    @Test
    fun mapsProviderImagePayloadRejectionToTypedImageSizeFailureWithoutRawBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(413).setBody("sensitive payload diagnostic"))
        val image = AiImageInput("image/jpeg", 1, 1, byteArrayOf(1, 2, 3))
        val visionModel = model.copy(id = "gemini-3.5-flash", capabilities = model.capabilities.copy(vision = true))
        val credential = ProviderCredential.fromCharacters(testKey.toCharArray())
        try {
            val failure = try {
                adapter.generateContent(AiProviderRequest(visionModel, "system", "analyze", listOf(image)), credential)
                throw AssertionError("Expected provider image-size rejection")
            } catch (expected: AiProviderException) {
                expected
            }
            assertEquals(AiErrorCode.IMAGE_TOO_LARGE, failure.failure.code)
            assertFalse(failure.message.orEmpty().contains("sensitive"))
        } finally {
            image.close()
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
