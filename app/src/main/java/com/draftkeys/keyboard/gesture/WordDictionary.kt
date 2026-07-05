package com.draftkeys.keyboard.gesture

import android.content.Context
import com.draftkeys.keyboard.ui.KeyboardLayout
import com.draftkeys.keyboard.ui.KeyModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the bundled word list from `assets/words_en.txt` and builds two indices
 * used by [GestureDecoder]:
 *
 *  1. [wordsByFirstLast] — maps `(firstKeyCode, lastKeyCode)` → list of words that
 *     start and end on those keys.  This prunes 95%+ of candidates before scoring.
 *
 *  2. [keySequences] — cached mapping of word → key code sequence on QWERTY.
 *     Built lazily on first gesture request and reused thereafter.
 *
 * The dictionary is loaded once at [KeyboardService.onCreate] on a background thread.
 */
class WordDictionary(private val context: Context) {

    /** All words from the asset file, in frequency order (most common first). */
    val words: List<String> get() = _words

    /** Inverted index: (firstKey, lastKey) → candidate words. */
    val wordsByFirstLast: Map<Pair<Int, Int>, List<String>> get() = _index

    /** Alphabetically sorted words for fast O(log N) prefix matching. */
    val alphabeticalWords: List<String> get() = _alphabeticalWords

    private val _words = mutableListOf<String>()
    private var _alphabeticalWords = listOf<String>()
    private val _index = mutableMapOf<Pair<Int, Int>, MutableList<String>>()

    /** Pre-computed key sequences for every word. Key = word, Value = ordered list of key codes. */
    private val _keySequences = mutableMapOf<String, List<Int>>()

    private var isLoaded = false

    // ── Static QWERTY key → char map ─────────────────────────────
    companion object {
        /** Maps every lowercase letter to its QWERTY key code (same as char code). */
        private val LETTER_TO_CODE: Map<Char, Int> =
            ('a'..'z').associateWith { it.code }

        const val ASSET_FILE = "words_en.txt"
    }

    // ── Loading ──────────────────────────────────────────────────

    /**
     * Load the word list from assets on a background thread.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext
        try {
            context.assets.open(ASSET_FILE).bufferedReader().forEachLine { line ->
                val word = line.trim().lowercase()
                if (word.length >= 2 && word.all { it.isLetter() }) {
                    _words.add(word)
                }
            }
            _alphabeticalWords = _words.sorted()
            isLoaded = true
        } catch (e: Exception) {
            // If asset is missing, the decoder will return nothing — keyboard still works
        }
    }

    /**
     * Build the (firstKey, lastKey) index using the provided [layout].
     * Must be called after [load] and after the keyboard layout is known.
     *
     * This takes ~50–200 ms for 100K words and should be called on [Dispatchers.IO].
     */
    suspend fun buildIndex() = withContext(Dispatchers.IO) {
        _index.clear()
        _keySequences.clear()

        for (word in _words) {
            val seq = keySequenceFor(word)
            if (seq.size < 2) continue

            _keySequences[word] = seq
            val key = Pair(seq.first(), seq.last())
            _index.getOrPut(key) { mutableListOf() }.add(word)
        }
    }

    /**
     * Returns (or computes and caches) the ordered sequence of key codes the user
     * would press to type [word] on a standard QWERTY layout.
     *
     * Consecutive duplicate keys are deduplicated (e.g. "ll" → just one 'l' key).
     */
    fun keySequenceFor(word: String): List<Int> {
        _keySequences[word]?.let { return it }

        val seq = mutableListOf<Int>()
        var lastCode = -1
        for (ch in word.lowercase()) {
            val code = LETTER_TO_CODE[ch] ?: continue
            if (code != lastCode) {
                seq.add(code)
                lastCode = code
            }
        }
        return seq
    }
}
