package com.aicheck.app.data.sharing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import com.aicheck.domain.model.AnalysisResult
import com.aicheck.domain.model.Classification
import com.aicheck.domain.model.SignalAvailability
import com.aicheck.domain.model.SignalType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Renders a shareable result-card PNG entirely from the aggregated result — never
 * the user's original image, to avoid unnecessary privacy/copyright exposure once
 * it's shared onward (see docs/PRIVACY.md).
 */
class ResultCardRenderer(private val context: Context) {

    suspend fun render(result: AnalysisResult): File = withContext(Dispatchers.Default) {
        val width = 1080
        val height = 1350
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val backgroundColor = Color.parseColor("#121318")
        val accentColor = classificationColor(result.classification)
        val onBackground = Color.parseColor("#F3F3FA")
        val mutedColor = Color.parseColor("#A6A6B8")
        val margin = 72f

        canvas.drawColor(backgroundColor)
        var y = 96f

        val headerPaint = TextPaint().apply {
            color = mutedColor
            textSize = 34f
            isAntiAlias = true
        }
        canvas.drawText("AI CHECK", margin, y, headerPaint)
        y += 110f

        val percentPaint = TextPaint().apply {
            color = onBackground
            textSize = 140f
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText("${(result.aiLikelihood * 100).toInt()}%", margin, y, percentPaint)
        y += 70f

        val labelPaint = TextPaint().apply {
            color = accentColor
            textSize = 44f
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText(classificationLabel(result.classification), margin, y, labelPaint)
        y += 90f

        canvas.drawLine(
            margin,
            y,
            width - margin,
            y,
            Paint().apply { color = Color.parseColor("#2B2C36"); strokeWidth = 2f },
        )
        y += 70f

        val rowLabelPaint = TextPaint().apply { color = mutedColor; textSize = 32f; isAntiAlias = true }
        val rowValuePaint = TextPaint().apply {
            color = onBackground
            textSize = 32f
            isAntiAlias = true
            isFakeBoldText = true
        }

        for ((label, value) in buildRows(result)) {
            canvas.drawText(label, margin, y, rowLabelPaint)
            val valueWidth = rowValuePaint.measureText(value)
            canvas.drawText(value, width - margin - valueWidth, y, rowValuePaint)
            y += 64f
        }

        y += 50f
        val disclaimerText =
            "AI detection is probabilistic. It can produce false positives and false negatives."
        val disclaimerPaint = TextPaint().apply {
            color = mutedColor
            textSize = 28f
            isAntiAlias = true
        }
        val disclaimerLayout = StaticLayout.Builder.obtain(
            disclaimerText,
            0,
            disclaimerText.length,
            disclaimerPaint,
            (width - 2 * margin).toInt(),
        ).setLineSpacing(4f, 1.1f).build()
        canvas.save()
        canvas.translate(margin, y)
        disclaimerLayout.draw(canvas)
        canvas.restore()

        val footerPaint = TextPaint().apply { color = mutedColor; textSize = 28f; isAntiAlias = true }
        canvas.drawText("Checked with AI Check", margin, height - 80f, footerPaint)

        val outFile = File(sharedCardsDir(context), "result_${UUID.randomUUID()}.png")
        FileOutputStream(outFile).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        outFile
    }

    private fun buildRows(result: AnalysisResult): List<Pair<String, String>> {
        fun describe(type: SignalType, label: String): Pair<String, String> {
            val signal = result.signals.firstOrNull { it.type == type }
            val available = signal != null && signal.availability == SignalAvailability.AVAILABLE
            val score = signal?.score
            val value = when {
                !available || score == null -> "Not available"
                type == SignalType.AI_CLASSIFIER -> "${(score * 100).toInt()}%"
                score > 0f -> "Found"
                else -> "Not found"
            }
            return label to value
        }
        return listOf(
            describe(SignalType.AI_CLASSIFIER, "Visual analysis"),
            describe(SignalType.GENERATOR_METADATA, "Generator metadata"),
            describe(SignalType.CONTENT_CREDENTIALS, "Content credentials"),
        )
    }

    private fun classificationLabel(classification: Classification): String = when (classification) {
        Classification.HIGH -> "LIKELY AI-GENERATED"
        Classification.UNCERTAIN -> "UNCERTAIN"
        Classification.LOW -> "LOW AI LIKELIHOOD"
    }

    private fun classificationColor(classification: Classification): Int = when (classification) {
        Classification.HIGH -> Color.parseColor("#FF6B5E")
        Classification.UNCERTAIN -> Color.parseColor("#F0C24B")
        Classification.LOW -> Color.parseColor("#5FD98A")
    }

    companion object {
        fun sharedCardsDir(context: Context): File = File(context.cacheDir, "shared").apply { mkdirs() }
    }
}
