package com.draftkeys.keyboard.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

class ThemeManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("draftkeys_prefs", Context.MODE_PRIVATE)

    var currentThemeName: String
        get() = prefs.getString("theme", "Graphite Grey") ?: "Graphite Grey"
        set(value) = prefs.edit().putString("theme", value).apply()

    fun getTheme(): ThemePalette {
        return THEMES[currentThemeName] ?: THEMES["Graphite Grey"]!!
    }

    var cornerRadiusDp: Float
        get() = prefs.getFloat("corner_radius", 8f)
        set(value) = prefs.edit().putFloat("corner_radius", value).apply()

    var autoShuffle: Boolean
        get() = prefs.getBoolean("auto_shuffle", true)
        set(value) = prefs.edit().putBoolean("auto_shuffle", value).apply()

    var lastShuffleTime: Long
        get() = prefs.getLong("last_shuffle_time", 0L)
        set(value) = prefs.edit().putLong("last_shuffle_time", value).apply()

    fun checkAutoShuffle(): Boolean {
        if (!autoShuffle) return false
        val now = System.currentTimeMillis()
        // Shuffle every 3 hours
        if (now - lastShuffleTime > 3 * 60 * 60 * 1000L) {
            val themes = THEMES.keys.toList()
            var newTheme = themes.random()
            while (newTheme == currentThemeName && themes.size > 1) {
                newTheme = themes.random()
            }
            currentThemeName = newTheme
            lastShuffleTime = now
            return true
        }
        return false
    }

    data class ThemePalette(
        val bg: Int,
        val keyNormal: Int,
        val keyModifier: Int,
        val keyPressed: Int,
        val keyShadow: Int,
        val keyText: Int,
        val keySecText: Int,
        val modText: Int,
        val accent: Int,
        /** When true, DraftKeysView renders rainbow per-key colours and the
         *  PlasmaBackgroundView drives the background instead of a solid colour. */
        val isWild: Boolean = false,
        /** When true, DraftKeysView draws radial touch-pulse ripples on the
         *  background on every keypress — random hue per tap. */
        val isPulse: Boolean = false
    )

    companion object {
        val THEMES = mapOf(
            "Graphite Grey" to ThemePalette(
                bg = 0xFF141415.toInt(),
                keyNormal = 0xFF363638.toInt(),
                keyModifier = 0xFF262628.toInt(),
                keyPressed = 0xFF4A4A4C.toInt(),
                keyShadow = 0xFF070708.toInt(),
                keyText = 0xFFE5E2E1.toInt(),
                keySecText = 0xFF8E8E93.toInt(),
                modText = 0xFFE5E2E1.toInt(),
                accent = 0xFFE3E2E0.toInt()
            ),
            "Midnight Grey" to ThemePalette(
                bg = 0xFF081226.toInt(),
                keyNormal = 0xFF14244B.toInt(),
                keyModifier = 0xFF0C1938.toInt(),
                keyPressed = 0xFF1F366E.toInt(),
                keyShadow = 0xFF040A17.toInt(),
                keyText = 0xFFE8F0FE.toInt(),
                keySecText = 0xFF8AB4F8.toInt(),
                modText = 0xFF8AB4F8.toInt(),
                accent = 0xFF8AB4F8.toInt()
            ),
            "Midnight Blue" to ThemePalette(
                bg = 0xFF001533.toInt(),
                keyNormal = 0xFF003380.toInt(),
                keyModifier = 0xFF002255.toInt(),
                keyPressed = 0xFF0044AA.toInt(),
                keyShadow = 0xFF00081A.toInt(),
                keyText = 0xFFFFFFFF.toInt(),
                keySecText = 0xFF99BBFF.toInt(),
                modText = 0xFF99BBFF.toInt(),
                accent = 0xFF3388FF.toInt()
            ),
            "Pure Black" to ThemePalette(
                bg = 0xFF000000.toInt(),
                keyNormal = 0xFF1C1C1E.toInt(),
                keyModifier = 0xFF000000.toInt(),
                keyPressed = 0xFF2C2C2E.toInt(),
                keyShadow = 0xFF000000.toInt(),
                keyText = 0xFFFFFFFF.toInt(),
                keySecText = 0xFF8E8E93.toInt(),
                modText = 0xFFFFFFFF.toInt(),
                accent = 0xFFFFFFFF.toInt()
            ),
            "Ocean Teal" to ThemePalette(
                bg = 0xFF0B191E.toInt(),
                keyNormal = 0xFF152A33.toInt(),
                keyModifier = 0xFF0E2027.toInt(),
                keyPressed = 0xFF1E3C48.toInt(),
                keyShadow = 0xFF050E12.toInt(),
                keyText = 0xFFFFFFFF.toInt(),
                keySecText = 0xFF658A99.toInt(),
                modText = 0xFF00BCD4.toInt(),
                accent = 0xFF00BCD4.toInt()
            ),
            "Crimson Red" to ThemePalette(
                bg = 0xFF1C0A0A.toInt(),
                keyNormal = 0xFF2A1515.toInt(),
                keyModifier = 0xFF1F0F0F.toInt(),
                keyPressed = 0xFF3D2121.toInt(),
                keyShadow = 0xFF0E0404.toInt(),
                keyText = 0xFFFFFFFF.toInt(),
                keySecText = 0xFFD88A8A.toInt(),
                modText = 0xFFFF5252.toInt(),
                accent = 0xFFFF5252.toInt()
            ),
            "Forest Green" to ThemePalette(
                bg = 0xFF0A1C12.toInt(),
                keyNormal = 0xFF152A20.toInt(),
                keyModifier = 0xFF0F1F17.toInt(),
                keyPressed = 0xFF213D2F.toInt(),
                keyShadow = 0xFF040E09.toInt(),
                keyText = 0xFFFFFFFF.toInt(),
                keySecText = 0xFF8AD8B0.toInt(),
                modText = 0xFF4CAF50.toInt(),
                accent = 0xFF4CAF50.toInt()
            ),
            "Sunset Orange" to ThemePalette(
                bg = 0xFF21130A.toInt(),
                keyNormal = 0xFF332015.toInt(),
                keyModifier = 0xFF26170E.toInt(),
                keyPressed = 0xFF4A3121.toInt(),
                keyShadow = 0xFF120904.toInt(),
                keyText = 0xFFFFFFFF.toInt(),
                keySecText = 0xFFD8A38A.toInt(),
                modText = 0xFFFF9800.toInt(),
                accent = 0xFFFF9800.toInt()
            ),
            "Soft Lavender" to ThemePalette(
                bg = 0xFF14121F.toInt(),
                keyNormal = 0xFF221F33.toInt(),
                keyModifier = 0xFF191626.toInt(),
                keyPressed = 0xFF322E4A.toInt(),
                keyShadow = 0xFF0A0912.toInt(),
                keyText = 0xFFFFFFFF.toInt(),
                keySecText = 0xFFAFA7D8.toInt(),
                modText = 0xFFB388FF.toInt(),
                accent = 0xFFB388FF.toInt()
            ),
            "Hacker Green" to ThemePalette(
                bg = 0xFF000000.toInt(),
                keyNormal = 0xFF0A0A0A.toInt(),
                keyModifier = 0xFF000000.toInt(),
                keyPressed = 0xFF1A1A1A.toInt(),
                keyShadow = 0xFF000000.toInt(),
                keyText = 0xFF00FF00.toInt(),
                keySecText = 0xFF008800.toInt(),
                modText = 0xFF00FF00.toInt(),
                accent = 0xFF00FF00.toInt()
            ),
            "Neon" to ThemePalette(
                bg = 0xFF0D0221.toInt(),
                keyNormal = 0xFF1B0A3D.toInt(),
                keyModifier = 0xFF281154.toInt(),
                keyPressed = 0xFF35176E.toInt(),
                keyShadow = 0xFF000000.toInt(),
                keyText = 0xFFFF00FF.toInt(),
                keySecText = 0xFF00FFFF.toInt(),
                modText = 0xFF00FFFF.toInt(),
                accent = 0xFFFF00FF.toInt()
            ),
            "Liquid Glass" to ThemePalette(
                bg = 0xFFDCEAF5.toInt(),
                keyNormal = 0xFFEBF3F9.toInt(),
                keyModifier = 0xFFD0E1EF.toInt(),
                keyPressed = 0xFFFFFFFF.toInt(),
                keyShadow = 0xFFB3C8D9.toInt(),
                keyText = 0xFF102A43.toInt(),
                keySecText = 0xFF486581.toInt(),
                modText = 0xFF102A43.toInt(),
                accent = 0xFF007AFF.toInt()
            ),
            "Plasma Storm" to ThemePalette(
                // Background is driven by PlasmaBackgroundView — key colours are
                // semi-transparent so the plasma bleeds through the key faces.
                bg = Color.TRANSPARENT,
                keyNormal   = Color.argb(55, 20, 10, 40),
                keyModifier = Color.argb(80, 10, 5, 25),
                keyPressed  = Color.argb(180, 255, 255, 255),
                keyShadow   = Color.argb(0, 0, 0, 0),
                keyText     = 0xFFFFFFFF.toInt(),
                keySecText  = 0xCCFFFFFF.toInt(),
                modText     = 0xFFFFFFFF.toInt(),
                accent      = 0xFFFFFFFF.toInt(),
                isWild      = true
            ),
            "Chromatic Pulse" to ThemePalette(
                // Pure-black canvas. On every keypress DraftKeysView spawns an expanding
                // radial ripple at the touch point with a random vivid hue.
                // Keys are charcoal-transparent so the bursts show through.
                bg          = 0xFF05050A.toInt(),
                keyNormal   = Color.argb(70,  30, 30, 45),
                keyModifier = Color.argb(100, 20, 20, 35),
                keyPressed  = Color.argb(160, 255, 255, 255),
                keyShadow   = Color.argb(0, 0, 0, 0),
                keyText     = 0xFFFFFFFF.toInt(),
                keySecText  = 0xAAFFFFFF.toInt(),
                modText     = 0xFFFFFFFF.toInt(),
                accent      = 0xFFFFFFFF.toInt(),
                isPulse     = true
            )
        )
    }
}
