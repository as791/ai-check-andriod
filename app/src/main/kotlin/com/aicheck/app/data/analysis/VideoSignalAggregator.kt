package com.aicheck.app.data.analysis

import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalAvailability
import com.aicheck.domain.model.SignalType

/**
 * Combines one [DetectionSignal] per sampled video frame (all [SignalType.AI_CLASSIFIER])
 * into a single classifier signal for the video as a whole. Pure and Android-free
 * so it's directly unit-testable without Robolectric or a real video file.
 */
object VideoSignalAggregator {
    fun aggregateFrameSignals(perFrameSignals: List<DetectionSignal>): DetectionSignal {
        val available = perFrameSignals.filter {
            it.availability == SignalAvailability.AVAILABLE && it.score != null
        }
        if (available.isEmpty()) {
            return DetectionSignal.unavailable(
                SignalType.AI_CLASSIFIER,
                "The on-device visual classifier is not bundled in this build, so sampled video " +
                    "frames could not be analyzed.",
            )
        }

        val scores = available.map { it.score!! }
        val meanScore = scores.average().toFloat()
        val meanConfidence = available.map { it.confidence }.average().toFloat()

        return DetectionSignal(
            type = SignalType.AI_CLASSIFIER,
            availability = SignalAvailability.AVAILABLE,
            score = meanScore,
            confidence = meanConfidence,
            description = "The on-device visual classifier estimates a ${(meanScore * 100).toInt()}% " +
                "average AI-generated probability across ${available.size} sampled video frame" +
                "${if (available.size == 1) "" else "s"}.",
            evidence = "Per-frame AI probability: " + scores.joinToString(", ") { "${(it * 100).toInt()}%" },
        )
    }
}
