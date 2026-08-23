package com.aicheck.app.data.detection.classifier

/**
 * Everything [AIImageClassifierProvider] needs to know about the bundled model's
 * contract. Full model card (name/source/license/size/limitations) lives in
 * docs/MODEL.md — this object only holds the numbers the code needs, sourced from
 * that same doc, so there is exactly one place to update if the model changes.
 *
 * The class order in [interpretOutput] is confirmed (not guessed) from the real
 * `Dafilab/ai-image-detector` config.json on Hugging Face: `label_mapping` is
 * `{"0": "ai", "1": "human"}`, i.e. output index 0 = P(ai), index 1 = P(human) —
 * the reverse of an earlier, unverified assumption in this file. [INPUT_NAME] is
 * still this project's best-documented expectation for the exported ONNX graph
 * (see docs/MODEL.md "Adding the model file"), not verified against the actual
 * export in this build (none is bundled — see [ModelAssets]); confirm it with
 * Netron once a real `.onnx` file exists.
 */
object ModelConfig {
    const val DISPLAY_NAME = "Dafilab/ai-image-detector (EfficientNet-B4, ONNX export)"

    const val INPUT_SIZE = 380
    val INPUT_SHAPE = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

    /** ONNX graph input tensor name — verify with Netron against the real export. */
    const val INPUT_NAME = "pixel_values"

    /** Standard ImageNet normalization used by timm-trained models by default. */
    val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * Fixed baseline confidence for this signal, independent of the model's own
     * output. Not empirically calibrated — see docs/MODEL.md "Known limitations" and
     * tools/evaluate.py, which is how this should eventually be replaced with a
     * data-driven value (e.g. scaled by margin-from-0.5, or by measured accuracy).
     */
    const val BASE_CONFIDENCE = 0.75f

    /**
     * Interprets the raw ONNX output as a single AI-probability float in [0,1].
     * Expects a 2-class `[1, 2]` softmax-style output ordered `[ai, human]`, per the
     * real model's config.json `label_mapping` (`{"0": "ai", "1": "human"}`) — still
     * worth re-confirming against the actual exported graph's output with Netron,
     * since a mismatched label order silently inverts every result.
     */
    fun interpretOutput(rawOutput: Any?): Float {
        val flat = flatten(rawOutput)
        return when (flat.size) {
            2 -> {
                // Softmax-style two-class output [ai, human]; normalize defensively
                // in case the export didn't already apply softmax.
                val (ai, human) = flat[0] to flat[1]
                val total = ai + human
                if (total <= 0f) 0.5f else (ai / total).coerceIn(0f, 1f)
            }
            1 -> flat[0].coerceIn(0f, 1f) // single sigmoid output = P(ai)
            else -> 0.5f
        }
    }

    private fun flatten(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> value.flatMap { flatten(it).toList() }.toFloatArray()
        is Float -> floatArrayOf(value)
        else -> floatArrayOf()
    }
}
