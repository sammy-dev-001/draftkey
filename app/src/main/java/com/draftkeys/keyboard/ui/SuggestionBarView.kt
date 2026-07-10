package com.draftkeys.keyboard.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private val rvSuggestions: RecyclerView
    private val btnClipboardBar: TextView
    private val btnVoice: ImageView
    private val btnSettings: ImageView
    
    private val adapter = SuggestionAdapter()

    init {
        LayoutInflater.from(context).inflate(R.layout.suggestion_bar, this, true)

        rvSuggestions = findViewById(R.id.rv_suggestions)
        btnClipboardBar = findViewById(R.id.btn_clipboard_bar)
        btnVoice = findViewById(R.id.btn_voice)
        btnSettings = findViewById(R.id.btn_settings)

        rvSuggestions.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvSuggestions.adapter = adapter

        btnClipboardBar.setOnClickListener {
            listener?.onClipboardButtonTapped()
        }

        btnVoice.setOnClickListener {
            listener?.onVoiceTapped()
        }

        btnSettings.setOnClickListener {
            listener?.onSettingsButtonTapped()
        }

        updateUi()
    }
    
    fun applyTheme(palette: ThemeManager.ThemePalette) {
        setBackgroundColor(palette.bg)
        colorTv0 = palette.keySecText
        colorTv1 = palette.keyText
        colorTv2 = palette.keySecText
        adapter.notifyDataSetChanged()
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
        suggestions.addAll(words)
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
        displayWords.clear()
        displayWords.add("↩ $original")
        adapter.notifyDataSetChanged()
    }

    private fun updateUi() {
        if (isUndoMode) return // Managed directly by showAutocorrectUndo
        
        displayWords.clear()

        // Show unknown typed word on the left if it's not already in suggestions
        val isUnknown = currentTypedWord.isNotEmpty() &&
                suggestions.none { it.equals(currentTypedWord, ignoreCase = true) }

        if (isUnknown) {
            displayWords.add(currentTypedWord)
            displayWords.addAll(suggestions)
            unknownWordAtLeft = true
        } else {
            displayWords.addAll(suggestions)
            unknownWordAtLeft = false
        }

        adapter.notifyDataSetChanged()
        if (displayWords.isNotEmpty()) {
            rvSuggestions.scrollToPosition(0)
        }
    }
    
    private inner class SuggestionAdapter : RecyclerView.Adapter<SuggestionAdapter.ViewHolder>() {
        
        inner class ViewHolder(val tv: TextView) : RecyclerView.ViewHolder(tv) {
            init {
                tv.setOnClickListener {
                    val index = bindingAdapterPosition
                    if (index != RecyclerView.NO_POSITION) {
                        if (isUndoMode) {
                            if (index == 0) {
                                listener?.onAutocorrectUndoTapped(undoOriginal)
                            }
                            isUndoMode = false
                            updateUi()
                            return@setOnClickListener
                        }
                        val word = displayWords.getOrNull(index)
                        if (!word.isNullOrEmpty()) {
                            listener?.onSuggestionTapped(word)
                        }
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false) as TextView
            return ViewHolder(tv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.tv.text = displayWords[position]
            
            if (isUndoMode && position == 0) {
                holder.tv.setTextColor(0xFF_FFA500.toInt())
            } else {
                if (position == 0 && unknownWordAtLeft) {
                    holder.tv.setTextColor(colorTv0)
                    holder.tv.paint.isFakeBoldText = false
                } else if (position == 0 || (position == 1 && unknownWordAtLeft)) {
                    // Top suggestion
                    holder.tv.setTextColor(colorTv1)
                    holder.tv.paint.isFakeBoldText = true
                } else {
                    holder.tv.setTextColor(colorTv2)
                    holder.tv.paint.isFakeBoldText = false
                }
            }
        }

        override fun getItemCount(): Int = displayWords.size
    }
}
