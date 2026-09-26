package com.genned.app.data.detection.classifier

import kotlin.math.exp

/**
 * How the two bundled detectors are combined. Every constant here was fit by
 * tools/ensemble_calibrate.py on the exact model files the app bundles, in Ensemble build
 * run 36196623887 (see internal-docs/MODEL.md "Ensemble"):
 *
 *     s = ((primaryGap - MEAN_PRIMARY) / STD_PRIMARY + (cfLogit - MEAN_CF) / STD_CF) / 2
 *     photo:  P(ai) = sigmoid(PHOTO_SLOPE * s + PHOTO_INTERCEPT)
 *     video:  P(ai) = sigmoid(VIDEO_SLOPE * mean(s over frames) + VIDEO_INTERCEPT)
 *
 * The standardization is fit on photo scores (label-free) and shared by both paths; the
 * calibrations are fit separately because video frames look different to both models.
 * The Python mirror is tools/evaluate.py APP_ENSEMBLE.
 */
object EnsembleConfig {
    const val DISPLAY_NAME = "${ModelConfig.DISPLAY_NAME} + ${CommunityForensicsConfig.DISPLAY_NAME} (ensemble)"

    const val MEAN_PRIMARY = -0.38708
    const val STD_PRIMARY = 8.00114
    const val MEAN_CF = -3.96926
    const val STD_CF = 4.37424

    const val PHOTO_SLOPE = 3.19350
    const val PHOTO_INTERCEPT = 0.16339
    const val VIDEO_SLOPE = 3.49213
    const val VIDEO_INTERCEPT = -1.85881

    /** The ensemble's uncalibrated score for one image or frame. */
    fun combine(primaryGap: Double, communityForensicsLogit: Double): Double =
        ((primaryGap - MEAN_PRIMARY) / STD_PRIMARY + (communityForensicsLogit - MEAN_CF) / STD_CF) / 2.0

    fun photoProbability(combined: Double): Float = sigmoid(PHOTO_SLOPE * combined + PHOTO_INTERCEPT)

    fun videoProbability(meanCombined: Double): Float = sigmoid(VIDEO_SLOPE * meanCombined + VIDEO_INTERCEPT)

    private fun sigmoid(x: Double): Float = (1.0 / (1.0 + exp(-x))).toFloat().coerceIn(0f, 1f)
}
