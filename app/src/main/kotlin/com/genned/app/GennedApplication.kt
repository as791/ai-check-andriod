package com.genned.app

import android.app.Application
import com.genned.app.data.storage.SharedCacheSweeper
import java.io.File
import java.util.concurrent.TimeUnit

class GennedApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Thread {
            // The age threshold spares an analysis or share already in flight at launch.
            SharedCacheSweeper.sweep(
                dir = File(cacheDir, "shared"),
                nowMillis = System.currentTimeMillis(),
                maxAgeMillis = TimeUnit.MINUTES.toMillis(15),
            )
        }.start()
    }
}
