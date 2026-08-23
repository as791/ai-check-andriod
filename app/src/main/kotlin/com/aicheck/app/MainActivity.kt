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
 * video, ACTION_VIEW (e.g. "Open with" from a file manager), and a direct
 * "open this saved analysis" request (from the experimental overlay bubble —
 * see [Routes.EXTRA_OPEN_ANALYSIS_ID]). launchMode="singleTask" means a share
 * while the app is already running arrives via [onNewIntent] rather than a new
 * instance.
 */
class MainActivity : ComponentActivity() {
    private val pendingSharedMedia = mutableStateOf<SharedMedia?>(null)
    private val pendingResultId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

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

                    val resultId by pendingResultId
                    LaunchedEffect(resultId) {
                        resultId?.let {
                            navController.navigate(Routes.result(it))
                            pendingResultId.value = null
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val openAnalysisId = intent.getLongExtra(Routes.EXTRA_OPEN_ANALYSIS_ID, -1L)
        if (openAnalysisId >= 0) {
            pendingResultId.value = openAnalysisId
        } else {
            pendingSharedMedia.value = ShareIntentParser.extractSharedMedia(intent)
        }
    }
}
