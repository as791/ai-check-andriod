package com.aicheck.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

enum class BubbleState { IDLE, ANALYZING, RESULT, ERROR }

/**
 * The floating bubble's visuals, drawn manually on a plain [View] (no Compose):
 * hosting a `ComposeView` inside a raw [android.view.WindowManager] overlay window
 * needs its own manual Lifecycle/ViewModelStore/SavedStateRegistry owner wiring to
 * avoid crashing, which is one more device-specific correctness risk this
 * already-hard-to-test feature doesn't need — plain [Canvas] drawing is the
 * simpler, more robust choice for a window that isn't hosted by an Activity.
 */
class BubbleView(context: Context) : View(context) {

    var state: BubbleState = BubbleState.IDLE
        set(value) {
            field = value
            invalidate()
        }

    var resultPercent: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var resultColor: Int = Color.GRAY
        set(value) {
            field = value
            invalidate()
        }

    private var analyzingSweepStart = 0f
    private var analyzingAnimator: ValueAnimator? = null

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1B1F3B") }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - 8f
        val ringRect = RectF(cx - radius + 10f, cy - radius + 10f, cx + radius - 10f, cy + radius - 10f)

        canvas.drawCircle(cx, cy, radius, backgroundPaint)

        when (state) {
            BubbleState.IDLE -> {
                textPaint.textSize = radius * 0.55f
                canvas.drawText("AI", cx, cy + textPaint.textSize / 3f, textPaint)
            }
            BubbleState.ANALYZING -> {
                ringPaint.color = Color.parseColor("#4A50C4")
                canvas.drawArc(ringRect, analyzingSweepStart, 120f, false, ringPaint)
            }
            BubbleState.RESULT -> {
                ringPaint.color = resultColor
                canvas.drawArc(ringRect, -90f, 360f * (resultPercent / 100f), false, ringPaint)
                textPaint.textSize = radius * 0.5f
                canvas.drawText("$resultPercent%", cx, cy + textPaint.textSize / 3f, textPaint)
            }
            BubbleState.ERROR -> {
                ringPaint.color = Color.parseColor("#D8342A")
                canvas.drawArc(ringRect, 0f, 360f, false, ringPaint)
                textPaint.textSize = radius * 0.6f
                canvas.drawText("!", cx, cy + textPaint.textSize / 3f, textPaint)
            }
        }
    }

    fun startAnalyzingAnimation() {
        analyzingAnimator?.cancel()
        analyzingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1_200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                analyzingSweepStart = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopAnalyzingAnimation() {
        analyzingAnimator?.cancel()
        analyzingAnimator = null
    }
}
