package com.aicheck.app.ui.result

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aicheck.app.data.sharing.ResultCardRenderer
import com.aicheck.app.data.storage.HistoryRepository
import com.aicheck.app.data.storage.SavedAnalysis
import com.aicheck.app.ui.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File

class ResultViewModel(
    savedStateHandle: SavedStateHandle,
    private val historyRepository: HistoryRepository,
    private val resultCardRenderer: ResultCardRenderer,
) : ViewModel() {

    private val analysisId: Long = checkNotNull(savedStateHandle.get<String>("analysisId")).toLong()

    val savedAnalysis: StateFlow<SavedAnalysis?> = historyRepository.observeSavedAnalysis(analysisId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun renderShareCard(): File? {
        val analysis = savedAnalysis.value ?: return null
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
