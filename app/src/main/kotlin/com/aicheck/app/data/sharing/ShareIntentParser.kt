package com.aicheck.app.data.sharing

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

enum class SharedMediaKind { IMAGE, VIDEO }

data class SharedMedia(val uri: Uri, val kind: SharedMediaKind)

/**
 * Pulls the shared image/video out of whatever launched the app: a share-sheet
 * ACTION_SEND or ACTION_SEND_MULTIPLE, an ACTION_VIEW "open with", or a plain
 * launcher intent (which carries no media and correctly resolves to null).
 * Isolated from [com.aicheck.app.MainActivity] so this — the actual "which intents
 * does AI Check accept" contract — is unit testable without instrumentation.
 *
 * ACTION_SEND_MULTIPLE is handled deliberately, not just ACTION_SEND: several
 * share panels (notably Samsung's Gallery "Share via" sheet) dispatch
 * SEND_MULTIPLE even for a single selected item, so an app that only handles
 * SEND simply never appears as a share target from those sources. Only the
 * first item of a multi-select share is analyzed; batch analysis is future scope.
 */
object ShareIntentParser {
    fun extractSharedMedia(intent: Intent?): SharedMedia? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val kind = mediaKindOf(intent.type) ?: return null
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                uri?.let { SharedMedia(it, kind) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val kind = mediaKindOf(intent.type) ?: return null
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                uris?.firstOrNull()?.let { SharedMedia(it, kind) }
            }
            Intent.ACTION_VIEW -> intent.data?.let { SharedMedia(it, SharedMediaKind.IMAGE) }
            else -> null
        }
    }

    private fun mediaKindOf(mimeType: String?): SharedMediaKind? = when {
        mimeType?.startsWith("image/") == true -> SharedMediaKind.IMAGE
        mimeType?.startsWith("video/") == true -> SharedMediaKind.VIDEO
        else -> null
    }
}
