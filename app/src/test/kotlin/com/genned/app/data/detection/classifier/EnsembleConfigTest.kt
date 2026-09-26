package com.genned.app.data.detection.classifier

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.exp

class EnsembleConfigTest {

    private fun sigmoid(x: Double) = (1.0 / (1.0 + exp(-x))).toFloat()

    @Test
    fun `both models at their photo means combine to zero`() {
        val combined = EnsembleConfig.combine(EnsembleConfig.MEAN_PRIMARY, EnsembleConfig.MEAN_CF)
        assertThat(combined).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `each model contributes its standardized logit with equal weight`() {
        val primaryOnly = EnsembleConfig.combine(
            EnsembleConfig.MEAN_PRIMARY + EnsembleConfig.STD_PRIMARY, EnsembleConfig.MEAN_CF,
        )
        val cfOnly = EnsembleConfig.combine(
            EnsembleConfig.MEAN_PRIMARY, EnsembleConfig.MEAN_CF + EnsembleConfig.STD_CF,
        )
        assertThat(primaryOnly).isWithin(1e-9).of(0.5)
        assertThat(cfOnly).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `combined score rises with either model's evidence`() {
        val base = EnsembleConfig.combine(0.0, 0.0)
        assertThat(EnsembleConfig.combine(1.0, 0.0)).isGreaterThan(base)
        assertThat(EnsembleConfig.combine(0.0, 1.0)).isGreaterThan(base)
    }

    @Test
    fun `photo and video probabilities are their fitted Platt calibrations`() {
        for (s in listOf(-2.0, -0.5, 0.0, 0.5, 2.0)) {
            assertThat(EnsembleConfig.photoProbability(s))
                .isWithin(1e-6f).of(sigmoid(EnsembleConfig.PHOTO_SLOPE * s + EnsembleConfig.PHOTO_INTERCEPT))
            assertThat(EnsembleConfig.videoProbability(s))
                .isWithin(1e-6f).of(sigmoid(EnsembleConfig.VIDEO_SLOPE * s + EnsembleConfig.VIDEO_INTERCEPT))
        }
    }

    @Test
    fun `the same evidence counts for less on video than on a photo`() {
        // Real video frames look more AI-like to both models, so the video calibration is
        // shifted down. Across the typical range, a video needs stronger evidence than a photo.
        for (s in listOf(-1.0, 0.0, 0.5, 1.0)) {
            assertThat(EnsembleConfig.videoProbability(s)).isLessThan(EnsembleConfig.photoProbability(s))
        }
    }

    @Test
    fun `probabilities stay in range for extreme inputs`() {
        assertThat(EnsembleConfig.photoProbability(1e6)).isAtMost(1f)
        assertThat(EnsembleConfig.photoProbability(-1e6)).isAtLeast(0f)
        assertThat(EnsembleConfig.videoProbability(1e6)).isAtMost(1f)
        assertThat(EnsembleConfig.videoProbability(-1e6)).isAtLeast(0f)
    }
}
