package com.genned.app.data.detection.classifier

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

        assertThat(p).isWithin(0.001f).of(ModelConfig.calibratedProbability(3.0))
        assertThat(p).isGreaterThan(0.6f)
    }

    @Test
    fun `logit difference is ai minus human`() {
        assertThat(ModelConfig.logitDifference(logits(ai = 3.8f, human = -2.9f))!!).isWithin(1e-5).of(6.7)
        assertThat(ModelConfig.logitDifference(floatArrayOf(4f))!!).isWithin(1e-6).of(4.0)
    }

    @Test
    fun `calibration keeps a real on-device output from claiming near certainty`() {
        // Logged on-device from an Instagram post: raw sigmoid said 99.97% AI. The
        // benchmark showed raw scores that high are right only 72-93% of the time.
        val p = ModelConfig.interpretOutput(logits(ai = 4.502205f, human = -3.5011606f))

        assertThat(p).isGreaterThan(0.8f)
        assertThat(p).isLessThan(0.9f)
    }

    @Test
    fun `strongly AI-leaning logits give a high but not certain probability`() {
        val p = ModelConfig.interpretOutput(logits(ai = 5f, human = -3f))
        assertThat(p).isGreaterThan(0.8f)
        assertThat(p).isLessThan(0.99f)
    }

    @Test
    fun `strongly human-leaning logits give a low probability`() {
        assertThat(ModelConfig.interpretOutput(logits(ai = -3f, human = 5f))).isLessThan(0.2f)
    }

    @Test
    fun `calibration is monotonic in the logit gap`() {
        val gaps = listOf(-20.0, -5.0, -1.0, 0.0, 1.0, 5.0, 20.0)
        val probabilities = gaps.map(ModelConfig::calibratedProbability)
        assertThat(probabilities).isInStrictOrder()
    }

    @Test
    fun `equal logits mean genuinely uncertain`() {
        assertThat(ModelConfig.interpretOutput(logits(ai = 1.7f, human = 1.7f))).isWithin(0.02f).of(0.5f)
    }

    @Test
    fun `extreme logits saturate to 0 and 1 without overflowing`() {
        assertThat(ModelConfig.interpretOutput(logits(ai = 5000f, human = -5000f))).isEqualTo(1f)
        assertThat(ModelConfig.interpretOutput(logits(ai = -5000f, human = 5000f))).isEqualTo(0f)
    }

    @Test
    fun `single raw logit is calibrated like a logit gap`() {
        assertThat(ModelConfig.interpretOutput(floatArrayOf(4f)))
            .isWithin(0.001f).of(ModelConfig.calibratedProbability(4.0))
    }

    @Test
    fun `unexpected outputs fall back to uncertain rather than crashing`() {
        assertThat(ModelConfig.interpretOutput(null)).isEqualTo(0.5f)
        assertThat(ModelConfig.interpretOutput(floatArrayOf(1f, 2f, 3f))).isEqualTo(0.5f)
        assertThat(ModelConfig.interpretOutput(logits(ai = Float.NaN, human = 1f))).isEqualTo(0.5f)
        assertThat(ModelConfig.logitDifference(null)).isNull()
    }
}
