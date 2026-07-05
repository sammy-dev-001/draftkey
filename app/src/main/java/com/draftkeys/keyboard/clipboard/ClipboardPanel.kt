package com.draftkeys.keyboard.clipboard

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.draftkeys.keyboard.R

/**
 * ClipboardPanel — a horizontally-scrollable panel that slides in above the keyboard.
 *
 * Shows the 30 most recent clipboard entries (pinned first, then newest).
 * Each chip shows a text preview and icon buttons for paste/pin/delete.
 *
 * Usage:
 *   • Toggle visibility with [show] / [hide] / [toggle].
 *   • Call [setClips] to populate with data from [ClipboardHistoryManager].
 *   • Set [onPaste], [onPin], [onDelete] callbacks.
 */
class ClipboardPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // ── Callbacks (set by KeyboardService) ──────────────────────
    var onPaste:  (ClipEntry) -> Unit = {}
    var onPin:    (ClipEntry) -> Unit = {}
    var onDelete: (ClipEntry) -> Unit = {}

    private val recyclerView: RecyclerView
    private val emptyHint: TextView
    private val adapter = ClipAdapter()

    init {
        LayoutInflater.from(context).inflate(R.layout.view_clipboard_panel, this, true)
        recyclerView = findViewById(R.id.rv_clips)
        emptyHint    = findViewById(R.id.tv_empty_hint)

        recyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.adapter       = adapter
    }

    fun setClips(clips: List<ClipEntry>) {
        adapter.clips = clips.toMutableList()
        adapter.notifyDataSetChanged()
        emptyHint.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
    }

    fun show() { visibility = View.VISIBLE }
    fun hide() { visibility = View.GONE    }
    fun toggle() { if (visibility == View.VISIBLE) hide() else show() }
    val isOpen get() = visibility == View.VISIBLE

    // ── Adapter ──────────────────────────────────────────────────

    inner class ClipAdapter : RecyclerView.Adapter<ClipAdapter.VH>() {

        var clips = mutableListOf<ClipEntry>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_clip_entry, parent, false)
            return VH(v)
        }

        override fun getItemCount() = clips.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(clips[position])
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvPreview: TextView   = itemView.findViewById(R.id.tv_clip_preview)
            private val btnPaste:  View       = itemView.findViewById(R.id.btn_clip_paste)
            private val btnPin:    View       = itemView.findViewById(R.id.btn_clip_pin)
            private val btnDelete: View       = itemView.findViewById(R.id.btn_clip_delete)

            fun bind(entry: ClipEntry) {
                // Show up to 60 chars preview
                tvPreview.text = entry.text.take(60).let {
                    if (entry.text.length > 60) "$it…" else it
                }

                // Pin icon state
                btnPin.alpha = if (entry.isPinned) 1f else 0.4f

                // Tapping anywhere on the chip pastes it
                itemView.setOnClickListener { onPaste(entry) }
                btnPaste.setOnClickListener  { onPaste(entry) }
                btnPin.setOnClickListener    { onPin(entry)   }
                btnDelete.setOnClickListener { onDelete(entry); removeAt(bindingAdapterPosition) }
            }
        }

        private fun removeAt(pos: Int) {
            if (pos in clips.indices) {
                clips.removeAt(pos)
                notifyItemRemoved(pos)
                if (clips.isEmpty()) emptyHint.visibility = View.VISIBLE
            }
        }
    }
}
