package com.aicheck.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicheck.app.R
import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalAvailability
import com.aicheck.domain.model.SignalType

private fun labelRes(type: SignalType): Int = when (type) {
    SignalType.AI_CLASSIFIER -> R.string.signal_ai_classifier
    SignalType.CONTENT_CREDENTIALS -> R.string.signal_content_credentials
    SignalType.GENERATOR_METADATA -> R.string.signal_generator_metadata
    SignalType.WATERMARK -> R.string.signal_watermark
    SignalType.EXIF_METADATA -> R.string.signal_exif_metadata
}

/** One "why" card per [DetectionSignal] on the Result screen — honest, never fabricated. */
@Composable
fun SignalCard(signal: DetectionSignal, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = stringResource(labelRes(signal.type)), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))

            val score = signal.score
            val summary = when {
                signal.availability == SignalAvailability.UNAVAILABLE -> "Unavailable"
                signal.availability == SignalAvailability.ERROR -> "Couldn't be checked"
                signal.type == SignalType.AI_CLASSIFIER && score != null -> "${(score * 100).toInt()}% AI probability"
                score != null && score > 0f -> "Found"
                else -> "Not found"
            }
            Text(text = summary, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = signal.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            signal.evidence?.let { evidence ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
