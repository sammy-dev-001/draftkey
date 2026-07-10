package com.draftkeys.keyboard.ui

/** Which set of keys the keyboard is currently displaying. */
enum class KeyboardLayoutType {
    /** Standard QWERTY layout with 5 rows (number row + 4 letter rows). */
    QWERTY,
    /** Symbols / punctuation layout with 4 rows. */
    SYMBOLS,
    /** Emoji layout. */
    EMOJI,
    /** Numeric keypad with 4 rows for numbers and basic symbols. */
    NUMPAD,
    /** Second page of symbols / punctuation. */
    SYMBOLS_MORE,
    /** Screen with ASCII emoticons / Kaomoji. */
    KAOMOJI
}
