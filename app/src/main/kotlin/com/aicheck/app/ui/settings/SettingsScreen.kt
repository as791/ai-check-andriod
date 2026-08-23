package com.aicheck.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aicheck.app.BuildConfig
import com.aicheck.app.R
import com.aicheck.app.data.detection.classifier.ModelAssets
import com.aicheck.app.data.detection.classifier.ModelConfig
import com.aicheck.app.overlay.OverlayCaptureService
import com.aicheck.app.overlay.OverlayPermissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val modelBundled = remember { ModelAssets.isBundled(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeading(stringResource(R.string.settings_privacy_heading))
            Text(text = stringResource(R.string.settings_privacy_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(24.dp))

            SectionHeading(stringResource(R.string.settings_model_heading))
            Text(
                text = if (modelBundled) {
                    stringResource(R.string.settings_model_body_present, ModelConfig.DISPLAY_NAME)
                } else {
                    stringResource(R.string.settings_model_body_absent)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(24.dp))

            SectionHeading(stringResource(R.string.settings_about_heading))
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(24.dp))

            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeading(stringResource(R.string.settings_experimental_heading))
            OverlaySection()
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * The overlay bubble needs three separate grants before it can run — draw-over-
 * other-apps, usage access, and a MediaProjection consent — each obtained via a
 * different system flow. This walks the user through them one switch-toggle at a
 * time rather than trying to silently chain three consecutive system dialogs,
 * which is both hard to get right and easy for a user to find confusing/alarming.
 */
@Composable
private fun OverlaySection() {
    val context = LocalContext.current
    var overlayEnabled by remember { mutableStateOf(false) }
    var permissionNotice by remember { mutableStateOf<Int?>(null) }

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { activityResult ->
        val data = activityResult.data
        if (activityResult.resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = OverlayCaptureService.startIntent(context, activityResult.resultCode, data)
            ContextCompat.startForegroundService(context, serviceIntent)
            overlayEnabled = true
            permissionNotice = null
        } else {
            overlayEnabled = false
        }
    }

    val systemSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // Just returning from Settings; the user taps the switch again to continue.
    }

    Text(text = stringResource(R.string.settings_overlay_body), style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(12.dp))

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = stringResource(R.string.settings_overlay_enable), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        Switch(
            checked = overlayEnabled,
            onCheckedChange = { checked ->
                if (!checked) {
                    context.stopService(Intent(context, OverlayCaptureService::class.java))
                    overlayEnabled = false
                    return@onCheckedChange
                }
                when {
                    !OverlayPermissions.canDrawOverlays(context) -> {
                        permissionNotice = R.string.settings_overlay_permission_overlay
                        systemSettingsLauncher.launch(OverlayPermissions.overlayPermissionIntent(context))
                    }
                    !OverlayPermissions.hasUsageAccess(context) -> {
                        permissionNotice = R.string.settings_overlay_permission_usage
                        systemSettingsLauncher.launch(OverlayPermissions.usageAccessSettingsIntent())
                    }
                    else -> {
                        permissionNotice = null
                        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    }
                }
            },
        )
    }

    permissionNotice?.let { notice ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleLarge)
    Spacer(modifier = Modifier.height(8.dp))
}
