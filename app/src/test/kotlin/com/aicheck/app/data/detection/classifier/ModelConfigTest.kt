package com.aicheck.app.data.detection.classifier

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure logic, no Android dependency — runs as a plain JUnit test. */
class ModelConfigTest {

    /** ONNX Runtime returns the model's `[1, 2]` float output as an Array<FloatArray>. */
    private fun logits(ai: Float, human: Float): Any = arrayOf(floatArrayOf(ai, human))

    @Test
    fun `both-negative logits leaning AI are not collapsed to 50 percent`() {
        // The regression: the old ai/(ai+human) "normalization" hit its total<=0 guard
        // on this perfectly ordinary output and reported exactly 0.5 for a clearly
        // AI-leaning prediction - seen on-device as a flat 50% on AI-generated images.
        val p = ModelConfig.interpretOutput(logits(ai = -1.0f, human = -4.0f))

        assertThat(p).isWithin(0.001f).of(0.9526f) // sigmoid(3)
    }

    @Test
    fun `strongly AI-leaning logits give a high probability`() {
        assertThat(ModelConfig.interpretOutput(logits(ai = 5f, human = -3f))).isGreaterThan(0.99f)
    }

    @Test
    fun `strongly human-leaning logits give a low probability`() {
        assertThat(ModelConfig.interpretOutput(logits(ai = -3f, human = 5f))).isLessThan(0.01f)
    }

    @Test
    fun `equal logits mean genuinely uncertain`() {
        assertThat(ModelConfig.interpretOutput(logits(ai = 1.7f, human = 1.7f))).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `extreme logits saturate to 0 and 1 without overflowing`() {
        assertThat(ModelConfig.interpretOutput(logits(ai = 500f, human = -500f))).isEqualTo(1f)
        assertThat(ModelConfig.interpretOutput(logits(ai = -500f, human = 500f))).isEqualTo(0f)
    }

    @Test
    fun `single raw logit is passed through a sigmoid`() {
        assertThat(ModelConfig.interpretOutput(floatArrayOf(0f))).isWithin(0.001f).of(0.5f)
        assertThat(ModelConfig.interpretOutput(floatArrayOf(4f))).isWithin(0.001f).of(0.982f)
    }

    @Test
    fun `unexpected outputs fall back to uncertain rather than crashing`() {
        assertThat(ModelConfig.interpretOutput(null)).isEqualTo(0.5f)
        assertThat(ModelConfig.interpretOutput(floatArrayOf(1f, 2f, 3f))).isEqualTo(0.5f)
        assertThat(ModelConfig.interpretOutput(logits(ai = Float.NaN, human = 1f))).isEqualTo(0.5f)
    }
}
