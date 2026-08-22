package com.aicheck.app.ui.analyzing

import com.aicheck.app.data.analysis.AnalysisStage

enum class ImageLoadFailureReason { UNSUPPORTED, CORRUPT, TOO_LARGE, UNKNOWN }

sealed interface AnalyzingUiState {
    data object Loading : AnalyzingUiState
    data class InProgress(val previewFilePath: String, val stage: AnalysisStage) : AnalyzingUiState
    data class Success(val analysisId: Long) : AnalyzingUiState
    data class Error(val reason: ImageLoadFailureReason) : AnalyzingUiState
}
