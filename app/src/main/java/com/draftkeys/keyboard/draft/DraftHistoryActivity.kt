package com.draftkeys.keyboard.draft

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.draftkeys.keyboard.ui.ThemeManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import android.os.Build
import com.draftkeys.keyboard.R
import com.draftkeys.keyboard.data.DraftDatabase
import com.draftkeys.keyboard.data.DraftEntity

class DraftHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DraftAdapter
    private lateinit var themeManager: ThemeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_draft_history)
        
        themeManager = ThemeManager(this)
        val theme = themeManager.getTheme()
        findViewById<View>(android.R.id.content).setBackgroundColor(theme.bg)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setBackgroundColor(theme.keyNormal)
        toolbar.setTitleTextColor(theme.keyText)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Draft History"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = DraftAdapter(this, themeManager.getTheme()) { draft ->
            deleteDraft(draft)
        }
        recyclerView.adapter = adapter

        loadDrafts()
    }

    private fun loadDrafts() {
        findViewById<View>(R.id.btnDeleteAll).setOnClickListener {
            val db = DraftDatabase.getInstance(this@DraftHistoryActivity)
            lifecycleScope.launch(Dispatchers.IO) {
                val hasDrafts = db.draftDao().getAllDrafts().isNotEmpty()
                if (!hasDrafts) return@launch

                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@DraftHistoryActivity)
                        .setTitle("Delete All Drafts")
                        .setMessage("Are you sure you want to permanently delete all saved drafts?")
                        .setPositiveButton("Delete") { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val dbInner = DraftDatabase.getInstance(this@DraftHistoryActivity)
                                dbInner.draftDao().clearAllDrafts()
                                loadDrafts()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
        lifecycleScope.launch {
            val db = DraftDatabase.getInstance(this@DraftHistoryActivity)
            val drafts = db.draftDao().getAllDrafts()
            adapter.submitList(drafts)
        }
    }

    private fun deleteDraft(draft: DraftEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Draft")
            .setMessage("Are you sure you want to delete this draft?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val db = DraftDatabase.getInstance(this@DraftHistoryActivity)
                    db.draftDao().deleteDraftById(draft.id)
                    loadDrafts()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class DraftAdapter(
    private val context: Context,
    private val palette: ThemeManager.ThemePalette,
    private val onDelete: (DraftEntity) -> Unit
) : ListAdapter<DraftEntity, DraftAdapter.ViewHolder>(DraftDiffCallback()) {

    private val pm: PackageManager = context.packageManager

    class DraftDiffCallback : DiffUtil.ItemCallback<DraftEntity>() {
        override fun areItemsTheSame(oldItem: DraftEntity, newItem: DraftEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DraftEntity, newItem: DraftEntity): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_draft, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val draft = getItem(position)
        
        holder.itemView.setBackgroundColor(palette.keyModifier)
        
        holder.tvDraftText.text = draft.textContent
        holder.tvDraftText.setTextColor(palette.keyText)
        
        val timeString = DateUtils.getRelativeTimeSpanString(
            draft.timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )
        holder.tvTime.text = timeString

        try {
            val appInfo = pm.getApplicationInfo(draft.appPackageName, 0)
            holder.tvAppName.text = pm.getApplicationLabel(appInfo)
            holder.tvAppName.setTextColor(palette.keyText)
            holder.ivAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
        } catch (e: PackageManager.NameNotFoundException) {
            holder.tvAppName.text = draft.appPackageName
            holder.tvAppName.setTextColor(palette.keyText)
            holder.ivAppIcon.setImageDrawable(null)
        }

        holder.itemView.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Draft", draft.textContent)
            clipboard.setPrimaryClip(clip)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        holder.itemView.setOnLongClickListener {
            onDelete(draft)
            true
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAppIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvDraftText: TextView = view.findViewById(R.id.tvDraftText)
    }
}
