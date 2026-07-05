package com.draftkeys.keyboard.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * EmojiKeyboardView — a fixed-height grid of emoji that slides in instead of [DraftKeysView].
 *
 * Height is capped at ~220dp (~40% of the screen height) so it doesn't consume the
 * full screen like a normal RecyclerView would — it feels like a keyboard overlay instead.
 *
 * Glitch fixes vs. v1:
 *  - Adapter uses stable IDs (`setHasStableIds(true)`) so RecyclerView can animate item
 *    changes without full redraws.
 *  - Theme updates use a payload (`PAYLOAD_COLOR`) so only the text colour is rebound,
 *    not the entire view — eliminates the flicker that happened when applyTheme() was called.
 */
class EmojiKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val recyclerView: RecyclerView
    private val adapter: EmojiAdapter
    var listener: KeyboardActionListener? = null

    /** Current theme palette — kept so new ViewHolders inherit the right colour. */
    private var currentPalette: ThemeManager.ThemePalette? = null

    init {
        // Fixed height: ~88dp ≈ 40% of a 4-row keyboard (4 × 52dp = 208dp → 88/208 ≈ 42%)
        recyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            layoutManager = GridLayoutManager(context, 8) // 8 columns
            setHasFixedSize(true)
            setPadding(0, 12, 0, 12)
            clipToPadding = false
            overScrollMode = OVER_SCROLL_NEVER
        }
        addView(recyclerView)

        adapter = EmojiAdapter()
        recyclerView.adapter = adapter
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        // Fixed height ~220dp (roughly the height of the normal keyboard, ~40% of screen)
        val fixedHeight = (220 * d).toInt()
        val widthSpec = widthMeasureSpec
        val heightSpec = MeasureSpec.makeMeasureSpec(fixedHeight, MeasureSpec.EXACTLY)
        super.onMeasure(widthSpec, heightSpec)
    }

    fun setEmojis(newEmojis: List<String>) {
        adapter.submitList(newEmojis)
    }

    fun applyTheme(palette: ThemeManager.ThemePalette) {
        currentPalette = palette
        setBackgroundColor(palette.bg)
        // Payload update: only rebind the colour — no full flicker redraw
        adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_COLOR)
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    private inner class EmojiAdapter : RecyclerView.Adapter<EmojiAdapter.VH>() {

        private var emojis: List<String> = emptyList()

        init {
            setHasStableIds(true) // prevents glitch-flicker on dataset updates
        }

        fun submitList(newEmojis: List<String>) {
            emojis = newEmojis
            notifyDataSetChanged()
        }

        /** Stable ID = emoji unicode codepoint(s) hashed. First item (🔙) always = 0. */
        override fun getItemId(position: Int): Long =
            emojis.getOrNull(position)?.hashCode()?.toLong() ?: position.toLong()

        override fun getItemCount() = emojis.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (44 * resources.displayMetrics.density).toInt()
                )
                textSize = 26f
                gravity = android.view.Gravity.CENTER
                // Ripple feedback
                val outValue = android.util.TypedValue()
                context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, outValue, true
                )
                background = context.getDrawable(outValue.resourceId)
                // Apply current palette colour if available
                currentPalette?.let { setTextColor(it.keyText) }
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(emojis[position])
        }

        override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
            if (payloads.contains(PAYLOAD_COLOR)) {
                // Only update the text colour — no full rebind, no flicker
                currentPalette?.let { holder.tv.setTextColor(it.keyText) }
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv) {
            fun bind(emoji: String) {
                tv.text = emoji
                tv.setOnClickListener { listener?.onEmojiTapped(emoji) }
                currentPalette?.let { tv.setTextColor(it.keyText) }
            }
        }
    }

    companion object {
        private const val PAYLOAD_COLOR = "color"
    }
}
