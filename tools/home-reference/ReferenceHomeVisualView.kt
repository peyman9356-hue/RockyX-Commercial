package com.rockyx.home.reference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.rockyx.app.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Reference-fidelity Home hero.
 *
 * The static visual is the user's approved Falow/reference composition.
 * The renderer adds only a restrained light pulse so the exact composition
 * remains visually dominant while the interaction surface stays responsive.
 */
class ReferenceHomeVisualView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private val bitmap: Bitmap = BitmapFactory.decodeResource(
        resources,
        R.drawable.rocky_home_reference_hero
    )

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringRect = RectF()
    private var running = false
    private var lastNanos = 0L
    private var time = 0f

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 0.016f
            else ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = now
            time += dt
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastNanos = 0L
        removeCallbacks(frame)
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(frame)
        lastNanos = 0L
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        val side = min(width, maxHeight)
        setMeasuredDimension(width, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(6, 12, 20))

        val side = min(width.toFloat(), height.toFloat())
        val left = (width - side) * 0.5f
        val top = (height - side) * 0.5f
        val dst = RectF(left, top, left + side, top + side)
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)

        drawReferencePulse(canvas, left, top, side)
    }

    private fun drawReferencePulse(canvas: Canvas, left: Float, top: Float, side: Float) {
        val cadence = 5.6f
        val phase = (time % cadence) / cadence
        val activeWindow = 0.28f
        if (phase >= activeWindow) return

        val p = (sin((phase / activeWindow) * PI).toFloat()).coerceIn(0f, 1f)
        val cx = left + side * 0.50f
        val cy = top + side * 0.52f
        val r = side * 0.405f

        val centerAngle = (Math.toRadians(165.0) - phase / activeWindow * Math.toRadians(235.0)).toFloat()
        val sweep = (32f + p * 54f)

        ringRect.set(cx - r, cy - r, cx + r, cy + r)
        pulsePaint.strokeWidth = 2.0f + p * 4.0f
        pulsePaint.color = Color.argb((40f + p * 140f).toInt().coerceIn(0, 190), 246, 213, 158)
        canvas.drawArc(ringRect, Math.toDegrees(centerAngle.toDouble()).toFloat(), sweep, false, pulsePaint)

        // Localized glow on the crystal nodes.
        for (i in 0 until 18) {
            val a = -PI.toFloat() + i * (2f * PI.toFloat() / 18f)
            val d = angularDistance(a, centerAngle)
            val hit = (1f - (d / 0.38f)).coerceIn(0f, 1f) * p
            if (hit <= 0.02f) continue
            val x = cx + cos(a) * r
            val y = cy + sin(a) * r
            nodePaint.shader = RadialGradient(
                x, y, side * (0.035f + hit * 0.05f),
                intArrayOf(
                    Color.argb((180f * hit).toInt().coerceIn(0, 180), 255, 226, 173),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, side * (0.032f + hit * 0.055f), nodePaint)
        }

        // Very restrained global shimmer during the pulse.
        glowPaint.shader = RadialGradient(
            cx, cy, side * 0.42f,
            intArrayOf(
                Color.argb((14f * p).toInt(), 255, 221, 171),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, side * 0.42f, glowPaint)
        glowPaint.shader = null
        nodePaint.shader = null
    }

    private fun angularDistance(a: Float, b: Float): Float {
        val full = 2f * PI.toFloat()
        val d = kotlin.math.abs((a - b) % full)
        return min(d, full - d)
    }
}
