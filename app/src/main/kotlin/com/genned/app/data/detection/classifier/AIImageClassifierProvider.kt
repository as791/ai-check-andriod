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
 * Runs the bundled on-device AI-generated-image classifiers via ONNX Runtime Mobile.
 * See internal-docs/MODEL.md for the model cards and the benchmarks behind every constant.
 *
 * Two models, both shipped as app assets ([ModelAssets]):
 * - the primary detector ([ModelConfig]), run on two views of the image;
 * - Community Forensics ViT-S 224 ([CommunityForensicsConfig]).
 *
 * When both load, the result is their ensemble ([EnsembleConfig]). They make different
 * mistakes (the primary catches more AI photos, Community Forensics is far stronger on
 * video and rarely flags real content), so combined they beat either alone on photos and
 * on video. If the second model is missing or fails to load, the primary runs alone with
 * its own calibration. If the primary is missing, [analyze] honestly reports
 * [SignalAvailability.UNAVAILABLE] rather than fabricating a score.
 *
 * Sessions are created lazily on first use and cached for the app's lifetime; inference
 * always runs off the main thread. Each signal carries its uncalibrated evidence in
 * [DetectionSignal.rawScore] so video frames can be combined before calibration
 * ([videoProbability]).
 */
class AIImageClassifierProvider(private val context: Context) : DetectionProvider {
    override val signalType: SignalType = SignalType.AI_CLASSIFIER

    private val sessionMutex = Mutex()
    private var primarySession: OrtSession? = null
    private var communityForensicsSession: OrtSession? = null
    private var sessionInitAttempted = false

    /** True once both models are loaded and results come from the ensemble. */
    val ensembleActive: Boolean get() = primarySession != null && communityForensicsSession != null

    override suspend fun analyze(image: AnalysisInput): DetectionSignal = withContext(Dispatchers.Default) {
        initSessions()
        val primary = primarySession
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
            // All inputs are prepared before the source bitmap is released.
            val primaryViews = listOf(
                squashed(bitmap, ModelConfig.INPUT_SIZE),
                centerCropped(bitmap, ModelConfig.INPUT_SIZE, ModelConfig.INPUT_SIZE),
            )
            val communityForensicsInput = communityForensicsSession?.let { communityForensicsView(bitmap) }
            bitmap.recycle()

            // Primary: two views, averaged in logit space - measured to beat either view alone.
            val primaryGaps = primaryViews.map { view ->
                runModel(primary, view, ModelConfig.INPUT_SIZE, ModelConfig.INPUT_SHAPE, ModelConfig.INPUT_NAME)
                    .let(ModelConfig::logitDifference)
                    .also { view.recycle() }
            }
            val communityForensicsGap = communityForensicsInput?.let { input ->
                runModel(
                    communityForensicsSession!!, input, CommunityForensicsConfig.INPUT_SIZE,
                    CommunityForensicsConfig.INPUT_SHAPE, CommunityForensicsConfig.INPUT_NAME,
                ).let(CommunityForensicsConfig::logit).also { input.recycle() }
            }
            if (primaryGaps.any { it == null } || (communityForensicsInput != null && communityForensicsGap == null)) {
                return@withContext DetectionSignal.error(signalType, "The visual classifier returned an unexpected output.")
            }
            val primaryGap = primaryGaps.filterNotNull().average()

            val (rawScore, aiProbability) = if (communityForensicsGap != null) {
                val combined = EnsembleConfig.combine(primaryGap, communityForensicsGap)
                combined to EnsembleConfig.photoProbability(combined)
            } else {
                primaryGap to ModelConfig.calibratedProbability(primaryGap)
            }
            // Only logits and derived numbers are logged - never image bytes, metadata, or
            // file names (see internal-docs/PRIVACY.md "Logging").
            Log.d(TAG, "Classifier primary gap=$primaryGap communityForensics=$communityForensicsGap -> P(ai)=$aiProbability")
            DetectionSignal(
                type = signalType,
                availability = SignalAvailability.AVAILABLE,
                score = aiProbability,
                confidence = ModelConfig.BASE_CONFIDENCE,
                description = "The on-device visual classifier estimates a " +
                    "${(aiProbability * 100).toInt()}% probability this image is " +
                    "AI-generated.",
                evidence = if (communityForensicsGap != null) EnsembleConfig.DISPLAY_NAME else ModelConfig.DISPLAY_NAME,
                rawScore = rawScore,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Classifier inference failed; reporting the signal as ERROR", e)
            DetectionSignal.error(signalType, "The visual classifier failed to run on this image.")
        }
    }

    /**
     * Video P(ai) from the mean [DetectionSignal.rawScore] of the sampled frames, calibrated
     * for video with whichever model setup produced those frames.
     */
    fun videoProbability(meanRawScore: Double): Float =
        if (ensembleActive) EnsembleConfig.videoProbability(meanRawScore)
        else ModelConfig.videoCalibratedProbability(meanRawScore)

    private suspend fun initSessions() {
        if (sessionInitAttempted) return
        sessionMutex.withLock {
            if (sessionInitAttempted) return
            sessionInitAttempted = true
            primarySession = loadSession(ModelAssets.ASSET_PATH)
            // The ensemble partner is only useful alongside the primary model.
            if (primarySession != null) communityForensicsSession = loadSession(ModelAssets.COMMUNITY_FORENSICS_ASSET_PATH)
            Log.i(TAG, "Classifier ready: primary=${primarySession != null} ensemble=$ensembleActive")
        }
    }

    private fun loadSession(assetPath: String): OrtSession? = try {
        val modelBytes = ModelAssets.openModelBytes(context, assetPath)
        if (modelBytes == null) {
            Log.i(TAG, "No bundled model asset at $assetPath")
            null
        } else {
            OrtEnvironment.getEnvironment().createSession(modelBytes, OrtSession.SessionOptions())
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create the ONNX session for $assetPath", e)
        null
    } catch (e: OutOfMemoryError) {
        // Models are read fully into memory before ORT copies them; degrade to
        // "unavailable" (or single-model) on a low-memory device rather than crashing.
        Log.e(TAG, "Out of memory loading $assetPath", e)
        null
    }

    private fun runModel(session: OrtSession, input: Bitmap, size: Int, shape: LongArray, inputName: String): Any? {
        val env = OrtEnvironment.getEnvironment()
        return OnnxTensor.createTensor(env, toInputBuffer(input, size), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val rawOutput = results[0].value
                Log.d(TAG, "Model raw output=${describe(rawOutput)}")
                rawOutput
            }
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

    /** The whole image resized to size x size, ignoring aspect ratio. */
    private fun squashed(bitmap: Bitmap, size: Int): Bitmap =
        Bitmap.createScaledBitmap(bitmap, size, size, true).let {
            // createScaledBitmap returns the source itself when no scaling is needed.
            if (it === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else it
        }

    /** Short side resized to [resizeTo], then the central [cropTo] x [cropTo] square. */
    private fun centerCropped(bitmap: Bitmap, resizeTo: Int, cropTo: Int): Bitmap {
        val scale = resizeTo.toFloat() / minOf(bitmap.width, bitmap.height)
        val width = maxOf(resizeTo, Math.round(bitmap.width * scale))
        val height = maxOf(resizeTo, Math.round(bitmap.height * scale))
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val cropped = Bitmap.createBitmap(resized, (width - cropTo) / 2, (height - cropTo) / 2, cropTo, cropTo)
        if (resized !== bitmap && resized !== cropped) resized.recycle()
        return if (cropped === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else cropped
    }

    /**
     * Community Forensics input with the authors' exact torchvision geometry: short side to
     * 256 with the long side truncated (Resize), then a 224 center crop with offsets rounded
     * half-to-even (CenterCrop). Off-by-one pixels move this ViT's logit noticeably, so this
     * mirrors tools/evaluate.py commfor_input, which is checked pixel-exact against torchvision.
     */
    private fun communityForensicsView(bitmap: Bitmap): Bitmap {
        val resize = CommunityForensicsConfig.RESIZE_SHORT_SIDE
        val crop = CommunityForensicsConfig.INPUT_SIZE
        val short = minOf(bitmap.width, bitmap.height)
        val long = maxOf(bitmap.width, bitmap.height)
        val newLong = (resize.toLong() * long / short).toInt()
        val (width, height) = if (bitmap.width <= bitmap.height) resize to newLong else newLong to resize
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val left = Math.rint((width - crop) / 2.0).toInt()
        val top = Math.rint((height - crop) / 2.0).toInt()
        val cropped = Bitmap.createBitmap(resized, left, top, crop, crop)
        if (resized !== bitmap && resized !== cropped) resized.recycle()
        return if (cropped === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else cropped
    }

    /** CHW float input with ImageNet normalization (both models use the same statistics). */
    private fun toInputBuffer(scaled: Bitmap, size: Int): FloatBuffer {
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)

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
