package com.aicheck.domain.model

/**
 * The kind of evidence a [com.aicheck.domain.provider.DetectionProvider] contributes.
 * Adding a new detector later means adding a case here and a weight in
 * [com.aicheck.domain.evidence.EvidenceWeights] — nothing else in the engine changes.
 */
enum class SignalType {
    AI_CLASSIFIER,
    EXIF_METADATA,
    GENERATOR_METADATA,
    CONTENT_CREDENTIALS,
    WATERMARK,
}
