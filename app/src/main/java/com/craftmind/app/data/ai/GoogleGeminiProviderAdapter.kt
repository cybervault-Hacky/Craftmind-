package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiFailure
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelCapabilities
import com.craftmind.app.domain.ai.AiProviderAdapter
import com.craftmind.app.domain.ai.AiProviderCapabilities
import com.craftmind.app.domain.ai.AiProviderDefinition
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.AiProviderRequest
import com.craftmind.app.domain.ai.AiProviderResponse
import com.craftmind.app.domain.ai.AiUsage
import com.craftmind.app.domain.ai.StructuredOutputMode
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.security.ProviderCredential
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Real direct-to-Google Gemini REST adapter. No prompt or key goes through a CraftMind server. */
class GoogleGeminiProviderAdapter internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val json: Json,
) : AiProviderAdapter {
    constructor() : this(defaultClient(), BASE_ENDPOINT.toHttpUrl(), Json { ignoreUnknownKeys = false })

    init {
        require(baseUrl.isHttps) { "Provider adapter requires HTTPS" }
    }
    override val definition = AiProviderDefinition(
        id = PROVIDER_ID,
        displayName = "Google Gemini",
        credentialType = com.craftmind.app.domain.ai.CredentialType.API_KEY,
        baseEndpoint = baseUrl.toString(),
        capabilities = AiProviderCapabilities(
            textGeneration = true,
            vision = false,
            publicUrlReferences = false,
            structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
            cancellation = true,
            streaming = false,
        ),
    )

    override suspend fun listModels(credential: ProviderCredential): List<AiModel> {
        val models = linkedMapOf<String, AiModel>()
        var pageToken: String? = null
        var pageCount = 0

        do {
            val url = modelsUrl(pageToken)
            val request = withProviderKey(Request.Builder().url(url).get(), credential).build()
            val root = executeJson(request, MAX_MODEL_RESPONSE_BYTES)
            val modelItems = root["models"] as? JsonArray
                ?: throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
            if (modelItems.size > MAX_MODELS_PER_PAGE) throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)

            modelItems.forEach { element ->
                val item = element as? JsonObject ?: throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
                val name = item.stringOrNull("name") ?: throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
                val id = name.removePrefix("models/")
                if (!MODEL_ID_PATTERN.matches(id)) throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
                val methods = item["supportedGenerationMethods"] as? JsonArray
                    ?: throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
                val methodNames = methods.map { method ->
                    (method as? JsonPrimitive)?.contentOrNull ?: throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
                }
                if ("generateContent" !in methodNames) return@forEach

                val displayName = item.stringOrNull("displayName")?.takeIf(String::isNotBlank) ?: id
                val contextTokens = item["inputTokenLimit"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                val outputTokens = item["outputTokenLimit"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                models[id] = AiModel(
                    id = id,
                    providerId = PROVIDER_ID,
                    displayName = displayName,
                    capabilities = AiModelCapabilities(
                        textGeneration = true,
                        vision = false,
                        publicUrlReferences = false,
                        structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
                        maximumContextTokens = contextTokens,
                        maximumOutputTokens = outputTokens,
                        toolCalling = false,
                        streaming = false,
                    ),
                )
            }

            val nextTokenElement = root["nextPageToken"]
            pageToken = if (nextTokenElement == null) {
                null
            } else {
                try {
                    nextTokenElement.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
                        ?: throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
                } catch (_: IllegalStateException) {
                    throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
                }
            }
            pageCount++
            if (models.size > MAX_MODELS) throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        } while (pageToken != null && pageCount < MAX_MODEL_PAGES)

        if (pageToken != null) throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        models.values.toList()
    }

    override suspend fun generateContent(
        request: AiProviderRequest,
        credential: ProviderCredential,
    ): AiProviderResponse {
        if (request.model.providerId != PROVIDER_ID || !MODEL_ID_PATTERN.matches(request.model.id)) {
            throw providerFailure(AiErrorCode.MODEL_UNAVAILABLE)
        }
        if (!request.model.capabilities.textGeneration ||
            request.model.capabilities.structuredOutput != StructuredOutputMode.JSON_MIME_TYPE
        ) {
            throw providerFailure(AiErrorCode.UNSUPPORTED_CAPABILITY)
        }

        val requestJson = buildJsonObject {
            put(
                "systemInstruction",
                buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", request.systemInstruction) }) })
                },
            )
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray { add(buildJsonObject { put("text", request.prompt) }) })
                        },
                    )
                },
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.2)
                    put("candidateCount", 1)
                    val modelMaximum = request.model.capabilities.maximumOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS
                    put("maxOutputTokens", minOf(modelMaximum, DEFAULT_MAX_OUTPUT_TOKENS))
                },
            )
        }

        val body = json.encodeToString(JsonObject.serializer(), requestJson)
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = withProviderKey(
            Request.Builder()
                .url(generateUrl(request.model.id))
                .header("Accept", "application/json")
                .post(body),
            credential,
        ).build()
        val responseJson = executeJson(httpRequest, BuildPlanLimits.MAX_RESPONSE_BYTES)
        parseGenerationResponse(responseJson)
    }

    private suspend fun executeJson(request: Request, maxBytes: Int): JsonObject {
        val response = client.newCall(request).await()
        val bytes = response.use { httpResponse ->
            if (!httpResponse.isSuccessful) {
                throw providerFailure(com.craftmind.app.domain.ai.AiErrorMapper.forHttpStatus(httpResponse.code).code)
            }
            httpResponse.body.readBounded(maxBytes)
        }
        return try {
            json.parseToJsonElement(String(bytes, StandardCharsets.UTF_8)).jsonObject
        } catch (_: SerializationException) {
            throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        } catch (_: IllegalStateException) {
            throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        } finally {
            bytes.fill(0)
        }
    }

    private fun parseGenerationResponse(root: JsonObject): AiProviderResponse {
        val candidates = root["candidates"] as? JsonArray
            ?: throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        if (candidates.size != 1) throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        val candidate = candidates.first() as? JsonObject
            ?: throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        val finishReason = candidate.stringOrNull("finishReason")
        if (finishReason != "STOP") throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        val content = candidate["content"] as? JsonObject
            ?: throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        val parts = content["parts"] as? JsonArray
            ?: throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        val text = parts.map { part ->
            (part as? JsonObject)?.stringOrNull("text") ?: throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)
        }.joinToString(separator = "")
        if (text.isBlank()) throw providerFailure(AiErrorCode.INVALID_AI_RESPONSE)

        val usageObject = root["usageMetadata"] as? JsonObject
        val usage = usageObject?.let { value ->
            AiUsage(
                inputTokens = value.longOrNull("promptTokenCount"),
                outputTokens = value.longOrNull("candidatesTokenCount"),
                providerRequestId = null,
            )
        }
        return AiProviderResponse(content = text, usage = usage)
    }

    private fun modelsUrl(pageToken: String?): HttpUrl = baseUrl.newBuilder()
        .addPathSegments("v1beta/models")
        .addQueryParameter("pageSize", MAX_MODELS_PER_PAGE.toString())
        .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
        .build()

    private fun generateUrl(modelId: String): HttpUrl = baseUrl.newBuilder()
        .addPathSegments("v1beta/models")
        .addPathSegment("$modelId:generateContent")
        .build()

    private fun withProviderKey(builder: Request.Builder, credential: ProviderCredential): Request.Builder =
        credential.useCharacters { characters -> builder.header(API_KEY_HEADER, String(characters)) }

    private fun JsonObject.stringOrNull(name: String): String? = try {
        this[name]?.jsonPrimitive?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun JsonObject.longOrNull(name: String): Long? = try {
        this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun ResponseBody.readBounded(maxBytes: Int): ByteArray {
        if (contentLength() > maxBytes) throw providerFailure(AiErrorCode.RESPONSE_TOO_LARGE)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        try {
            byteStream().use { input ->
                while (true) {
                    val allowance = (maxBytes + 1 - total).coerceAtMost(buffer.size)
                    if (allowance <= 0) throw providerFailure(AiErrorCode.RESPONSE_TOO_LARGE)
                    val count = input.read(buffer, 0, allowance)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw providerFailure(AiErrorCode.RESPONSE_TOO_LARGE)
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    private fun providerFailure(code: AiErrorCode) =
        com.craftmind.app.domain.ai.AiProviderException(AiFailure(code, retryable = code in RETRYABLE_ERRORS))

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        try {
                            continuation.resume(response) { _, abandonedResponse, _ -> abandonedResponse.close() }
                        } catch (_: IllegalStateException) {
                            response.close()
                        }
                    } else {
                        response.close()
                    }
                }
            },
        )
    }

    private companion object {
        val PROVIDER_ID = AiProviderId("google_gemini")
        const val BASE_ENDPOINT = "https://generativelanguage.googleapis.com/"
        const val API_KEY_HEADER = "x-goog-api-key"
        const val MAX_MODEL_PAGES = 4
        const val MAX_MODELS_PER_PAGE = 100
        const val MAX_MODELS = MAX_MODEL_PAGES * MAX_MODELS_PER_PAGE
        const val MAX_MODEL_RESPONSE_BYTES = 1024 * 1024
        const val DEFAULT_MAX_OUTPUT_TOKENS = 8_192
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val MODEL_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,160}")
        val RETRYABLE_ERRORS = setOf(
            AiErrorCode.PROVIDER_UNAVAILABLE,
            AiErrorCode.RATE_LIMITED,
            AiErrorCode.NETWORK_TIMEOUT,
            AiErrorCode.NETWORK_UNAVAILABLE,
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
