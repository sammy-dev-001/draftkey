package com.draftkeys.keyboard

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.draftkeys.keyboard.data.DraftDatabase
import com.draftkeys.keyboard.prediction.TextExpansionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * D1.5: UI for managing Text Shortcuts.
 * Allows users to add, edit, or delete custom text expansions.
 */
class TextShortcutsActivity : AppCompatActivity() {

    private lateinit var shortcutsList: LinearLayout
    private val db by lazy { DraftDatabase.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_shortcuts)

        shortcutsList = findViewById(R.id.shortcutsList)

        findViewById<View>(R.id.btnAddShortcut).setOnClickListener {
            showEditDialog(null)
        }

        loadShortcuts()
    }

    private fun loadShortcuts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = db.textExpansionDao().getAll()
            withContext(Dispatchers.Main) {
                shortcutsList.removeAllViews()
                if (list.isEmpty()) {
                    val emptyTv = TextView(this@TextShortcutsActivity).apply {
                        text = "No shortcuts yet. Add one to type faster!"
                        setTextColor(Color.parseColor("#55667788"))
                        textSize = 16f
                        gravity = Gravity.CENTER
                        setPadding(0, 60, 0, 0)
                    }
                    shortcutsList.addView(emptyTv)
                } else {
                    for (entry in list) {
                        shortcutsList.addView(createShortcutView(entry))
                    }
                }
            }
        }
    }

    private fun createShortcutView(entry: TextExpansionEntity): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 20)
            setBackgroundResource(R.drawable.settings_card_bg)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, 16)
            layoutParams = lp
            
            // Add internal padding to make it a card
            setPadding(30, 30, 30, 30)

            setOnClickListener { showEditDialog(entry) }
            setOnLongClickListener { 
                showDeleteDialog(entry)
                true
            }
        }

        val tvShortcut = TextView(this).apply {
            text = entry.shortcut
            setTextColor(Color.WHITE)
            textSize = 16f
            paint.isFakeBoldText = true
        }

        val tvExpansion = TextView(this).apply {
            text = entry.expansion
            setTextColor(Color.parseColor("#CCCCDD"))
            textSize = 14f
            setPadding(0, 10, 0, 0)
        }

        container.addView(tvShortcut)
        container.addView(tvExpansion)
        return container
    }

    private fun showEditDialog(existing: TextExpansionEntity?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 0)
        }

        val etShortcut = EditText(this).apply {
            hint = "Shortcut (e.g. omw)"
            setText(existing?.shortcut)
        }

        val etExpansion = EditText(this).apply {
            hint = "Expansion (e.g. On my way!)"
            setText(existing?.expansion)
        }

        layout.addView(etShortcut)
        layout.addView(etExpansion)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Shortcut" else "Edit Shortcut")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val shortcut = etShortcut.text.toString().trim().lowercase()
                val expansion = etExpansion.text.toString().trim()
                if (shortcut.isNotEmpty() && expansion.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.textExpansionDao().upsert(TextExpansionEntity(shortcut, expansion))
                        loadShortcuts()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog(entry: TextExpansionEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Shortcut")
            .setMessage("Delete '${entry.shortcut}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.textExpansionDao().delete(entry.shortcut)
                    loadShortcuts()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
