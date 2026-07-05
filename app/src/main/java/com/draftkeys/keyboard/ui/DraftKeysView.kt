package com.draftkeys.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.res.ResourcesCompat
import com.draftkeys.keyboard.R
import com.draftkeys.keyboard.gesture.GesturePoint
import kotlin.math.sqrt
import kotlin.math.sin

class DraftKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var listener: KeyboardActionListener? = null

    private var layoutType = KeyboardLayoutType.QWERTY
    private var isShifted  = false
    private var keyboardLayout: KeyboardLayout? = null

    private var enterAction = EditorInfo.IME_ACTION_UNSPECIFIED

    fun setEnterAction(action: Int) {
        enterAction = action
        invalidate()
    }

    // ── Multi-touch State ─────────────────────────────────────────────────────

    class ActivePointer(
        val id: Int,
        var currentKey: KeyModel?,
        var startX: Float,
        var startY: Float
    ) {
        var isWordDeleteTriggered = false
        var isLongPressTriggered = false
        var longPressRunnable: Runnable? = null
    }

    private val activePointers = SparseArray<ActivePointer>()
    private var swipePointerId: Int? = null
    
    private val gesturePoints = mutableListOf<GesturePoint>()
    private val gesturePath   = Path()

    // ── Wild / Plasma Storm mode ───────────────────────────────────────────────
    private var wildMode = false
    private var wildHueTick = 0f
    // Per-key hue offset so each key gets a unique colour — assigned once, stable
    private val keyHueOffset = mutableMapOf<KeyModel, Float>()

    var expectedNextChars: Set<Char> = emptySet()
    
    // ── Smooth Animation Loop ──────────────────────────────────────────────────
    
    class KeyAnimState {
        var progress: Float = 0f
        var velocity: Float = 0f
        var isPressed: Boolean = false
    }
    
    private val keyAnimStates = mutableMapOf<KeyModel, KeyAnimState>()
    private val frameHandler = Handler(Looper.getMainLooper())
    private var isAnimating = false
    private var isFadingOutSwipe = false
    private var swipeFadeAlpha = 255

    private val frameRunnable = object : Runnable {
        override fun run() {
            var needsMoreFrames = false
            for (state in keyAnimStates.values) {
                val target = if (state.isPressed) 1f else 0f
                if (Math.abs(target - state.progress) > 0.005f) {
                    // Simple, stable animation without bounce (exponential smoothing)
                    state.progress += (target - state.progress) * 0.4f
                    
                    needsMoreFrames = true
                } else {
                    state.progress = target
                    state.velocity = 0f
                }
            }
            if (isFadingOutSwipe) {
                swipeFadeAlpha = (swipeFadeAlpha - 20).coerceAtLeast(0)
                if (swipeFadeAlpha == 0) {
                    isFadingOutSwipe = false
                    gesturePoints.clear()
                    gesturePath.reset()
                } else {
                    needsMoreFrames = true
                }
            }
            if (wildMode) {
                wildHueTick = (wildHueTick + 0.8f) % 360f
                needsMoreFrames = true
            }
            if (needsMoreFrames) {
                invalidate()
                frameHandler.postDelayed(this, 16)
            } else {
                isAnimating = false
                keyAnimStates.entries.retainAll { it.value.progress > 0f }
            }
        }
    }

    private fun startAnimationLoop() {
        if (!isAnimating) {
            isAnimating = true
            frameHandler.post(frameRunnable)
        }
    }

    // ── Paints & Typography ───────────────────────────────────────────────────
    
    private val typefacePrimary = try { ResourcesCompat.getFont(context, R.font.hanken_grotesk) } catch (e: Exception) { null }
    private val typefaceSecondary = try { ResourcesCompat.getFont(context, R.font.jetbrains_mono) } catch (e: Exception) { null }

    private val keyNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyModifierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = typefacePrimary }
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; typeface = typefaceSecondary }
    private val modTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = typefacePrimary }
    private val specialTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = typefacePrimary }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val popTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_POPUP_TEXT
        textAlign = Paint.Align.CENTER
    }
    private val gesturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap  = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // Wild-mode paints
    private val wildKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val wildGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var themeManager = ThemeManager(context)
    private var cornerRadius = dp(8f)
    private val swipeThresholdPx = dp(SWIPE_THRESHOLD_DP)

    init {
        gesturePaint.strokeWidth = dp(4f)
        wildGlowPaint.strokeWidth = dp(1.5f)
        popTextPaint.typeface = typefacePrimary
        applyTheme()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAnimating = false
        frameHandler.removeCallbacks(frameRunnable)
    }

    fun applyTheme() {
        val theme = themeManager.getTheme()
        cornerRadius = dp(themeManager.cornerRadiusDp)
        wildMode = theme.isWild
        
        keyNormalPaint.color = theme.keyNormal
        keyModifierPaint.color = theme.keyModifier
        keyPressedPaint.color = theme.keyPressed
        keyShadowPaint.color = theme.keyShadow
        textPaint.color = theme.keyText
        secondaryTextPaint.color = theme.keySecText
        modTextPaint.color = theme.modText
        specialTextPaint.color = theme.accent
        popupPaint.color = theme.keyPressed
        
        if (wildMode) {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            startAnimationLoop() // keep hue ticking continuously
        } else {
            setBackgroundColor(theme.bg)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width    = MeasureSpec.getSize(widthMeasureSpec)
        val d        = resources.displayMetrics.density
        val rowCount = 4
        val keyH     = (KeyboardLayout.KEY_HEIGHT_DP * d).toInt()
        val gap      = (KeyboardLayout.KEY_GAP_DP * d).toInt()
        val botPad   = (KeyboardLayout.ROW_BOTTOM_PADDING_DP * d).toInt()
        val height   = rowCount * keyH + rowCount * gap + botPad
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
    }

    fun switchToQwerty() {
        layoutType = KeyboardLayoutType.QWERTY
        requestLayout(); rebuildLayout()
    }

    fun switchToSymbols() {
        layoutType = KeyboardLayoutType.SYMBOLS
        requestLayout(); rebuildLayout()
    }

    fun switchToEmoji() {
        layoutType = KeyboardLayoutType.EMOJI
        requestLayout(); rebuildLayout()
    }

    fun setShifted(shifted: Boolean) {
        if (isShifted == shifted) return
        isShifted = shifted
        invalidate()
    }

    fun isSymbolMode() = layoutType == KeyboardLayoutType.SYMBOLS

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val layout = keyboardLayout ?: return

        // 1. Draw standard keys
        for (key in layout.keys) {
            val animState = keyAnimStates[key]
            val progress = animState?.progress ?: 0f
            val isAnimKey = progress > 0f

            if (wildMode) {
                // ── Wild mode: per-key rainbow hue fill + neon border ──
                val offset = keyHueOffset.getOrPut(key) {
                    // Give each key a unique stable offset based on its position
                    ((key.bounds.centerX() / 40f + key.bounds.centerY() / 30f) * 37f) % 360f
                }
                val hue = (wildHueTick + offset) % 360f
                val sat = if (key.isModifier) 0.6f else 0.95f
                val brightness = if (isAnimKey) 1.0f else 0.70f
                val alpha = if (isAnimKey) 220 else (if (key.isModifier) 110 else 70)

                wildKeyPaint.color = hsvColor(hue, sat, brightness, alpha)

                canvas.save()
                if (isAnimKey) {
                    val squishScale = 1f - (0.08f * progress)
                    canvas.scale(squishScale, squishScale, key.bounds.centerX(), key.bounds.centerY())
                }
                canvas.drawRoundRect(key.bounds, cornerRadius, cornerRadius, wildKeyPaint)

                // Neon glow border
                wildGlowPaint.color = hsvColor(hue, 1f, 1f, if (isAnimKey) 255 else 160)
                canvas.drawRoundRect(key.bounds, cornerRadius, cornerRadius, wildGlowPaint)
                canvas.restore()
            } else {
                // ── Normal mode ──────────────────────────────────────
                val bgPaint = when {
                    isAnimKey -> keyPressedPaint
                    key.isModifier -> keyModifierPaint
                    else -> keyNormalPaint
                }
                
                canvas.save()
                if (isAnimKey) {
                    val squishScale = 1f - (0.08f * progress)
                    canvas.scale(squishScale, squishScale, key.bounds.centerX(), key.bounds.centerY())
                }

                if (progress < 0.3f) {
                    val alpha = ((1f - (progress / 0.3f)) * 255).toInt()
                    keyShadowPaint.alpha = alpha
                    val shadowRect = RectF(
                        key.bounds.left  + dp(1f),
                        key.bounds.bottom - dp(2f),
                        key.bounds.right - dp(1f),
                        key.bounds.bottom + dp(2f)
                    )
                    canvas.drawRoundRect(shadowRect, dp(4f), dp(4f), keyShadowPaint)
                    keyShadowPaint.alpha = 255
                }

                canvas.drawRoundRect(key.bounds, cornerRadius, cornerRadius, bgPaint)
                canvas.restore()
            }

            // Draw label (same squish anim in both normal and wild mode)
            canvas.save()
            val labelProgress = keyAnimStates[key]?.progress ?: 0f
            if (labelProgress > 0f) {
                val sq = 1f - (0.08f * labelProgress)
                canvas.scale(sq, sq, key.bounds.centerX(), key.bounds.centerY())
            }
            val label = displayLabel(key)
            val paint = chooseLabelPaint(key)
            paint.textSize = labelTextSize(key)
            val textY = key.bounds.centerY() - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(label, key.bounds.centerX(), textY, paint)

            if (key.secondaryLabel != null) {
                secondaryTextPaint.textSize = dp(10f)
                val padX = dp(6f)
                val padY = dp(4f)
                canvas.drawText(
                    key.secondaryLabel, 
                    key.bounds.right - padX, 
                    key.bounds.top + padY - secondaryTextPaint.ascent(), 
                    secondaryTextPaint
                )
            }
            canvas.restore()
        }

        // 2. Draw Popups (smoothly animated in size)
        if (swipePointerId == null) {
            for ((pKey, state) in keyAnimStates) {
                if (pKey.isModifier || state.progress <= 0.05f) continue
                
                val popupRect = RectF(
                    pKey.bounds.left - dp(6f),
                    pKey.bounds.top - pKey.bounds.height() - dp(12f),
                    pKey.bounds.right + dp(6f),
                    pKey.bounds.top + dp(12f)
                )
                
                canvas.save()
                canvas.scale(state.progress, state.progress, popupRect.centerX(), pKey.bounds.bottom)
                
                val alphaInt = (state.progress * 255).toInt()
                popupPaint.alpha = alphaInt
                canvas.drawRoundRect(popupRect, dp(8f), dp(8f), popupPaint)
                
                var showSecondary = false
                if (pKey.secondaryLabel != null) {
                    for (i in 0 until activePointers.size()) {
                        val ptr = activePointers.valueAt(i)
                        if (ptr.currentKey == pKey && ptr.isLongPressTriggered) {
                            showSecondary = true; break
                        }
                    }
                }
                
                val popupText = if (showSecondary) pKey.secondaryLabel!! else displayLabel(pKey)
                
                popTextPaint.textSize = if (popupText.length > 2) dp(16f) else dp(28f)
                popTextPaint.alpha = alphaInt
                
                val popY = popupRect.centerY() - (popTextPaint.ascent() + popTextPaint.descent()) / 2f
                canvas.drawText(popupText, popupRect.centerX(), popY, popTextPaint)
                
                canvas.restore()
            }
        }

        // 3. Swipe trail
        if ((swipePointerId != null || isFadingOutSwipe) && gesturePoints.size > 1) {
            val first = gesturePoints.first()
            val last  = gesturePoints.last()
            val alphaBase = if (isFadingOutSwipe) swipeFadeAlpha else 255
            val startAlpha = (alphaBase * 0.0f).toInt()
            val midAlpha = (alphaBase * 0.8f).toInt()
            val endAlpha = (alphaBase * 0.2f).toInt()
            
            gesturePaint.shader = LinearGradient(
                first.x, first.y, last.x, last.y,
                intArrayOf(
                    Color.argb(startAlpha, 0xE3, 0xE2, 0xE0),
                    Color.argb(midAlpha, 0xE3, 0xE2, 0xE0),
                    Color.argb(endAlpha, 0xE3, 0xE2, 0xE0)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(gesturePath, gesturePaint)
        }
    }

    private fun displayLabel(key: KeyModel): String {
        if (key.code == 10) {
            return when (enterAction) {
                EditorInfo.IME_ACTION_SEARCH -> "🔍"
                EditorInfo.IME_ACTION_SEND -> "Send"
                EditorInfo.IME_ACTION_GO -> "Go"
                EditorInfo.IME_ACTION_DONE -> "Done"
                EditorInfo.IME_ACTION_NEXT -> "Next"
                else -> "↵"
            }
        }
        return if (isShifted && key.code in 97..122) key.label.uppercase() else key.label
    }

    private fun chooseLabelPaint(key: KeyModel): Paint = when {
        key.code in listOf(KeyboardLayout.CODE_SHIFT, KeyboardLayout.CODE_BACKSPACE, 10) -> specialTextPaint
        key.isModifier -> modTextPaint
        else -> textPaint
    }

    private fun labelTextSize(key: KeyModel): Float = when {
        key.label.length > 4 -> dp(12f)
        key.label.length > 1 -> dp(16f)
        else                 -> dp(20f)
    }

    // ── Multi-Touch Engine ────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val layout = keyboardLayout ?: return false
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val key = layout.findKeyAt(x, y, expectedNextChars)
                
                // If it's the very first finger and it misses a key, let the OS handle it
                if (action == MotionEvent.ACTION_DOWN && key == null) return false 

                val ptr = ActivePointer(pointerId, key, x, y)
                activePointers.put(pointerId, ptr)
                
                if (key != null) {
                    setKeyPressed(key, true)
                    listener?.onPress(key.code)
                    if (key.code == KeyboardLayout.CODE_BACKSPACE || key.secondaryLabel != null) {
                        scheduleLongPress(ptr, key)
                    }
                    if (key.code == 32) {
                        // For spacebar, delay swipe detection slightly so taps aren't misread as cursor moves
                        ptr.isWordDeleteTriggered = false // Reusing flag as 'isCursorMoveTriggered'
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Check if any pointer initiated a swipe
                if (swipePointerId == null && activePointers.size() > 0) {
                    for (i in 0 until activePointers.size()) {
                        val ptrId = activePointers.keyAt(i)
                        val ptr = activePointers.valueAt(i)
                        
                        val pIdx = event.findPointerIndex(ptrId)
                        if (pIdx == -1) continue
                        
                        val dx = event.getX(pIdx) - ptr.startX
                        val dy = event.getY(pIdx) - ptr.startY
                        val dist = sqrt(dx * dx + dy * dy)
                        
                        if (ptr.currentKey?.code == 32) {
                            // Spacebar cursor control
                            if (Math.abs(dx) > swipeThresholdPx && !ptr.isWordDeleteTriggered) {
                                ptr.isWordDeleteTriggered = true // Lock into cursor mode
                                cancelLongPress(ptr)
                            }
                            if (ptr.isWordDeleteTriggered) {
                                val steps = (dx / (swipeThresholdPx * 0.8f)).toInt()
                                if (steps != 0) {
                                    listener?.onCursorMove(steps)
                                    // Reset start X so we track continuous movement relatively
                                    ptr.startX += steps * (swipeThresholdPx * 0.8f)
                                }
                            }
                            continue
                        } else if (ptr.currentKey?.code == KeyboardLayout.CODE_BACKSPACE) {
                            // Backspace swipe to delete
                            if (dx < -swipeThresholdPx && !ptr.isWordDeleteTriggered) {
                                ptr.isWordDeleteTriggered = true
                                cancelLongPress(ptr)
                            }
                            if (ptr.isWordDeleteTriggered) {
                                val deleteCount = (-dx / (swipeThresholdPx * 1.5f)).toInt()
                                if (deleteCount > 0) {
                                    listener?.onSwipeDelete(deleteCount)
                                    ptr.startX -= deleteCount * (swipeThresholdPx * 1.5f)
                                }
                            }
                            continue
                        }

                        if (!ptr.isWordDeleteTriggered && ptr.currentKey?.isModifier != true) {
                            if (dist > swipeThresholdPx) {
                                startSwipe(ptrId, ptr)
                                break
                            }
                        }
                    }
                }
                
                // Update swipe path
                if (swipePointerId != null) {
                    val pIdx = event.findPointerIndex(swipePointerId!!)
                    if (pIdx != -1) {
                        val x = event.getX(pIdx)
                        val y = event.getY(pIdx)
                        gesturePoints.add(GesturePoint(x, y, event.eventTime))
                        gesturePath.lineTo(x, y)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ptr = activePointers.get(pointerId)
                if (ptr != null) {
                    cancelLongPress(ptr)
                    
                    if (swipePointerId == pointerId) {
                        if (gesturePoints.size > MIN_SWIPE_POINTS && action != MotionEvent.ACTION_CANCEL) {
                            listener?.onSwipe(gesturePoints.toList(), keyboardLayout!!)
                        }
                        endSwipe()
                    } else if (ptr.currentKey != null && action != MotionEvent.ACTION_CANCEL) {
                        setKeyPressed(ptr.currentKey!!, false)
                        listener?.onRelease(ptr.currentKey!!.code)
                        
                        // Only trigger tap if we didn't trigger a special swipe mode
                        if (!ptr.isLongPressTriggered && !ptr.isWordDeleteTriggered) {
                            listener?.onKey(ptr.currentKey!!.code)
                        }
                    }
                    activePointers.remove(pointerId)
                }
            }
        }
        return true
    }

    private fun setKeyPressed(key: KeyModel, pressed: Boolean) {
        val state = keyAnimStates.getOrPut(key) { KeyAnimState() }
        state.isPressed = pressed
        startAnimationLoop()
    }
    
    private fun startSwipe(pointerId: Int, ptr: ActivePointer) {
        swipePointerId = pointerId
        cancelLongPress(ptr)
        ptr.currentKey?.let { setKeyPressed(it, false) }
        ptr.currentKey = null
        isFadingOutSwipe = false
        gesturePoints.clear()
        gesturePoints.add(GesturePoint(ptr.startX, ptr.startY, 0))
        gesturePath.reset()
        gesturePath.moveTo(ptr.startX, ptr.startY)
        startAnimationLoop()
    }
    
    private fun endSwipe() {
        swipePointerId = null
        isFadingOutSwipe = true
        startAnimationLoop()
    }

    private fun scheduleLongPress(pointer: ActivePointer, key: KeyModel) {
        pointer.longPressRunnable = object : Runnable {
            override fun run() {
                if (pointer.currentKey != key) return
                if (key.code == KeyboardLayout.CODE_BACKSPACE) {
                    listener?.onWordDelete()
                    pointer.isWordDeleteTriggered = true
                    frameHandler.postDelayed(this, 100)
                } else if (key.secondaryLabel != null) {
                    listener?.onLongPressText(key.secondaryLabel)
                    pointer.isLongPressTriggered = true
                    startAnimationLoop()
                }
            }
        }
        frameHandler.postDelayed(pointer.longPressRunnable!!, LONG_PRESS_MS)
    }

    private fun cancelLongPress(pointer: ActivePointer) {
        pointer.longPressRunnable?.let { frameHandler.removeCallbacks(it) }
        pointer.longPressRunnable = null
    }

    private fun rebuildLayout() {
        if (width > 0 && height > 0) {
            keyboardLayout = KeyboardLayout.build(layoutType, width, height, context)
            keyHueOffset.clear() // Prevent memory leak when switching layouts
            invalidate()
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun hsvColor(hue: Float, sat: Float, value: Float, alpha: Int): Int {
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
        return android.graphics.Color.argb(alpha,
            android.graphics.Color.red(rgb),
            android.graphics.Color.green(rgb),
            android.graphics.Color.blue(rgb)
        )
    }

    companion object {
        private const val COLOR_POPUP_TEXT   = 0xFFFFFFFF.toInt()
        private const val SWIPE_THRESHOLD_DP = 24f
        private const val LONG_PRESS_MS = 350L
        private const val MIN_SWIPE_POINTS = 5
    }
}
