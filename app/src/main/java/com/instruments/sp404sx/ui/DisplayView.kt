package com.instruments.sp404sx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.instruments.sp404sx.model.BankId

/**
 * Custom LCD-style display mimicking the classic SP-404SX 2-line dot matrix screen.
 *
 * Features illuminated green fluorescent characters on a dark green backdrop,
 * subtle CRT/LCD scanlines, inset bezel border, dual-channel VU level meter bars,
 * recording indicator, and temporary message overlays with automatic restoration.
 */
class DisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val COLOR_BEZEL = 0xFF141F14.toInt()
        private const val COLOR_BG = 0xFF1B3A1B.toInt()
        private const val COLOR_TEXT = 0xFF33FF33.toInt()
        private const val COLOR_TEXT_DIM = 0xFF1B6B1B.toInt()
        private const val COLOR_SCANLINE = 0x1A000000
        private const val COLOR_VU_OFF = 0xFF122C12.toInt()
        private const val COLOR_VU_GREEN = 0xFF33FF33.toInt()
        private const val COLOR_VU_YELLOW = 0xFFFFCC00.toInt()
        private const val COLOR_VU_RED = 0xFFFF3333.toInt()

        private const val VU_SEGMENTS = 7
    }

    // Properties
    var line1: String = "SP-404SX"
        set(value) {
            field = value
            invalidate()
        }

    var line2: String = "READY"
        set(value) {
            field = value
            invalidate()
        }

    var vuLeft: Float = 0.0f
        set(value) {
            val clamped = value.coerceIn(0.0f, 1.0f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var vuRight: Float = 0.0f
        set(value) {
            val clamped = value.coerceIn(0.0f, 1.0f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    private var isRecording: Boolean = false
    private var isShowingTempMessage: Boolean = false
    private var savedLine1: String = ""
    private var savedLine2: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private var restoreRunnable: Runnable? = null

    // Drawing primitives
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val vuBarRect = RectF()

    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_BEZEL
    }

    private val bezelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF0E170E.toInt()
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_BG
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = COLOR_TEXT
    }

    private val recIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = COLOR_VU_RED
    }

    private val scanlinePaint = Paint().apply {
        color = COLOR_SCANLINE
        strokeWidth = 1.5f
    }

    private val vuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val vuLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = COLOR_TEXT_DIM
        textAlign = Paint.Align.CENTER
        textSize = 16f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        outerRect.set(4f, 4f, w - 4f, h - 4f)
        innerRect.set(12f, 12f, w - 12f, h - 12f)

        // Scale monospace text relative to display height
        textPaint.textSize = (h * 0.26f).coerceIn(20f, 42f)
        recIndicatorPaint.textSize = (h * 0.20f).coerceIn(16f, 32f)
        vuLabelPaint.textSize = (h * 0.12f).coerceIn(12f, 20f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val outerRadius = 14f
        val innerRadius = 8f

        // 1. Draw outer display frame
        canvas.drawRoundRect(outerRect, outerRadius, outerRadius, bezelPaint)
        canvas.drawRoundRect(outerRect, outerRadius, outerRadius, bezelStrokePaint)

        // 2. Draw inner LCD screen
        canvas.drawRoundRect(innerRect, innerRadius, innerRadius, bgPaint)

        // 3. Subtle horizontal scanline overlay
        var y = innerRect.top + 3f
        while (y < innerRect.bottom - 2f) {
            canvas.drawLine(innerRect.left + 2f, y, innerRect.right - 2f, y, scanlinePaint)
            y += 4f
        }

        // 4. VU meter layout on the right
        val vuAreaWidth = innerRect.width() * 0.18f
        val vuRightMargin = innerRect.right - 10f
        val vuLeftEdge = vuRightMargin - vuAreaWidth

        val colWidth = (vuAreaWidth - 6f) / 2f
        val meterTop = innerRect.top + 8f
        val meterBottom = innerRect.bottom - 20f
        val totalMeterHeight = meterBottom - meterTop
        val segGap = 2f
        val segHeight = (totalMeterHeight - (VU_SEGMENTS - 1) * segGap) / VU_SEGMENTS

        // Draw Left VU Column
        drawVuColumn(canvas, vuLeftEdge, colWidth, meterBottom, segHeight, segGap, vuLeft)
        // Draw Right VU Column
        drawVuColumn(canvas, vuLeftEdge + colWidth + 4f, colWidth, meterBottom, segHeight, segGap, vuRight)

        // Meter labels (L, R)
        val labelY = innerRect.bottom - 6f
        canvas.drawText("L", vuLeftEdge + colWidth / 2f, labelY, vuLabelPaint)
        canvas.drawText("R", vuLeftEdge + colWidth + 4f + colWidth / 2f, labelY, vuLabelPaint)

        // 5. Draw Text Lines
        val textStartX = innerRect.left + 16f
        val line1Y = innerRect.top + innerRect.height() * 0.40f
        val line2Y = innerRect.top + innerRect.height() * 0.82f

        // If recording, display REC indicator
        if (isRecording) {
            val recText = "[REC]"
            val recWidth = recIndicatorPaint.measureText(recText)
            val recX = vuLeftEdge - recWidth - 8f
            canvas.drawText(recText, recX, line1Y, recIndicatorPaint)
        }

        canvas.drawText(line1, textStartX, line1Y, textPaint)
        canvas.drawText(line2, textStartX, line2Y, textPaint)
    }

    /**
     * Draws a single segmented VU meter column.
     */
    private fun drawVuColumn(
        canvas: Canvas,
        x: Float,
        width: Float,
        bottomY: Float,
        segHeight: Float,
        segGap: Float,
        level: Float
    ) {
        val litCount = (level * VU_SEGMENTS).toInt().coerceIn(0, VU_SEGMENTS)

        for (i in 0 until VU_SEGMENTS) {
            // i=0 is bottom segment, i=VU_SEGMENTS-1 is top segment (peak)
            val isLit = i < litCount
            val segBottom = bottomY - i * (segHeight + segGap)
            val segTop = segBottom - segHeight
            vuBarRect.set(x, segTop, x + width, segBottom)

            vuPaint.color = when {
                !isLit -> COLOR_VU_OFF
                i == VU_SEGMENTS - 1 -> COLOR_VU_RED
                i >= VU_SEGMENTS - 3 -> COLOR_VU_YELLOW
                else -> COLOR_VU_GREEN
            }

            canvas.drawRect(vuBarRect, vuPaint)
        }
    }

    /**
     * Sets arbitrary text for both display lines.
     */
    fun setText(line1: String, line2: String) {
        this.line1 = line1
        this.line2 = line2
    }

    /**
     * Displays bank selection info.
     */
    fun setBank(bankId: BankId) {
        line1 = "BANK: ${bankId.displayName}"
        line2 = "READY"
    }

    /**
     * Displays pad information.
     */
    fun setPadInfo(padIndex: Int, name: String) {
        val padNum = padIndex + 1
        line1 = "PAD %02d".format(padNum)
        line2 = if (name.isBlank()) "EMPTY" else name.take(14)
    }

    /**
     * Displays active effect details and parameter values.
     */
    fun setEffectInfo(effectName: String, params: FloatArray) {
        line1 = "FX: $effectName".take(14)
        val p1 = if (params.isNotEmpty()) (params[0] * 100).toInt() else 0
        val p2 = if (params.size > 1) (params[1] * 100).toInt() else 0
        val p3 = if (params.size > 2) (params[2] * 100).toInt() else 0
        line2 = "1:%02d 2:%02d 3:%02d".format(p1, p2, p3)
    }

    /**
     * Updates stereo VU meter levels.
     */
    fun setLevel(left: Float, right: Float) {
        vuLeft = left
        vuRight = right
    }

    /**
     * Sets recording status indicator.
     */
    fun setRecording(recording: Boolean) {
        if (isRecording != recording) {
            isRecording = recording
            invalidate()
        }
    }

    /**
     * Shows a temporary popup message on the display and restores previous text after durationMs.
     */
    fun showMessage(message: String, durationMs: Long = 2000L) {
        restoreRunnable?.let { handler.removeCallbacks(it) }

        if (!isShowingTempMessage) {
            savedLine1 = line1
            savedLine2 = line2
            isShowingTempMessage = true
        }

        line2 = message.take(16)

        restoreRunnable = Runnable {
            if (isShowingTempMessage) {
                line1 = savedLine1
                line2 = savedLine2
                isShowingTempMessage = false
            }
        }.also {
            handler.postDelayed(it, durationMs)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        restoreRunnable?.let { handler.removeCallbacks(it) }
    }
}
