package com.aicheck.app.data.detection.classifier

/**
 * Everything [AIImageClassifierProvider] needs to know about the bundled model's
 * contract. Full model card (name/source/license/size/limitations) lives in
 * docs/MODEL.md — this object only holds the numbers the code needs, sourced from
 * that same doc, so there is exactly one place to update if the model changes.
 *
 * IMPORTANT: [INPUT_NAME] and the two-class assumption in [interpretOutput] are
 * this project's best-documented expectation for the target model (see docs/MODEL.md
 * "Adding the model file"), not something verified against the actual exported
 * `model.onnx` in this build (none is bundled — see [ModelAssets]). Before trusting
 * scores from a newly added model file, inspect its real input/output names (e.g.
 * with Netron) and its `config.json` id2label mapping, and update this object to
 * match.
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
     * Expects a 2-class `[1, 2]` softmax-style output ordered `[human, ai]` — this
     * ordering must be confirmed against the real model's `config.json` `id2label`
     * before shipping; a mismatched label order silently inverts every result.
     */
    fun interpretOutput(rawOutput: Any?): Float {
        val flat = flatten(rawOutput)
        return when (flat.size) {
            2 -> {
                // Softmax-style two-class output [human, ai]; normalize defensively
                // in case the export didn't already apply softmax.
                val (human, ai) = flat[0] to flat[1]
                val total = human + ai
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
