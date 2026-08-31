package com.example.virtualcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * LT / RT pad.
 *
 * This is NOT a slider any more. You press and hold it like a button; the
 * analog value ramps 0 -> 1 over RAMP_UP_MS and falls back to 0 over
 * RAMP_DOWN_MS on release. So it reads as a plain button to the user but
 * still produces a real analog axis for the game.
 *
 * Multi-touch is handled by pointer ID, same as JoystickView.
 */
class TriggerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val RAMP_UP_MS = 110.0
        private const val RAMP_DOWN_MS = 70.0
    }

    private val basePaint = Paint().apply {
        color = Color.parseColor("#2B2B2B")
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#6E8FBF")
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        textSize = 34f
        isFakeBoldText = true
    }

    private val rect = RectF()

    /** Text drawn in the middle of the pad, e.g. "LT". */
    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var value = 0.0
        private set

    var onValueChanged: ((Double) -> Unit)? = null

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var held = false
    private var lastFrameNanos = 0L
    private var animating = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val radius = min(w, h) * 0.14f

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, basePaint)

        val fillTop = h - h * value.toFloat()
        rect.set(0f, fillTop, w, h)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        if (label.isNotEmpty()) {
            val baseline = h / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(label, w / 2f, baseline, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {

                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {

                    activePointerId = event.getPointerId(event.actionIndex)

                    parent?.requestDisallowInterceptTouchEvent(true)

                    held = true
                    startAnimating()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {

                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    release()
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                release()
            }
        }

        return true
    }

    private fun release() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        held = false
        startAnimating()
    }

    private fun startAnimating() {
        if (animating) return

        animating = true
        lastFrameNanos = System.nanoTime()

        postOnAnimation(rampRunnable)
    }

    private val rampRunnable = object : Runnable {
        override fun run() {

            val now = System.nanoTime()
            val deltaMs = (now - lastFrameNanos) / 1_000_000.0
            lastFrameNanos = now

            val target = if (held) 1.0 else 0.0
            val rampMs = if (held) RAMP_UP_MS else RAMP_DOWN_MS
            val step = deltaMs / rampMs

            val previous = value

            value = if (held) {
                min(1.0, value + step)
            } else {
                kotlin.math.max(0.0, value - step)
            }

            if (value != previous) {
                onValueChanged?.invoke(value)
                invalidate()
            }

            if (value != target) {
                postOnAnimation(this)
            } else {
                animating = false
                // Make sure the exact endpoint is reported once.
                onValueChanged?.invoke(value)
                invalidate()
            }
        }
    }

    fun reset() {
        held = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        animating = false
        removeCallbacks(rampRunnable)
        value = 0.0
        onValueChanged?.invoke(0.0)
        invalidate()
    }
}