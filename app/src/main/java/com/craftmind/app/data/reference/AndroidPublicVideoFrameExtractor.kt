package com.craftmind.app.data.reference

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.craftmind.app.domain.ai.AiImageInput
import com.craftmind.app.domain.reference.ExtractedPublicVideoFrames
import com.craftmind.app.domain.reference.PublicVideoFrame
import com.craftmind.app.domain.reference.PublicVideoFrameExtractor
import com.craftmind.app.domain.reference.PublicVideoFrameSamplingPolicy
import com.craftmind.app.domain.reference.PublicVideoReferenceException
import com.craftmind.app.domain.reference.PublicVideoReferenceFailure
import com.craftmind.app.domain.reference.PublicVideoReferenceLimits
import com.craftmind.app.domain.reference.ResolvedPublicVideoReference
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

/** Bounded Android decoder: random-access ranges, at most five deterministic frames, RAM-only output. */
class AndroidPublicVideoFrameExtractor(
    client: okhttp3.OkHttpClient = PublicVideoHttpClient.create(),
) : PublicVideoFrameExtractor {
    private val client = PublicVideoHttpClient.restrictedClient(client)

    override suspend fun extract(reference: ResolvedPublicVideoReference): ExtractedPublicVideoFrames =
        suspendCancellableCoroutine { continuation ->
            val dataSource = BoundedRemoteVideoDataSource(reference, client)
            val stagedImages = ConcurrentLinkedQueue<AiImageInput>()
            val stopRequested = AtomicBoolean(false)
            val timeoutTask: ScheduledFuture<*> = EXTRACTION_TIMEOUT_EXECUTOR.schedule({
                val failure = dataSource.failureOrNull() ?: PublicVideoReferenceException(PublicVideoReferenceFailure.TIMEOUT)
                dataSource.failWith(failure)
                stopRequested.set(true)
                resumeFailure(continuation, failure)
                closeStagedImages(stagedImages)
                dataSource.close()
            }, PublicVideoReferenceLimits.MAX_EXTRACTION_TIME_MILLIS, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation {
                stopRequested.set(true)
                timeoutTask.cancel(false)
                dataSource.cancel()
                closeStagedImages(stagedImages)
                dataSource.close()
            }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                if (!continuation.isActive) {
                    timeoutTask.cancel(false)
                    stopRequested.set(true)
                    closeStagedImages(stagedImages)
                    dataSource.close()
                    return@Runnable
                }
                val result = try {
                    extractBlocking(reference, dataSource, stagedImages, stopRequested)
                } catch (error: PublicVideoReferenceException) {
                    resumeFailure(continuation, error)
                    null
                } catch (error: CancellationException) {
                    if (continuation.isActive) continuation.cancel(error)
                    null
                } catch (_: OutOfMemoryError) {
                    resumeFailure(
                        continuation,
                        PublicVideoReferenceException(PublicVideoReferenceFailure.FRAME_EXTRACTION_FAILED),
                    )
                    null
                } catch (_: Exception) {
                    val sourceFailure = dataSource.failureOrNull()
                        ?: PublicVideoReferenceException(PublicVideoReferenceFailure.FRAME_EXTRACTION_FAILED)
                    resumeFailure(continuation, sourceFailure)
                    null
                } finally {
                    timeoutTask.cancel(false)
                    dataSource.close()
                }
                if (result != null) {
                    stagedImages.clear()
                    try {
                        if (continuation.isActive) {
                            continuation.resume(result) { _, abandoned, _ -> abandoned.close() }
                        } else {
                            result.close()
                        }
                    } catch (_: IllegalStateException) {
                        result.close()
                    }
                } else {
                    closeStagedImages(stagedImages)
                }
            })
        }

    private fun extractBlocking(
        reference: ResolvedPublicVideoReference,
        dataSource: BoundedRemoteVideoDataSource,
        stagedImages: ConcurrentLinkedQueue<AiImageInput>,
        stopRequested: AtomicBoolean,
    ): ExtractedPublicVideoFrames {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<PublicVideoFrame>()
        val hashes = mutableListOf<ByteArray>()
        var succeeded = false
        try {
            checkActive(dataSource)
            retriever.setDataSource(dataSource)
            dataSource.failureOrNull()?.let { throw it }

            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw PublicVideoReferenceException(PublicVideoReferenceFailure.FRAME_EXTRACTION_FAILED)
            if (duration !in PublicVideoReferenceLimits.MIN_VIDEO_DURATION_MS..PublicVideoReferenceLimits.MAX_VIDEO_DURATION_MS) {
                throw PublicVideoReferenceException(PublicVideoReferenceFailure.VIDEO_TOO_LONG)
            }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: throw PublicVideoReferenceException(PublicVideoReferenceFailure.FRAME_EXTRACTION_FAILED)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: throw PublicVideoReferenceException(PublicVideoReferenceFailure.FRAME_EXTRACTION_FAILED)
            if (width !in 1..PublicVideoReferenceLimits.MAX_VIDEO_WIDTH ||
                height !in 1..PublicVideoReferenceLimits.MAX_VIDEO_HEIGHT ||
                width.toLong() * height > PublicVideoReferenceLimits.MAX_VIDEO_PIXELS
            ) {
                throw PublicVideoReferenceException(PublicVideoReferenceFailure.FRAME_EXTRACTION_FAILED)
            }
            if (reference.contentLengthBytes > PublicVideoReferenceLimits.MAX_VIDEO_FILE_BYTES) {
                throw PublicVideoReferenceException(PublicVideoReferenceFailure.CONTENT_TOO_LARGE)
            }

            PublicVideoFrameSamplingPolicy.timestamps(duration).forEach { timestamp ->
                checkActive(dataSource)
                val bitmap = try {
                    retriever.getFrameAtTime(
                        timestamp * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                } catch (_: RuntimeException) {
                    dataSource.failureOrNull()?.let { throw it }
                    null
                }
                dataSource.failureOrNull()?.let { bitmap?.recycle(); throw it }
                if (bitmap != null) {
                    try {
                        if (bitmap.width <= 0 || bitmap.height <= 0 ||
                            bitmap.width > PublicVideoReferenceLimits.MAX_VIDEO_WIDTH ||
                            bitmap.height > PublicVideoReferenceLimits.MAX_VIDEO_HEIGHT ||
                            bitmap.width.toLong() * bitmap.height > PublicVideoReferenceLimits.MAX_VIDEO_PIXELS
                        ) {
                            throw PublicVideoReferenceException(PublicVideoReferenceFailure.FRAME_EXTRACTION_FAILED)
                        }
                        val image = encodeFrame(bitmap)
                        stagedImages += image
                        if (stopRequested.get() || dataSource.failureOrNull() != null) {
                            stagedImages.remove(image)
                            image.close()
                            checkActive(dataSource)
                            throw CancellationException("Video frame extraction cancelled")
                        }
                        val hash = image.useBytes { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes) }
                        val duplicate = hashes.any { it.contentEquals(hash) }
                        if (duplicate) {
                            hash.fill(0)
                            stagedImages.remove(image)
                            image.close()
                        } else {
                            hashes += hash
                            frames += PublicVideoFrame(timestampMillis = timestamp, image = image)
                        }
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
                dataSource.failureOrNull()?.let { throw it }
            }
            checkActive(dataSource)
            if (frames.size < PublicVideoReferenceLimits.MIN_FRAME_COUNT) {
                throw PublicVideoReferenceException(PublicVideoReferenceFailure.NO_DISTINCT_FRAMES)
            }
            val extracted = ExtractedPublicVideoFrames(
                durationMillis = duration,
                videoWidth = width,
                videoHeight = height,
                frames = frames.toList(),
            )
            succeeded = true
            return extracted
        } catch (error: PublicVideoReferenceException) {
            throw error
        } catch (error: InterruptedIOException) {
            dataSource.failureOrNull()?.let { throw it }
            if (dataSource.isCancelled()) throw CancellationException("Video frame extraction cancelled", error)
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.TIMEOUT)
        } catch (_: RuntimeException) {
            dataSource.failureOrNull()?.let { throw it }
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.FRAME_EXTRACTION_FAILED)
        } finally {
            try {
                retriever.release()
            } catch (_: RuntimeException) {
                // Native release failures do not change the already typed analysis outcome.
            }
            hashes.forEach { it.fill(0) }
            if (!succeeded) frames.forEach { it.image.close() }
        }
    }

    private fun checkActive(dataSource: BoundedRemoteVideoDataSource) {
        dataSource.failureOrNull()?.let { throw it }
        if (dataSource.isCancelled()) throw CancellationException("Video frame extraction cancelled")
    }

    private fun closeStagedImages(stagedImages: ConcurrentLinkedQueue<AiImageInput>) {
        while (true) {
            val image = stagedImages.poll() ?: return
            image.close()
        }
    }

    private fun encodeFrame(source: Bitmap): AiImageInput {
        val scale = minOf(
            1f,
            PublicVideoReferenceLimits.MAX_FRAME_DIMENSION.toFloat() / source.width,
            PublicVideoReferenceLimits.MAX_FRAME_DIMENSION.toFloat() / source.height,
            kotlin.math.sqrt(
                PublicVideoReferenceLimits.MAX_FRAME_PIXELS.toDouble() /
                    (source.width.toLong() * source.height),
            ).toFloat(),
        )
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        var bitmap = if (width == source.width && height == source.height) source else
            Bitmap.createScaledBitmap(source, width, height, true)
        try {
            val qualities = intArrayOf(82, 70, 58)
            var resizeAttempt = 0
            while (resizeAttempt < MAX_RESIZE_ATTEMPTS) {
                for (quality in qualities) {
                    val output = BoundedWipingByteArrayOutputStream(PublicVideoReferenceLimits.MAX_FRAME_IMAGE_BYTES)
                    val compressed = try {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    } catch (_: RuntimeException) {
                        false
                    } catch (_: IOException) {
                        false
                    }
                    if (compressed && output.size() in 1..PublicVideoReferenceLimits.MAX_FRAME_IMAGE_BYTES) {
                        val encoded = output.takeBytes()
                        try {
                            return AiImageInput("image/jpeg", bitmap.width, bitmap.height, encoded)
                        } finally {
                            encoded.fill(0)
                            output.wipe()
                        }
                    }
                    output.wipe()
                }
                if (resizeAttempt == MAX_RESIZE_ATTEMPTS - 1 ||
                    maxOf(bitmap.width, bitmap.height) <= MIN_FRAME_DIMENSION
                ) break
                val nextWidth = (bitmap.width * FRAME_RESIZE_FACTOR).toInt().coerceAtLeast(1)
                val nextHeight = (bitmap.height * FRAME_RESIZE_FACTOR).toInt().coerceAtLeast(1)
                val smaller = Bitmap.createScaledBitmap(bitmap, nextWidth, nextHeight, true)
                if (smaller !== bitmap) {
                    if (bitmap !== source && !bitmap.isRecycled) bitmap.recycle()
                    bitmap = smaller
                }
                resizeAttempt++
            }
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.FRAME_EXTRACTION_FAILED)
        } finally {
            if (bitmap !== source && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun resumeFailure(
        continuation: CancellableContinuation<ExtractedPublicVideoFrames>,
        error: PublicVideoReferenceException,
    ) {
        if (!continuation.isActive) return
        try {
            continuation.resumeWith(Result.failure(error))
        } catch (_: IllegalStateException) {
            // Cancellation or another terminal result won the race.
        }
    }

    private class BoundedWipingByteArrayOutputStream(
        private val maxBytes: Int,
    ) : ByteArrayOutputStream(minOf(maxBytes, 8 * 1024)) {
        override fun write(value: Int) {
            if (count >= maxBytes) throw IOException("Frame exceeds limit")
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length < 0 || count + length > maxBytes) throw IOException("Frame exceeds limit")
            super.write(bytes, offset, length)
        }

        fun takeBytes(): ByteArray = toByteArray()

        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private companion object {
        const val MAX_RESIZE_ATTEMPTS = 4
        const val MIN_FRAME_DIMENSION = 256
        const val FRAME_RESIZE_FACTOR = 0.75f
        val EXTRACTION_TIMEOUT_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "CraftMind-video-extraction-timeout").apply { isDaemon = true }
        }
    }
}
