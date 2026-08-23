package com.aicheck.app.data.analysis

/**
 * Real pipeline stages, reported as each group of providers actually starts running —
 * not a fabricated timer. See AnalyzeImageUseCase / AnalyzeVideoUseCase.
 */
enum class AnalysisStage {
    PROVENANCE,
    METADATA,
    VISUAL,

    /** Video only: extracting still frames before any classification runs. */
    SAMPLING_FRAMES,
}
