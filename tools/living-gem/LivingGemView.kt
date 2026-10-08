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
import kotlin.math.abs
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
    private val facetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bandPath = Path()
    private val facetPath = Path()
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
    private var visualTimeSec = 0f
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
            visualTimeSec += dt
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
        val cy = h * 0.49f
        val gemR = min(w * 0.47f, h * 0.48f)
        val profile = VisualProfiles.forState(state)
        val timeSec = if (lastNanos == 0L) 0f else lastNanos / 1_000_000_000f

        canvas.drawColor(Color.rgb(5, 14, 23))
        glowPaint.alpha =
            (255f * (0.28f + profile.glow * 0.42f + pulse * 0.24f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, gemR * 1.13f, glowPaint)

        project(cx, cy, gemR)
        drawBackFacets(canvas, profile, timeSec)
        drawBackStructure(canvas, profile, timeSec)
        drawOuterRings(canvas, cx, cy, gemR, profile)
        drawRocky(canvas, cx, cy, timeSec)
        drawFrontFacets(canvas, profile, timeSec)
        drawGoldReflection(canvas, profile, timeSec)
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

    private fun drawBackFacets(canvas: Canvas, profile: VisualProfile, timeSec: Float) {
        drawFacets(canvas, front = false, profile = profile, timeSec = timeSec)
    }

    private fun drawFrontFacets(canvas: Canvas, profile: VisualProfile, timeSec: Float) {
        drawFacets(canvas, front = true, profile = profile, timeSec = timeSec)
    }

    private fun drawFacets(
        canvas: Canvas,
        front: Boolean,
        profile: VisualProfile,
        timeSec: Float
    ) {
        val cadence = pulseCadenceSec()
        val pulseValue = automaticPulseValue(cadence)
        val pulseCenter = ((visualTimeSec / cadence) * 2f * PI.toFloat()) - PI.toFloat()

        for (i in mesh.faceA.indices) {
            val a = mesh.faceA[i]
            val b = mesh.faceB[i]
            val c = mesh.faceC[i]
            val z = (depth[a] + depth[b] + depth[c]) / 3f
            if ((z >= 0.02f) != front) continue

            val shimmer = 0.5f + 0.5f * (
                (sin(timeSec * profile.lightSweepHz * 2f * PI.toFloat() + mesh.phase[a]) + 1f) * 0.5f
            )
            val faceAngle = (mesh.theta[a] + mesh.theta[b] + mesh.theta[c]) / 3f + yaw
            val pulseHit = ((0.42f - angularDistance(faceAngle, pulseCenter)).coerceAtLeast(0f) / 0.42f)

            val alphaBase = if (front) 52f else 28f
            val alpha = (alphaBase + profile.lightIntensity * 66f * shimmer + pulseHit * pulseValue * 84f)
                .toInt()
                .coerceIn(8, 158)

            val warm = 0.25f + 0.75f * shimmer
            val goldMix = (pulseHit * pulseValue * 0.85f).coerceIn(0f, 0.85f)
            val red = (154f + 84f * warm + 55f * goldMix).toInt().coerceIn(0, 255)
            val green = (178f + 56f * warm + 28f * goldMix).toInt().coerceIn(0, 255)
            val blue = (186f + 36f * warm - 42f * goldMix).toInt().coerceIn(0, 255)

            facetPaint.color = Color.argb(alpha, red, green, blue)
            facetPath.reset()
            facetPath.moveTo(projectedX[a], projectedY[a])
            facetPath.lineTo(projectedX[b], projectedY[b])
            facetPath.lineTo(projectedX[c], projectedY[c])
            facetPath.close()
            canvas.drawPath(facetPath, facetPaint)
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

    private fun pulseCadenceSec(): Float = when (state) {
        TrainingUiState.TRAINING -> 4.6f
        TrainingUiState.SUCCESS -> 4.2f
        TrainingUiState.READY -> 5.6f
        TrainingUiState.IDLE -> 6.4f
        TrainingUiState.REST -> 7.4f
    }

    private fun angularDistance(a: Float, b: Float): Float {
        val full = 2f * PI.toFloat()
        val d = abs((a - b) % full)
        return min(d, full - d)
    }

    private fun automaticPulseValue(cadence: Float): Float {
        val phase = (visualTimeSec % cadence) / cadence
        val duration = 0.13f
        if (phase > duration) return 0f
        val t = phase / duration
        return sin(t * PI.toFloat()).coerceIn(0f, 1f)
    }

    private fun drawGoldReflection(canvas: Canvas, profile: VisualProfile, timeSec: Float) {
        val cadence = pulseCadenceSec()
        val pulseValue = automaticPulseValue(cadence)
        val reflectionCenter = yaw + 0.62f
        val pulseCenter = ((visualTimeSec / cadence) * 2f * PI.toFloat()) - PI.toFloat()

        for (i in mesh.faceA.indices) {
            val a = mesh.faceA[i]
            val b = mesh.faceB[i]
            val c = mesh.faceC[i]
            val z = (depth[a] + depth[b] + depth[c]) / 3f
            if (z < 0.08f) continue

            val angle = (mesh.theta[a] + mesh.theta[b] + mesh.theta[c]) / 3f + yaw
            val staticHit = ((0.25f - angularDistance(angle, reflectionCenter)).coerceAtLeast(0f) / 0.25f)
            val movingHit = ((0.34f - angularDistance(angle, pulseCenter)).coerceAtLeast(0f) / 0.34f) * pulseValue
            val strength = (staticHit * 0.46f + movingHit).coerceIn(0f, 1f)
            if (strength <= 0.025f) continue

            facetPaint.color = Color.argb(
                (24f + strength * 126f).toInt().coerceIn(0, 156),
                244, 210, 154
            )
            facetPath.reset()
            facetPath.moveTo(projectedX[a], projectedY[a])
            facetPath.lineTo(projectedX[b], projectedY[b])
            facetPath.lineTo(projectedX[c], projectedY[c])
            facetPath.close()
            canvas.drawPath(facetPath, facetPaint)
        }
    }

    private fun drawRocky(canvas: Canvas, cx: Float, cy: Float, timeSec: Float) {
        val bitmap = dogBitmap ?: return
        val baseH = min(height * 0.70f, 470f)
        val baseW = baseH * bitmap.width / bitmap.height.toFloat()
        val breath = 1f + 0.006f * sin(timeSec * 1.4f)
        val dx = sin(timeSec * 0.45f) * 1.8f
        val dy = sin(timeSec * 1.4f) * 0.9f
        val w = baseW * breath
        val h = baseH * breath
        val left = cx - w * 0.5f + dx
        val top = cy - h * 0.33f + dy
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
        val automatic = automaticPulseValue(pulseCadenceSec())
        val p = maxOf(pulse, automatic)
        if (p <= 0.01f) return

        pulsePaint.strokeWidth = 2.0f + p * 1.4f
        pulsePaint.color = Color.argb((68f * p).toInt().coerceIn(0, 88), 255, 219, 157)
        val r = radius * (0.84f + (1f - p) * 0.22f)
        canvas.drawOval(
            cx - r, cy - r * 0.97f,
            cx + r, cy + r * 0.97f, pulsePaint
        )
    }
}
