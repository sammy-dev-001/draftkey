package com.draftkeys.keyboard.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.draftkeys.keyboard.R

/**
 * SuggestionBarView — a slim strip showing up to 3 word predictions above the keyboard.
 *
 * Inflates `R.layout.suggestion_bar` which contains 3 TextViews and 2 ImageViews.
 *
 * Unknown-word behaviour: if the user is typing a word not found in [suggestions], the
 * typed word is shown in the leftmost slot. Tapping it commits + saves (learns) it via the
 * normal [KeyboardActionListener.onSuggestionTapped] callback, which already calls
 * `personalWordDao().learn()` in [KeyboardService].
 */
class SuggestionBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var listener: KeyboardActionListener? = null

    /** Prediction engine suggestions (up to 3). */
    private val suggestions = mutableListOf<String>()

    /** The word currently being typed, set by [setCurrentWord]. */
    private var currentTypedWord = ""

    /**
     * Effective display list: [0..2] what each TextView actually shows.
     * Index 0 may be the unknown typed word when [unknownWordAtLeft] is true.
     */
    private val displayWords = mutableListOf<String>()
    private var unknownWordAtLeft = false
    private var isUndoMode = false
    private var undoOriginal = ""

    private var colorTv0 = 0xFF_C4C7C4.toInt()
    private var colorTv1 = 0xFF_E5E2E1.toInt()
    private var colorTv2 = 0xFF_C4C7C4.toInt()

    private val tvSuggest0: TextView
    private val tvSuggest1: TextView
    private val tvSuggest2: TextView
    private val btnClipboardBar: TextView
    private val btnSettings: ImageView

    init {
        LayoutInflater.from(context).inflate(R.layout.suggestion_bar, this, true)

        tvSuggest0 = findViewById(R.id.tv_suggest_0)
        tvSuggest1 = findViewById(R.id.tv_suggest_1)
        tvSuggest2 = findViewById(R.id.tv_suggest_2)
        btnClipboardBar = findViewById(R.id.btn_clipboard_bar)
        btnSettings = findViewById(R.id.btn_settings)

        btnClipboardBar.setOnClickListener {
            listener?.onClipboardButtonTapped()
        }

        btnSettings.setOnClickListener {
            listener?.onSettingsButtonTapped()
        }

        val clickListener = OnClickListener { v ->
            val index = when (v.id) {
                R.id.tv_suggest_0 -> 0
                R.id.tv_suggest_1 -> 1
                R.id.tv_suggest_2 -> 2
                else -> -1
            }
            if (isUndoMode) {
                if (index == 0) {
                    listener?.onAutocorrectUndoTapped(undoOriginal)
                }
                isUndoMode = false
                updateUi()
                return@OnClickListener
            }
            val word = displayWords.getOrNull(index)
            if (!word.isNullOrEmpty()) {
                listener?.onSuggestionTapped(word)
            }
        }

        tvSuggest0.setOnClickListener(clickListener)
        tvSuggest1.setOnClickListener(clickListener)
        tvSuggest2.setOnClickListener(clickListener)

        updateUi()
    }
    
    fun applyTheme(palette: ThemeManager.ThemePalette) {
        setBackgroundColor(palette.bg)
        colorTv0 = palette.keySecText
        colorTv1 = palette.keyText
        colorTv2 = palette.keySecText
        tvSuggest0.setTextColor(colorTv0)
        tvSuggest1.setTextColor(colorTv1)
        tvSuggest2.setTextColor(colorTv2)
        btnClipboardBar.setTextColor(palette.keyText)
    }

    /**
     * Called every time the user types or deletes a letter so the bar always
     * reflects the in-progress word. Pass an empty string to hide.
     */
    fun setCurrentWord(word: String) {
        currentTypedWord = word
        isUndoMode = false
        updateUi()
    }

    fun setSuggestions(words: List<String>) {
        suggestions.clear()
        suggestions.addAll(words.take(3))
        isUndoMode = false
        updateUi()
    }

    fun clearSuggestions() {
        suggestions.clear()
        currentTypedWord = ""
        isUndoMode = false
        updateUi()
    }

    fun showAutocorrectUndo(original: String) {
        isUndoMode = true
        undoOriginal = original
        animateTextChange(tvSuggest0, "↩ $original")
        animateTextChange(tvSuggest1, "")
        animateTextChange(tvSuggest2, "")
        
        // Highlight undo action
        tvSuggest0.setTextColor(0xFF_FFA500.toInt()) // subtle orange/yellow indicator
    }

    private fun animateTextChange(tv: TextView, newText: String) {
        if (tv.text.toString() == newText) return
        tv.alpha = 0f
        tv.text = newText
        tv.animate().alpha(1f).setDuration(150).start()
    }

    private fun updateUi() {
        if (isUndoMode) return // Managed directly by showAutocorrectUndo
        
        displayWords.clear()

        // Show unknown typed word on the left if it's not already in suggestions
        val isUnknown = currentTypedWord.isNotEmpty() &&
                suggestions.none { it.equals(currentTypedWord, ignoreCase = true) }

        if (isUnknown) {
            // Left: typed unknown word  |  Middle + Right: top suggestions
            displayWords.add(currentTypedWord)
            displayWords.addAll(suggestions.take(2))
            unknownWordAtLeft = true
        } else {
            displayWords.addAll(suggestions.take(3))
            unknownWordAtLeft = false
        }

        tvSuggest0.setTextColor(colorTv0)

        animateTextChange(tvSuggest0, displayWords.getOrNull(0) ?: "")
        animateTextChange(tvSuggest1, displayWords.getOrNull(1) ?: "")
        animateTextChange(tvSuggest2, displayWords.getOrNull(2) ?: "")
    }
}
