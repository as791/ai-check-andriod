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
     * output. Not empirically calibrated — see internal-docs/MODEL.md "Known limitations" and
     * tools/evaluate.py, which is how this should eventually be replaced with a
     * data-driven value (e.g. scaled by margin-from-0.5, or by measured accuracy).
     */
    const val BASE_CONFIDENCE = 0.75f

    /**
     * Interprets the raw ONNX output as a single AI-probability float in [0,1].
     *
     * The export (tools/convert_model.py) emits the classifier head's raw *logits* —
     * its output tensor is literally named "logits", and timm's efficientnet_b4 has no
     * softmax layer — so the softmax is applied here. For a 2-class output that is
     * exactly sigmoid(ai_logit - human_logit). Class order is `[ai, human]`, per the
     * real model's config.json `label_mapping` (`{"0": "ai", "1": "human"}`).
     *
     * An earlier version "normalized" with `ai / (ai + human)`, which is not a
     * softmax: whenever both logits were negative (an entirely ordinary output) its
     * `total <= 0` guard forced exactly 0.5, so real, confident predictions showed
     * up as a flat 50% — confirmed on-device with the real bundled model.
     */
    fun interpretOutput(rawOutput: Any?): Float {
        val flat = flatten(rawOutput)
        if (flat.any { it.isNaN() }) return 0.5f
        return when (flat.size) {
            2 -> sigmoid(flat[0].toDouble() - flat[1].toDouble())
            1 -> sigmoid(flat[0].toDouble()) // a single raw logit for P(ai)
            else -> 0.5f
        }
    }

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
