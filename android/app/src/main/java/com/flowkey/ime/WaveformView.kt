package com.flowkey.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

/**
 * The keyboard's voice visual, drawn behind the mic: while listening, level bars run outward
 * from the mic on both sides (newest next to the mic) and a ring around the mic follows the
 * voice; while transcribing or polishing, an arc circles the mic. Draws nothing when idle, and
 * only animates while it has something to show.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, WORKING }

    private val dp = resources.displayMetrics.density
    private val levels = FloatArray(MAX_BARS_PER_SIDE)
    private val drawLevels = FloatArray(MAX_BARS_PER_SIDE)
    private val lock = Any()
    private var ring = 0f

    /** Radius of the mic button this view surrounds (the mic is 96dp). */
    var micRadius = 48 * dp

    private val listenColor = ContextCompat.getColor(context, R.color.mic_recording)
    private val workColor = ContextCompat.getColor(context, R.color.flowkey_accent_light)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3 * dp
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arcBox = RectF()

    var mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            if (value != Mode.LISTENING) synchronized(lock) { levels.fill(0f) }
            ring = 0f
            postInvalidateOnAnimation()
        }

    /** One audio level (RMS of ~30 ms of samples). Safe from any thread. */
    fun pushRms(rms: Float) {
        // -50 dBFS (room noise) → 0, -12 dBFS (loud speech) → 1
        val db = if (rms <= 1e-6f) -120f else 20f * log10(rms)
        val level = ((db + 50f) / 38f).coerceIn(0f, 1f)
        synchronized(lock) {
            System.arraycopy(levels, 0, levels, 1, levels.size - 1)
            levels[0] = level
        }
        if (mode == Mode.LISTENING) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = mode
        if (m == Mode.IDLE) return
        val cx = width / 2f
        val cy = height / 2f
        val t = SystemClock.uptimeMillis() / 1000f

        if (m == Mode.LISTENING) {
            synchronized(lock) { levels.copyInto(drawLevels) }
        } else {
            for (i in drawLevels.indices) drawLevels[i] = 0.12f + 0.10f * (1 + sin(t * 5f - i * 0.55f)) / 2f
        }
        val color = if (m == Mode.LISTENING) listenColor else workColor

        // Bars: between the mic's ring and the side keys (52dp key column + 8dp gap).
        val inner = micRadius + 24 * dp
        val outer = cx - 60 * dp
        val step = 6 * dp
        val n = min(MAX_BARS_PER_SIDE, floor((outer - inner) / step).toInt().coerceAtLeast(0))
        val maxHalf = min(height * 0.32f, 34 * dp)
        val minHalf = 2 * dp
        barPaint.color = color
        for (i in 0 until n) {
            val half = minHalf + drawLevels[i] * maxHalf
            barPaint.alpha = (255 * (1f - 0.65f * i / n.coerceAtLeast(1))).toInt()
            val dx = inner + i * step
            canvas.drawLine(cx - dx, cy - half, cx - dx, cy + half, barPaint)
            canvas.drawLine(cx + dx, cy - half, cx + dx, cy + half, barPaint)
        }

        if (m == Mode.LISTENING) {
            // The ring follows the voice, smoothed so it breathes instead of flickering.
            ring += (drawLevels[0] - ring) * 0.35f
            val r = micRadius + 5 * dp + ring * 13 * dp
            glowPaint.color = color
            glowPaint.alpha = (40 + 60 * ring).toInt()
            canvas.drawCircle(cx, cy, r, glowPaint)
            ringPaint.color = color
            ringPaint.alpha = (110 + 145 * ring).toInt()
            canvas.drawCircle(cx, cy, r, ringPaint)
            if (ring > 0.01f) postInvalidateOnAnimation() // let the ring settle
        } else {
            // Working: an arc circles the mic.
            val r = micRadius + 7 * dp
            arcBox.set(cx - r, cy - r, cx + r, cy + r)
            ringPaint.color = color
            ringPaint.alpha = 60
            canvas.drawCircle(cx, cy, r, ringPaint)
            ringPaint.alpha = 255
            canvas.drawArc(arcBox, (t * 300f) % 360f, 80f, false, ringPaint)
            postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val MAX_BARS_PER_SIDE = 16
    }
}
