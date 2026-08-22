package com.aicheck.app.ui.analyzing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aicheck.app.data.analysis.AnalyzeImageUseCase
import com.aicheck.app.data.image.ImageLoadException
import com.aicheck.app.data.image.ImageLoader
import com.aicheck.app.data.image.NormalizedImage
import com.aicheck.app.ui.appContainer
import com.aicheck.domain.model.AnalysisInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AnalyzingViewModel(
    private val imageLoader: ImageLoader,
    private val analyzeImageUseCase: AnalyzeImageUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AnalyzingUiState>(AnalyzingUiState.Loading)
    val uiState: StateFlow<AnalyzingUiState> = _uiState.asStateFlow()

    private var started = false
    private var currentNormalizedImage: NormalizedImage? = null

    fun start(encodedUri: String) {
        if (started) return
        started = true
        runAnalysis(encodedUri)
    }

    fun retry(encodedUri: String) {
        _uiState.value = AnalyzingUiState.Loading
        runAnalysis(encodedUri)
    }

    private fun runAnalysis(encodedUri: String) = viewModelScope.launch {
        val uri = Uri.parse(encodedUri)

        val normalized = try {
            imageLoader.normalize(uri)
        } catch (e: ImageLoadException.Unsupported) {
            _uiState.value = AnalyzingUiState.Error(ImageLoadFailureReason.UNSUPPORTED)
            return@launch
        } catch (e: ImageLoadException.Corrupt) {
            _uiState.value = AnalyzingUiState.Error(ImageLoadFailureReason.CORRUPT)
            return@launch
        } catch (e: ImageLoadException.TooLarge) {
            _uiState.value = AnalyzingUiState.Error(ImageLoadFailureReason.TOO_LARGE)
            return@launch
        } catch (e: Exception) {
            _uiState.value = AnalyzingUiState.Error(ImageLoadFailureReason.UNKNOWN)
            return@launch
        }
        currentNormalizedImage = normalized

        val input = AnalysisInput(
            originalFilePath = normalized.originalFile.absolutePath,
            normalizedFilePath = normalized.normalizedFile.absolutePath,
            originalMimeType = normalized.originalMimeType,
            widthPx = normalized.widthPx,
            heightPx = normalized.heightPx,
            fileSizeBytes = normalized.fileSizeBytes,
        )

        try {
            val (analysisId, _) = analyzeImageUseCase.run(input, normalized.normalizedFile) { stage ->
                _uiState.value = AnalyzingUiState.InProgress(normalized.normalizedFile.absolutePath, stage)
            }
            cleanUp(normalized)
            _uiState.value = AnalyzingUiState.Success(analysisId)
        } catch (e: Exception) {
            cleanUp(normalized)
            _uiState.value = AnalyzingUiState.Error(ImageLoadFailureReason.UNKNOWN)
        }
    }

    private fun cleanUp(normalized: NormalizedImage) {
        normalized.originalFile.delete()
        normalized.normalizedFile.delete()
        currentNormalizedImage = null
    }

    override fun onCleared() {
        super.onCleared()
        currentNormalizedImage?.let {
            it.originalFile.delete()
            it.normalizedFile.delete()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                AnalyzingViewModel(container.imageLoader, container.analyzeImageUseCase)
            }
        }
    }
}
