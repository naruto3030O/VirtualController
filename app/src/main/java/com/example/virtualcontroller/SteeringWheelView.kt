package com.example.virtualcontroller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * On-screen steering wheel.
 *
 * The source art was a PNG inside an SVG wrapper, and the wheel was not
 * centred in that canvas. A circle fitted to the rim put the true axis at
 * (1407.6, 769.5) with radius 680.8, twelve pixels above the bitmap's
 * bounding-box centre, so the asset was re-cropped square about that axis.
 * Rotating about the view centre therefore spins the wheel true, with no
 * wobble.
 *
 * Drag behaviour is relative: the wheel turns by the angle your finger sweeps
 * around the centre, so grabbing it anywhere feels natural and it never jumps
 * to meet your thumb.
 */
class SteeringWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /**
         * Degrees of wheel rotation for full lock. Real cars run 450-540, but
         * that needs hand-over-hand; on-screen wheels use a shorter throw so
         * full lock is reachable in one sweep. 180 is the usual compromise.
         */
        const val MAX_ANGLE = 180f

        /** Ignore rotation this small so a resting thumb does not creep. */
        private const val ANGLE_DEADZONE = 1.5f

        /** Spring-back speed on release, degrees per second. */
        private const val RETURN_SPEED = 900f

        /** Touches nearer the hub than this fraction of radius are ignored. */
        private const val HUB_EXCLUSION = 0.18f
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private var bitmap: Bitmap? = null
    private val srcRect = Rect()
    private val dstRect = RectF()

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    /** Current wheel rotation in degrees. Negative is left. */
    private var angle = 0f

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastTouchAngle = 0f

    private var returning = false
    private var lastFrameNanos = 0L

    /** -1 (full left) .. 0 .. +1 (full right) */
    var steering = 0.0
        private set

    var onSteeringChanged: ((Double) -> Unit)? = null

    init {
        bitmap = BitmapFactory.decodeResource(resources, R.drawable.wheel)

        bitmap?.let { srcRect.set(0, 0, it.width, it.height) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        centerX = w / 2f
        centerY = h / 2f

        val side = minOf(w, h).toFloat()
        radius = side / 2f

        dstRect.set(
            centerX - side / 2f,
            centerY - side / 2f,
            centerX + side / 2f,
            centerY + side / 2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bmp = bitmap ?: return

        canvas.save()
        canvas.rotate(angle, centerX, centerY)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        canvas.restore()
    }

    // -----------------------------------------------------------------
    // Touch
    // -----------------------------------------------------------------

    private fun touchAngle(event: MotionEvent, pointerIndex: Int): Float {
        val dx = event.getX(pointerIndex) - centerX
        val dy = event.getY(pointerIndex) - centerY

        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun withinGrip(event: MotionEvent, pointerIndex: Int): Boolean {
        val dx = event.getX(pointerIndex) - centerX
        val dy = event.getY(pointerIndex) - centerY

        val d = hypot(dx, dy)

        // Reject the dead centre, where a tiny movement would swing the
        // wheel wildly, and anything outside the rim.
        return d > radius * HUB_EXCLUSION && d < radius * 1.05f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    val index = event.actionIndex

                    if (!withinGrip(event, index)) return false

                    activePointerId = event.getPointerId(index)
                    lastTouchAngle = touchAngle(event, index)
                    returning = false

                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return true

                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true

                val current = touchAngle(event, index)

                var delta = current - lastTouchAngle

                // Keep the delta in -180..180 so crossing the 180/-180
                // boundary does not spin the wheel a full turn.
                while (delta > 180f) delta -= 360f
                while (delta < -180f) delta += 360f

                if (abs(delta) >= ANGLE_DEADZONE || angle != 0f) {
                    angle = (angle + delta).coerceIn(-MAX_ANGLE, MAX_ANGLE)
                    lastTouchAngle = current

                    publish()
                    invalidate()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    releaseGrip()
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> releaseGrip()
        }

        return true
    }

    private fun releaseGrip() {
        activePointerId = MotionEvent.INVALID_POINTER_ID

        if (angle != 0f && !returning) {
            returning = true
            lastFrameNanos = System.nanoTime()
            postOnAnimation(returnRunnable)
        }
    }

    /** Spring back to centre, the way a real wheel self-centres. */
    private val returnRunnable = object : Runnable {
        override fun run() {
            if (!returning) return

            val now = System.nanoTime()
            val dt = (now - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = now

            val step = RETURN_SPEED * dt

            angle = when {
                angle > step -> angle - step
                angle < -step -> angle + step
                else -> 0f
            }

            publish()
            invalidate()

            if (angle != 0f) {
                postOnAnimation(this)
            } else {
                returning = false
            }
        }
    }

    private fun publish() {
        steering = (angle / MAX_ANGLE).toDouble().coerceIn(-1.0, 1.0)
        onSteeringChanged?.invoke(steering)
    }

    fun reset() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        returning = false
        removeCallbacks(returnRunnable)

        angle = 0f

        publish()
        invalidate()
    }
}
