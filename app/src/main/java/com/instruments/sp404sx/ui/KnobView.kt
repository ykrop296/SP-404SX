package com.instruments.sp404sx.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom rotary knob control modeled after the SP-404SX potentiometer knobs.
 *
 * Supports metallic rendering with radial gradients, outer indicator arc,
 * 270-degree rotation range (from 7 o'clock to 5 o'clock), vertical touch dragging,
 * value change listeners, and animated updates.
 */
class KnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // Rotation range: 270 degrees sweep
        // 135 degrees = 7:30 o'clock (down-left)
        // 405 degrees = 4:30 o'clock (down-right)
        private const val START_ANGLE = 135f
        private const val SWEEP_RANGE = 270f

        private const val COLOR_BODY_LIGHT = 0xFF4A4A4A.toInt()
        private const val COLOR_BODY_MID = 0xFF2E2E2E.toInt()
        private const val COLOR_BODY_DARK = 0xFF1C1C1C.toInt()
        private const val COLOR_ACCENT = 0xFFFF6B35.toInt()
        private const val COLOR_TRACK_BG = 0xFF252525.toInt()
        private const val COLOR_INDICATOR = 0xFFFFFFFF.toInt()
    }

    // Properties
    var value: Float = 0.5f
        set(v) {
            val clamped = v.coerceIn(0.0f, 1.0f)
            if (field != clamped) {
                field = clamped
                invalidate()
                onValueChanged?.invoke(clamped)
            }
        }

    var label: String = ""
        set(v) {
            field = v
            invalidate()
        }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1.0f else 0.45f
        invalidate()
    }

    // Callbacks
    var onValueChanged: ((Float) -> Unit)? = null
    var onTouchStart: (() -> Unit)? = null
    var onTouchEnd: (() -> Unit)? = null

    // Touch interaction
    private var lastTouchY: Float = 0f
    private var isDragging: Boolean = false
    private var animator: ValueAnimator? = null

    // Drawing primitives
    private val knobRect = RectF()
    private val trackRect = RectF()

    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = COLOR_TRACK_BG
    }

    private val trackActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = COLOR_ACCENT
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x66000000
    }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF404040.toInt()
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        color = COLOR_INDICATOR
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = 0xFF9E9E9E.toInt()
    }

    init {
        isClickable = true
        isFocusable = true
    }

    /**
     * Programmatically animates to a target knob value.
     */
    fun setValueAnimated(target: Float, durationMs: Long = 180L) {
        animator?.cancel()
        val clamped = target.coerceIn(0.0f, 1.0f)
        animator = ValueAnimator.ofFloat(value, clamped).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                value = anim.animatedValue as Float
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val labelAreaHeight = if (label.isNotEmpty()) 28f else 12f
        val availableHeight = (h - paddingTop - paddingBottom).toFloat() - labelAreaHeight
        val availableWidth = (w - paddingLeft - paddingRight).toFloat()
        val diameter = min(availableWidth, availableHeight)

        val cx = w / 2f
        val cy = paddingTop + diameter / 2f + 4f
        val radius = diameter / 2f - 10f

        knobRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val trackRadius = radius + 6f
        trackRect.set(cx - trackRadius, cy - trackRadius, cx + trackRadius, cy + trackRadius)

        // Metallic radial gradient for knob face
        bodyPaint.shader = RadialGradient(
            cx - radius * 0.25f,
            cy - radius * 0.25f,
            radius * 1.2f,
            intArrayOf(COLOR_BODY_LIGHT, COLOR_BODY_MID, COLOR_BODY_DARK),
            floatArrayOf(0.0f, 0.65f, 1.0f),
            Shader.TileMode.CLAMP
        )

        // Subtle gradient for center cap
        capPaint.shader = LinearGradient(
            cx, cy - radius * 0.4f,
            cx, cy + radius * 0.4f,
            0xFF383838.toInt(),
            0xFF1E1E1E.toInt(),
            Shader.TileMode.CLAMP
        )

        labelPaint.textSize = (w * 0.18f).coerceIn(18f, 26f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = knobRect.centerX()
        val cy = knobRect.centerY()
        val radius = knobRect.width() / 2f

        // 1. Draw outer arc track (unlit and active portion)
        canvas.drawArc(trackRect, START_ANGLE, SWEEP_RANGE, false, trackBgPaint)
        if (value > 0.005f) {
            canvas.drawArc(trackRect, START_ANGLE, SWEEP_RANGE * value, false, trackActivePaint)
        }

        // 2. Drop shadow under knob body
        canvas.drawCircle(cx, cy + 3f, radius, shadowPaint)

        // 3. Metallic knob body
        canvas.drawCircle(cx, cy, radius, bodyPaint)

        // 4. Subtle outer bezel highlight
        canvas.drawCircle(cx, cy, radius, bezelPaint)

        // 5. Center cap (recessed SP-404 style cap)
        val capRadius = radius * 0.38f
        canvas.drawCircle(cx, cy, capRadius, capPaint)

        // 6. White indicator line
        val currentAngle = START_ANGLE + (value * SWEEP_RANGE)
        val angleRad = Math.toRadians(currentAngle.toDouble())

        val innerRadius = radius * 0.38f
        val outerRadius = radius * 0.88f

        val startX = cx + (innerRadius * cos(angleRad)).toFloat()
        val startY = cy + (innerRadius * sin(angleRad)).toFloat()
        val endX = cx + (outerRadius * cos(angleRad)).toFloat()
        val endY = cy + (outerRadius * sin(angleRad)).toFloat()

        indicatorPaint.color = if (isDragging) COLOR_ACCENT else COLOR_INDICATOR
        canvas.drawLine(startX, startY, endX, endY, indicatorPaint)

        // 7. Label text below knob
        if (label.isNotEmpty()) {
            val textY = height - paddingBottom - 4f
            labelPaint.color = if (isDragging) Color.WHITE else 0xFFAAAAAA.toInt()
            canvas.drawText(label, cx, textY, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                isDragging = true
                lastTouchY = event.y
                onTouchStart?.invoke()
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val deltaY = lastTouchY - event.y
                    lastTouchY = event.y

                    // Drag sensitivity: 220dp vertical drag maps to full 0.0 to 1.0 range
                    val sensitivity = 220f * resources.displayMetrics.density
                    val deltaVal = deltaY / sensitivity
                    value = (value + deltaVal).coerceIn(0.0f, 1.0f)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onTouchEnd?.invoke()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        performClick()
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
