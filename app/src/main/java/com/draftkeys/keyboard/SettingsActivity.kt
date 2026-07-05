package com.draftkeys.keyboard

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.draftkeys.keyboard.ui.ThemeManager

/**
 * SettingsActivity — premium redesign.
 *
 * • Theme section: a 4-column swatch grid where each swatch shows the theme's
 *   key colour as a circle. Tapping animates a selection ring + live preview.
 * • Key Shape section: a 3-way pill-style toggle for corner radius.
 * • Done button pops the stack.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var themeManager: ThemeManager

    // Shape toggle views
    private lateinit var shapeRounded: TextView
    private lateinit var shapeSquare: TextView
    private lateinit var shapePill: TextView

    // Swatch views keyed by theme name
    private val swatchViews = mutableMapOf<String, View>()
    private var selectedSwatchName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        themeManager = ThemeManager(this)

        buildThemeSwatches()
        setupShapePills()

        findViewById<View>(R.id.btnSaveSettings).setOnClickListener {
            finish()
        }
    }

    // ── Theme Swatches ─────────────────────────────────────────────────────────

    private fun buildThemeSwatches() {
        val container = findViewById<LinearLayout>(R.id.themeSwatchContainer)
        val tvName    = findViewById<TextView>(R.id.tvSelectedThemeName)
        selectedSwatchName = themeManager.currentThemeName

        tvName.text = selectedSwatchName

        val themes     = ThemeManager.THEMES.entries.toList()
        val columns    = 4
        val dp         = resources.displayMetrics.density

        // Build rows of [columns] swatches each
        var rowLayout: LinearLayout? = null
        themes.forEachIndexed { index, (name, palette) ->
            if (index % columns == 0) {
                rowLayout = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = (10 * dp).toInt() }
                    orientation = LinearLayout.HORIZONTAL
                }
                container.addView(rowLayout)
            }

            val swatchCell = buildSwatchCell(name, palette, dp)
            rowLayout?.addView(swatchCell)
            swatchViews[name] = swatchCell

            swatchCell.setOnClickListener {
                selectTheme(name, tvName)
            }
        }

        // Fill any empty cells in last row
        val remainder = themes.size % columns
        if (remainder != 0) {
            repeat(columns - remainder) {
                val spacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                }
                rowLayout?.addView(spacer)
            }
        }

        // Highlight current
        highlightSwatch(selectedSwatchName)
    }

    private fun buildSwatchCell(
        name: String,
        palette: ThemeManager.ThemePalette,
        dp: Float
    ): FrameLayout {
        val size = (52 * dp).toInt()
        val ringSize = (60 * dp).toInt()

        // The colour circle
        val keyColor = if (palette.keyNormal == Color.TRANSPARENT) {
            0xFF1B0A3D.toInt()  // Plasma Storm fallback swatch colour
        } else {
            palette.keyNormal
        }

        val circle = View(this).apply {
            tag = "circle"
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // Linear top-to-bottom gradient: highlight -> key colour -> shadow
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    blendWith(keyColor, 0xFFFFFFFF.toInt(), 0.35f),
                    keyColor,
                    blendWith(keyColor, 0xFF000000.toInt(), 0.35f)
                )
            }
        }

        // Selection ring (initially transparent)
        val ring = View(this).apply {
            tag = "ring"
            layoutParams = FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke((2.5 * dp).toInt(), Color.argb(0, 123, 95, 239))
            }
        }

        // Theme label
        val label = TextView(this).apply {
            text = name
            textSize = 9f
            setTextColor(0xAAFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                (ringSize + (8 * dp).toInt()),
                LinearLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).also { it.bottomMargin = 0 }
        }

        val cell = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, (90 * dp).toInt(), 1f)
            setPadding((4 * dp).toInt())
        }
        cell.addView(ring)
        cell.addView(circle)
        cell.addView(label)
        return cell
    }

    private fun selectTheme(name: String, tvName: TextView) {
        // Deselect old
        unhighlightSwatch(selectedSwatchName)
        // Select new
        selectedSwatchName = name
        highlightSwatch(name)
        tvName.text = name
        // Persist
        themeManager.currentThemeName = name
    }

    private fun highlightSwatch(name: String) {
        val cell = swatchViews[name] as? FrameLayout ?: return
        val ring = cell.findViewWithTag<View>("ring") ?: return
        val bg = ring.background as? GradientDrawable ?: return
        animateRingAlpha(bg, 0, 255)
        // Scale up circle slightly
        val circle = cell.findViewWithTag<View>("circle") ?: return
        circle.animate().scaleX(1.18f).scaleY(1.18f).setDuration(200).start()
    }

    private fun unhighlightSwatch(name: String) {
        val cell = swatchViews[name] as? FrameLayout ?: return
        val ring = cell.findViewWithTag<View>("ring") ?: return
        val bg = ring.background as? GradientDrawable ?: return
        animateRingAlpha(bg, 255, 0)
        val circle = cell.findViewWithTag<View>("circle") ?: return
        circle.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    private fun animateRingAlpha(drawable: GradientDrawable, from: Int, to: Int) {
        val strokeWidth = (2.5 * resources.displayMetrics.density).toInt()
        ValueAnimator.ofInt(from, to).apply {
            duration = 180
            addUpdateListener {
                val alpha = it.animatedValue as Int
                drawable.setStroke(strokeWidth, Color.argb(alpha, 123, 95, 239))
            }
            start()
        }
    }

    // ── Shape Pills ────────────────────────────────────────────────────────────

    private fun setupShapePills() {
        shapeRounded = findViewById(R.id.shapeRounded)
        shapeSquare  = findViewById(R.id.shapeSquare)
        shapePill    = findViewById(R.id.shapePill)

        // Set initial selection
        when (themeManager.cornerRadiusDp) {
            2f  -> activatePill(shapeSquare)
            16f -> activatePill(shapePill)
            else -> activatePill(shapeRounded)
        }

        shapeRounded.setOnClickListener { activatePill(it as TextView); themeManager.cornerRadiusDp = 8f }
        shapeSquare.setOnClickListener  { activatePill(it as TextView); themeManager.cornerRadiusDp = 2f }
        shapePill.setOnClickListener    { activatePill(it as TextView); themeManager.cornerRadiusDp = 16f }
    }

    private fun activatePill(active: TextView) {
        listOf(shapeRounded, shapeSquare, shapePill).forEach { pill ->
            if (pill == active) {
                pill.setBackgroundResource(R.drawable.settings_pill_active)
                pill.setTextColor(0xFFFFFFFF.toInt())
                pill.animate().scaleX(1.02f).scaleY(1.02f).setDuration(150).start()
            } else {
                pill.setBackgroundResource(R.drawable.settings_pill_inactive)
                pill.setTextColor(0xBBCCCCDD.toInt())
                pill.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Blends [color] toward [target] by [ratio] (0=all color, 1=all target). */
    private fun blendWith(color: Int, target: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.argb(
            255,
            (Color.red(color) * inv + Color.red(target) * ratio).toInt().coerceIn(0, 255),
            (Color.green(color) * inv + Color.green(target) * ratio).toInt().coerceIn(0, 255),
            (Color.blue(color) * inv + Color.blue(target) * ratio).toInt().coerceIn(0, 255)
        )
    }
}
