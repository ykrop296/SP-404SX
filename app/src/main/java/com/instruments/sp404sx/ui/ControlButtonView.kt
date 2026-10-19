package com.instruments.sp404sx.ui

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
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * Custom tactile control button for the SP-404SX panel.
 *
 * Supports momentary and toggle operating modes, integrated LED indicator,
 * 3D bevel press simulation, haptic feedback, and custom active tint colors.
 */
class ControlButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val COLOR_BODY_DEFAULT = 0xFF3C3C3C.toInt()
        private const val COLOR_BEZEL = 0xFF1C1C1C.toInt()
        private const val COLOR_LED_DEFAULT = 0xFFFF4444.toInt()
        private const val COLOR_LED_OFF = 0xFF221111.toInt()
    }

    // Properties
    var text: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var isToggle: Boolean = false

    var isActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var ledColor: Int = COLOR_LED_DEFAULT
        set(value) {
            field = value
            invalidate()
        }

    // Callbacks
    var onButtonPressed: (() -> Unit)? = null
    var onButtonReleased: (() -> Unit)? = null
    var onToggled: ((Boolean) -> Unit)? = null

    // Press state tracking
    private var isPressedDown: Boolean = false

    // Drawing primitives
    private val bezelRect = RectF()
    private val buttonRect = RectF()

    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_BEZEL
    }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ledHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = 0xFFCCCCCC.toInt()
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = 3f
        bezelRect.set(inset, inset, w - inset, h - inset)
        buttonRect.set(inset + 2f, inset + 2f, w - inset - 2f, h - inset - 2f)

        // Text size dynamically fits button height and width
        val baseSize = (h * 0.28f).coerceIn(16f, 26f)
        textPaint.textSize = baseSize
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cornerRadius = 8f
        val pressOffsetY = if (isPressedDown) 2f else 0f

        // 1. Draw outer bezel
        canvas.drawRoundRect(bezelRect, cornerRadius + 2f, cornerRadius + 2f, bezelPaint)

        // 2. Draw button body with 3D press translation
        canvas.save()
        if (pressOffsetY != 0f) {
            canvas.translate(0f, pressOffsetY)
        }

        val baseBodyColor = if (isActive) {
            ColorUtils.blendARGB(COLOR_BODY_DEFAULT, ledColor, 0.28f)
        } else {
            COLOR_BODY_DEFAULT
        }
        bodyPaint.color = baseBodyColor
        canvas.drawRoundRect(buttonRect, cornerRadius, cornerRadius, bodyPaint)

        // 3. 3D Bevel strokes
        val topHighlight = if (isPressedDown) 0x22000000 else 0x44FFFFFF
        val bottomShadow = if (isPressedDown) 0x33FFFFFF else 0x55000000

        highlightPaint.color = topHighlight
        shadowPaint.color = bottomShadow

        // Top line
        canvas.drawLine(
            buttonRect.left + cornerRadius, buttonRect.top,
            buttonRect.right - cornerRadius, buttonRect.top,
            highlightPaint
        )
        // Bottom line
        canvas.drawLine(
            buttonRect.left + cornerRadius, buttonRect.bottom,
            buttonRect.right - cornerRadius, buttonRect.bottom,
            shadowPaint
        )

        // 4. LED indicator dot
        val ledRadius = (buttonRect.height() * 0.08f).coerceIn(4f, 7f)
        val ledCx = buttonRect.centerX()
        val ledCy = buttonRect.top + buttonRect.height() * 0.26f

        if (isActive) {
            ledHaloPaint.color = ColorUtils.setAlphaComponent(ledColor, 90)
            canvas.drawCircle(ledCx, ledCy, ledRadius * 2.0f, ledHaloPaint)
            ledPaint.color = ledColor
        } else {
            ledPaint.color = COLOR_LED_OFF
        }
        canvas.drawCircle(ledCx, ledCy, ledRadius, ledPaint)

        // 5. Button label text
        if (text.isNotEmpty()) {
            val textY = buttonRect.top + buttonRect.height() * 0.78f
            textPaint.color = if (isActive || isPressedDown) Color.WHITE else 0xFFCCCCCC.toInt()

            // Measure and auto-scale text if label exceeds button bounds
            val textWidth = textPaint.measureText(text)
            val maxAvailableWidth = buttonRect.width() - 8f
            if (textWidth > maxAvailableWidth && textWidth > 0f) {
                val originalSize = textPaint.textSize
                textPaint.textSize = (originalSize * (maxAvailableWidth / textWidth)).coerceAtLeast(14f)
                canvas.drawText(text, buttonRect.centerX(), textY, textPaint)
                textPaint.textSize = originalSize
            } else {
                canvas.drawText(text, buttonRect.centerX(), textY, textPaint)
            }
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressedDown = true
                triggerHaptic()
                if (isToggle) {
                    isActive = !isActive
                    onToggled?.invoke(isActive)
                } else {
                    isActive = true
                }
                onButtonPressed?.invoke()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                isPressedDown = false
                if (!isToggle) {
                    isActive = false
                }
                onButtonReleased?.invoke()
                performClick()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressedDown = false
                if (!isToggle) {
                    isActive = false
                }
                onButtonReleased?.invoke()
                invalidate()
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
     * Triggers tactile haptic feedback vibration.
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
}
