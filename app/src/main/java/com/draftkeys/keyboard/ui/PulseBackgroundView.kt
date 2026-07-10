package com.draftkeys.keyboard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class PulseBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class PulseRipple(
        val cx: Float,
        val cy: Float,
        val hue: Float,
        var progress: Float = 0f, // 0.0 to 1.0
        var isAlive: Boolean = true
    )

    private val ripples = mutableListOf<PulseRipple>()
    private val maxRadius: Float
    
    // Paints are reused to avoid allocation in onDraw
    private val centerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var pulseAnimator: ValueAnimator? = null

    init {
        // Enable hardware acceleration for this view explicitly
        setLayerType(LAYER_TYPE_HARDWARE, null)
        val dm = resources.displayMetrics
        // Max radius covers the whole screen diagonally roughly
        maxRadius = Math.sqrt((dm.widthPixels * dm.widthPixels + dm.heightPixels * dm.heightPixels).toDouble()).toFloat()
    }

    fun spawnPulse(x: Float, y: Float) {
        val hue = (Math.random() * 360).toFloat()
        synchronized(ripples) {
            ripples.add(PulseRipple(x, y, hue))
            // Limit to 12 concurrent ripples to ensure 60fps
            if (ripples.size > 12) {
                ripples.removeAt(0)
            }
        }
        startAnimation()
    }

    fun clearPulses() {
        synchronized(ripples) {
            ripples.clear()
        }
        pulseAnimator?.cancel()
        invalidate()
    }

    private fun startAnimation() {
        if (pulseAnimator?.isRunning == true) return

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000 // Infinite loop, we manually manage progress
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                var needsMoreFrames = false
                synchronized(ripples) {
                    val iter = ripples.iterator()
                    while (iter.hasNext()) {
                        val r = iter.next()
                        // Fast, snappy expansion: 3.5% of max screen radius per frame
                        r.progress += 0.035f 
                        if (r.progress >= 1f) {
                            iter.remove()
                        } else {
                            needsMoreFrames = true
                        }
                    }
                }
                
                if (needsMoreFrames) {
                    invalidate()
                } else {
                    cancel() // Stop animator when no ripples are active
                }
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw the solid dark background for the pulse theme
        canvas.drawColor(0xFF05050A.toInt())
        
        synchronized(ripples) {
            for (r in ripples) {
                // Ease out function for smoother fade
                val alphaProgress = 1f - Math.pow(r.progress.toDouble(), 1.5).toFloat()
                if (alphaProgress <= 0f) continue
                
                val currentRadius = r.progress * maxRadius * 0.5f // Expand to half screen
                val alphaInt = (alphaProgress * 255).coerceIn(0f, 255f).toInt()
                
                // A soft, expanding pulse
                if (currentRadius > 0) {
                    val innerAlpha = (alphaProgress * 160).coerceIn(0f, 255f).toInt()
                    centerGlowPaint.shader = RadialGradient(
                        r.cx, r.cy, currentRadius,
                        intArrayOf(hsvColor(r.hue, 0.85f, 1.0f, innerAlpha), Color.TRANSPARENT),
                        null,
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(r.cx, r.cy, currentRadius, centerGlowPaint)
                }
            }
        }
    }

    private fun hsvColor(hue: Float, sat: Float, value: Float, alpha: Int): Int {
        val rgb = Color.HSVToColor(floatArrayOf(hue, sat, value))
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    }
}
