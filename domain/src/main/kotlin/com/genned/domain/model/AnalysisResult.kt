package com.genned.domain.model

/**
 * The aggregated output of [com.genned.domain.evidence.EvidenceEngine], ready for the
 * result screen. [aiLikelihood] and [classification] are estimates, never proof.
 */
data class AnalysisResult(
    val aiLikelihood: Float,
    val classification: Classification,
    val signals: List<DetectionSignal>,
    val limitations: List<String>,
)
