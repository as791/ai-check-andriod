package com.aicheck.app.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Stores a small history-row thumbnail per analysis in app-private internal storage —
 * never the full-resolution image (see docs/PRIVACY.md). Thumbnails live outside the
 * FileProvider-exposed cache dir, so they are not shareable by construction.
 */
class ThumbnailStore(context: Context) {
    private val dir = File(context.filesDir, "thumbnails").apply { mkdirs() }

    suspend fun save(sourceFile: File): String? = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return@withContext null
        val scale = MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }

        val outFile = File(dir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(outFile).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 80, out) }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        outFile.absolutePath
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        File(path).delete()
        Unit
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    companion object {
        private const val MAX_DIMENSION = 256
    }
}
