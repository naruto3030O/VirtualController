package com.example.virtualcontroller

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Look-around stick, built from Joystick.svg.
 *
 * The SVG is a single flat image, so it is split into two drawables: a static
 * base (bezel + dish) and a knob that this view translates. Two Figma effects
 * have no VectorDrawable equivalent and are drawn here instead:
 *
 *   dish inner shadow   dy 1, blur 5.35, black 81%
 *   knob drop shadow    dy 5, blur 2,    black 52%
 *
 * Knob travel runs to the outer bezel edge (bezel r 54.5 - knob r 30.5),
 * so it overlaps the dish rim at full deflection by design.
 *
 * Recentres on release. Multi-touch handled by pointer ID.
 */
class ViewJoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val SRC_W = 147f
        private const val SRC_H = 150f

        // Dish, in source units. Used for the inner shadow.
        private const val DISH_CX = 74f
        private const val DISH_CY = 75f
        private const val DISH_R = 42f

        // Outer bezel. Bounds the knob's travel.
        private const val BEZEL_R = 54.5f

        private const val KNOB_R = 30.5f

        private const val INNER_DY = 1f
        private const val INNER_BLUR = 5.35f
        private const val INNER_ALPHA = 0.81f

        private const val DROP_DY = 5f
        private const val DROP_BLUR = 2f
        private const val DROP_ALPHA = 0.52f

        private const val DEADZONE = 0.06
    }

    private val baseDrawable =
        ContextCompat.getDrawable(context, R.drawable.ic_joystick_base)

    private val knobDrawable =
        ContextCompat.getDrawable(context, R.drawable.ic_joystick_knob)

    private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val dropShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val clipPath = Path()

    private var scale = 1f

    /** Knob offset from rest, in pixels. */
    private var offsetX = 0f
    private var offsetY = 0f

    private var maxTravel = 0f

    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    var normX = 0.0
        private set

    var normY = 0.0
        private set

    var onMoveListener: ((Double, Double) -> Unit)? = null

    init {
        // BlurMaskFilter is unreliable on some hardware-accelerated paths.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        scale = min(w / SRC_W, h / SRC_H)

        // Knob rim reaches the outer bezel edge.
        maxTravel = (BEZEL_R - KNOB_R) * scale

        baseDrawable?.setBounds(0, 0, w, h)
        knobDrawable?.setBounds(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        baseDrawable?.draw(canvas)

        drawDishInnerShadow(canvas)

        val kx = DISH_CX * scale + offsetX
        val ky = DISH_CY * scale + offsetY

        drawKnobDropShadow(canvas, kx, ky)


        // The knob drawable shares the base's viewport, so translating by the
        // offset alone puts it in the right place.
        canvas.save()
        canvas.translate(offsetX, offsetY)
        knobDrawable?.draw(canvas)
        canvas.restore()
    }

    private fun drawDishInnerShadow(canvas: Canvas) {
        val blur = INNER_BLUR * scale
        if (blur <= 0f) return

        val r = DISH_R * scale
        val cx = DISH_CX * scale
        val cy = DISH_CY * scale

        val strokeWidth = blur * 2f

        innerShadowPaint.strokeWidth = strokeWidth
        innerShadowPaint.color = Color.BLACK
        innerShadowPaint.alpha = (INNER_ALPHA * 255).toInt()
        innerShadowPaint.maskFilter =
            BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)

        clipPath.reset()
        clipPath.addCircle(cx, cy, r, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)

        canvas.drawCircle(
            cx,
            cy + INNER_DY * scale,
            r + strokeWidth / 2f,
            innerShadowPaint
        )

        canvas.restore()
    }

    private fun drawKnobDropShadow(canvas: Canvas, kx: Float, ky: Float) {
        val blur = DROP_BLUR * scale
        if (blur <= 0f) return

        dropShadowPaint.color = Color.BLACK
        dropShadowPaint.alpha = (DROP_ALPHA * 255).toInt()
        dropShadowPaint.maskFilter =
            BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)

        canvas.drawCircle(
            kx,
            ky + DROP_DY * scale,
            KNOB_R * scale,
            dropShadowPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    val index = event.actionIndex

                    activePointerId = event.getPointerId(index)

                    parent?.requestDisallowInterceptTouchEvent(true)

                    update(event, index)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    val index = event.findPointerIndex(activePointerId)
                    if (index >= 0) update(event, index)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    release()
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> release()
        }

        return true
    }

    private fun update(event: MotionEvent, pointerIndex: Int) {

        if (maxTravel <= 0f) return

        var dx = event.getX(pointerIndex) - DISH_CX * scale
        var dy = event.getY(pointerIndex) - DISH_CY * scale

        val distance = sqrt(dx * dx + dy * dy)

        if (distance > maxTravel) {
            dx = dx / distance * maxTravel
            dy = dy / distance * maxTravel
        }

        offsetX = dx
        offsetY = dy

        var x = (dx / maxTravel).toDouble().coerceIn(-1.0, 1.0)
        var y = (-dy / maxTravel).toDouble().coerceIn(-1.0, 1.0)

        val magnitude = sqrt(x * x + y * y)

        if (magnitude < DEADZONE) {
            x = 0.0
            y = 0.0
        } else {
            val k = ((magnitude - DEADZONE) / (1.0 - DEADZONE))
                .coerceIn(0.0, 1.0) / magnitude

            x *= k
            y *= k
        }

        normX = x
        normY = y

        onMoveListener?.invoke(normX, normY)

        invalidate()
    }

    private fun release() {
        activePointerId = MotionEvent.INVALID_POINTER_ID

        offsetX = 0f
        offsetY = 0f

        normX = 0.0
        normY = 0.0

        onMoveListener?.invoke(0.0, 0.0)

        invalidate()
    }

    fun reset() = release()
}