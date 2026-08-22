package com.aicheck.domain.model

/**
 * One piece of evidence from one [com.aicheck.domain.provider.DetectionProvider].
 *
 * @param score AI-likelihood contribution in `[0, 1]`, or `null` when [availability]
 *   is not [SignalAvailability.AVAILABLE]. Never fabricate a value here — an
 *   unimplemented or inapplicable detector must report [SignalAvailability.UNAVAILABLE]
 *   with `score = null`.
 * @param confidence How much the provider itself trusts this particular reading
 *   (`[0, 1]`), independent of the score. Used by [com.aicheck.domain.evidence.EvidenceEngine]
 *   to down-weight shaky evidence (e.g. a classifier run on a very small image).
 * @param description Honest, user-facing summary. Must not overclaim — "No generator
 *   metadata found" rather than "Human-made".
 * @param evidence Optional raw detail (e.g. the literal EXIF string matched) shown in
 *   an expandable "why" section.
 */
data class DetectionSignal(
    val type: SignalType,
    val availability: SignalAvailability,
    val score: Float?,
    val confidence: Float,
    val description: String,
    val evidence: String? = null,
) {
    init {
        require(score == null || score in 0f..1f) { "score must be in [0,1], was $score" }
        require(confidence in 0f..1f) { "confidence must be in [0,1], was $confidence" }
        require(availability == SignalAvailability.AVAILABLE || score == null) {
            "score must be null when availability is $availability"
        }
    }

    companion object {
        fun unavailable(type: SignalType, description: String): DetectionSignal =
            DetectionSignal(
                type = type,
                availability = SignalAvailability.UNAVAILABLE,
                score = null,
                confidence = 0f,
                description = description,
            )

        fun error(type: SignalType, description: String): DetectionSignal =
            DetectionSignal(
                type = type,
                availability = SignalAvailability.ERROR,
                score = null,
                confidence = 0f,
                description = description,
            )
    }
}
