package com.example.virtualcontroller

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * App background, from iPhone_17_-_5.svg.
 *
 * Two things in that file have no VectorDrawable equivalent, which is why
 * this is a view rather than a drawable:
 *
 *   1. The base fill is a radial gradient with a NON-uniform, rotated
 *      transform - scale(453.037, 984.96) at 114.727 degrees. Android's
 *      vector <gradient> only takes a single gradientRadius, so it cannot
 *      describe an ellipse. Shader.setLocalMatrix can, so the transform is
 *      rebuilt here exactly as the SVG composes it.
 *
 *   2. The teal pool along the bottom edge is a Gaussian blur
 *      (stdDeviation 30.05) over an ellipse that sits just below the frame.
 */
class BackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val SRC_W = 874f
        private const val SRC_H = 402f

        // Base gradient
        private val BASE_INNER = Color.parseColor("#0F0F0F")
        private val BASE_OUTER = Color.parseColor("#2B383A")

        // Bottom glow
        private val GLOW_INNER = Color.parseColor("#367078")
        private val GLOW_OUTER = Color.parseColor("#191919")

        private const val GLOW_CX = 437f
        private const val GLOW_CY = 416f
        private const val GLOW_RX = 333f
        private const val GLOW_RY = 24f

        /**
         * SVG stdDeviation was 30.05. BlurMaskFilter's radius is not sigma,
         * so this is scaled to match visually; raise it for a softer pool,
         * lower it for a tighter one.
         */
        private const val GLOW_BLUR = 60f
    }

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val glowRect = RectF()

    init {
        // BlurMaskFilter is unreliable on some hardware-accelerated paths.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (w == 0 || h == 0) return

        val sx = w / SRC_W
        val sy = h / SRC_H

        buildBaseShader(sx, sy)
        buildGlowShader(sx, sy)

        glowRect.set(
            (GLOW_CX - GLOW_RX) * sx,
            (GLOW_CY - GLOW_RY) * sy,
            (GLOW_CX + GLOW_RX) * sx,
            (GLOW_CY + GLOW_RY) * sy
        )

        glowPaint.maskFilter = BlurMaskFilter(
            GLOW_BLUR * minOf(sx, sy),
            BlurMaskFilter.Blur.NORMAL
        )
    }

    /**
     * Unit-circle gradient pushed through the SVG's composed transform:
     *
     *   rotate(-90 about 0,402)  the rect's own transform
     *   translate(201, 851.5)
     *   rotate(114.727)
     *   scale(453.037, 984.96)
     */
    private fun buildBaseShader(sx: Float, sy: Float) {
        val m = Matrix()

        m.setRotate(-90f, 0f, 402f)
        m.preTranslate(201f, 851.5f)
        m.preRotate(114.727f)
        m.preScale(453.037f, 984.96f)

        // Then map the 874x402 artboard onto the actual view.
        m.postScale(sx, sy)

        val shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(BASE_INNER, BASE_OUTER),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        shader.setLocalMatrix(m)

        basePaint.shader = shader
    }

    private fun buildGlowShader(sx: Float, sy: Float) {
        val m = Matrix()

        m.setTranslate(GLOW_CX, GLOW_CY)
        m.preScale(265f, 253.953f)
        m.postScale(sx, sy)

        val shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(GLOW_INNER, GLOW_OUTER),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        shader.setLocalMatrix(m)

        glowPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)

        // Sits mostly below the bottom edge; the blur is what spills upward.
        canvas.drawOval(glowRect, glowPaint)
    }
}
