package com.rockyx.home.reference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.view.View
import com.rockyx.app.R
import kotlin.math.min

/**
 * Reference-led Home hero renderer.
 *
 * The approved Home art remains a single, static bitmap. The gold network is
 * brightened only for an explicit START/CONTINUE training intent; attachment,
 * redraws, scrolling, and returning to Home never start an animation.
 *
 * The pulse mask is extracted from warm pixels in the reference bitmap and is
 * constrained to the visible right-side network. It deliberately does not draw
 * an invented orbit/arc or claim a complete canonical edge-traversal path.
 */
class ReferenceHomeVisualView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private val bitmap: Bitmap = requireNotNull(
        BitmapFactory.decodeResource(resources, R.drawable.rocky_home_reference_hero)
    ) { "The validated Home hero resource could not be decoded" }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val goldMask: Bitmap by lazy(LazyThreadSafetyMode.NONE) {
        createGoldNetworkMask(bitmap)
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(
            Color.rgb(255, 222, 164),
            PorterDuff.Mode.SRC_IN
        )
    }
    private val destination = RectF()

    private var pulseRunning = false
    private var pulseStartedNanos = 0L
    private var lastTrainingIntentEventId: String? = null
    private var onPulseComplete: (() -> Unit)? = null

    private val pulseFrame = object : Runnable {
        override fun run() {
            if (!pulseRunning) return

            val elapsedMs = (System.nanoTime() - pulseStartedNanos) / 1_000_000L
            if (elapsedMs >= PULSE_DURATION_MS) {
                pulseRunning = false
                invalidate()
                val completion = onPulseComplete
                onPulseComplete = null
                completion?.invoke()
                return
            }

            invalidate()
            postOnAnimation(this)
        }
    }

    /**
     * Starts one gold-network flare for one explicit training intent.
     * Repeated event IDs are ignored; a new intent replaces an active pulse.
     */
    fun playTrainingIntentPulse(
        trainingIntentEventId: String,
        onComplete: () -> Unit
    ) {
        if (trainingIntentEventId.isBlank()) return
        if (trainingIntentEventId == lastTrainingIntentEventId) return

        lastTrainingIntentEventId = trainingIntentEventId
        pulseStartedNanos = System.nanoTime()
        pulseRunning = true
        onPulseComplete = onComplete
        removeCallbacks(pulseFrame)
        postOnAnimation(pulseFrame)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        pulseRunning = false
        removeCallbacks(pulseFrame)
        onPulseComplete = null
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
        if (side <= 0f) return

        val left = (width - side) * 0.5f
        val top = (height - side) * 0.5f
        destination.set(left, top, left + side, top + side)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)

        if (pulseRunning) {
            val elapsedMs = (System.nanoTime() - pulseStartedNanos).coerceAtLeast(0L) / 1_000_000L
            val rampEnd = PULSE_DURATION_MS * PULSE_RAMP_FRACTION
            val ramp = (elapsedMs.toFloat() / rampEnd).coerceIn(0f, 1f)
            val eased = ramp * ramp * (3f - 2f * ramp)
            pulsePaint.alpha = (MAX_PULSE_ALPHA * eased).toInt().coerceIn(0, MAX_PULSE_ALPHA)
            canvas.drawBitmap(goldMask, null, destination, pulsePaint)
            pulsePaint.alpha = 255
        }
    }

    /**
     * Creates an alpha mask from warm-gold pixels present in the reference.
     * Rocky's foreground silhouette and Persian labels are excluded so the
     * pulse brightens network/facets rather than fur or text.
     */
    private fun createGoldNetworkMask(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val sourcePixels = IntArray(w * h)
        source.getPixels(sourcePixels, 0, w, 0, 0, w, h)

        val dogMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val dogCanvas = Canvas(dogMask)
        val dogPath = Path().apply {
            moveTo(w * 0.27f, h * 0.13f)
            lineTo(w * 0.30f, h * 0.12f)
            lineTo(w * 0.33f, h * 0.15f)
            lineTo(w * 0.36f, h * 0.23f)
            lineTo(w * 0.40f, h * 0.27f)
            lineTo(w * 0.50f, h * 0.24f)
            lineTo(w * 0.57f, h * 0.20f)
            lineTo(w * 0.61f, h * 0.13f)
            lineTo(w * 0.64f, h * 0.12f)
            lineTo(w * 0.68f, h * 0.17f)
            lineTo(w * 0.70f, h * 0.28f)
            lineTo(w * 0.71f, h * 0.42f)
            lineTo(w * 0.71f, h * 0.58f)
            lineTo(w * 0.73f, h * 0.72f)
            lineTo(w * 0.72f, h * 0.82f)
            lineTo(w * 0.68f, h * 0.90f)
            lineTo(w * 0.61f, h * 0.95f)
            lineTo(w * 0.40f, h * 0.95f)
            lineTo(w * 0.30f, h * 0.94f)
            lineTo(w * 0.24f, h * 0.89f)
            lineTo(w * 0.22f, h * 0.81f)
            lineTo(w * 0.21f, h * 0.69f)
            lineTo(w * 0.20f, h * 0.57f)
            lineTo(w * 0.20f, h * 0.46f)
            lineTo(w * 0.21f, h * 0.34f)
            lineTo(w * 0.23f, h * 0.24f)
            close()
        }
        dogCanvas.drawPath(dogPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        })
        val dogPixels = IntArray(w * h)
        dogMask.getPixels(dogPixels, 0, w, 0, 0, w, h)
        dogMask.recycle()

        val outputPixels = IntArray(w * h)
        val focusLabel = RectF(w * 0.40f, h * 0.09f, w * 0.58f, h * 0.20f)
        val establishedLabel = RectF(w * 0.76f, h * 0.44f, w * 0.98f, h * 0.62f)
        val nextGoalLabel = RectF(w * 0.48f, h * 0.84f, w * 0.67f, h * 0.96f)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (x < w * GOLD_NETWORK_LEFT_BOUNDARY) continue
                if (y < h * 0.04f || y > h * 0.98f) continue
                if ((dogPixels[i] ushr 24) > 100) continue
                if (focusLabel.contains(x.toFloat(), y.toFloat()) ||
                    establishedLabel.contains(x.toFloat(), y.toFloat()) ||
                    nextGoalLabel.contains(x.toFloat(), y.toFloat())
                ) continue

                val pixel = sourcePixels[i]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r >= g && g >= b + 5 && r > 100 && g > 72 && b > 42) {
                    val goldContrast = ((r - g) * 2 + (g - b) * 2 + 35)
                    val alpha = goldContrast.coerceIn(50, 190)
                    outputPixels[i] = Color.argb(alpha, 255, 255, 255)
                }
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(outputPixels, 0, w, 0, 0, w, h)
        }
    }

    companion object {
        private const val PULSE_DURATION_MS = 1_150L
        private const val PULSE_RAMP_FRACTION = 0.78f
        private const val MAX_PULSE_ALPHA = 142
        private const val GOLD_NETWORK_LEFT_BOUNDARY = 0.435f
    }
}
