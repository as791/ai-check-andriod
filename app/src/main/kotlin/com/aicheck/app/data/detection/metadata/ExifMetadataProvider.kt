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
 * A narrow, honest EXIF signal: is standard *camera capture* metadata (make, model,
 * exposure) present? Its presence is real evidence a camera was involved somewhere in
 * this file's history (weak, since it doesn't rule out later AI editing); its
 * *absence* is deliberately treated as near-zero evidence rather than a sign of AI,
 * since social apps and screenshots strip EXIF constantly. This is why its weight in
 * EvidenceWeights is the smallest of all signals.
 *
 * Generator-tool string matching is a separate, stronger signal — see
 * [KnownGeneratorMetadataProvider].
 */
class ExifMetadataProvider : DetectionProvider {
    override val signalType: SignalType = SignalType.EXIF_METADATA

    override suspend fun analyze(image: AnalysisInput): DetectionSignal = withContext(Dispatchers.IO) {
        val file = File(image.originalFilePath)
        val exif = try {
            ExifInterface(file)
        } catch (e: Exception) {
            return@withContext DetectionSignal.error(signalType, "Could not read EXIF metadata for this file.")
        }

        val cameraFields = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_DATETIME_ORIGINAL,
        )
        val presentFields = cameraFields.filter { !exif.getAttribute(it).isNullOrBlank() }

        if (presentFields.isNotEmpty()) {
            DetectionSignal(
                type = signalType,
                availability = SignalAvailability.AVAILABLE,
                score = 0f,
                confidence = 0.5f,
                description = "Camera capture metadata (${presentFields.size} field" +
                    "${if (presentFields.size == 1) "" else "s"}, e.g. make/model/exposure) is " +
                    "present. This is consistent with a camera-captured photo, but does not rule " +
                    "out later AI editing or generation.",
                evidence = presentFields.joinToString(", "),
            )
        } else {
            DetectionSignal(
                type = signalType,
                availability = SignalAvailability.AVAILABLE,
                score = 0.3f,
                confidence = 0.3f,
                description = "No camera capture metadata (make, model, exposure) was found. " +
                    "This is common for both AI-generated images and ordinary photos that were " +
                    "re-saved, screenshotted, or shared through messaging or social apps — it is " +
                    "weak evidence on its own.",
            )
        }
    }
}
