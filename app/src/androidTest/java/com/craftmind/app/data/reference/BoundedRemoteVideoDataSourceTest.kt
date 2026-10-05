package com.craftmind.app.data.reference

import com.craftmind.app.domain.reference.PublicVideoReferenceFailure
import com.craftmind.app.domain.reference.ResolvedPublicVideoReference
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedRemoteVideoDataSourceTest {
    private val sourceUrl = "https://raw.githubusercontent.com/owner/repo/main/video.mp4"
    private val sourceBytes = ByteArray(2 * 64 * 1024) { (it % 251).toByte() }
    private val reference = ResolvedPublicVideoReference(
        canonicalUrl = sourceUrl,
        sourceDomain = "raw.githubusercontent.com",
        mediaType = "video/mp4",
        contentLengthBytes = sourceBytes.size.toLong(),
    )

    @Test
    fun readsOnlyRequested64KiBBlocksAndCachesNearbyReads() {
        val requests = CopyOnWriteArrayList<Request>()
        val dataSource = BoundedRemoteVideoDataSource(reference, rangeClient(requests))
        try {
            assertEquals(sourceBytes.size.toLong(), dataSource.getSize())
            val first = ByteArray(32)
            assertEquals(first.size, dataSource.readAt(10L, first, 0, first.size))
            assertArrayEquals(sourceBytes.copyOfRange(10, 42), first)
            val cached = ByteArray(8)
            assertEquals(cached.size, dataSource.readAt(100L, cached, 0, cached.size))
            assertArrayEquals(sourceBytes.copyOfRange(100, 108), cached)
            assertEquals(1, requests.size)
            assertEquals("bytes=0-65535", requests.single().header("Range"))

            val acrossBoundary = ByteArray(16)
            assertEquals(acrossBoundary.size, dataSource.readAt(65_530L, acrossBoundary, 0, acrossBoundary.size))
            assertArrayEquals(sourceBytes.copyOfRange(65_530, 65_546), acrossBoundary)
            assertEquals(2, requests.size)
            assertEquals("bytes=65536-131071", requests.last().header("Range"))
            assertTrue(requests.all { it.url.host == "raw.githubusercontent.com" && it.url.scheme == "https" })
        } finally {
            dataSource.close()
        }

        val cancelledRead = runCatching { dataSource.readAt(0L, ByteArray(1), 0, 1) }.exceptionOrNull()
        assertTrue(cancelledRead is InterruptedIOException)
    }

    @Test
    fun refusesAFull200ResponseInsteadOfDownloadingTheWholeSource() {
        val dataSource = BoundedRemoteVideoDataSource(
            reference,
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("full response")
                    .body(sourceBytes.copyOf().toResponseBody("video/mp4".toMediaType()))
                    .build()
            }.build(),
        )
        try {
            val failure = runCatching { dataSource.readAt(0L, ByteArray(8), 0, 8) }.exceptionOrNull()
            assertEquals(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED, dataSource.failureOrNull()?.failure)
            assertTrue(failure is java.io.IOException)
        } finally {
            dataSource.close()
            sourceBytes.fill(0)
        }
    }

    private fun rangeClient(requests: MutableList<Request>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val bounds = RANGE.matchEntire(request.header("Range").orEmpty())
            ?: throw AssertionError("A byte-range header is required")
        val start = bounds.groupValues[1].toInt()
        val end = bounds.groupValues[2].toInt()
        val responseBytes = sourceBytes.copyOfRange(start, end + 1)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(206)
            .message("partial content")
            .header("Content-Range", "bytes $start-$end/${sourceBytes.size}")
            .header("Content-Encoding", "identity")
            .body(responseBytes.toResponseBody("video/mp4".toMediaType()))
            .build()
    }.build()

    private companion object {
        val RANGE = Regex("bytes=(\\d+)-(\\d+)")
    }
}
