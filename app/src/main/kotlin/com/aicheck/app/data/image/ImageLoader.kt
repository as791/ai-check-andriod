package com.aicheck.app.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Why loading a shared/picked image failed, surfaced as distinct, honest UI states. */
sealed class ImageLoadException(message: String) : Exception(message) {
    class Unsupported : ImageLoadException("Unsupported image format")
    class Corrupt : ImageLoadException("Corrupt or unreadable image")
    class TooLarge : ImageLoadException("Image exceeds the safe decode size for this device")
}

data class NormalizedImage(
    /** Byte-exact copy of the source; EXIF/PNG chunks/C2PA data are intact. */
    val originalFile: File,
    /** Orientation-corrected, downscaled JPEG for preview and classifier input. */
    val normalizedFile: File,
    val widthPx: Int,
    val heightPx: Int,
    val originalMimeType: String?,
    val fileSizeBytes: Long,
)

/**
 * Turns whatever a share-sheet or Photo Picker `content://` URI points at into a
 * normalized, orientation-corrected JPEG in the app's private cache, bounded to a
 * sane resolution so downstream decoding (preview, classifier) never has to deal
 * with an arbitrarily huge source bitmap. Runs entirely off the main thread.
 */
class ImageLoader(private val context: Context) {

    suspend fun normalize(uri: Uri): NormalizedImage = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri)

        val originalFile = copyOriginalBytes(uri, mimeType)

        val bounds = decodeBounds(originalFile)
        // A guard against decompression-bomb-style inputs (e.g. a 1px-tall, 200000px
        // -wide PNG) rather than a claim about "large but reasonable" photos, which
        // downsample fine via inSampleSize below.
        val megapixels = (bounds.outWidth.toLong() * bounds.outHeight.toLong()) / 1_000_000.0
        if (megapixels > 200) {
            throw ImageLoadException.TooLarge()
        }

        val sampleSize = calculateInSampleSize(bounds, MAX_DIMENSION_PX)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val rawBitmap = try {
            BitmapFactory.decodeFile(originalFile.absolutePath, decodeOptions)
        } catch (io: Exception) {
            throw ImageLoadException.Corrupt()
        } ?: throw ImageLoadException.Corrupt()

        val orientedBitmap = applyExifOrientation(originalFile, rawBitmap)
        val boundedBitmap = downscaleIfNeeded(orientedBitmap, MAX_DIMENSION_PX)

        val outFile = File(sharedCacheDir(context), "normalized_${UUID.randomUUID()}.jpg")
        FileOutputStream(outFile).use { out ->
            boundedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        val width = boundedBitmap.width
        val height = boundedBitmap.height
        if (boundedBitmap !== orientedBitmap) orientedBitmap.recycle()
        if (orientedBitmap !== rawBitmap) rawBitmap.recycle()
        boundedBitmap.recycle()

        NormalizedImage(
            originalFile = originalFile,
            normalizedFile = outFile,
            widthPx = width,
            heightPx = height,
            originalMimeType = mimeType,
            fileSizeBytes = originalFile.length(),
        )
    }

    /**
     * Copies the source bytes verbatim before any decode/re-encode touches them, so
     * metadata and provenance providers see exactly what the sharing app sent —
     * decoding through [Bitmap] and re-compressing (as [normalize] does for preview
     * and classifier input) discards EXIF, PNG chunks, and any C2PA manifest.
     */
    private fun copyOriginalBytes(uri: Uri, mimeType: String?): File {
        val extension = when {
            mimeType?.contains("png") == true -> "png"
            mimeType?.contains("webp") == true -> "webp"
            else -> "jpg"
        }
        val originalFile = File(sharedCacheDir(context), "original_${UUID.randomUUID()}.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(originalFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var totalBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        totalBytes += read
                        if (totalBytes > MAX_SOURCE_FILE_BYTES) throw ImageLoadException.TooLarge()
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw ImageLoadException.Unsupported()
        } catch (io: IOException) {
            throw ImageLoadException.Corrupt()
        }
        return originalFile
    }

    private fun decodeBounds(file: File): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            throw ImageLoadException.Corrupt()
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw ImageLoadException.Unsupported()
        }
        return options
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, targetMaxDimension: Int): Int {
        var sampleSize = 1
        var largest = maxOf(options.outWidth, options.outHeight)
        while (largest / (sampleSize * 2) >= targetMaxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun downscaleIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largest
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    companion object {
        private const val MAX_DIMENSION_PX = 2048

        /** Original-bytes copy guard; well above any legitimate shared photo. */
        private const val MAX_SOURCE_FILE_BYTES = 75L * 1024 * 1024

        fun sharedCacheDir(context: Context): File =
            File(context.cacheDir, "shared").apply { mkdirs() }
    }
}
