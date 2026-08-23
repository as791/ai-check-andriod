package com.aicheck.app.data.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Why sampling frames from a shared video failed, surfaced as honest UI states. */
sealed class VideoLoadException(message: String) : Exception(message) {
    class Unsupported : VideoLoadException("Unsupported or unreadable video")
    class TooLong : VideoLoadException("Video exceeds the supported duration for on-device analysis")
}

data class SampledFrame(val file: File, val widthPx: Int, val heightPx: Int)

/**
 * Extracts a handful of evenly-spaced still frames from a shared video so the
 * existing image classifier pipeline (see AIImageClassifierProvider) can run on
 * each one — this is frame-sampled analysis, not full video/motion/audio
 * understanding, and every result built from it says so explicitly (see
 * AnalyzeVideoUseCase). No video-specific model is used or implied.
 */
class VideoFrameSampler(private val context: Context) {

    suspend fun sampleFrames(uri: Uri): List<SampledFrame> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(context, uri)
            } catch (e: Exception) {
                throw VideoLoadException.Unsupported()
            }

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            if (durationMs == null || durationMs <= 0) {
                throw VideoLoadException.Unsupported()
            }
            if (durationMs > MAX_DURATION_MS) {
                throw VideoLoadException.TooLong()
            }

            val frames = mutableListOf<SampledFrame>()
            for (i in 0 until FRAME_COUNT) {
                // Evenly spaced across the clip, avoiding the very first/last instants
                // where a fade-in/out or a black frame is more likely.
                val fraction = (i + 1).toFloat() / (FRAME_COUNT + 1)
                val timeUs = (durationMs * 1000 * fraction).toLong()
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                frames += saveFrame(bitmap)
            }

            if (frames.isEmpty()) throw VideoLoadException.Unsupported()
            frames
        } finally {
            retriever.release()
        }
    }

    private fun saveFrame(rawBitmap: Bitmap): SampledFrame {
        val bounded = downscaleIfNeeded(rawBitmap, MAX_FRAME_DIMENSION)
        val outFile = File(sharedCacheDir(context), "frame_${UUID.randomUUID()}.jpg")
        FileOutputStream(outFile).use { out -> bounded.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        val width = bounded.width
        val height = bounded.height
        if (bounded !== rawBitmap) bounded.recycle()
        rawBitmap.recycle()
        return SampledFrame(outFile, width, height)
    }

    private fun downscaleIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        const val FRAME_COUNT = 5
        private const val MAX_FRAME_DIMENSION = 1024

        /** 3 minutes: generous headroom above typical Reels/Shorts (usually <=90s). */
        private const val MAX_DURATION_MS = 3 * 60 * 1000L

        fun sharedCacheDir(context: Context): File = File(context.cacheDir, "shared").apply { mkdirs() }
    }
}
