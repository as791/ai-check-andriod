package com.genned.app.ui.result

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.genned.app.R
import com.genned.app.data.sharing.ShareIntentFactory
import com.genned.app.data.storage.SavedAnalysis
import com.genned.app.ui.components.DisclaimerCard
import com.genned.app.ui.components.SignalCard
import com.genned.app.ui.components.classificationColor
import com.genned.app.ui.components.classificationLabel
import kotlinx.coroutines.launch
import android.graphics.BitmapFactory

@Composable
fun ResultScreen(
    onCheckAnother: () -> Unit,
    onBack: () -> Unit,
    viewModel: ResultViewModel = viewModel(factory = ResultViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    when (val state = uiState) {
        ResultUiState.Loading -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
        ResultUiState.NotFound -> ResultNotFound(onBack = onBack)
        is ResultUiState.Loaded -> ResultContent(
            analysis = state.analysis,
            onShare = {
                scope.launch {
                    val card = viewModel.renderShareCard() ?: return@launch
                    context.startActivity(ShareIntentFactory.forResultCard(context, card))
                }
            },
            onCheckAnother = onCheckAnother,
        )
    }
}

@Composable
private fun ResultNotFound(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.result_not_found_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.result_not_found_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onBack) {
            Text(text = stringResource(R.string.cd_back))
        }
    }
}

@Composable
private fun ResultContent(
    analysis: SavedAnalysis,
    onShare: () -> Unit,
    onCheckAnother: () -> Unit,
) {
    val result = analysis.result

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 32.dp),
    ) {
        item {
            ResultHeader(analysis)
            Spacer(modifier = Modifier.height(24.dp))
        }
        item {
            Text(text = stringResource(R.string.result_why_heading), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
        }
        items(result.signals) { signal ->
            SignalCard(signal = signal, modifier = Modifier.padding(bottom = 12.dp))
        }
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(R.string.result_important_heading), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            DisclaimerCard()
            result.limitations.drop(1).forEach { limitation ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = limitation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.result_share))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onCheckAnother, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.result_check_another))
            }
        }
    }
}

@Composable
private fun ResultHeader(analysis: SavedAnalysis) {
    val bitmap = remember(analysis.thumbnailPath) {
        analysis.thumbnailPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.cd_image_preview),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .height(72.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column {
            Text(text = stringResource(R.string.result_ai_likelihood), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${(analysis.result.aiLikelihood * 100).toInt()}%",
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                text = classificationLabel(analysis.result.classification),
                style = MaterialTheme.typography.titleLarge,
                color = classificationColor(analysis.result.classification),
            )
        }
    }
}
