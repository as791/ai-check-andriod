package com.aicheck.app

import android.content.Intent
import android.net.Uri
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
import com.aicheck.app.data.sharing.ShareIntentParser
import com.aicheck.app.ui.navigation.AiCheckNavHost
import com.aicheck.app.ui.navigation.Routes
import com.aicheck.app.ui.theme.AiCheckTheme

/**
 * Single-Activity host. Handles three entry points into the same nav graph:
 * normal launch (Home), a share-sheet ACTION_SEND of an image, and ACTION_VIEW
 * (e.g. "Open with" from a file manager). launchMode="singleTask" means a share
 * while the app is already running arrives via [onNewIntent] rather than a new
 * instance.
 */
class MainActivity : ComponentActivity() {
    private val pendingImageUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImageUri.value = ShareIntentParser.extractImageUri(intent)

        setContent {
            AiCheckTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    AiCheckNavHost(navController = navController)

                    val uri by pendingImageUri
                    LaunchedEffect(uri) {
                        uri?.let {
                            navController.navigate(Routes.analyzing(it.toString()))
                            pendingImageUri.value = null
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingImageUri.value = ShareIntentParser.extractImageUri(intent)
    }
}
