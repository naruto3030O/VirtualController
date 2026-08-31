package com.example.virtualcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Gas / reverse indicator, drawn from Till_front_back_indicator.svg.
 *
 * 16 slanted bars. Index 5 from the bottom is the neutral marker (in the
 * source SVG it is the only bar with a linear rather than radial gradient,
 * which is how it was identified). Bars 6..15 fill upward for gas in green,
 * bars 4..0 fill downward for reverse in orange-to-red.
 *
 * Drawn rather than imported because the fill is dynamic, and because the
 * SVG's 16 per-bar drop shadows have no VectorDrawable equivalent anyway.
 */
class ThrottleBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        // Source artboard, used to scale the geometry to whatever size the
        // view is given.
        private const val SRC_W = 109f
        private const val SRC_H = 247f

        private const val NEUTRAL_INDEX = 5
        private const val BAR_COUNT = 16

        private val IDLE = Color.parseColor("#1B1B1B")
        private val IDLE_EDGE = Color.parseColor("#2E2E2E")

        private val NEUTRAL_ACTIVE = Color.parseColor("#FFF94B")
        private val NEUTRAL_IDLE = Color.parseColor("#E8E8E0")

        private val GAS_LOW = Color.parseColor("#2BFF88")
        private val GAS_HIGH = Color.parseColor("#00FF6A")

        private val REV_LOW = Color.parseColor("#FF9A2B")
        private val REV_HIGH = Color.parseColor("#FF2B2B")
    }

    /**
     * Bar geometry lifted straight from the SVG path data, bottom bar first.
     * Each entry is (topY, bottomY) of the bar's left edge; the right edge is
     * offset downward by SLANT to give the slant.
     */
    private val barTops = floatArrayOf(
        199.25f, 188f, 177f, 166f, 155f,
        143f,                                   // neutral
        132f, 121f, 110f, 99f, 88f, 77f, 66f, 54f, 43f, 32f
    )

    private val barHeights = floatArrayOf(
        14.628f, 8.302f, 8.302f, 8.302f, 8.302f,
        8.302f,
        8.302f, 8.302f, 8.302f, 8.302f, 8.302f, 8.302f, 8.302f, 8.302f, 8.302f, 8.302f
    )

    private val leftX = 26f
    private val rightX = 81f
    private val slant = 12.698f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** 0..1 */
    var gas = 0.0
        set(value) { field = value; invalidate() }

    /** 0..1 */
    var reverse = 0.0
        set(value) { field = value; invalidate() }

    private fun barPath(index: Int, sx: Float, sy: Float): Path {
        val top = barTops[index]
        val height = barHeights[index]

        path.reset()
        path.moveTo(leftX * sx, top * sy)
        path.lineTo(rightX * sx, (top + slant) * sy)
        path.lineTo(rightX * sx, (top + slant + height) * sy)
        path.lineTo(leftX * sx, (top + height) * sy)
        path.close()

        return path
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val sx = width / SRC_W
        val sy = height / SRC_H

        // How many bars each side lights up.
        val gasBars = Math.round(gas * (BAR_COUNT - 1 - NEUTRAL_INDEX)).toInt()
        val revBars = Math.round(reverse * NEUTRAL_INDEX).toInt()

        val moving = gas > 0.001 || reverse > 0.001

        for (i in 0 until BAR_COUNT) {

            val p = barPath(i, sx, sy)

            when {
                i == NEUTRAL_INDEX -> {
                    fillPaint.shader = null
                    fillPaint.color =
                        if (moving) NEUTRAL_IDLE else NEUTRAL_ACTIVE
                }

                // Gas fills upward from just above neutral.
                i > NEUTRAL_INDEX && i - NEUTRAL_INDEX <= gasBars -> {
                    val t = (i - NEUTRAL_INDEX).toFloat() /
                            (BAR_COUNT - 1 - NEUTRAL_INDEX)

                    fillPaint.shader = null
                    fillPaint.color = blend(GAS_LOW, GAS_HIGH, t)
                }

                // Reverse fills downward from just below neutral.
                i < NEUTRAL_INDEX && NEUTRAL_INDEX - i <= revBars -> {
                    val t = (NEUTRAL_INDEX - i).toFloat() / NEUTRAL_INDEX

                    fillPaint.shader = null
                    fillPaint.color = blend(REV_LOW, REV_HIGH, t)
                }

                else -> {
                    fillPaint.shader = LinearGradient(
                        leftX * sx, 0f, rightX * sx, 0f,
                        IDLE, IDLE_EDGE, Shader.TileMode.CLAMP
                    )
                }
            }

            canvas.drawPath(p, fillPaint)
        }

        fillPaint.shader = null
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)

        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * clamped).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * clamped).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * clamped).toInt()
        )
    }
}