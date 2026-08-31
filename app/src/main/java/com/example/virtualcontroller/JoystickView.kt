package com.example.virtualcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Analog stick.
 *
 * Key fix vs the old version: this tracks a single pointer ID for the whole
 * gesture and uses actionMasked. The old code used event.action + event.x,
 * which meant that as soon as a second finger touched the screen the knob
 * started following whichever finger happened to be at pointer index 0,
 * and ACTION_POINTER_UP (262) was never matched so the stick could stick.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val basePaint = Paint().apply {
        color = Color.parseColor("#2B2B2B")
        isAntiAlias = true
    }

    private val ringPaint = Paint().apply {
        color = Color.parseColor("#4A4A4A")
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val knobPaint = Paint().apply {
        color = Color.parseColor("#B0B0B0")
        isAntiAlias = true
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f

    private var knobX = 0f
    private var knobY = 0f

    /** Pointer that owns this stick right now, or INVALID_POINTER_ID. */
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    var normX = 0.0
        private set

    var normY = 0.0
        private set

    var onMoveListener: ((Double, Double) -> Unit)? = null

    private val deadzone = 0.06

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        centerX = w / 2f
        centerY = h / 2f

        baseRadius = min(w, h) / 2f * 0.92f
        knobRadius = baseRadius * 0.38f

        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius, ringPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {

                // Only claim the stick if it is currently free.
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {

                    val index = event.actionIndex

                    activePointerId = event.getPointerId(index)

                    // Stop any scrolling parent from stealing the gesture.
                    parent?.requestDisallowInterceptTouchEvent(true)

                    updateFromPointer(event, index)
                }
            }

            MotionEvent.ACTION_MOVE -> {

                if (activePointerId != MotionEvent.INVALID_POINTER_ID) {

                    val index = event.findPointerIndex(activePointerId)

                    if (index >= 0) {
                        updateFromPointer(event, index)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {

                // Only release if it was OUR finger that lifted.
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

    private fun updateFromPointer(event: MotionEvent, pointerIndex: Int) {

        var dx = event.getX(pointerIndex) - centerX
        var dy = event.getY(pointerIndex) - centerY

        val distance = sqrt(dx * dx + dy * dy)
        val maxDistance = baseRadius - knobRadius

        if (maxDistance <= 0f) return

        if (distance > maxDistance) {
            dx = dx / distance * maxDistance
            dy = dy / distance * maxDistance
        }

        knobX = centerX + dx
        knobY = centerY + dy

        var x = (dx / maxDistance).toDouble().coerceIn(-1.0, 1.0)
        // Screen Y grows downward, XInput Y grows upward.
        var y = (-dy / maxDistance).toDouble().coerceIn(-1.0, 1.0)

        val magnitude = sqrt(x * x + y * y)

        if (magnitude < deadzone) {
            x = 0.0
            y = 0.0
        } else {
            val normalizedMagnitude =
                ((magnitude - deadzone) / (1.0 - deadzone)).coerceIn(0.0, 1.0)

            val scale = normalizedMagnitude / magnitude

            x *= scale
            y *= scale
        }

        normX = x
        normY = y

        onMoveListener?.invoke(normX, normY)

        invalidate()
    }

    private fun release() {

        activePointerId = MotionEvent.INVALID_POINTER_ID

        knobX = centerX
        knobY = centerY

        normX = 0.0
        normY = 0.0

        onMoveListener?.invoke(0.0, 0.0)

        invalidate()
    }

    fun reset() {
        release()
    }
}