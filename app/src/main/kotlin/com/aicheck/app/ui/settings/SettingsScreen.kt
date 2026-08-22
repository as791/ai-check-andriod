package com.aicheck.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicheck.app.BuildConfig
import com.aicheck.app.R
import com.aicheck.app.data.detection.classifier.ModelAssets
import com.aicheck.app.data.detection.classifier.ModelConfig

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
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleLarge)
    Spacer(modifier = Modifier.height(8.dp))
}
