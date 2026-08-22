package com.aicheck.app.data.sharing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Builds the native Android share-sheet intent for a rendered result card. */
object ShareIntentFactory {
    fun forResultCard(context: Context, cardFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cardFile)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, null)
    }
}
