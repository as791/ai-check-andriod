package com.genned.app.data.detection.classifier

/**
 * The second bundled detector: Community Forensics ViT-S 224 (OwensLab/commfor-model-224,
 * MIT; Park & Owens, "Community Forensics: Using Thousands of Generators to Train Fake
 * Image Detectors", CVPR 2025). Trained on images from ~4,800 generators.
 *
 * Exported to ONNX and stored with fp16 weights by tools/build_models.py, which also
 * checks that this preprocessing matches the authors' own test transform (short side ->
 * 256, center crop 224, ImageNet normalization) to within a negligible logit difference.
 */
object CommunityForensicsConfig {
    const val DISPLAY_NAME = "Community Forensics ViT-S 224"

    const val INPUT_SIZE = 224
    const val RESIZE_SHORT_SIDE = 256
    val INPUT_SHAPE = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

    /** ONNX graph input name, set by tools/build_models.py. */
    const val INPUT_NAME = "pixel_values"

    /**
     * The model's single output logit (sigmoid = P(generated)), or null for an output it
     * should never produce.
     */
    fun logit(rawOutput: Any?): Double? {
        val value = when (rawOutput) {
            is Array<*> -> (rawOutput.singleOrNull() as? FloatArray)?.singleOrNull()
            is FloatArray -> rawOutput.singleOrNull()
            else -> null
        } ?: return null
        return value.toDouble().takeUnless { it.isNaN() }
    }
}
