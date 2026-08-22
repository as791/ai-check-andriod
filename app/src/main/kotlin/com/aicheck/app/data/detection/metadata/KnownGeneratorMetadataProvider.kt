package com.aicheck.app.data.detection.metadata

import androidx.exifinterface.media.ExifInterface
import com.aicheck.domain.model.AnalysisInput
import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalAvailability
import com.aicheck.domain.model.SignalType
import com.aicheck.domain.provider.DetectionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans EXIF (Software/UserComment/ImageDescription) and, for PNG, tEXt/iTXt chunks
 * for a real, known generator signature (see [GeneratorSignatures]). A match is
 * concrete evidence — the literal matched string is preserved as `evidence`. No
 * match means nothing was found; it is never presented as evidence of a human origin,
 * since this metadata is trivially stripped by re-saving or sharing an image.
 */
class KnownGeneratorMetadataProvider : DetectionProvider {
    override val signalType: SignalType = SignalType.GENERATOR_METADATA

    override suspend fun analyze(image: AnalysisInput): DetectionSignal = withContext(Dispatchers.IO) {
        val file = File(image.originalFilePath)

        val exifMatch = findExifMatch(file)
        val pngMatch = if (exifMatch == null) findPngMatch(file) else null
        val match = exifMatch ?: pngMatch

        if (match != null) {
            DetectionSignal(
                type = signalType,
                availability = SignalAvailability.AVAILABLE,
                score = 1f,
                confidence = 0.9f,
                description = "Software metadata contains a reference associated with an AI " +
                    "image-generation tool.",
                evidence = match,
            )
        } else {
            DetectionSignal(
                type = signalType,
                availability = SignalAvailability.AVAILABLE,
                score = 0f,
                confidence = 0.5f,
                description = "No generator metadata found. This does not indicate that the " +
                    "image is authentic — social platforms and messaging apps frequently strip " +
                    "metadata on upload or download.",
            )
        }
    }

    private fun findExifMatch(file: File): String? {
        val exif = try {
            ExifInterface(file)
        } catch (e: Exception) {
            return null
        }
        val candidates = listOfNotNull(
            exif.getAttribute(ExifInterface.TAG_SOFTWARE),
            exif.getAttribute(ExifInterface.TAG_USER_COMMENT),
            exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION),
            exif.getAttribute(ExifInterface.TAG_ARTIST),
        )
        for (candidate in candidates) {
            GeneratorSignatures.findMatch(candidate)?.let { return it }
        }
        return null
    }

    private fun findPngMatch(file: File): String? {
        if (!file.name.endsWith(".png", ignoreCase = true) && !looksLikePng(file)) return null
        val chunks = PngChunkReader.readTextChunks(file)
        for (chunk in chunks) {
            if (chunk.keyword.equals(GeneratorSignatures.SD_PARAMETERS_CHUNK_KEYWORD, ignoreCase = true)) {
                return "PNG \"parameters\" chunk (Stable Diffusion–family generation metadata)"
            }
            GeneratorSignatures.findMatch(chunk.value)?.let { return it }
            GeneratorSignatures.findMatch(chunk.keyword)?.let { return it }
        }
        return null
    }

    private fun looksLikePng(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val header = ByteArray(8)
            val read = stream.read(header)
            read == 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte()
        }
    } catch (e: Exception) {
        false
    }
}
