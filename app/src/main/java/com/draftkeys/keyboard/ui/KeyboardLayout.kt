
package com.draftkeys.keyboard.ui

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF

/**
 * Computes pixel [KeyModel.bounds] for every key in a given layout type.
 *
 * This class replaces the deprecated [android.inputmethodservice.Keyboard] and the
 * XML layout files `res/xml/qwerty.xml` / `res/xml/symbols.xml`.
 *
 * Key widths are still expressed as fractions of total keyboard width (matching the
 * old `android:keyWidth="10%p"` attributes), making the layout fully responsive.
 *
 * QWERTY — 5 rows
 * ┌──────────────────────────────────────┐
 * │  1  2  3  4  5  6  7  8  9  0       │  ← number row (10 × 10%)
 * │  q  w  e  r  t  y  u  i  o  p       │  ← QWERTY   (10 × 10%)
 * │   a  s  d  f  g  h  j  k  l        │  ← ASDF     ( 9 × 10%, 5% inset each side)
 * │ ⇧  z  x  c  v  b  n  m  ⌫         │  ← ZXCV     (⇧ 15%, 7×10%, ⌫ 15%)
 * │ ?123  ,     SPACE      .  ↵         │  ← Bottom   (20 10 40 10 20)
 * └──────────────────────────────────────┘
 *
 * SYMBOLS — 4 rows
 * ┌──────────────────────────────────────┐
 * │  1  2  3  4  5  6  7  8  9  0       │
 * │  @  #  $  %  &  -  +  (  )  /       │
 * │  =  *  !  "  '  :  ;  ⌫            │  (8 × 12.5%)
 * │ ABC  ←    SPACE    →  ↵             │
 * └──────────────────────────────────────┘
 */
class KeyboardLayout private constructor(
    /** All keys in display order (row-major). */
    val keys: List<KeyModel>
) {

    // ── Hit-testing ──────────────────────────────────────────────

    /** Returns the key whose [KeyModel.bounds] contain ([x], [y]), expanding expected keys by 15%. */
    fun findKeyAt(x: Float, y: Float, expectedNextChars: Set<Char> = emptySet()): KeyModel? {
        if (expectedNextChars.isEmpty()) {
            return keys.firstOrNull { it.bounds.contains(x, y) }
        }
        
        var bestKey: KeyModel? = null
        var bestDist = Float.MAX_VALUE
        
        for (key in keys) {
            var bounds = key.bounds
            val isExpected = key.label.isNotEmpty() && expectedNextChars.contains(key.label.firstOrNull()?.lowercaseChar() ?: ' ')
            if (isExpected) {
                val dx = bounds.width() * 0.15f // Expand 15% in all directions
                val dy = bounds.height() * 0.15f
                bounds = RectF(bounds.left - dx, bounds.top - dy, bounds.right + dx, bounds.bottom + dy)
            }
            if (bounds.contains(x, y)) {
                val cx = key.bounds.centerX() - x
                val cy = key.bounds.centerY() - y
                var dist = cx * cx + cy * cy
                
                // Discount distance heavily for expected keys so they win tie-breaks
                // if the touch falls in overlapping bounds (e.g. user tapped near edge of wrong key)
                if (isExpected) {
                    dist *= 0.1f 
                }
                
                if (dist < bestDist) {
                    bestDist = dist
                    bestKey = key
                }
            }
        }
        return bestKey
    }

    /**
     * Returns the key whose centre is closest to ([x], [y]).
     * Used by the gesture decoder when a swipe path passes *near* but not *over* a key.
     */
    fun findNearestKey(x: Float, y: Float): KeyModel? =
        keys.minByOrNull {
            val dx = it.bounds.centerX() - x
            val dy = it.bounds.centerY() - y
            dx * dx + dy * dy
        }

    /** Returns the screen centre of the key with the given [code], or null. */
    fun keyCentre(code: Int): PointF? =
        keys.firstOrNull { it.code == code }
            ?.bounds
            ?.let { PointF(it.centerX(), it.centerY()) }

    // ── Companion ────────────────────────────────────────────────

    companion object {

        // ── Special key codes (mirrors old XML codes) ─────────────
        const val CODE_SHIFT        = -1
        const val CODE_BACKSPACE    = -5
        const val CODE_MODE_CHANGE  = -2
        const val CODE_SYMBOLS_PAGE_1 = -105
        const val CODE_SYMBOLS_PAGE_2 = -106
        const val CODE_EMOTICON = -107
        const val CODE_TEXT_INSERT = -109
        const val CODE_CURSOR_LEFT  = -100
        const val CODE_CURSOR_RIGHT = -101

        // ── Key dimensions ────────────────────────────────────────
        /** Height of each key row in dp. */
        const val KEY_HEIGHT_DP     = 52f
        /** Horizontal + vertical gap between keys in dp. */
        const val KEY_GAP_DP        = 2f
        /** Bottom padding below the last row in dp. */
        const val ROW_BOTTOM_PADDING_DP = 0f

        // ─────────────────────────────────────────────────────────
        /**
         * Builds a [KeyboardLayout] for the given [type] and screen [totalWidth].
         * Call this from [android.view.View.onSizeChanged].
         */
        fun build(
            type: KeyboardLayoutType,
            totalWidth: Int,
            totalHeight: Int,
            context: Context
        ): KeyboardLayout {
            val d          = context.resources.displayMetrics.density
            val keyHeight  = KEY_HEIGHT_DP * d
            val gap        = KEY_GAP_DP * d
            
            // Calculate total height needed for the keys (rows + per-row gaps + bottom padding)
            val rowCount   = 4 // All layouts currently use 4 rows
            val keysHeight = rowCount * keyHeight + rowCount * gap + ROW_BOTTOM_PADDING_DP * d

            // Align to bottom: remaining space above becomes top padding
            val topPad     = (totalHeight - keysHeight).coerceAtLeast(0f)

            val rowDefs = when (type) {
                KeyboardLayoutType.QWERTY -> qwertyRows()
                KeyboardLayoutType.SYMBOLS -> symbolsRows()
                KeyboardLayoutType.SYMBOLS_MORE -> symbolsMoreRows()
                KeyboardLayoutType.KAOMOJI -> kaomojiRows()
                KeyboardLayoutType.EMOJI -> emojiRows()
                KeyboardLayoutType.NUMPAD -> numpadRows()
            }
            val keys    = mutableListOf<KeyModel>()
            var currentY = topPad

            for (rowDef in rowDefs) {
                var currentX = rowDef.xOffsetFraction * totalWidth

                for (kd in rowDef.keys) {
                    val keyWidth = kd.widthFraction * totalWidth
                    val bounds = RectF(
                        currentX + gap * 0.5f,
                        currentY + gap * 0.5f,
                        currentX + keyWidth - gap * 0.5f,
                        currentY + keyHeight - gap * 0.5f
                    )
                    keys.add(
                        KeyModel(
                            code       = kd.code,
                            label      = kd.label,
                            bounds     = bounds,
                            isModifier = kd.isModifier,
                            secondaryLabel = kd.secondaryLabel,
                            alternates = kd.alternates
                        )
                    )
                    currentX += keyWidth
                }
                currentY += keyHeight
            }

            return KeyboardLayout(keys)
        }

        // ════════════════════════════════════════════════════════════════════════
        // Layout Definitions
        // ════════════════════════════════════════════════════════════════════════

        private fun numpadRows() = listOf(
            row(
                key('1'.code, "1", widthFraction = 0.25f), key('2'.code, "2", widthFraction = 0.25f), key('3'.code, "3", widthFraction = 0.25f), mod(CODE_BACKSPACE, "⌫", 0.25f)
            ),
            row(
                key('4'.code, "4", widthFraction = 0.25f), key('5'.code, "5", widthFraction = 0.25f), key('6'.code, "6", widthFraction = 0.25f), key('-'.code, "-", widthFraction = 0.25f)
            ),
            row(
                key('7'.code, "7", widthFraction = 0.25f), key('8'.code, "8", widthFraction = 0.25f), key('9'.code, "9", widthFraction = 0.25f), key(' '.code, "␣", widthFraction = 0.25f)
            ),
            row(
                mod(CODE_MODE_CHANGE, "?123", 0.25f), key('0'.code, "0", widthFraction = 0.25f), key('.'.code, ".", widthFraction = 0.25f), mod(10, "↵", 0.25f)
            )
        )

        // ─────────────────────────────────────────────────────────
        // QWERTY row definitions
        // ─────────────────────────────────────────────────────────

        private fun qwertyRows() = listOf(
            // ── Row 1: QWERTY row (10 × 10%) ──────────────────────
            row(
                key('q'.code, "q", secondaryLabel = "1"), key('w'.code, "w", secondaryLabel = "2"), key('e'.code, "e", secondaryLabel = "3", alternates = listOf("é","è","ê","ë")),
                key('r'.code, "r", secondaryLabel = "4"), key('t'.code, "t", secondaryLabel = "5"), key('y'.code, "y", secondaryLabel = "6"),
                key('u'.code, "u", secondaryLabel = "7", alternates = listOf("ú","ù","û","ü")),
                key('i'.code, "i", secondaryLabel = "8", alternates = listOf("í","ì","î","ï")),
                key('o'.code, "o", secondaryLabel = "9", alternates = listOf("ó","ò","ô","ö","ø")),
                key('p'.code, "p", secondaryLabel = "0")
            ),
            // ── Row 2: ASDF row (9 keys, centred — 5% inset each side) ──
            row(
                key('a'.code, "a", secondaryLabel = "@", alternates = listOf("á","à","â","ä","å","æ")),
                key('s'.code, "s", secondaryLabel = "#", alternates = listOf("ß")),
                key('d'.code, "d", secondaryLabel = "$"), key('f'.code, "f", secondaryLabel = "&"),
                key('g'.code, "g", secondaryLabel = "*"), key('h'.code, "h", secondaryLabel = "-"),
                key('j'.code, "j", secondaryLabel = "+"), key('k'.code, "k", secondaryLabel = "("),
                key('l'.code, "l", secondaryLabel = ")"),
                xOffset = 0.05f
            ),
            // ── Row 3: ZXCV row ────────────────────────────────────
            row(
                mod(CODE_SHIFT,    "⇧",    0.15f),
                key('z'.code, "z", secondaryLabel = "%"), key('x'.code, "x", secondaryLabel = "\""),
                key('c'.code, "c", secondaryLabel = "'", alternates = listOf("ç")),
                key('v'.code, "v", secondaryLabel = ":"), key('b'.code, "b", secondaryLabel = ";"),
                key('n'.code, "n", secondaryLabel = "!", alternates = listOf("ñ")),
                key('m'.code, "m", secondaryLabel = "?"),
                mod(CODE_BACKSPACE,"⌫",    0.15f)
            ),
            // ── Row 4: Bottom row ──────────────────────────────────────────────────
            row(
                mod(CODE_MODE_CHANGE,"?123", 0.15f),
                key(','.code,   ",",         0.10f),
                mod(-10,        "😃",        0.10f), // Emoji
                mod(32,         "SPACE",     0.35f),
                key('.'.code,   ".",         0.10f),
                mod(10,         "↵",         0.20f)
            )
        )

        // ─────────────────────────────────────────────────────────
        // Symbols row definitions
        // ─────────────────────────────────────────────────────────

        private fun symbolsRows() = listOf(
            // ── Row 0: Number row ──────────────────────────────────
            row(
                key('1'.code, "1"), key('2'.code, "2"), key('3'.code, "3"),
                key('4'.code, "4"), key('5'.code, "5"), key('6'.code, "6"),
                key('7'.code, "7"), key('8'.code, "8"), key('9'.code, "9"),
                key('0'.code, "0")
            ),
            // ── Row 1: Symbols ─────────────────────────────────────
            row(
                key('@'.code, "@", alternates = listOf("©", "®")), 
                key('#'.code, "#", alternates = listOf("№")), 
                key('$'.code, "$", alternates = listOf("€", "£", "¥", "¢")),
                key('%'.code, "%", alternates = listOf("‰")), 
                key('&'.code, "&"), 
                key('-'.code, "-", alternates = listOf("_", "—", "–")),
                key('+'.code, "+", alternates = listOf("±")), 
                key('('.code, "(", alternates = listOf("[", "{", "<")), 
                key(')'.code, ")", alternates = listOf("]", "}", ">")),
                key('/'.code, "/", alternates = listOf("\\", "|"))
            ),
            // ── Row 2: More symbols (9 keys) ───────────────────────
            row(
                key('?'.code, "?",  0.111f, alternates = listOf("¿")), 
                key('!'.code, "!",  0.111f, alternates = listOf("¡")),
                key('"'.code, "\"", 0.111f, alternates = listOf("“", "”", "„")), 
                key('\''.code,"'",  0.111f, alternates = listOf("‘", "’", "`")),
                key(':'.code, ":",  0.111f), 
                key(';'.code, ";",  0.111f),
                key('='.code, "=",  0.111f, alternates = listOf("≈", "≠", "≡")), 
                key('*'.code, "*",  0.111f, alternates = listOf("×", "÷", "★")),
                mod(CODE_BACKSPACE, "⌫",    0.112f)
            ),
            // ── Row 3: Bottom row ───────────────────────────────────
            row(
                mod(CODE_MODE_CHANGE,  "ABC", 0.20f),
                mod(CODE_EMOTICON,     "^_^", 0.10f, alternates = listOf("¯\\_(ツ)_/¯", "( ͡° ͜ʖ ͡°)", "ಠ_ಠ", "ʕ•ᴥ•ʔ", "(╯°□°)╯︵ ┻━┻", "༼ つ ◕_◕ ༽つ")),
                mod(32,                "SPACE",0.40f),
                mod(CODE_SYMBOLS_PAGE_2, "2/2", 0.10f),
                mod(10,                "↵",   0.20f)
            )
        )

        private fun symbolsMoreRows() = listOf(
            row(
                key('~'.code, "~"), key('`'.code, "`"), key('|'.code, "|"),
                key('•'.code, "•"), key('√'.code, "√"), key('π'.code, "π"),
                key('÷'.code, "÷"), key('×'.code, "×"), key('¶'.code, "¶"),
                key('∆'.code, "∆")
            ),
            row(
                key('£'.code, "£"), key('¢'.code, "¢"), key('€'.code, "€"),
                key('¥'.code, "¥"), key('^'.code, "^"), key('°'.code, "°"),
                key('='.code, "="), key('{'.code, "{"), key('}'.code, "}"),
                key('\\'.code, "\\")
            ),
            row(
                key('%'.code, "%"), key('©'.code, "©"),
                key('®'.code, "®"), key('™'.code, "™"),
                key('✓'.code, "✓"), key('['.code, "["),
                key(']'.code, "]"), key('<'.code, "<"),
                key('>'.code, ">"),
                mod(CODE_BACKSPACE, "⌫", 0.1f)
            ),
            row(
                mod(CODE_MODE_CHANGE,  "ABC", 0.20f),
                mod(CODE_SYMBOLS_PAGE_1, "1/2", 0.10f),
                mod(32,                "SPACE",0.40f),
                mod(CODE_SYMBOLS_PAGE_2, "2/2", 0.10f),
                mod(10,                "↵",   0.20f)
            )
        )

        // ─────────────────────────────────────────────────────────
        // Kaomoji row definitions
        // ─────────────────────────────────────────────────────────

        private fun kaomojiRows() = listOf(
            row(
                mod(CODE_TEXT_INSERT, "¯\\_(ツ)_/¯", 0.33f), mod(CODE_TEXT_INSERT, "( ͡° ͜ʖ ͡°)", 0.33f), mod(CODE_TEXT_INSERT, "ಠ_ಠ", 0.34f)
            ),
            row(
                mod(CODE_TEXT_INSERT, "ʕ•ᴥ•ʔ", 0.33f), mod(CODE_TEXT_INSERT, "(╯°□°)╯", 0.33f), mod(CODE_TEXT_INSERT, "༼ つ ◕_◕ ༽つ", 0.34f)
            ),
            row(
                mod(CODE_TEXT_INSERT, "•ᴗ•", 0.33f), mod(CODE_TEXT_INSERT, "T_T", 0.33f), mod(CODE_TEXT_INSERT, "♡", 0.34f)
            ),
            row(
                mod(CODE_MODE_CHANGE, "ABC", 0.20f), mod(CODE_TEXT_INSERT, "(-_-)", 0.30f), mod(CODE_TEXT_INSERT, "(* ^ ω ^)", 0.30f), mod(CODE_BACKSPACE, "⌫", 0.20f)
            )
        )

        // ─────────────────────────────────────────────────────────
        // Emoji row definitions
        // ─────────────────────────────────────────────────────────

        private fun emojiRows() = listOf(
            row(
                key(0x1F602, "😂", 0.125f), key(0x1F62D, "😭", 0.125f),
                key(0x1F97A, "🥺", 0.125f), key(0x1F923, "🤣", 0.125f),
                key(0x2764, "❤", 0.125f),  key(0x2728, "✨", 0.125f),
                key(0x1F60D, "😍", 0.125f), key(0x1F64F, "🙏", 0.125f)
            ),
            row(
                key(0x1F970, "🥰", 0.125f), key(0x1F60A, "😊", 0.125f),
                key(0x1F480, "💀", 0.125f), key(0x1F4AF, "💯", 0.125f),
                key(0x1F60E, "😎", 0.125f), key(0x1F44D, "👍", 0.125f),
                key(0x1F525, "🔥", 0.125f), key(0x1F612, "😒", 0.125f)
            ),
            row(
                key(0x1F644, "🙄", 0.125f), key(0x1F914, "🤔", 0.125f),
                key(0x1F614, "😔", 0.125f), key(0x1F609, "😉", 0.125f),
                key(0x1F44C, "👌", 0.125f), key(0x1F937, "🤷", 0.125f),
                key(0x1F60C, "😌", 0.125f), mod(CODE_BACKSPACE, "⌫", 0.125f)
            ),
            row(
                mod(CODE_MODE_CHANGE, "ABC", 0.20f),
                mod(32, "SPACE", 0.60f),
                mod(10, "↵", 0.20f)
            )
        )

        // ─────────────────────────────────────────────────────────
        // Shorthand builders
        // ─────────────────────────────────────────────────────────

        /** Regular (character) key — default width 10% of keyboard. */
        private fun key(
            code: Int, label: String,
            widthFraction: Float = 0.1f,
            secondaryLabel: String? = null,
            alternates: List<String> = emptyList()
        ) = KeyDef(code, label, widthFraction, isModifier = false, secondaryLabel = secondaryLabel, alternates = alternates)

        /** Modifier / action key. */
        private fun mod(code: Int, label: String, widthFraction: Float, alternates: List<String> = emptyList()) =
            KeyDef(code, label, widthFraction, isModifier = true, alternates = alternates)

        /** Row with an optional horizontal x-offset (for the centred ASDF row). */
        private fun row(vararg keys: KeyDef, xOffset: Float = 0f) =
            RowDef(keys.toList(), xOffset)

        // ─────────────────────────────────────────────────────────
        // Internal data holders (not exposed outside this file)
        // ─────────────────────────────────────────────────────────

        private data class KeyDef(
            val code: Int,
            val label: String,
            val widthFraction: Float,
            val isModifier: Boolean,
            val secondaryLabel: String? = null,
            val alternates: List<String> = emptyList()
        )

        private data class RowDef(
            val keys: List<KeyDef>,
            val xOffsetFraction: Float = 0f
        )
    }
}
