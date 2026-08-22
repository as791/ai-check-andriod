package com.aicheck.app.data.sharing

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * Pulls the image URI out of whatever launched the app: a share-sheet ACTION_SEND, an
 * ACTION_VIEW "open with", or a plain launcher intent (which carries no image and
 * correctly resolves to null). Isolated from [com.aicheck.app.MainActivity] so this —
 * the actual "which intents does AI Check accept as an image" contract — is unit
 * testable without instrumentation.
 */
object ShareIntentParser {
    fun extractImageUri(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    null
                }
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }
}
