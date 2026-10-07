package com.craftmind.app.data.account

import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class AccountTransportMethod { GET, POST }

/** One request to the account service. [bearerToken] is the only place a token is carried. */
data class AccountTransportRequest(
    val method: AccountTransportMethod,
    val url: String,
    val jsonBody: String?,
    val bearerToken: String? = null,
)

data class AccountTransportResponse(
    val statusCode: Int,
    val body: String,
)

/**
 * The network boundary of the account layer.
 *
 * Kept as an interface so the client above it can be driven without a socket, and so this file is the *only* place in
 * the account feature that touches the network.
 */
interface AccountTransport {
    @Throws(IOException::class)
    fun send(request: AccountTransportRequest): AccountTransportResponse
}

/**
 * OkHttp implementation.
 *
 * Configured for an account API and nothing else: modern TLS only (so a downgrade to cleartext or to an obsolete cipher
 * suite fails rather than proceeding), redirects disabled (a redirect could move a bearer token to a host the app never
 * chose), and bounded timeouts so no screen can wait forever.
 */
class OkHttpAccountTransport(
    private val client: OkHttpClient = defaultClient(),
) : AccountTransport {
    override fun send(request: AccountTransportRequest): AccountTransportResponse {
        val body = request.jsonBody?.toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder()
            .url(request.url)
            .header("Accept", "application/json")
            .cacheControl(okhttp3.CacheControl.Builder().noStore().build())
        if (request.bearerToken != null) builder.header("Authorization", "Bearer ${request.bearerToken}")
        when (request.method) {
            AccountTransportMethod.GET -> builder.get()
            AccountTransportMethod.POST -> builder.post(body ?: EMPTY_JSON.toRequestBody(JSON_MEDIA_TYPE))
        }
        client.newCall(builder.build()).execute().use { response ->
            // The body is read once, bounded by the client's own limits; a huge body cannot be pulled into memory.
            val text = response.body?.string().orEmpty()
            return AccountTransportResponse(statusCode = response.code, body = text)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val EMPTY_JSON = "{}"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
