package com.craftmind.app.domain.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicVideoReferenceUrlPolicyTest {
    @Test
    fun acceptsOnlyDirectRawGitHubMp4AndWebmFiles() {
        val mp4 = PublicVideoReferenceUrlPolicy.validate(
            "https://raw.githubusercontent.com/owner/repo/main/assets/video.mp4",
        ) as PublicVideoUrlValidation.Valid
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/assets/video.mp4", mp4.value.canonicalUrl)
        assertEquals("raw.githubusercontent.com", mp4.value.sourceDomain)
        assertEquals("video/mp4", mp4.value.mediaType)

        val webm = PublicVideoReferenceUrlPolicy.validate(
            "https://raw.githubusercontent.com/owner/repo/main/video.WEBM",
        ) as PublicVideoUrlValidation.Valid
        assertEquals("video/webm", webm.value.mediaType)
    }

    @Test
    fun rejectsCredentialsQueriesFragmentsNonHttpsPortsAndOtherHosts() {
        val rejected = listOf(
            "http://raw.githubusercontent.com/owner/repo/main/video.mp4",
            "https://user:secret@raw.githubusercontent.com/owner/repo/main/video.mp4",
            "https://raw.githubusercontent.com:8443/owner/repo/main/video.mp4",
            "https://raw.githubusercontent.com/owner/repo/main/video.mp4?token=secret",
            "https://raw.githubusercontent.com/owner/repo/main/video.mp4#frame",
            "https://localhost/owner/repo/main/video.mp4",
            "https://127.0.0.1/owner/repo/main/video.mp4",
            "https://example.com/owner/repo/main/video.mp4",
        )

        rejected.forEach { value ->
            assertTrue("Expected rejection for a URL that must not be fetched", PublicVideoReferenceUrlPolicy.validate(value) is PublicVideoUrlValidation.Invalid)
        }
    }

    @Test
    fun rejectsPagesUnsupportedExtensionsAndAmbiguousOrEncodedPaths() {
        val rejected = listOf(
            "https://raw.githubusercontent.com/owner/repo/main/index.html",
            "https://raw.githubusercontent.com/owner/repo/main/video.m3u8",
            "https://raw.githubusercontent.com/owner/repo/main/video.mp4/",
            "https://raw.githubusercontent.com/owner/repo/main/../video.mp4",
            "https://raw.githubusercontent.com/owner/repo/main/%2e%2e/video.mp4",
            "https://raw.githubusercontent.com/owner/repo/main//video.mp4",
            "https://raw.githubusercontent.com/owner/repo/video.mp4",
        )
        rejected.forEach { value ->
            assertTrue("Expected unsupported source/path", PublicVideoReferenceUrlPolicy.validate(value) is PublicVideoUrlValidation.Invalid)
        }
    }

    @Test
    fun rejectsMalformedAndOversizedUrlsWithoutEmbeddingThemInFailure() {
        listOf(
            "not a URI",
            "https://",
            "https://raw.githubusercontent.com/owner/repo/main/video.mp4" + "a".repeat(2_100),
        ).forEach { value ->
            val result = PublicVideoReferenceUrlPolicy.validate(value)
            assertTrue(result is PublicVideoUrlValidation.Invalid)
            assertFalseFailureDoesNotExposeInput(value, result)
        }
    }

    @Test
    fun choosesFiveDeterministicPointsIncludingAnAlmostCompletedEndState() {
        assertEquals(
            listOf(1_000L, 3_500L, 6_000L, 8_000L, 9_500L),
            PublicVideoFrameSamplingPolicy.timestamps(10_000L),
        )
        assertEquals(
            listOf(200L, 700L, 1_200L, 1_600L, 1_900L),
            PublicVideoFrameSamplingPolicy.timestamps(2_000L),
        )
        assertEquals(
            listOf(18_000L, 63_000L, 108_000L, 144_000L, 171_000L),
            PublicVideoFrameSamplingPolicy.timestamps(180_000L),
        )
    }

    @Test
    fun rejectsDurationsOutsideTheBoundedIntervalWithTypedFailure() {
        listOf(1_999L, 180_001L, 0L).forEach { duration ->
            val failure = try {
                PublicVideoFrameSamplingPolicy.timestamps(duration)
                throw AssertionError("Expected a duration failure")
            } catch (expected: PublicVideoReferenceException) {
                expected
            }
            assertEquals(PublicVideoReferenceFailure.VIDEO_TOO_LONG, failure.failure)
            assertTrue(failure.message.orEmpty().contains(PublicVideoReferenceFailure.VIDEO_TOO_LONG.name))
        }
    }

    private fun assertFalseFailureDoesNotExposeInput(value: String, result: PublicVideoUrlValidation) {
        val failure = (result as PublicVideoUrlValidation.Invalid).failure
        assertTrue(failure.name.isNotBlank())
        assertEquals(false, failure.name.contains(value))
    }
}
