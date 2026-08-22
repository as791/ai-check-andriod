package com.aicheck.app.data.detection.classifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aicheck.domain.model.AnalysisInput
import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalAvailability
import com.aicheck.domain.model.SignalType
import com.aicheck.domain.provider.DetectionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * Runs the bundled on-device AI-generated-image classifier via ONNX Runtime Mobile.
 * See docs/MODEL.md for the model card (name, source, license, size, preprocessing,
 * output interpretation, known limitations) and instructions for adding the model
 * file — none is bundled in this build (see [ModelAssets]), so [analyze] honestly
 * reports [SignalAvailability.UNAVAILABLE] rather than fabricating a score.
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
                "The on-device visual classifier is not bundled in this build. See " +
                    "docs/MODEL.md for how to add ${ModelConfig.DISPLAY_NAME}.",
            )

        try {
            val bitmap = BitmapFactory.decodeFile(image.normalizedFilePath)
                ?: return@withContext DetectionSignal.error(
                    signalType,
                    "Could not decode the normalized image for classification.",
                )
            val inputBuffer = preprocess(bitmap)
            bitmap.recycle()

            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, inputBuffer, ModelConfig.INPUT_SHAPE).use { tensor ->
                ortSession.run(mapOf(ModelConfig.INPUT_NAME to tensor)).use { results ->
                    val rawOutput = results[0].value
                    val aiProbability = ModelConfig.interpretOutput(rawOutput)
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
                }
            }
        } catch (e: Exception) {
            DetectionSignal.error(signalType, "The visual classifier failed to run on this image.")
        }
    }

    private suspend fun getOrCreateSession(): OrtSession? {
        if (sessionInitAttempted) return session
        return sessionMutex.withLock {
            if (sessionInitAttempted) return@withLock session
            sessionInitAttempted = true
            session = try {
                val modelBytes = ModelAssets.openModelBytes(context) ?: return@withLock null
                OrtEnvironment.getEnvironment().createSession(modelBytes, OrtSession.SessionOptions())
            } catch (e: Exception) {
                null
            }
            session
        }
    }

    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val size = ModelConfig.INPUT_SIZE
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
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
        if (scaled !== bitmap) scaled.recycle()
        return buffer
    }
}
