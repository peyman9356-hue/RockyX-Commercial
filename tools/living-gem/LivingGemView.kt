package com.rockyx.livinggem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.rockyx.app.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class LivingGemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val mesh = GemMesh()
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f
        strokeCap = Paint.Cap.ROUND
    }
    private val frontEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.55f
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dogPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bandPath = Path()
    private val blinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val projectedX = FloatArray(mesh.count)
    private val projectedY = FloatArray(mesh.count)
    private val depth = FloatArray(mesh.count)

    private var dogBitmap: Bitmap? = null
    private var state = TrainingUiState.READY
    private var reducedMotion = false
    private var running = false
    private var lastNanos = 0L
    private var yaw = 0f
    private var pulse = 0f
    private var blinkPulse = 0f
    private var cachedW = 0
    private var cachedH = 0

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 0.016f
            else ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = now
            val profile = VisualProfiles.forState(state)
            val motion = if (reducedMotion) 0.14f else 1f
            yaw += profile.orbitDegPerSec * motion * dt * (PI.toFloat() / 180f)
            pulse = (pulse - dt * 1.65f).coerceAtLeast(0f)
            blinkPulse = (blinkPulse - dt * 4.8f).coerceAtLeast(0f)
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        glowPaint.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(54, 255, 214, 145), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    fun setRockyDrawable(resId: Int = R.drawable.rocky_home_rocky_cutout) {
        dogBitmap = BitmapFactory.decodeResource(resources, resId)
        invalidate()
    }

    fun setState(newState: TrainingUiState) {
        state = newState
        invalidate()
    }

    fun playEvent(event: GemEvent) {
        when (event) {
            GemEvent.SUCCESS_PULSE -> pulse = 1f
            GemEvent.BLINK -> blinkPulse = 1f
        }
        invalidate()
    }

    fun setReducedMotion(value: Boolean) {
        reducedMotion = value
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startClock()
    }

    override fun onDetachedFromWindow() {
        stopClock()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) {
            if (visibility == VISIBLE) startClock() else stopClock()
        }
    }

    private fun startClock() {
        if (running) return
        running = true
        lastNanos = 0L
        removeCallbacks(frame)
        postOnAnimation(frame)
    }

    private fun stopClock() {
        running = false
        removeCallbacks(frame)
        lastNanos = 0L
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cachedW = w
        cachedH = h
        val cx = w * 0.5f
        val cy = h * 0.39f
        val r = min(w.toFloat() * 0.43f, h.toFloat() * 0.37f)
        glowPaint.shader = RadialGradient(
            cx, cy, r * 1.32f,
            intArrayOf(
                Color.argb(76, 255, 214, 155),
                Color.argb(16, 74, 136, 154),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cachedW <= 0 || cachedH <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.40f
        val gemR = min(w * 0.445f, h * 0.40f)
        val profile = VisualProfiles.forState(state)
        val timeSec = if (lastNanos == 0L) 0f else lastNanos / 1_000_000_000f

        canvas.drawColor(Color.rgb(5, 14, 23))
        glowPaint.alpha =
            (255f * (0.28f + profile.glow * 0.42f + pulse * 0.24f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, gemR * 1.13f, glowPaint)

        project(cx, cy, gemR)
        drawBackStructure(canvas, profile, timeSec)
        drawOuterRings(canvas, cx, cy, gemR, profile)
        drawGoldBand(canvas, cx, cy, gemR, profile)
        drawRocky(canvas, cx, cy, timeSec)
        drawFrontStructure(canvas, profile, timeSec)
        drawBlink(canvas, cx, cy, timeSec)
        drawPulse(canvas, cx, cy, gemR)
    }

    private fun project(cx: Float, cy: Float, radius: Float) {
        val camera = 3.1f
        for (i in 0 until mesh.count) {
            val lon = mesh.theta[i] + yaw
            val cosLat = cos(mesh.lat[i])
            val x3 = cosLat * cos(lon) * mesh.radius[i]
            val y3 = sin(mesh.lat[i]) * 0.94f * mesh.radius[i]
            val z3 = cosLat * sin(lon) * mesh.radius[i]
            val p = camera / (camera - z3 * 0.82f)
            projectedX[i] = cx + x3 * radius * p
            projectedY[i] = cy + y3 * radius * p
            depth[i] = z3 * p
        }
    }

    private fun drawBackStructure(canvas: Canvas, profile: VisualProfile, timeSec: Float) {
        val baseAlpha = (42f + profile.lightIntensity * 76f).toInt()
        edgePaint.color = Color.argb(baseAlpha, 161, 194, 202)
        for (e in mesh.edgeA.indices) {
            val a = mesh.edgeA[e]
            val b = mesh.edgeB[e]
            if ((depth[a] + depth[b]) * 0.5f < 0.02f) {
                val shimmer = 0.62f + 0.38f * ((sin(timeSec * 0.7f + mesh.phase[a]) + 1f) * 0.5f)
                edgePaint.alpha = (baseAlpha * shimmer).toInt()
                canvas.drawLine(projectedX[a], projectedY[a], projectedX[b], projectedY[b], edgePaint)
            }
        }
        drawNodes(canvas, false, profile, timeSec)
    }

    private fun drawFrontStructure(canvas: Canvas, profile: VisualProfile, timeSec: Float) {
        val frontAlpha = (72f + profile.lightIntensity * 100f).toInt().coerceAtMost(205)
        frontEdgePaint.color = Color.argb(frontAlpha, 207, 216, 217)
        for (e in mesh.edgeA.indices) {
            val a = mesh.edgeA[e]
            val b = mesh.edgeB[e]
            if ((depth[a] + depth[b]) * 0.5f >= 0.02f) {
                val shimmer =
                    0.68f + 0.32f * ((sin(timeSec * (2.0f * PI.toFloat()) * 0.10f + mesh.phase[a]) + 1f) * 0.5f)
                frontEdgePaint.alpha = (frontAlpha * shimmer).toInt()
                canvas.drawLine(projectedX[a], projectedY[a], projectedX[b], projectedY[b], frontEdgePaint)
            }
        }
        drawNodes(canvas, true, profile, timeSec)
    }

    private fun drawNodes(
        canvas: Canvas,
        front: Boolean,
        profile: VisualProfile,
        timeSec: Float
    ) {
        for (i in 0 until mesh.count) {
            if ((depth[i] >= 0.02f) != front) continue
            val shimmer =
                0.42f + 0.58f * ((sin(timeSec * profile.lightSweepHz * 2f * PI.toFloat() + mesh.phase[i]) + 1f) * 0.5f)
            nodePaint.color =
                if (shimmer > 0.68f) {
                    Color.argb((78 + 140 * shimmer).toInt().coerceAtMost(235), 244, 211, 158)
                } else {
                    Color.argb((56 + 120 * shimmer).toInt().coerceAtMost(190), 122, 177, 188)
                }
            val size = if (front) 2.2f + shimmer * 1.6f else 1.4f + shimmer
            canvas.drawCircle(projectedX[i], projectedY[i], size, nodePaint)
        }
    }

    private fun drawOuterRings(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        profile: VisualProfile
    ) {
        ringPaint.strokeWidth = 1f
        ringPaint.color = Color.argb((72 + profile.lightIntensity * 60f).toInt(), 203, 213, 214)
        ringPaint.alpha = (76 + profile.lightIntensity * 70f).toInt()
        canvas.drawOval(
            cx - radius * 1.02f, cy - radius * 0.98f,
            cx + radius * 1.02f, cy + radius * 0.98f, ringPaint
        )
        ringPaint.alpha = 44
        canvas.drawOval(
            cx - radius * 0.90f, cy - radius * 0.87f,
            cx + radius * 0.90f, cy + radius * 0.87f, ringPaint
        )
    }

    private fun drawGoldBand(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        profile: VisualProfile
    ) {
        val bandLon = yaw * 0.82f + 0.55f
        bandPath.reset()
        val points = 11
        for (i in 0 until points) {
            val lat = -0.78f + 1.56f * i / (points - 1)
            val cl = cos(lat)
            val x3 = cl * cos(bandLon) * 0.96f
            val y3 = sin(lat) * 0.94f
            val z3 = cl * sin(bandLon) * 0.96f
            val p = 3.1f / (3.1f - z3 * 0.82f)
            val x = cx + x3 * radius * p
            val y = cy + y3 * radius * p
            if (i == 0) bandPath.moveTo(x, y) else bandPath.lineTo(x, y)
        }

        val width = radius * 0.23f
        var i = points - 1
        while (i >= 0) {
            val lat = -0.78f + 1.56f * i / (points - 1)
            val cl = cos(lat)
            val x3 = cl * cos(bandLon + 0.13f) * 0.96f
            val y3 = sin(lat) * 0.94f
            val z3 = cl * sin(bandLon + 0.13f) * 0.96f
            val p = 3.1f / (3.1f - z3 * 0.82f)
            val x = cx + x3 * radius * p
            val y = cy + y3 * radius * p
            bandPath.lineTo(x - width * 0.42f, y)
            i--
        }
        bandPath.close()

        bandPaint.color = Color.argb(
            (78f + profile.lightIntensity * 74f + pulse * 70f).toInt().coerceAtMost(205),
            244, 207, 143
        )
        canvas.drawPath(bandPath, bandPaint)
    }

    private fun drawRocky(canvas: Canvas, cx: Float, cy: Float, timeSec: Float) {
        val bitmap = dogBitmap ?: return
        val baseH = min(height * 0.53f, 356f)
        val baseW = baseH * bitmap.width / bitmap.height.toFloat()
        val breath = 1f + 0.006f * sin(timeSec * 1.4f)
        val dx = sin(timeSec * 0.45f) * 1.8f
        val dy = sin(timeSec * 1.4f) * 0.9f
        val w = baseW * breath
        val h = baseH * breath
        val left = cx - w * 0.5f + dx
        val top = cy - h * 0.23f + dy
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + w, top + h), dogPaint)
    }

    private fun drawBlink(canvas: Canvas, cx: Float, cy: Float, timeSec: Float) {
        val auto = (timeSec % 5.8f) in 4.76f..4.96f
        if (!auto && blinkPulse <= 0f) return
        val bitmap = dogBitmap ?: return
        val baseH = min(height * 0.53f, 356f)
        val baseW = baseH * bitmap.width / bitmap.height.toFloat()
        val left = cx - baseW * 0.5f
        val top = cy - baseH * 0.23f
        val eyeY = top + baseH * 0.285f
        val eyeHalf = baseW * 0.13f
        val amount = if (blinkPulse > 0f) blinkPulse
        else ((4.96f - (timeSec % 5.8f)) / 0.20f).coerceIn(0f, 1f)

        blinkPaint.color = Color.argb((150f * amount).toInt(), 9, 13, 17)
        blinkPaint.strokeWidth = maxOf(3f, baseH * 0.011f)
        canvas.drawLine(left + baseW * 0.32f - eyeHalf, eyeY, left + baseW * 0.32f + eyeHalf, eyeY, blinkPaint)
        canvas.drawLine(left + baseW * 0.68f - eyeHalf, eyeY, left + baseW * 0.68f + eyeHalf, eyeY, blinkPaint)
    }

    private fun drawPulse(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (pulse <= 0f) return
        pulsePaint.strokeWidth = 2.6f
        pulsePaint.color = Color.argb((180f * pulse).toInt(), 255, 219, 157)
        val r = radius * (0.98f + (1f - pulse) * 0.10f)
        canvas.drawOval(
            cx - r, cy - r * 0.98f,
            cx + r, cy + r * 0.98f, pulsePaint
        )
    }
}
