package com.genned.app.ui.result

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.genned.app.data.sharing.ResultCardRenderer
import com.genned.app.data.storage.HistoryRepository
import com.genned.app.data.storage.SavedAnalysis
import com.genned.app.ui.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

sealed interface ResultUiState {
    data object Loading : ResultUiState
    data object NotFound : ResultUiState
    data class Loaded(val analysis: SavedAnalysis) : ResultUiState
}

class ResultViewModel(
    savedStateHandle: SavedStateHandle,
    private val historyRepository: HistoryRepository,
    private val resultCardRenderer: ResultCardRenderer,
) : ViewModel() {

    private val analysisId: Long? = savedStateHandle.get<String>("analysisId")?.toLongOrNull()

    val uiState: StateFlow<ResultUiState> = (
        if (analysisId == null) {
            flowOf(ResultUiState.NotFound)
        } else {
            historyRepository.observeSavedAnalysis(analysisId)
                .map { it?.let(ResultUiState::Loaded) ?: ResultUiState.NotFound }
        }
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResultUiState.Loading)

    suspend fun renderShareCard(): File? {
        val analysis = (uiState.value as? ResultUiState.Loaded)?.analysis ?: return null
        return resultCardRenderer.render(analysis.result)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                ResultViewModel(createSavedStateHandle(), container.historyRepository, container.resultCardRenderer)
            }
        }
    }
}
