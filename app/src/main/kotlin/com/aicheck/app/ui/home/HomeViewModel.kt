package com.aicheck.app.ui.home

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

class HomeViewModel(historyRepository: HistoryRepository) : ViewModel() {

    val recentChecks: StateFlow<List<HistoryEntry>> = historyRepository.observeRecent(RECENT_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        private const val RECENT_LIMIT = 3

        val Factory = viewModelFactory {
            initializer { HomeViewModel(appContainer().historyRepository) }
        }
    }
}
