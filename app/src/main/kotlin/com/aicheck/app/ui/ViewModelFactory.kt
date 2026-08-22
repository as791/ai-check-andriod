package com.aicheck.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.aicheck.app.AiCheckApplication
import com.aicheck.app.AppContainer

/** Every screen's ViewModel is constructed from the same small [AppContainer]. */
internal fun CreationExtras.appContainer(): AppContainer {
    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AiCheckApplication
    return app.container
}
