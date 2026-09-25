package com.genned.app.data.detection.classifier

/**
 * Everything [AIImageClassifierProvider] needs to know about the bundled model's
 * contract. Full model card (name/source/license/size/limitations) lives in
 * internal-docs/MODEL.md — this object only holds the numbers the code needs, sourced from
 * that same doc, so there is exactly one place to update if the model changes.
 *
 * The class order in [interpretOutput] is confirmed (not guessed) from the real
 * `Dafilab/ai-image-detector` config.json on Hugging Face: `label_mapping` is
 * `{"0": "ai", "1": "human"}`, i.e. output index 0 = P(ai), index 1 = P(human) —
 * the reverse of an earlier, unverified assumption in this file. [INPUT_NAME] matches
 * the bundled ONNX export: it loads and runs on-device with this input name and emits
 * two raw logits. Re-check both with Netron if the model is ever replaced (see
 * internal-docs/MODEL.md "Replacing the model file").
 */
object ModelConfig {
    const val DISPLAY_NAME = "Dafilab/ai-image-detector (EfficientNet-B4, ONNX export)"

    const val INPUT_SIZE = 380
    val INPUT_SHAPE = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

    /** ONNX graph input tensor name — confirmed against the bundled export; re-check if the model changes. */
    const val INPUT_NAME = "pixel_values"

    /** Standard ImageNet normalization used by timm-trained models by default. */
    val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * Fixed baseline confidence for this signal, independent of the model's own
     * output. It only weighs the classifier against the other signals in
     * EvidenceEngine; the probability itself is calibrated (see [CALIBRATION_SLOPE]).
     */
    const val BASE_CONFIDENCE = 0.75f

    /**
     * Platt scaling of the raw logit gap: P(ai) = sigmoid(SLOPE * (ai - human) + INTERCEPT).
     *
     * The raw model is badly overconfident: in the first benchmark 60-74% of its
     * scores were above 99% or below 1%, while scores above 90% were only 72-93%
     * AI. These values were fit by tools/calibrate.py on 3,000 per-image scores
     * (2 public datasets x 3 conditions, `avg` preprocessing) in Model eval run
     * 36185656205. After calibration about 1% of scores are that extreme. Refit
     * whenever the model or AIImageClassifierProvider's preprocessing changes. See internal-docs/MODEL.md.
     */
    const val CALIBRATION_SLOPE = 0.2252f
    const val CALIBRATION_INTERCEPT = 0.0494f

    /**
     * Separate calibration for video: P(ai) = sigmoid(SLOPE * meanGap + INTERCEPT), where
     * meanGap is the mean per-frame logit gap across the sampled frames.
     *
     * The image model reads real video frames as AI-like (compression, motion blur,
     * talking-head framing). With the image calibration, 26% of real talking-head clips
     * showed HIGH. Fit by tools/calibrate.py on Video eval run 36190406439 (100 real + 100
     * AI videos each from DF26 and DeepAction, the app's exact 5-frame pipeline).
     * Calibration error 0.36/0.49 raw -> 0.14/0.20 when fit on the other dataset.
     *
     * Consequence, by design: with this model (video AUC 0.65-0.79), a video only reaches
     * the HIGH band if its frames are near-certain as photos (mean image score about 0.98+).
     * No benchmark video, real or AI, got there. Real clips land UNCERTAIN, and only
     * clearly real-looking ones reach LOW. That's the honest ceiling for this model on video.
     */
    const val VIDEO_CALIBRATION_SLOPE = 0.2071f
    const val VIDEO_CALIBRATION_INTERCEPT = -1.3977f

    /**
     * Interprets the raw ONNX output as a single AI-probability float in [0,1].
     *
     * The export (tools/convert_model.py) emits the classifier head's raw *logits* —
     * its output tensor is literally named "logits", and timm's efficientnet_b4 has no
     * softmax layer — so the softmax is applied here. For a 2-class output that is
     * exactly sigmoid(ai_logit - human_logit). Class order is `[ai, human]`, per the
     * real model's config.json `label_mapping` (`{"0": "ai", "1": "human"}`). The logit
     * gap is then calibrated ([calibratedProbability]) so the percentage means what it
     * says.
     *
     * An earlier version "normalized" with `ai / (ai + human)`, which is not a
     * softmax: whenever both logits were negative (an entirely ordinary output) its
     * `total <= 0` guard forced exactly 0.5, so real, confident predictions showed
     * up as a flat 50% — confirmed on-device with the real bundled model.
     */
    fun interpretOutput(rawOutput: Any?): Float = logitDifference(rawOutput)?.let(::calibratedProbability) ?: 0.5f

    /**
     * The raw evidence, before calibration: ai_logit - human_logit (or a single AI
     * logit). Null when the output isn't one the model should produce.
     */
    fun logitDifference(rawOutput: Any?): Double? {
        val flat = flatten(rawOutput)
        if (flat.any { it.isNaN() }) return null
        return when (flat.size) {
            2 -> flat[0].toDouble() - flat[1].toDouble()
            1 -> flat[0].toDouble() // a single raw logit for P(ai)
            else -> null
        }
    }

    /** Calibrated P(ai) for a logit gap, e.g. the mean of the gaps from each input view. */
    fun calibratedProbability(logitDifference: Double): Float =
        sigmoid(CALIBRATION_SLOPE * logitDifference + CALIBRATION_INTERCEPT)

    /**
     * Inverse of [calibratedProbability]: recovers a frame's logit gap from its (image-
     * calibrated) score, so video frames can be averaged in logit space. Clamped away from
     * 0/1 so a saturated score maps to a large but finite gap.
     */
    fun logitDifferenceFromCalibratedProbability(probability: Float): Double {
        val p = probability.toDouble().coerceIn(1e-6, 1.0 - 1e-6)
        return (kotlin.math.ln(p / (1.0 - p)) - CALIBRATION_INTERCEPT) / CALIBRATION_SLOPE
    }

    /** Video P(ai) from the mean per-frame logit gap - see [VIDEO_CALIBRATION_SLOPE]. */
    fun videoCalibratedProbability(meanLogitDifference: Double): Float =
        sigmoid(VIDEO_CALIBRATION_SLOPE * meanLogitDifference + VIDEO_CALIBRATION_INTERCEPT)

    /** Computed in Double so extreme logits saturate cleanly to 0/1 instead of overflowing. */
    private fun sigmoid(x: Double): Float =
        (1.0 / (1.0 + kotlin.math.exp(-x))).toFloat().coerceIn(0f, 1f)

    private fun flatten(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> value.flatMap { flatten(it).toList() }.toFloatArray()
        is Float -> floatArrayOf(value)
        else -> floatArrayOf()
    }
}
