package com.craftmind.app.domain.reference

import com.craftmind.app.domain.ai.AiImageInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractedPublicVideoFramesTest {
    @Test
    fun snapshotsBoundedFrameListAndZeroesImageBytesWhenClosed() {
        val first = AiImageInput("image/jpeg", 2, 2, byteArrayOf(1, 2, 3))
        val second = AiImageInput("image/jpeg", 2, 2, byteArrayOf(4, 5, 6))
        val mutableFrames = mutableListOf(
            PublicVideoFrame(1_000L, first),
            PublicVideoFrame(9_500L, second),
        )
        val extracted = ExtractedPublicVideoFrames(
            durationMillis = 10_000L,
            videoWidth = 640,
            videoHeight = 360,
            frames = mutableFrames,
        )
        mutableFrames.clear()
        assertEquals(2, extracted.frames.size)

        extracted.close()
        assertTrue(runCatching { first.useBytes { it.size } }.isFailure)
        assertTrue(runCatching { second.useBytes { it.size } }.isFailure)
    }

    @Test
    fun rejectsTooFewFramesDuplicateOrOutOfRangeTimestampsAndUnsafeDimensions() {
        val onlyFrame = AiImageInput("image/jpeg", 2, 2, byteArrayOf(1))
        assertTrue(
            runCatching {
                ExtractedPublicVideoFrames(
                    durationMillis = 10_000L,
                    videoWidth = 640,
                    videoHeight = 360,
                    frames = listOf(PublicVideoFrame(1_000L, onlyFrame)),
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )

        val first = AiImageInput("image/jpeg", 2, 2, byteArrayOf(1))
        val second = AiImageInput("image/jpeg", 2, 2, byteArrayOf(2))
        val duplicateTimestampRejected = runCatching {
            ExtractedPublicVideoFrames(
                durationMillis = 10_000L,
                videoWidth = 640,
                videoHeight = 360,
                frames = listOf(PublicVideoFrame(1_000L, first), PublicVideoFrame(1_000L, second)),
            )
        }.exceptionOrNull()
        assertTrue(duplicateTimestampRejected is IllegalArgumentException)

        val outsideDurationRejected = runCatching {
            ExtractedPublicVideoFrames(
                durationMillis = 10_000L,
                videoWidth = 640,
                videoHeight = 360,
                frames = listOf(PublicVideoFrame(1_000L, first), PublicVideoFrame(10_000L, second)),
            )
        }.exceptionOrNull()
        assertTrue(outsideDurationRejected is IllegalArgumentException)
        assertTrue(
            runCatching {
                ExtractedPublicVideoFrames(
                    durationMillis = 10_000L,
                    videoWidth = PublicVideoReferenceLimits.MAX_VIDEO_WIDTH + 1,
                    videoHeight = 360,
                    frames = listOf(PublicVideoFrame(1_000L, first), PublicVideoFrame(9_000L, second)),
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )

        onlyFrame.close()
        first.close()
        second.close()
    }
}
