package com.aicheck.app.ui.analyzing

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicheck.app.R
import com.aicheck.app.data.analysis.AnalysisStage

@Composable
fun AnalyzingScreen(
    encodedUri: String,
    isVideo: Boolean,
    onComplete: (Long) -> Unit,
    onCancel: () -> Unit,
    viewModel: AnalyzingViewModel = viewModel(factory = AnalyzingViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(encodedUri, isVideo) { viewModel.start(encodedUri, isVideo) }
    LaunchedEffect(state) {
        val success = state as? AnalyzingUiState.Success ?: return@LaunchedEffect
        onComplete(success.analysisId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (val current = state) {
            is AnalyzingUiState.Error -> ErrorContent(
                reason = current.reason,
                onRetry = { viewModel.retry(encodedUri, isVideo) },
                onCancel = onCancel,
            )
            is AnalyzingUiState.InProgress -> ProgressContent(current)
            AnalyzingUiState.Loading, is AnalyzingUiState.Success -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun ProgressContent(state: AnalyzingUiState.InProgress) {
    val bitmap = remember(state.previewFilePath) {
        runCatching { BitmapFactory.decodeFile(state.previewFilePath)?.asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.cd_image_preview),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(20.dp)),
        )
    }
    Spacer(modifier = Modifier.height(32.dp))
    CircularProgressIndicator()
    Spacer(modifier = Modifier.height(24.dp))
    Text(text = stringResource(R.string.analyzing_title), style = MaterialTheme.typography.titleLarge)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(stageLabel(state.stage)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun stageLabel(stage: AnalysisStage): Int = when (stage) {
    AnalysisStage.PROVENANCE -> R.string.analyzing_stage_provenance
    AnalysisStage.METADATA -> R.string.analyzing_stage_metadata
    AnalysisStage.VISUAL -> R.string.analyzing_stage_visual
    AnalysisStage.SAMPLING_FRAMES -> R.string.analyzing_stage_sampling_frames
}

@Composable
private fun ErrorContent(reason: ImageLoadFailureReason, onRetry: () -> Unit, onCancel: () -> Unit) {
    Text(text = stringResource(R.string.result_error_title), style = MaterialTheme.typography.titleLarge)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(errorMessage(reason)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.result_try_again))
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.result_check_another))
    }
}

private fun errorMessage(reason: ImageLoadFailureReason): Int = when (reason) {
    ImageLoadFailureReason.UNSUPPORTED -> R.string.result_error_unsupported
    ImageLoadFailureReason.CORRUPT -> R.string.result_error_corrupt
    ImageLoadFailureReason.TOO_LARGE -> R.string.result_error_too_large
    ImageLoadFailureReason.UNKNOWN -> R.string.result_error_generic
}
