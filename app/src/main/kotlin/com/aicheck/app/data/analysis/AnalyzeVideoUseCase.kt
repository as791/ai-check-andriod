package com.aicheck.app.data.analysis

import android.net.Uri
import com.aicheck.app.data.storage.HistoryRepository
import com.aicheck.app.data.video.SampledFrame
import com.aicheck.app.data.video.VideoFrameSampler
import com.aicheck.domain.evidence.EvidenceEngine
import com.aicheck.domain.model.AnalysisInput
import com.aicheck.domain.model.AnalysisResult
import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalType
import com.aicheck.domain.provider.DetectionProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Video support: sample a handful of still frames (see [VideoFrameSampler]) and run
 * the *same* on-device image classifier used for photos on each one, then combine
 * the per-frame scores ([VideoSignalAggregator]) into one signal fed through the
 * same [EvidenceEngine] as the image path.
 *
 * This is deliberately labeled and limited to what it actually is: frame-sampled
 * still-image classification, not video, audio, or motion/temporal analysis — no
 * video-specific model exists here or is implied. Metadata/provenance inspection
 * (EXIF, generator signatures, Content Credentials) is not implemented for video
 * in this version; that is stated explicitly in the result's signals and
 * limitations rather than silently skipped.
 */
class AnalyzeVideoUseCase(
    private val frameSampler: VideoFrameSampler,
    private val classifierProvider: DetectionProvider,
    private val watermarkProvider: DetectionProvider,
    private val evidenceEngine: EvidenceEngine,
    private val historyRepository: HistoryRepository,
) {
    suspend fun run(
        videoUri: Uri,
        onPreview: suspend (String) -> Unit,
        onStage: suspend (AnalysisStage) -> Unit,
    ): Pair<Long, AnalysisResult> {
        onStage(AnalysisStage.SAMPLING_FRAMES)
        val frames = frameSampler.sampleFrames(videoUri)
        val middleFrame = frames[frames.size / 2]
        onPreview(middleFrame.file.absolutePath)

        onStage(AnalysisStage.VISUAL)
        val perFrameSignals = coroutineScope {
            frames.map { frame -> async { analyzeFrame(frame) } }.awaitAll()
        }
        val classifierSignal = VideoSignalAggregator.aggregateFrameSignals(perFrameSignals)
        val watermarkSignal = runCatching { watermarkProvider.analyze(frameInput(middleFrame)) }
            .getOrElse { DetectionSignal.error(watermarkProvider.signalType, "This detector failed unexpectedly.") }

        val signals = listOf(
            classifierSignal,
            watermarkSignal,
            DetectionSignal.unavailable(
                SignalType.GENERATOR_METADATA,
                "Metadata inspection is not yet implemented for video files.",
            ),
            DetectionSignal.unavailable(
                SignalType.EXIF_METADATA,
                "Metadata inspection is not yet implemented for video files.",
            ),
            DetectionSignal.unavailable(
                SignalType.CONTENT_CREDENTIALS,
                "Content Credentials checking is not enabled in this build.",
            ),
        )

        val baseResult = evidenceEngine.aggregate(signals)
        val result = baseResult.copy(limitations = baseResult.limitations + videoLimitation(frames.size))

        val analysisId = historyRepository.save(result, middleFrame.file)
        frames.forEach { it.file.delete() }

        return analysisId to result
    }

    private suspend fun analyzeFrame(frame: SampledFrame): DetectionSignal =
        runCatching { classifierProvider.analyze(frameInput(frame)) }
            .getOrElse { DetectionSignal.error(classifierProvider.signalType, "This detector failed unexpectedly.") }

    private fun frameInput(frame: SampledFrame) = AnalysisInput(
        originalFilePath = frame.file.absolutePath,
        normalizedFilePath = frame.file.absolutePath,
        originalMimeType = "image/jpeg",
        widthPx = frame.widthPx,
        heightPx = frame.heightPx,
        fileSizeBytes = frame.file.length(),
    )

    private fun videoLimitation(frameCount: Int): String =
        "This is a video: the estimate above is based on classifying $frameCount sampled frames as " +
            "still images, not the full video, audio, or motion/temporal analysis. Frame-level " +
            "classification may miss video-specific manipulation."
}
