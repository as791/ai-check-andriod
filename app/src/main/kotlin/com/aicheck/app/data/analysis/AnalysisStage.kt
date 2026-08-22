package com.aicheck.app.data.analysis

/**
 * Real pipeline stages, reported as each group of providers actually starts running —
 * not a fabricated timer. See AnalyzeImageUseCase.
 */
enum class AnalysisStage {
    PROVENANCE,
    METADATA,
    VISUAL,
}
