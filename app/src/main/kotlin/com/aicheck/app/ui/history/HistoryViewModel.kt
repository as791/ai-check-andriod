package com.aicheck.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aicheck.app.data.storage.HistoryEntry
import com.aicheck.app.data.storage.HistoryRepository
import com.aicheck.app.ui.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val historyRepository: HistoryRepository) : ViewModel() {

    val entries: StateFlow<List<HistoryEntry>> = historyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { historyRepository.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch { historyRepository.clearAll() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { HistoryViewModel(appContainer().historyRepository) }
        }
    }
}
