package com.aicheck.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.aicheck.app.R
import com.aicheck.app.ui.theme.ClassificationColors
import com.aicheck.domain.model.Classification

@Composable
fun classificationLabel(classification: Classification): String = when (classification) {
    Classification.HIGH -> stringResource(R.string.result_classification_high)
    Classification.UNCERTAIN -> stringResource(R.string.result_classification_uncertain)
    Classification.LOW -> stringResource(R.string.result_classification_low)
}

@Composable
fun classificationColor(classification: Classification): Color = when (classification) {
    Classification.HIGH -> ClassificationColors.high
    Classification.UNCERTAIN -> ClassificationColors.uncertain
    Classification.LOW -> ClassificationColors.low
}
