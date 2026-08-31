package com.example.virtualcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Steering indicator, from left_right_tilt.svg.
 *
 * The dot rides the crescent's true centreline. That centreline is not a
 * simple curve: the crescent is bounded by cubic Beziers, so the midline is
 * sampled at construction by averaging the outer and inner edges, then
 * arc-length parameterised so the dot moves at a constant rate rather than
 * bunching up near the tips.
 *
 * (An earlier version approximated this with a single quadratic. It was exact
 * at the centre and at both tips and drifted up to 19 units in between, which
 * is why the dot appeared to float off the band.)
 */
class SteeringArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val SRC_W = 327f
        private const val SRC_H = 137f

        // Fraction of the centreline left unused at each tip, so the dot
        // does not overhang the tapered ends.
        private const val END_MARGIN = 0.03f

        private const val SAMPLES = 96

        private const val DOT_R = 7.5f

        private const val ARC_PATH =
            "M163.5 46C185 46 303 93 303 93C303 93 198.591 58 163.5 58" +
                    "C128.409 58 24 93 24 93C24 93 142 46 163.5 46Z"
    }

    // Crescent edges, in source coordinates.
    // Outer edge: apex -> right tip. Inner edge: right tip -> inner apex.
    private val topEdge = arrayOf(
        163.5f to 46f, 185f to 46f, 303f to 93f, 303f to 93f
    )

    private val innerEdge = arrayOf(
        303f to 93f, 303f to 93f, 198.591f to 58f, 163.5f to 58f
    )

    /** Sampled centreline, left tip to right tip, in source coordinates. */
    private val lineX = FloatArray(SAMPLES * 2 - 1)
    private val lineY = FloatArray(SAMPLES * 2 - 1)

    /** Cumulative arc length at each sample. */
    private val arcLength = FloatArray(SAMPLES * 2 - 1)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3C3C3C")
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val arcPath = Path()
    private val scaledPath = Path()

    /** -1 (full left) .. 0 (centre) .. +1 (full right) */
    var steering = 0.0
        set(value) {
            field = value.coerceIn(-1.0, 1.0)
            invalidate()
        }

    init {
        arcPath.set(parsePath())
        buildCentreline()
    }

    private fun parsePath(): Path =
        android.graphics.Path().apply {
            // Transcribed from the SVG rather than parsed, to avoid pulling in
            // a path parser for one shape.
            moveTo(163.5f, 46f)
            cubicTo(185f, 46f, 303f, 93f, 303f, 93f)
            cubicTo(303f, 93f, 198.591f, 58f, 163.5f, 58f)
            cubicTo(128.409f, 58f, 24f, 93f, 24f, 93f)
            cubicTo(24f, 93f, 142f, 46f, 163.5f, 46f)
            close()
        }

    private fun cubicAt(p: Array<Pair<Float, Float>>, t: Float): Pair<Float, Float> {
        val u = 1 - t

        val x = u * u * u * p[0].first +
                3 * u * u * t * p[1].first +
                3 * u * t * t * p[2].first +
                t * t * t * p[3].first

        val y = u * u * u * p[0].second +
                3 * u * u * t * p[1].second +
                3 * u * t * t * p[2].second +
                t * t * t * p[3].second

        return x to y
    }

    /**
     * Builds the centreline once. The right half is the average of the outer
     * and inner edges; the crescent is symmetric about x = 163.5, so the left
     * half is that mirrored.
     */
    private fun buildCentreline() {
        val mid = SAMPLES - 1

        for (i in 0 until SAMPLES) {
            val t = i.toFloat() / (SAMPLES - 1)

            val top = cubicAt(topEdge, t)
            val inner = cubicAt(innerEdge, 1 - t)

            val x = (top.first + inner.first) / 2f
            val y = (top.second + inner.second) / 2f

            // Right half, walking outward from the apex.
            lineX[mid + i] = x
            lineY[mid + i] = y

            // Left half, mirrored about the apex.
            lineX[mid - i] = 2 * 163.5f - x
            lineY[mid - i] = y
        }

        arcLength[0] = 0f

        for (i in 1 until lineX.size) {
            val dx = lineX[i] - lineX[i - 1]
            val dy = lineY[i] - lineY[i - 1]

            arcLength[i] = arcLength[i - 1] + kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }

    /**
     * Position at a given fraction of total arc length, so travel is uniform
     * instead of bunching where the curve is steep.
     */
    private fun pointAtFraction(fraction: Float): Pair<Float, Float> {
        val total = arcLength.last()
        val target = fraction.coerceIn(0f, 1f) * total

        var i = arcLength.binarySearch(target)
        if (i < 0) i = -i - 1

        if (i <= 0) return lineX[0] to lineY[0]
        if (i >= lineX.size) return lineX.last() to lineY.last()

        val span = arcLength[i] - arcLength[i - 1]
        val k = if (span > 0f) (target - arcLength[i - 1]) / span else 0f

        return (lineX[i - 1] + (lineX[i] - lineX[i - 1]) * k) to
                (lineY[i - 1] + (lineY[i] - lineY[i - 1]) * k)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val sx = width / SRC_W
        val sy = height / SRC_H

        // Crescent
        scaledPath.set(arcPath)

        val matrix = android.graphics.Matrix().apply { setScale(sx, sy) }
        scaledPath.transform(matrix)

        canvas.drawPath(scaledPath, arcPaint)

        // Dot position: steering -1..1 maps onto arc length along the
        // centreline, inset slightly at each end.
        val fraction = END_MARGIN +
                ((steering + 1.0) / 2.0).toFloat() * (1f - 2 * END_MARGIN)

        val point = pointAtFraction(fraction)

        val dx = point.first * sx
        val dy = point.second * sy

        val radius = DOT_R * minOf(sx, sy)

        // Glow, done as a radial gradient rather than a blurred bitmap.
        glowPaint.shader = RadialGradient(
            dx, dy, radius * 2.6f,
            intArrayOf(
                Color.parseColor("#B3FFF94B"),
                Color.parseColor("#40FFF94B"),
                Color.parseColor("#00FFF94B")
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.drawCircle(dx, dy, radius * 2.6f, glowPaint)

        // Core, matching the SVG's #FFFDC5 -> #FFF94B radial.
        dotPaint.shader = RadialGradient(
            dx, dy, radius,
            Color.parseColor("#FFFDC5"),
            Color.parseColor("#FFF94B"),
            Shader.TileMode.CLAMP
        )

        canvas.drawCircle(dx, dy, radius, dotPaint)
    }
}