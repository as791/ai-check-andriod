package com.aicheck.app.data.analysis

import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalAvailability
import com.aicheck.domain.model.SignalType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure logic, no Android dependency — runs as a plain JUnit test. */
class VideoSignalAggregatorTest {

    private fun frameSignal(score: Float, confidence: Float = 1f) = DetectionSignal(
        type = SignalType.AI_CLASSIFIER,
        availability = SignalAvailability.AVAILABLE,
        score = score,
        confidence = confidence,
        description = "test",
    )

    @Test
    fun `averages scores and confidences across available frames`() {
        val result = VideoSignalAggregator.aggregateFrameSignals(
            listOf(frameSignal(0.2f, 0.5f), frameSignal(0.4f, 0.7f), frameSignal(0.6f, 0.9f)),
        )

        assertThat(result.availability).isEqualTo(SignalAvailability.AVAILABLE)
        assertThat(result.score).isWithin(0.001f).of(0.4f)
        assertThat(result.confidence).isWithin(0.001f).of(0.7f)
        assertThat(result.description).contains("3 sampled video frames")
    }

    @Test
    fun `uses singular wording for a single frame`() {
        val result = VideoSignalAggregator.aggregateFrameSignals(listOf(frameSignal(0.9f)))

        assertThat(result.description).contains("1 sampled video frame")
        assertThat(result.description).doesNotContain("1 sampled video frames")
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
        assertThat(result.score).isWithin(0.001f).of(0.8f)
        assertThat(result.description).contains("1 sampled video frame")
    }
}
