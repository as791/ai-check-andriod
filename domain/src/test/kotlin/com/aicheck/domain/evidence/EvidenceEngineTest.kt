package com.aicheck.domain.evidence

import com.aicheck.domain.model.Classification
import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalAvailability
import com.aicheck.domain.model.SignalType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EvidenceEngineTest {

    private val engine = EvidenceEngine()

    private fun available(
        type: SignalType,
        score: Float,
        confidence: Float = 1f,
    ) = DetectionSignal(
        type = type,
        availability = SignalAvailability.AVAILABLE,
        score = score,
        confidence = confidence,
        description = "test",
    )

    @Test
    fun `high classifier score alone yields HIGH classification`() {
        val result = engine.aggregate(
            listOf(available(SignalType.AI_CLASSIFIER, score = 0.95f)),
        )

        assertThat(result.aiLikelihood).isWithin(0.001f).of(0.95f)
        assertThat(result.classification).isEqualTo(Classification.HIGH)
    }

    @Test
    fun `low classifier score alone yields LOW classification`() {
        val result = engine.aggregate(
            listOf(available(SignalType.AI_CLASSIFIER, score = 0.05f)),
        )

        assertThat(result.classification).isEqualTo(Classification.LOW)
    }

    @Test
    fun `mid classifier score alone yields UNCERTAIN classification`() {
        val result = engine.aggregate(
            listOf(available(SignalType.AI_CLASSIFIER, score = 0.5f)),
        )

        assertThat(result.classification).isEqualTo(Classification.UNCERTAIN)
    }

    @Test
    fun `no available signals yields UNCERTAIN with a limitation, never a fabricated extreme`() {
        val result = engine.aggregate(
            listOf(
                DetectionSignal.unavailable(SignalType.AI_CLASSIFIER, "not bundled"),
                DetectionSignal.unavailable(SignalType.CONTENT_CREDENTIALS, "not implemented"),
            ),
        )

        assertThat(result.aiLikelihood).isWithin(0.001f).of(EvidenceWeights.NO_EVIDENCE_LIKELIHOOD)
        assertThat(result.classification).isEqualTo(Classification.UNCERTAIN)
    }

    @Test
    fun `absent generator metadata never pulls likelihood toward AI or toward human`() {
        val withoutMetadata = engine.aggregate(
            listOf(
                available(SignalType.AI_CLASSIFIER, score = 0.5f),
                available(SignalType.GENERATOR_METADATA, score = 0f),
            ),
        )
        val withoutMetadataSignalAtAll = engine.aggregate(
            listOf(available(SignalType.AI_CLASSIFIER, score = 0.5f)),
        )

        // A confirmed "no generator metadata found" (score 0f, still AVAILABLE) must
        // not move the result any further than simply not having run that check.
        assertThat(withoutMetadata.aiLikelihood).isWithin(0.001f).of(withoutMetadataSignalAtAll.aiLikelihood)
    }

    @Test
    fun `matched generator metadata increases likelihood but classifier still dominates`() {
        val classifierOnly = engine.aggregate(
            listOf(available(SignalType.AI_CLASSIFIER, score = 0.5f)),
        )
        val withGeneratorMatch = engine.aggregate(
            listOf(
                available(SignalType.AI_CLASSIFIER, score = 0.5f),
                available(SignalType.GENERATOR_METADATA, score = 1f),
            ),
        )

        assertThat(withGeneratorMatch.aiLikelihood).isGreaterThan(classifierOnly.aiLikelihood)
        // Classifier weight (0.70) still outweighs generator metadata weight (0.22).
        assertThat(withGeneratorMatch.aiLikelihood).isLessThan(0.8f)
    }

    @Test
    fun `low provider confidence reduces that signal's influence`() {
        val fullConfidence = engine.aggregate(
            listOf(
                available(SignalType.AI_CLASSIFIER, score = 0.9f, confidence = 1f),
            ),
        )
        val lowConfidence = engine.aggregate(
            listOf(
                available(SignalType.AI_CLASSIFIER, score = 0.9f, confidence = 0.1f),
                available(SignalType.GENERATOR_METADATA, score = 0.5f, confidence = 1f),
            ),
        )

        // With the classifier's own confidence crushed, the metadata signal should
        // pull the blended result away from the classifier's raw 0.9.
        assertThat(lowConfidence.aiLikelihood).isLessThan(fullConfidence.aiLikelihood)
    }

    @Test
    fun `verified content credentials override the probabilistic blend entirely`() {
        val result = engine.aggregate(
            listOf(
                available(SignalType.AI_CLASSIFIER, score = 0.1f), // classifier thinks human
                DetectionSignal(
                    type = SignalType.CONTENT_CREDENTIALS,
                    availability = SignalAvailability.AVAILABLE,
                    score = 0.98f, // manifest declares generative-AI tool use
                    confidence = 1f,
                    description = "Content Credentials validated; declares AI tool use",
                ),
            ),
        )

        assertThat(result.aiLikelihood).isWithin(0.001f).of(0.98f)
        assertThat(result.classification).isEqualTo(Classification.HIGH)
    }

    @Test
    fun `verified content credentials declaring capture-only push likelihood low despite classifier`() {
        val result = engine.aggregate(
            listOf(
                available(SignalType.AI_CLASSIFIER, score = 0.9f), // classifier thinks AI
                DetectionSignal(
                    type = SignalType.CONTENT_CREDENTIALS,
                    availability = SignalAvailability.AVAILABLE,
                    score = 0.02f, // manifest declares camera capture, no generative actions
                    confidence = 1f,
                    description = "Content Credentials validated; camera capture only",
                ),
            ),
        )

        assertThat(result.aiLikelihood).isWithin(0.001f).of(0.02f)
        assertThat(result.classification).isEqualTo(Classification.LOW)
    }

    @Test
    fun `unavailable content credentials do not affect the probabilistic blend`() {
        val withUnavailableCredentials = engine.aggregate(
            listOf(
                available(SignalType.AI_CLASSIFIER, score = 0.6f),
                DetectionSignal.unavailable(SignalType.CONTENT_CREDENTIALS, "not implemented"),
            ),
        )
        val withoutCredentialsSignalAtAll = engine.aggregate(
            listOf(available(SignalType.AI_CLASSIFIER, score = 0.6f)),
        )

        assertThat(withUnavailableCredentials.aiLikelihood)
            .isWithin(0.001f).of(withoutCredentialsSignalAtAll.aiLikelihood)
    }

    @Test
    fun `error signals are excluded from the blend just like unavailable ones`() {
        val result = engine.aggregate(
            listOf(
                available(SignalType.AI_CLASSIFIER, score = 0.6f),
                DetectionSignal.error(SignalType.EXIF_METADATA, "corrupt EXIF block"),
            ),
        )

        assertThat(result.aiLikelihood).isWithin(0.001f).of(0.6f)
    }

    @Test
    fun `result always carries the standard probabilistic disclaimer`() {
        val result = engine.aggregate(listOf(available(SignalType.AI_CLASSIFIER, score = 0.5f)))

        assertThat(result.limitations).isNotEmpty()
        assertThat(result.limitations.first()).contains("probabilistic")
    }

    @Test
    fun `boundary scores classify using the documented thresholds`() {
        assertThat(
            engine.aggregate(listOf(available(SignalType.AI_CLASSIFIER, score = EvidenceWeights.HIGH_THRESHOLD)))
                .classification,
        ).isEqualTo(Classification.HIGH)

        assertThat(
            engine.aggregate(
                listOf(available(SignalType.AI_CLASSIFIER, score = EvidenceWeights.LOW_THRESHOLD)),
            ).classification,
        ).isEqualTo(Classification.UNCERTAIN)

        assertThat(
            engine.aggregate(
                listOf(
                    available(
                        SignalType.AI_CLASSIFIER,
                        score = EvidenceWeights.LOW_THRESHOLD - 0.001f,
                    ),
                ),
            ).classification,
        ).isEqualTo(Classification.LOW)
    }
}
