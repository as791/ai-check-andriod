package com.genned.domain.evidence

/**
 * Transparent, fixed weights for [EvidenceEngine]'s weighted-average blend.
 *
 * The classification thresholds are set from benchmark data (see [HIGH_THRESHOLD]).
 * The blend weights are still a documented starting point, see
 * internal-docs/ARCHITECTURE.md "Recalibrating the evidence weights". They live in one
 * place, isolated from providers and UI, so they can be replaced from evaluation data.
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

    /**
     * aiLikelihood >= this -> [com.genned.domain.model.Classification.HIGH].
     *
     * Chosen from data by tools/calibrate.py (Model eval run 36185656205) on calibrated
     * classifier scores. It is the lowest threshold at which at most 5% of real images
     * show HIGH in every benchmark dataset x condition (worst case 2.8%). With 0.70,
     * up to 39% of real images in the harder dataset showed HIGH.
     */
    const val HIGH_THRESHOLD = 0.90f

    /**
     * aiLikelihood < this -> [com.genned.domain.model.Classification.LOW]; between the two
     * is UNCERTAIN. Chosen the same way: the highest threshold at which at most 10% of
     * AI content shows LOW. For the shipped ensemble (Ensemble build run 36196623887) that
     * is 0.15 for both photos (worst case 10.0%) and video (9.0%); at 0.25, 17-18% of AI
     * content read LOW. [HIGH_THRESHOLD] still holds for the ensemble: 2.8% of real photos
     * and 2.0% of real videos show HIGH.
     */
    const val LOW_THRESHOLD = 0.15f

    /** Likelihood reported when no signal produced usable evidence at all. */
    const val NO_EVIDENCE_LIKELIHOOD = 0.5f
}
