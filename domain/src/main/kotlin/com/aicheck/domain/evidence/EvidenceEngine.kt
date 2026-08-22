package com.aicheck.domain.evidence

import com.aicheck.domain.model.AnalysisResult
import com.aicheck.domain.model.Classification
import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalAvailability
import com.aicheck.domain.model.SignalType

/**
 * Turns independent [DetectionSignal]s into one [AnalysisResult]. This is the one
 * place score-blending policy lives — providers never combine their own evidence with
 * anyone else's, and the UI never sees raw provider output.
 *
 * Two modes, chosen automatically:
 * 1. **Verified provenance available**: if a [SignalType.CONTENT_CREDENTIALS] signal is
 *    [SignalAvailability.AVAILABLE] (i.e. a C2PA manifest was cryptographically
 *    validated), its score *is* the result — it is not blended with probabilistic
 *    signals, matching "cryptographically verified provenance should be presented
 *    separately from probabilistic ML detection." This branch is unreachable in V1
 *    (the bundled C2PAProvider always reports UNAVAILABLE) but is implemented and
 *    tested now so enabling that provider later needs no engine changes.
 * 2. **Probabilistic blend** (the normal V1 path): a confidence-weighted average over
 *    every other AVAILABLE signal, using [EvidenceWeights]. Signals that are
 *    UNAVAILABLE or ERROR contribute nothing to the score — critically, an absent
 *    signal (e.g. no generator metadata found) never pushes the likelihood down,
 *    since absence of evidence is not evidence of absence.
 */
class EvidenceEngine {

    fun aggregate(signals: List<DetectionSignal>): AnalysisResult {
        val limitations = buildLimitations(signals)

        val verifiedCredentials = signals.firstOrNull {
            it.type == SignalType.CONTENT_CREDENTIALS &&
                it.availability == SignalAvailability.AVAILABLE &&
                it.score != null
        }

        val aiLikelihood = if (verifiedCredentials != null) {
            verifiedCredentials.score!!.coerceIn(0f, 1f)
        } else {
            blendProbabilisticSignals(signals)
        }

        return AnalysisResult(
            aiLikelihood = aiLikelihood,
            classification = classify(aiLikelihood),
            signals = signals,
            limitations = limitations,
        )
    }

    private fun blendProbabilisticSignals(signals: List<DetectionSignal>): Float {
        val weighted = signals.mapNotNull { signal ->
            if (signal.availability != SignalAvailability.AVAILABLE) return@mapNotNull null
            val score = signal.score?.coerceIn(0f, 1f) ?: return@mapNotNull null
            val weight = weightFor(signal.type, score) * signal.confidence.coerceIn(0f, 1f)
            if (weight <= 0f) return@mapNotNull null
            score to weight
        }

        if (weighted.isEmpty()) return EvidenceWeights.NO_EVIDENCE_LIKELIHOOD

        val totalWeight = weighted.sumOf { it.second.toDouble() }
        val weightedSum = weighted.sumOf { (score, weight) -> (score * weight).toDouble() }
        return (weightedSum / totalWeight).toFloat().coerceIn(0f, 1f)
    }

    /**
     * The classifier is inherently bidirectional — trained to discriminate both ways,
     * so even a low score is real evidence and always carries its full weight.
     * Corroborating signals (generator metadata, watermark, EXIF anomalies) are
     * one-directional: finding something is real evidence, but *not* finding it is
     * merely an absence of evidence, not evidence of a human origin. Scaling their
     * weight by their own score means a "not found" (score 0) contributes nothing —
     * it neither drags the result toward AI nor dilutes the classifier's weight
     * toward human, which a flat weight would otherwise do.
     */
    private fun weightFor(type: SignalType, score: Float): Float = when (type) {
        SignalType.AI_CLASSIFIER -> EvidenceWeights.CLASSIFIER_WEIGHT
        SignalType.GENERATOR_METADATA -> EvidenceWeights.GENERATOR_METADATA_WEIGHT * score
        SignalType.WATERMARK -> EvidenceWeights.WATERMARK_WEIGHT * score
        SignalType.EXIF_METADATA -> EvidenceWeights.EXIF_ANOMALY_WEIGHT * score
        // Only reached if a CONTENT_CREDENTIALS signal has a null score despite being
        // AVAILABLE, which providers must not produce; treated as no contribution.
        SignalType.CONTENT_CREDENTIALS -> 0f
    }

    private fun classify(aiLikelihood: Float): Classification = when {
        aiLikelihood >= EvidenceWeights.HIGH_THRESHOLD -> Classification.HIGH
        aiLikelihood < EvidenceWeights.LOW_THRESHOLD -> Classification.LOW
        else -> Classification.UNCERTAIN
    }

    private fun buildLimitations(signals: List<DetectionSignal>): List<String> {
        val limitations = mutableListOf(
            "AI detection is probabilistic. It can produce false positives and false " +
                "negatives, and newer generation models may not be well represented in " +
                "the classifier's training data.",
        )

        val classifierSignal = signals.firstOrNull { it.type == SignalType.AI_CLASSIFIER }
        if (classifierSignal == null || classifierSignal.availability != SignalAvailability.AVAILABLE) {
            limitations += "The on-device visual classifier did not run for this image; " +
                "the estimate below relies on metadata signals only and is less reliable."
        }

        val credentialsSignal = signals.firstOrNull { it.type == SignalType.CONTENT_CREDENTIALS }
        if (credentialsSignal == null || credentialsSignal.availability != SignalAvailability.AVAILABLE) {
            limitations += "Content Credentials (C2PA) verification is not available in " +
                "this build, so no cryptographic provenance could be checked."
        }

        val generatorSignal = signals.firstOrNull { it.type == SignalType.GENERATOR_METADATA }
        if (generatorSignal != null &&
            generatorSignal.availability == SignalAvailability.AVAILABLE &&
            (generatorSignal.score == null || generatorSignal.score == 0f)
        ) {
            limitations += "No generator metadata was found, but this does not indicate " +
                "the image is authentic — social platforms and editing tools frequently " +
                "strip metadata."
        }

        return limitations
    }
}
