package com.genned.app.data.detection.metadata

import androidx.exifinterface.media.ExifInterface
import com.genned.domain.model.AnalysisInput
import com.genned.domain.model.DetectionSignal
import com.genned.domain.model.SignalAvailability
import com.genned.domain.model.SignalType
import com.genned.domain.provider.DetectionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans EXIF (Software/UserComment/ImageDescription/Artist) and, for PNG, tEXt/iTXt
 * chunks for a real, known generator signature (see [GeneratorSignatures]). A match
 * is concrete evidence — the field and the matched signature are preserved as
 * `evidence`. No match means nothing was found; it is never presented as evidence
 * of a human origin, since this metadata is trivially stripped by re-saving or
 * sharing an image.
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
                description = match.description,
                evidence = match.evidence,
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

    private data class MetadataMatch(val evidence: String, val description: String)

    private fun findExifMatch(file: File): MetadataMatch? {
        val exif = try {
            ExifInterface(file)
        } catch (e: Exception) {
            return null
        }
        GeneratorSignatures.matchSoftware(exif.getAttribute(ExifInterface.TAG_SOFTWARE))?.let {
            return softwareMatch("EXIF Software", it)
        }
        val freeTextFields = listOf(
            "EXIF UserComment" to ExifInterface.TAG_USER_COMMENT,
            "EXIF ImageDescription" to ExifInterface.TAG_IMAGE_DESCRIPTION,
            "EXIF Artist" to ExifInterface.TAG_ARTIST,
        )
        for ((field, tag) in freeTextFields) {
            GeneratorSignatures.matchStructuredParameters(exif.getAttribute(tag))?.let {
                return parametersMatch(field, it)
            }
        }
        return null
    }

    private fun findPngMatch(file: File): MetadataMatch? {
        if (!file.name.endsWith(".png", ignoreCase = true) && !looksLikePng(file)) return null
        val chunks = PngChunkReader.readTextChunks(file)
        for (chunk in chunks) {
            val field = "PNG \"${chunk.keyword}\" chunk"
            if (chunk.keyword.equals(GeneratorSignatures.SD_PARAMETERS_CHUNK_KEYWORD, ignoreCase = true)) {
                return MetadataMatch(
                    evidence = "PNG \"parameters\" chunk (Stable Diffusion–family generation metadata)",
                    description = "Image metadata contains a PNG \"parameters\" text chunk, where " +
                        "Stable Diffusion–family tools store generation settings.",
                )
            }
            val match = if (chunk.keyword.equals("Software", ignoreCase = true)) {
                GeneratorSignatures.matchSoftware(chunk.value)?.let { softwareMatch(field, it) }
            } else {
                GeneratorSignatures.matchStructuredParameters(chunk.value, chunk.keyword)
                    ?.let { parametersMatch(field, it) }
            }
            if (match != null) return match
        }
        return null
    }

    private fun softwareMatch(field: String, tool: String) = MetadataMatch(
        evidence = "$field: $tool",
        description = "Image metadata ($field) names an AI image-generation tool.",
    )

    private fun parametersMatch(field: String, label: String) = MetadataMatch(
        evidence = "$field: $label",
        description = "Image metadata ($field) contains AI image-generation parameters.",
    )

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
