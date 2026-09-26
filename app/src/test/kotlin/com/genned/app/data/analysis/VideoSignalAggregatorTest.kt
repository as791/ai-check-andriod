package com.genned.app.data.analysis

import com.genned.app.data.detection.classifier.ModelConfig
import com.genned.domain.evidence.EvidenceWeights
import com.genned.domain.model.DetectionSignal
import com.genned.domain.model.SignalAvailability
import com.genned.domain.model.SignalType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure logic, no Android dependency — runs as a plain JUnit test. */
class VideoSignalAggregatorTest {

    private fun frameSignal(score: Float, confidence: Float = 1f, rawScore: Double? = null) = DetectionSignal(
        type = SignalType.AI_CLASSIFIER,
        availability = SignalAvailability.AVAILABLE,
        score = score,
        confidence = confidence,
        description = "test",
        rawScore = rawScore,
    )

    /** What the aggregator should produce: frames averaged in logit space, then video-calibrated. */
    private fun expectedVideoScore(vararg frameScores: Float): Float = ModelConfig.videoCalibratedProbability(
        frameScores.map(ModelConfig::logitDifferenceFromCalibratedProbability).average(),
    )

    @Test
    fun `averages frames in logit space, applies the video calibration and averages confidences`() {
        val result = VideoSignalAggregator.aggregateFrameSignals(
            listOf(frameSignal(0.2f, 0.5f), frameSignal(0.4f, 0.7f), frameSignal(0.6f, 0.9f)),
        )

        assertThat(result.availability).isEqualTo(SignalAvailability.AVAILABLE)
        assertThat(result.score).isWithin(0.001f).of(expectedVideoScore(0.2f, 0.4f, 0.6f))
        assertThat(result.confidence).isWithin(0.001f).of(0.7f)
        assertThat(result.description).contains("3 sampled frames")
    }

    @Test
    fun `uses singular wording for a single frame`() {
        val result = VideoSignalAggregator.aggregateFrameSignals(listOf(frameSignal(0.9f)))

        assertThat(result.description).contains("1 sampled frame")
        assertThat(result.description).doesNotContain("1 sampled frames")
    }

    @Test
    fun `a typical real talking-head clip no longer reads HIGH`() {
        // In the DF26 benchmark, real talking-head videos averaged ~0.83 per frame with the
        // image calibration, and 26% of them showed HIGH. The video calibration must not
        // let that happen.
        val result = VideoSignalAggregator.aggregateFrameSignals(List(5) { frameSignal(0.83f) })

        assertThat(result.score!!).isLessThan(EvidenceWeights.HIGH_THRESHOLD)
        assertThat(result.score!!).isLessThan(0.6f)
    }

    @Test
    fun `a strongly AI-looking clip is still only UNCERTAIN`() {
        // Frames that would read 95% as photos: for video that evidence is much weaker
        // (video AUC 0.65-0.79), so the clip stays out of HIGH.
        val result = VideoSignalAggregator.aggregateFrameSignals(List(5) { frameSignal(0.95f) })

        assertThat(result.score!!).isLessThan(EvidenceWeights.HIGH_THRESHOLD)
        assertThat(result.score!!).isGreaterThan(0.6f)
    }

    @Test
    fun `only near-certain frames can take a video to HIGH`() {
        val result = VideoSignalAggregator.aggregateFrameSignals(List(5) { frameSignal(0.995f) })

        assertThat(result.score!!).isAtLeast(EvidenceWeights.HIGH_THRESHOLD)
    }

    @Test
    fun `video score increases with frame scores`() {
        val scores = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 0.99f).map { frame ->
            VideoSignalAggregator.aggregateFrameSignals(List(5) { frameSignal(frame) }).score!!
        }
        assertThat(scores).isInStrictOrder()
    }

    @Test
    fun `returns unavailable when no frame produced a usable score`() {
        val result = VideoSignalAggregator.aggregateFrameSignals(
            listOf(
                DetectionSignal.unavailable(SignalType.AI_CLASSIFIER, "not bundled"),
                DetectionSignal.error(SignalType.AI_CLASSIFIER, "failed"),
            ),
        )

        assertThat(result.availability).isEqualTo(SignalAvailability.UNAVAILABLE)
        assertThat(result.score).isNull()
    }

    @Test
    fun `returns an error, not unavailable, when every frame failed`() {
        val result = VideoSignalAggregator.aggregateFrameSignals(
            listOf(
                DetectionSignal.error(SignalType.AI_CLASSIFIER, "failed"),
                DetectionSignal.error(SignalType.AI_CLASSIFIER, "failed"),
            ),
        )

        assertThat(result.availability).isEqualTo(SignalAvailability.ERROR)
        assertThat(result.score).isNull()
        assertThat(result.description).doesNotContain("not bundled")
    }

    @Test
    fun `returns unavailable for an empty frame list`() {
        val result = VideoSignalAggregator.aggregateFrameSignals(emptyList())

        assertThat(result.availability).isEqualTo(SignalAvailability.UNAVAILABLE)
    }

    @Test
    fun `ignores unavailable and error frames when averaging the rest`() {
        val result = VideoSignalAggregator.aggregateFrameSignals(
            listOf(
                frameSignal(0.8f),
                DetectionSignal.unavailable(SignalType.AI_CLASSIFIER, "not bundled"),
                DetectionSignal.error(SignalType.AI_CLASSIFIER, "failed"),
            ),
        )

        assertThat(result.availability).isEqualTo(SignalAvailability.AVAILABLE)
        assertThat(result.score).isWithin(0.001f).of(expectedVideoScore(0.8f))
        assertThat(result.description).contains("1 sampled frame")
    }

    @Test
    fun `frames carrying a raw score are averaged raw and passed to the given video calibration`() {
        var received: Double? = null
        val result = VideoSignalAggregator.aggregateFrameSignals(
            listOf(frameSignal(0.9f, rawScore = 1.0), frameSignal(0.1f, rawScore = -0.4)),
            videoProbability = { mean -> received = mean; 0.42f },
        )

        // The raw ensemble scores are used, not the photo probabilities shown per frame.
        assertThat(received).isWithin(1e-9).of(0.3)
        assertThat(result.score).isEqualTo(0.42f)
    }

    @Test
    fun `frames without a raw score fall back to inverting the photo calibration`() {
        var received: Double? = null
        VideoSignalAggregator.aggregateFrameSignals(
            listOf(frameSignal(0.7f)),
            videoProbability = { mean -> received = mean; 0.5f },
        )

        assertThat(received).isWithin(1e-4).of(ModelConfig.logitDifferenceFromCalibratedProbability(0.7f))
    }
}
