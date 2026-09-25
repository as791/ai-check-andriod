package com.genned.app.data.analysis

import com.genned.app.data.detection.classifier.ModelConfig
import com.genned.domain.model.DetectionSignal
import com.genned.domain.model.SignalAvailability
import com.genned.domain.model.SignalType

/**
 * Combines one [DetectionSignal] per sampled video frame (all [SignalType.AI_CLASSIFIER])
 * into a single classifier signal for the video as a whole. Pure and Android-free
 * so it's directly unit-testable without Robolectric or a real video file.
 *
 * Frames are combined in logit space (each frame's image-calibrated score is mapped
 * back to its logit gap, and the gaps are averaged), then the *video* calibration is
 * applied. The image calibration alone overstated video: real clips read AI-like.
 * See [ModelConfig.VIDEO_CALIBRATION_SLOPE].
 */
object VideoSignalAggregator {
    fun aggregateFrameSignals(perFrameSignals: List<DetectionSignal>): DetectionSignal {
        val available = perFrameSignals.filter {
            it.availability == SignalAvailability.AVAILABLE && it.score != null
        }
        if (available.isEmpty()) {
            // Frames can fail with the model bundled; only blame bundling when a frame said so.
            if (perFrameSignals.isNotEmpty() &&
                perFrameSignals.none { it.availability == SignalAvailability.UNAVAILABLE }
            ) {
                return DetectionSignal.error(
                    SignalType.AI_CLASSIFIER,
                    "The visual classifier couldn't analyze the sampled video frames.",
                )
            }
            return DetectionSignal.unavailable(
                SignalType.AI_CLASSIFIER,
                "The on-device visual classifier is not bundled in this build, so sampled video " +
                    "frames could not be analyzed.",
            )
        }

        val scores = available.map { it.score!! }
        val meanGap = scores.map(ModelConfig::logitDifferenceFromCalibratedProbability).average()
        val videoScore = ModelConfig.videoCalibratedProbability(meanGap)
        val meanConfidence = available.map { it.confidence }.average().toFloat()

        return DetectionSignal(
            type = SignalType.AI_CLASSIFIER,
            availability = SignalAvailability.AVAILABLE,
            score = videoScore,
            confidence = meanConfidence,
            description = "The on-device visual classifier estimates a ${(videoScore * 100).toInt()}% " +
                "AI-generated probability for this video from ${available.size} sampled frame" +
                "${if (available.size == 1) "" else "s"}, calibrated for video, which is harder to " +
                "judge than photos.",
            evidence = "Per-frame AI probability: " + scores.joinToString(", ") { "${(it * 100).toInt()}%" },
        )
    }
}
