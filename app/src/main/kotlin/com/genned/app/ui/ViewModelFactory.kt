package com.genned.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.genned.app.GennedApplication
import com.genned.app.AppContainer

/** Every screen's ViewModel is constructed from the same small [AppContainer]. */
internal fun CreationExtras.appContainer(): AppContainer {
    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as GennedApplication
    return app.container
}
