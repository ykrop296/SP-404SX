package com.instruments.sp404sx.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.ColorUtils

/**
 * Custom View representing a single SP-404SX performance pad.
 *
 * Provides authentic visual appearance with 3D bevels, active/loaded/pressed states,
 * dynamic LED indicator, pulsing playback animation, and haptic feedback.
 */
class PadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val COLOR_DEFAULT = 0xFF4A4A4A.toInt()
        private const val COLOR_LOADED = 0xFF5A5A5A.toInt()
        private const val COLOR_PRESSED = 0xFFFF6B35.toInt()
        private const val COLOR_PLAYING = 0xFFFFB347.toInt()

        private const val LED_OFF = 0xFF2B2B2B.toInt()
        private const val LED_LOADED = 0xFFFF9900.toInt()
        private const val LED_ACTIVE = 0xFFFF3300.toInt()

        private const val ANIMATION_DURATION_MS = 100L
        private const val PULSE_DURATION_MS = 380L
    }

    // Properties
    var padIndex: Int = 0

    var padNumber: Int = 1
        set(value) {
            field = value
            invalidate()
        }

    var isLoaded: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateColorState(animate = true)
            }
        }

    var isActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    startPulseAnimation()
                } else {
                    stopPulseAnimation()
                }
                updateColorState(animate = true)
            }
        }

    var sampleName: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var padColor: Int = 0
        set(value) {
            field = value
            updateColorState(animate = true)
        }

    // Callbacks
    var onPadPressed: ((Int) -> Unit)? = null
    var onPadReleased: ((Int) -> Unit)? = null

    // Touch and state tracking
    private var isPressedState: Boolean = false
    private var currentColor: Int = COLOR_DEFAULT
    private var pulseFactor: Float = 1.0f

    // Animators
    private var colorAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()

    // Drawing primitives
    private val padRect = RectF()
    private val bezelRect = RectF()
    private val glowRect = RectF()

    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF1F1F1F.toInt()
    }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ledGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = Color.WHITE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        color = 0xFFDDDDDD.toInt()
    }

    init {
        isClickable = true
        isFocusable = true
        currentColor = getTargetBaseColor()
    }

    /**
     * Resolves the target base color depending on current pad state.
     */
    private fun getTargetBaseColor(): Int {
        return when {
            isPressedState -> COLOR_PRESSED
            isActive -> COLOR_PLAYING
            padColor != 0 -> {
                if (isLoaded) padColor else ColorUtils.blendARGB(padColor, COLOR_DEFAULT, 0.5f)
            }
            isLoaded -> COLOR_LOADED
            else -> COLOR_DEFAULT
        }
    }

    /**
     * Smoothly transitions the current pad color to the target state color.
     */
    private fun updateColorState(animate: Boolean) {
        val targetColor = getTargetBaseColor()
        colorAnimator?.cancel()

        if (animate && isAttachedToWindow) {
            colorAnimator = ValueAnimator.ofObject(argbEvaluator, currentColor, targetColor).apply {
                duration = ANIMATION_DURATION_MS
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    currentColor = animator.animatedValue as Int
                    invalidate()
                }
                start()
            }
        } else {
            currentColor = targetColor
            invalidate()
        }
    }

    /**
     * Starts pulsing animation for active sample playback.
     */
    private fun startPulseAnimation() {
        stopPulseAnimation()
        pulseAnimator = ValueAnimator.ofFloat(0.72f, 1.0f).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                pulseFactor = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Stops pulsing animation.
     */
    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseFactor = 1.0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = 4f
        bezelRect.set(inset, inset, w - inset, h - inset)
        padRect.set(inset + 2.5f, inset + 2.5f, w - inset - 2.5f, h - inset - 2.5f)
        glowRect.set(inset - 2f, inset - 2f, w - inset + 2f, h - inset + 2f)

        // Scale text size proportional to view height
        numberPaint.textSize = (h * 0.28f).coerceIn(24f, 58f)
        labelPaint.textSize = (h * 0.13f).coerceIn(16f, 26f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cornerRadius = 14f
        val currentFillColor = if (isActive && !isPressedState) {
            ColorUtils.blendARGB(currentColor, Color.WHITE, (pulseFactor - 0.72f) * 0.5f)
        } else {
            currentColor
        }

        // 1. Draw outer glow when active or pressed
        if (isPressedState || isActive) {
            val glowColor = if (isPressedState) COLOR_PRESSED else COLOR_PLAYING
            val alpha = if (isPressedState) 100 else (70 * pulseFactor).toInt()
            glowPaint.color = ColorUtils.setAlphaComponent(glowColor, alpha.coerceIn(0, 255))
            canvas.drawRoundRect(glowRect, cornerRadius + 4f, cornerRadius + 4f, glowPaint)
        }

        // 2. Draw outer recessed bezel
        canvas.drawRoundRect(bezelRect, cornerRadius + 2f, cornerRadius + 2f, bezelPaint)

        // 3. Draw pad body
        bodyPaint.color = currentFillColor
        canvas.drawRoundRect(padRect, cornerRadius, cornerRadius, bodyPaint)

        // 4. Subtle 3D Bevel highlight and shadow
        val highlightColor = if (isPressedState) {
            Color.argb(35, 0, 0, 0)
        } else {
            Color.argb(55, 255, 255, 255)
        }
        val shadowColor = if (isPressedState) {
            Color.argb(60, 255, 255, 255)
        } else {
            Color.argb(80, 0, 0, 0)
        }

        highlightPaint.color = highlightColor
        shadowPaint.color = shadowColor

        val offset = if (isPressedState) 1.5f else 0f
        canvas.save()
        if (offset != 0f) {
            canvas.translate(0f, offset)
        }

        // Highlight stroke (top & left)
        canvas.drawLine(padRect.left + cornerRadius, padRect.top, padRect.right - cornerRadius, padRect.top, highlightPaint)
        canvas.drawLine(padRect.left, padRect.top + cornerRadius, padRect.left, padRect.bottom - cornerRadius, highlightPaint)

        // Shadow stroke (bottom & right)
        canvas.drawLine(padRect.left + cornerRadius, padRect.bottom, padRect.right - cornerRadius, padRect.bottom, shadowPaint)
        canvas.drawLine(padRect.right, padRect.top + cornerRadius, padRect.right, padRect.bottom - cornerRadius, shadowPaint)

        // 5. LED indicator in top-right corner
        val ledRadius = 7f
        val ledCx = padRect.right - 20f
        val ledCy = padRect.top + 20f

        val (targetLedColor, ledActive) = when {
            isActive -> Pair(LED_ACTIVE, true)
            isLoaded -> Pair(LED_LOADED, true)
            else -> Pair(LED_OFF, false)
        }

        if (ledActive) {
            val haloAlpha = if (isActive) (140 * pulseFactor).toInt() else 80
            ledGlowPaint.color = ColorUtils.setAlphaComponent(targetLedColor, haloAlpha.coerceIn(0, 255))
            canvas.drawCircle(ledCx, ledCy, ledRadius * 1.8f, ledGlowPaint)
        }
        ledPaint.color = targetLedColor
        canvas.drawCircle(ledCx, ledCy, ledRadius, ledPaint)

        // 6. Draw pad number in center
        val centerX = padRect.centerX()
        val textShiftY = if (sampleName.isNotEmpty()) -padRect.height() * 0.08f else 0f
        val numY = padRect.centerY() + (numberPaint.textSize / 3f) + textShiftY

        numberPaint.color = if (isPressedState) Color.WHITE else 0xFFF0F0F0.toInt()
        canvas.drawText(padNumber.toString(), centerX, numY, numberPaint)

        // 7. Draw sample name below pad number
        if (sampleName.isNotEmpty()) {
            val labelY = padRect.centerY() + padRect.height() * 0.28f
            val maxLabelWidth = padRect.width() - 32f
            val truncatedName = TextUtils.ellipsize(
                sampleName,
                android.text.TextPaint(labelPaint),
                maxLabelWidth,
                TextUtils.TruncateAt.END
            ).toString()

            labelPaint.color = if (isPressedState) Color.WHITE else 0xFFD0D0D0.toInt()
            canvas.drawText(truncatedName, centerX, labelY, labelPaint)
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressedState = true
                triggerHaptic()
                updateColorState(animate = true)
                onPadPressed?.invoke(padIndex)
                return true
            }

            MotionEvent.ACTION_UP -> {
                isPressedState = false
                updateColorState(animate = true)
                onPadReleased?.invoke(padIndex)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressedState = false
                updateColorState(animate = true)
                onPadReleased?.invoke(padIndex)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Delivers a 10ms haptic feedback pulse on pad touch.
     */
    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator?.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(10)
            }
        } catch (_: Exception) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        colorAnimator?.cancel()
        pulseAnimator?.cancel()
    }
}
