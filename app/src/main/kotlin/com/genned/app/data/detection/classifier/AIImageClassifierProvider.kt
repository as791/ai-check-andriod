package com.genned.app.data.detection.classifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.genned.domain.model.AnalysisInput
import com.genned.domain.model.DetectionSignal
import com.genned.domain.model.SignalAvailability
import com.genned.domain.model.SignalType
import com.genned.domain.provider.DetectionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * Runs the bundled on-device AI-generated-image classifier via ONNX Runtime Mobile.
 * See internal-docs/MODEL.md for the model card (name, source, license, size, preprocessing,
 * output interpretation, known limitations) and instructions for replacing the model
 * file. The model ships as an app asset (see [ModelAssets]); if it is ever missing or
 * fails to load, [analyze] honestly reports [SignalAvailability.UNAVAILABLE] rather
 * than fabricating a score.
 *
 * The ONNX session is created lazily on first use and cached for the app's lifetime;
 * inference always runs off the main thread.
 */
class AIImageClassifierProvider(private val context: Context) : DetectionProvider {
    override val signalType: SignalType = SignalType.AI_CLASSIFIER

    private val sessionMutex = Mutex()
    private var session: OrtSession? = null
    private var sessionInitAttempted = false

    override suspend fun analyze(image: AnalysisInput): DetectionSignal = withContext(Dispatchers.Default) {
        val ortSession = getOrCreateSession()
            ?: return@withContext DetectionSignal.unavailable(
                signalType,
                "The on-device visual classifier is not bundled in this build.",
            )

        try {
            val bitmap = BitmapFactory.decodeFile(image.normalizedFilePath)
                ?: return@withContext DetectionSignal.error(
                    signalType,
                    "Could not decode the normalized image for classification.",
                )
            // Two views of the same image - the whole frame squashed to a square, and
            // an aspect-preserving center crop - averaged in logit space. Measured to
            // beat either view alone on both benchmark datasets (internal-docs/MODEL.md).
            val views = listOf(squashed(bitmap), centerCropped(bitmap))
            bitmap.recycle()

            val env = OrtEnvironment.getEnvironment()
            val logitDifferences = views.map { view ->
                OnnxTensor.createTensor(env, toInputBuffer(view), ModelConfig.INPUT_SHAPE).use { tensor ->
                    ortSession.run(mapOf(ModelConfig.INPUT_NAME to tensor)).use { results ->
                        val rawOutput = results[0].value
                        // Only the output logits and derived numbers are logged - never
                        // image bytes, metadata, or file names (see internal-docs/PRIVACY.md
                        // "Logging"). This keeps odd results diagnosable from logcat.
                        Log.d(TAG, "Classifier raw output=${describe(rawOutput)}")
                        ModelConfig.logitDifference(rawOutput)
                    }
                }.also { view.recycle() }
            }
            if (logitDifferences.any { it == null }) {
                return@withContext DetectionSignal.error(signalType, "The visual classifier returned an unexpected output.")
            }
            val meanDifference = logitDifferences.filterNotNull().average()
            val aiProbability = ModelConfig.calibratedProbability(meanDifference)
            Log.d(TAG, "Classifier mean logit gap=$meanDifference -> calibrated P(ai)=$aiProbability")
            DetectionSignal(
                type = signalType,
                availability = SignalAvailability.AVAILABLE,
                score = aiProbability,
                confidence = ModelConfig.BASE_CONFIDENCE,
                description = "The on-device visual classifier estimates a " +
                    "${(aiProbability * 100).toInt()}% probability this image is " +
                    "AI-generated.",
                evidence = ModelConfig.DISPLAY_NAME,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Classifier inference failed; reporting the signal as ERROR", e)
            DetectionSignal.error(signalType, "The visual classifier failed to run on this image.")
        }
    }

    private suspend fun getOrCreateSession(): OrtSession? {
        if (sessionInitAttempted) return session
        return sessionMutex.withLock {
            if (sessionInitAttempted) return@withLock session
            sessionInitAttempted = true
            session = try {
                val modelBytes = ModelAssets.openModelBytes(context)
                if (modelBytes == null) {
                    Log.i(TAG, "No bundled classifier asset at ${ModelAssets.ASSET_PATH}; classifier unavailable")
                    return@withLock null
                }
                OrtEnvironment.getEnvironment().createSession(modelBytes, OrtSession.SessionOptions())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create the ONNX session for the bundled classifier; classifier unavailable", e)
                null
            } catch (e: OutOfMemoryError) {
                // A ~70MB model is read fully into memory before ORT copies it; degrade
                // to "unavailable" on a low-memory device rather than crashing the app.
                Log.e(TAG, "Out of memory loading the bundled classifier; classifier unavailable", e)
                null
            }
            session
        }
    }

    private fun describe(rawOutput: Any?): String = when (rawOutput) {
        is Array<*> -> rawOutput.contentDeepToString()
        is FloatArray -> rawOutput.contentToString()
        else -> rawOutput.toString()
    }

    private companion object {
        const val TAG = "AIImageClassifier"
    }

    /** The whole image resized to INPUT_SIZE x INPUT_SIZE, ignoring aspect ratio. */
    private fun squashed(bitmap: Bitmap): Bitmap {
        val size = ModelConfig.INPUT_SIZE
        return Bitmap.createScaledBitmap(bitmap, size, size, true).let {
            // createScaledBitmap returns the source itself when no scaling is needed.
            if (it === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else it
        }
    }

    /** Short side resized to INPUT_SIZE, then the central INPUT_SIZE square. */
    private fun centerCropped(bitmap: Bitmap): Bitmap {
        val size = ModelConfig.INPUT_SIZE
        val scale = size.toFloat() / minOf(bitmap.width, bitmap.height)
        val width = maxOf(size, Math.round(bitmap.width * scale))
        val height = maxOf(size, Math.round(bitmap.height * scale))
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val cropped = Bitmap.createBitmap(resized, (width - size) / 2, (height - size) / 2, size, size)
        if (resized !== bitmap && resized !== cropped) resized.recycle()
        return if (cropped === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else cropped
    }

    private fun toInputBuffer(scaled: Bitmap): FloatBuffer {
        val size = ModelConfig.INPUT_SIZE
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)

        // CHW layout with ImageNet normalization — see ModelConfig doc: verify
        // against the real bundled model before trusting scores.
        val channelSize = size * size
        val buffer = FloatBuffer.allocate(3 * channelSize)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            buffer.put(i, (r - ModelConfig.MEAN[0]) / ModelConfig.STD[0])
            buffer.put(channelSize + i, (g - ModelConfig.MEAN[1]) / ModelConfig.STD[1])
            buffer.put(2 * channelSize + i, (b - ModelConfig.MEAN[2]) / ModelConfig.STD[2])
        }
        return buffer
    }
}
