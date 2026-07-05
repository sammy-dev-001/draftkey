package com.draftkeys.keyboard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * PlasmaBackgroundView — full-bleed animated plasma lava-lamp background.
 * Used exclusively by the "Plasma Storm" keyboard theme.
 */
class PlasmaBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Blob(
        var cx: Float, var cy: Float,
        var vx: Float, var vy: Float,
        var hue: Float, var hueSpeed: Float,
        var radius: Float, var phase: Float
    )

    private val blobs = mutableListOf(
        Blob(0.20f, 0.30f,  0.0011f,  0.0008f,   0f, 0.7f, 0.55f, 0.00f),
        Blob(0.75f, 0.20f, -0.0009f,  0.0013f,  90f, 0.9f, 0.50f, 1.20f),
        Blob(0.50f, 0.75f,  0.0014f, -0.0007f, 200f, 1.1f, 0.60f, 2.40f),
        Blob(0.10f, 0.80f,  0.0007f,  0.0015f, 300f, 0.8f, 0.45f, 0.80f),
        Blob(0.85f, 0.65f, -0.0012f, -0.0010f, 150f, 1.2f, 0.50f, 3.10f),
        Blob(0.40f, 0.10f,  0.0010f,  0.0009f, 260f, 0.6f, 0.55f, 1.70f)
    )

    private var running = false
    private val frameHandler = Handler(Looper.getMainLooper())

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val scanlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 0, 0, 0)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    
    private var scanlineBitmap: Bitmap? = null

    private val frameRunnable = object : Runnable {
        override fun run() {
            updateBlobs()
            invalidate()
            if (running) frameHandler.postDelayed(this, 33)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        frameHandler.post(frameRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        frameHandler.removeCallbacks(frameRunnable)
    }

    private fun updateBlobs() {
        for (b in blobs) {
            var nx = b.cx + b.vx
            var ny = b.cy + b.vy
            if (nx < -0.1f || nx > 1.1f) { b.vx = -b.vx; nx = b.cx + b.vx }
            if (ny < -0.1f || ny > 1.1f) { b.vy = -b.vy; ny = b.cy + b.vy }
            b.cx = nx; b.cy = ny
            b.hue = (b.hue + b.hueSpeed) % 360f
            b.phase += 0.018f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            var sy = 0f
            while (sy < h) {
                canvas.drawLine(0f, sy, w.toFloat(), sy, scanlinePaint)
                sy += 3f
            }
            scanlineBitmap?.recycle()
            scanlineBitmap = bitmap
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        canvas.drawColor(0xFF03000A.toInt())

        // ── Draw blobs using an offscreen layer for ADD blending ──
        // Hardware acceleration ignores PorterDuff.ADD if drawn directly to the 
        // window's backing canvas. It needs a saveLayer to work correctly.
        val layerId = canvas.saveLayer(0f, 0f, w, h, null)
        
        for (b in blobs) {
            val pulse = 0.82f + 0.18f * sin(b.phase.toDouble()).toFloat()
            val r = b.radius * maxOf(w, h) * pulse
            val cx = b.cx * w
            val cy = b.cy * h
            blobPaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    hsvColor(b.hue, 1f, 0.95f, 210),
                    hsvColor((b.hue + 55f) % 360f, 0.85f, 0.65f, 90),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, blobPaint)
        }
        canvas.restoreToCount(layerId)

        // CRT scanlines (from cached bitmap)
        scanlineBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        // Vignette
        vignettePaint.shader = RadialGradient(
            w / 2f, h / 2f, maxOf(w, h) * 0.72f,
            Color.argb(0, 0, 0, 0),
            Color.argb(180, 0, 0, 8),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }

    private fun hsvColor(hue: Float, sat: Float, value: Float, alpha: Int): Int {
        val rgb = Color.HSVToColor(floatArrayOf(hue, sat, value))
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    }
}
