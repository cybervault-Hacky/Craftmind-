package com.craftmind.app.data.reference

import com.craftmind.app.domain.reference.PublicVideoReferenceException
import com.craftmind.app.domain.reference.PublicVideoReferenceFailure
import com.craftmind.app.domain.reference.PublicVideoReferenceResolver
import com.craftmind.app.domain.reference.ResolvedPublicVideoReference
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRawVideoReferenceResolverTest {
    private val videoUrl = "https://raw.githubusercontent.com/owner/repo/main/video.mp4"

    @Test
    fun sendsOnlySmallIdentityEncodedRangeAndValidatesMp4Container() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val resolver = resolver(requests = requests)

        val resolved = resolver.resolve(videoUrl)

        assertEquals(videoUrl, resolved.canonicalUrl)
        assertEquals("raw.githubusercontent.com", resolved.sourceDomain)
        assertEquals("video/mp4", resolved.mediaType)
        assertEquals(8_192L, resolved.contentLengthBytes)
        assertEquals(1, requests.size)
        assertEquals("bytes=0-4095", requests.single().header("Range"))
        assertEquals("identity", requests.single().header("Accept-Encoding"))
        assertEquals("raw.githubusercontent.com", requests.single().url.host)
        assertEquals("https", requests.single().url.scheme)
    }

    @Test
    fun rejectsRedirectsWithoutFollowingThem() = runBlocking {
        val resolver = resolver(code = 302, extraHeaders = mapOf("Location" to "https://127.0.0.1/private"))

        val failure = failure { resolver.resolve(videoUrl) }

        assertEquals(PublicVideoReferenceFailure.REDIRECT_NOT_ALLOWED, failure.failure)
        assertFalse(failure.message.orEmpty().contains(videoUrl))
    }

    @Test
    fun rejectsSuccessfulFullDownloadAndMalformedRangeResponses() = runBlocking {
        assertEquals(
            PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED,
            failure { resolver(code = 200).resolve(videoUrl) }.failure,
        )
        assertEquals(
            PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED,
            failure { resolver(contentRange = "bytes 0-4095/*").resolve(videoUrl) }.failure,
        )
        assertEquals(
            PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED,
            failure { resolver(contentRange = "bytes 0-8191/8192").resolve(videoUrl) }.failure,
        )
    }

    @Test
    fun mapsAccessRestrictionsOversizedFilesAndMismatchedContainersToTypedErrors() = runBlocking {
        assertEquals(
            PublicVideoReferenceFailure.ACCESS_RESTRICTED,
            failure { resolver(code = 403).resolve(videoUrl) }.failure,
        )
        assertEquals(
            PublicVideoReferenceFailure.CONTENT_TOO_LARGE,
            failure { resolver(contentRange = "bytes 0-4095/134217729").resolve(videoUrl) }.failure,
        )
        assertEquals(
            PublicVideoReferenceFailure.UNSUPPORTED_MEDIA,
            failure { resolver(bodyBytes = ByteArray(4_096)).resolve(videoUrl) }.failure,
        )
    }

    @Test
    fun rejectsResponseWhoseRequestWasRewrittenToAnotherDestination() = runBlocking {
        val resolver = resolver(
            returnedUrl = { request -> request.newBuilder().url("https://example.com/other.mp4").build() },
        )

        assertEquals(
            PublicVideoReferenceFailure.REDIRECT_NOT_ALLOWED,
            failure { resolver.resolve(videoUrl) }.failure,
        )
    }

    @Test
    fun reportsPrivateDnsAnswersAsUnsafeDestinationWithoutAttemptingConnection() = runBlocking {
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
            })
            .build()
        val failure = failure { GitHubRawVideoReferenceResolver(client).resolve(videoUrl) }

        assertEquals(PublicVideoReferenceFailure.UNSAFE_DESTINATION, failure.failure)
    }

    private fun resolver(
        code: Int = 206,
        contentRange: String? = "bytes 0-4095/8192",
        bodyBytes: ByteArray = validMp4Probe(),
        contentType: String = "video/mp4",
        extraHeaders: Map<String, String> = emptyMap(),
        returnedUrl: (Request) -> Request = { it },
        requests: MutableList<Request> = CopyOnWriteArrayList(),
    ): PublicVideoReferenceResolver {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val responseRequest = returnedUrl(request)
            val response = Response.Builder()
                .request(responseRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test response")
                .body(bodyBytes.copyOf().toResponseBody(contentType.toMediaType()))
                .apply {
                    if (contentRange != null) header("Content-Range", contentRange)
                    extraHeaders.forEach { (name, value) -> header(name, value) }
                }
                .build()
            response
        }.build()
        return GitHubRawVideoReferenceResolver(client)
    }

    private fun validMp4Probe(): ByteArray = ByteArray(4_096).apply {
        this[4] = 'f'.code.toByte()
        this[5] = 't'.code.toByte()
        this[6] = 'y'.code.toByte()
        this[7] = 'p'.code.toByte()
        this[8] = 'i'.code.toByte()
        this[9] = 's'.code.toByte()
        this[10] = 'o'.code.toByte()
        this[11] = 'm'.code.toByte()
    }

    private suspend fun failure(block: suspend () -> ResolvedPublicVideoReference): PublicVideoReferenceException =
        try {
            block()
            throw AssertionError("Expected a typed public-video failure")
        } catch (expected: PublicVideoReferenceException) {
            expected
        }
}
