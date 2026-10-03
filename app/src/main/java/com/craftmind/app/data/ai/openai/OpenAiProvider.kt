package com.craftmind.app.data.ai.openai

import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelIdValidator
import com.craftmind.app.domain.ai.AiProvider
import com.craftmind.app.domain.ai.AiProviderDescriptor
import com.craftmind.app.domain.ai.AiProviderError
import com.craftmind.app.domain.ai.AiProviderErrorCode
import com.craftmind.app.domain.ai.AiProviderResult
import com.craftmind.app.domain.ai.ProviderId
import com.craftmind.app.domain.ai.SecretValue
import com.craftmind.app.domain.model.BuildPlanDraft
import com.craftmind.app.domain.model.BuildRequest
import com.craftmind.app.domain.planning.BuildPlanJsonParser
import com.craftmind.app.domain.planning.BuildPlanParseIssue
import com.craftmind.app.domain.planning.BuildPlanParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** First production adapter. Requests are restricted to api.openai.com over HTTPS. */
internal class OpenAiProvider(
    private val transport: OpenAiHttpTransport,
    private val planParser: BuildPlanJsonParser = BuildPlanJsonParser(),
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AiProvider {
    override val descriptor = AiProviderDescriptor(
        id = ProviderId.OPENAI,
        displayName = "OpenAI",
        description = "Generate a text-only, structured Minecraft build plan.",
    )

    override suspend fun generateBuildPlan(
        request: BuildRequest,
        model: AiModel,
        credential: SecretValue,
    ): AiProviderResult<BuildPlanDraft> {
        try {
            val modelId = AiModelIdValidator.normalize(model.id)
                ?: return failure(AiProviderErrorCode.UNSUPPORTED_MODEL)
            val requestBody = withContext(computationDispatcher) {
                OpenAiRequestFactory.createCompletionRequest(request, modelId)
            } ?: return failure(AiProviderErrorCode.REQUEST_REJECTED)
            return when (val response = transport.execute(OpenAiHttpCall.CreateCompletion(requestBody), credential)) {
                is OpenAiHttpResult.Failed -> response.reason.toProviderFailure()
                OpenAiHttpResult.RequestTooLarge -> failure(AiProviderErrorCode.REQUEST_REJECTED)
                OpenAiHttpResult.ResponseTooLarge -> failure(AiProviderErrorCode.RESPONSE_TOO_LARGE)
                is OpenAiHttpResult.Response -> withContext(computationDispatcher) {
                    parseCompletionResponse(response.statusCode, response.body)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(AiProviderErrorCode.UNKNOWN, retryable = true)
        } finally {
            credential.clear()
        }
    }

    override suspend fun testConnection(
        model: AiModel,
        credential: SecretValue,
    ): AiProviderResult<Unit> {
        try {
            val modelId = AiModelIdValidator.normalize(model.id)
                ?: return failure(AiProviderErrorCode.UNSUPPORTED_MODEL)
            return when (val response = transport.execute(OpenAiHttpCall.RetrieveModel(modelId), credential)) {
                is OpenAiHttpResult.Failed -> response.reason.toProviderFailure()
                OpenAiHttpResult.RequestTooLarge -> failure(AiProviderErrorCode.REQUEST_REJECTED)
                OpenAiHttpResult.ResponseTooLarge -> failure(AiProviderErrorCode.RESPONSE_TOO_LARGE)
                is OpenAiHttpResult.Response -> withContext(computationDispatcher) {
                    if (response.statusCode !in 200..299) {
                        response.statusCode.toProviderError(response.body)
                    } else if (OpenAiResponseParser.modelId(response.body) == modelId) {
                        AiProviderResult.Success(Unit)
                    } else {
                        failure(AiProviderErrorCode.UNSUPPORTED_MODEL)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(AiProviderErrorCode.UNKNOWN, retryable = true)
        } finally {
            credential.clear()
        }
    }

    private fun parseCompletionResponse(statusCode: Int, body: String): AiProviderResult<BuildPlanDraft> {
        if (statusCode !in 200..299) return statusCode.toProviderError(body)
        return when (val content = OpenAiResponseParser.completionContent(body)) {
            is OpenAiCompletionContent.PlanJson -> when (val parsed = planParser.parse(content.content)) {
                is BuildPlanParseResult.Parsed -> AiProviderResult.Success(parsed.draft)
                is BuildPlanParseResult.Rejected -> failure(
                    if (parsed.issue == BuildPlanParseIssue.RESPONSE_TOO_LARGE) {
                        AiProviderErrorCode.RESPONSE_TOO_LARGE
                    } else {
                        AiProviderErrorCode.MALFORMED_RESPONSE
                    },
                    retryable = parsed.issue != BuildPlanParseIssue.RESPONSE_TOO_LARGE,
                )
            }
            OpenAiCompletionContent.Refused -> failure(AiProviderErrorCode.REQUEST_REJECTED)
            OpenAiCompletionContent.Truncated -> failure(AiProviderErrorCode.MALFORMED_RESPONSE, retryable = true)
            OpenAiCompletionContent.Invalid -> failure(AiProviderErrorCode.MALFORMED_RESPONSE, retryable = true)
        }
    }

    private fun Int.toProviderError(body: String): AiProviderResult.Failure {
        val providerCode = OpenAiResponseParser.errorCode(body)?.lowercase().orEmpty()
        val error = when {
            this == 401 || this == 403 -> AiProviderErrorCode.INVALID_API_KEY
            this == 404 || providerCode.contains("model_not_found") -> AiProviderErrorCode.UNSUPPORTED_MODEL
            this == 408 || this == 504 -> AiProviderErrorCode.REQUEST_TIMED_OUT
            this == 413 -> AiProviderErrorCode.REQUEST_REJECTED
            this == 429 -> AiProviderErrorCode.RATE_LIMITED
            this >= 500 -> AiProviderErrorCode.PROVIDER_UNAVAILABLE
            providerCode.contains("unsupported_model") ||
                providerCode.contains("unsupported_value") ||
                providerCode.contains("invalid_json_schema") ||
                providerCode.contains("unsupported_parameter") -> AiProviderErrorCode.UNSUPPORTED_MODEL
            else -> AiProviderErrorCode.REQUEST_REJECTED
        }
        val retryable = error in setOf(
            AiProviderErrorCode.REQUEST_TIMED_OUT,
            AiProviderErrorCode.RATE_LIMITED,
            AiProviderErrorCode.PROVIDER_UNAVAILABLE,
        )
        return AiProviderResult.Failure(AiProviderError(error, retryable))
    }

    private fun OpenAiTransportFailure.toProviderError(): AiProviderErrorCode = when (this) {
        OpenAiTransportFailure.NO_INTERNET -> AiProviderErrorCode.NO_INTERNET
        OpenAiTransportFailure.REQUEST_TIMED_OUT -> AiProviderErrorCode.REQUEST_TIMED_OUT
        OpenAiTransportFailure.PROVIDER_UNAVAILABLE -> AiProviderErrorCode.PROVIDER_UNAVAILABLE
    }

    private fun OpenAiTransportFailure.toProviderFailure(): AiProviderResult.Failure {
        val code = toProviderError()
        val retryable = code in setOf(
            AiProviderErrorCode.NO_INTERNET,
            AiProviderErrorCode.REQUEST_TIMED_OUT,
            AiProviderErrorCode.PROVIDER_UNAVAILABLE,
        )
        return AiProviderResult.Failure(AiProviderError(code, retryable))
    }

    private fun failure(
        code: AiProviderErrorCode,
        retryable: Boolean = false,
    ) = AiProviderResult.Failure(AiProviderError(code, retryable))
}
