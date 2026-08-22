package com.aicheck.domain.evidence

/**
 * Transparent, fixed weights for [EvidenceEngine]'s weighted-average blend.
 *
 * These are a documented starting point, not a validated calibration — see
 * docs/ARCHITECTURE.md "Recalibrating the evidence weights". They live in one place,
 * isolated from providers and UI, specifically so they can be replaced once real
 * evaluation data (tools/evaluate.py) exists.
 *
 * All weights below except [CLASSIFIER_WEIGHT] are *maximums*: [EvidenceEngine] scales
 * them by the signal's own score, so a corroborating signal that found nothing (score
 * 0) contributes zero weight instead of diluting the blend toward "human".
 */
object EvidenceWeights {
    /** The visual classifier is the strongest probabilistic signal we have. */
    const val CLASSIFIER_WEIGHT = 0.70f

    /** A matched generator signature (e.g. an SD "parameters" chunk) is strong but not proof. */
    const val GENERATOR_METADATA_WEIGHT = 0.22f

    /** A positive watermark match would be strong corroborating evidence. */
    const val WATERMARK_WEIGHT = 0.20f

    /** Generic EXIF anomalies (missing/stripped/inconsistent fields) are weak on their own. */
    const val EXIF_ANOMALY_WEIGHT = 0.06f

    /** aiLikelihood >= this -> [com.aicheck.domain.model.Classification.HIGH]. */
    const val HIGH_THRESHOLD = 0.70f

    /** aiLikelihood < this -> [com.aicheck.domain.model.Classification.LOW]; between the two is UNCERTAIN. */
    const val LOW_THRESHOLD = 0.30f

    /** Likelihood reported when no signal produced usable evidence at all. */
    const val NO_EVIDENCE_LIKELIHOOD = 0.5f
}
