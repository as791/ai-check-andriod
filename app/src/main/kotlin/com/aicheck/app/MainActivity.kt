package com.aicheck.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.aicheck.app.data.sharing.SharedMedia
import com.aicheck.app.data.sharing.SharedMediaKind
import com.aicheck.app.data.sharing.ShareIntentParser
import com.aicheck.app.ui.navigation.AiCheckNavHost
import com.aicheck.app.ui.navigation.Routes
import com.aicheck.app.ui.theme.AiCheckTheme

/**
 * Single-Activity host. Handles entry points into the same nav graph: normal
 * launch (Home), a share-sheet ACTION_SEND/ACTION_SEND_MULTIPLE of an image or
 * video, and ACTION_VIEW (e.g. "Open with" from a file manager).
 * launchMode="singleTask" means a share while the app is already running arrives
 * via [onNewIntent] rather than a new instance.
 */
class MainActivity : ComponentActivity() {
    private val pendingSharedMedia = mutableStateOf<SharedMedia?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSharedMedia.value = ShareIntentParser.extractSharedMedia(intent)

        setContent {
            AiCheckTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    AiCheckNavHost(navController = navController)

                    val media by pendingSharedMedia
                    LaunchedEffect(media) {
                        media?.let {
                            val isVideo = it.kind == SharedMediaKind.VIDEO
                            navController.navigate(Routes.analyzing(it.uri.toString(), isVideo))
                            pendingSharedMedia.value = null
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSharedMedia.value = ShareIntentParser.extractSharedMedia(intent)
    }
}
