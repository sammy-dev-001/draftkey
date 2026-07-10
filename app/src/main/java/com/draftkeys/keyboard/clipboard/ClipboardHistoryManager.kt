package com.draftkeys.keyboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * ClipboardHistoryManager — listens for clipboard changes and persists them to Room.
 *
 * Improvements over v1:
 *
 * **Deduplication**: Skips insert if the same text was already saved within the last
 * 60 seconds. This prevents double-entries when (a) the same text is copied twice, or
 * (b) the keyboard's own "paste" action re-triggers the listener.
 *
 * **Startup capture**: Call [captureCurrentClipboard] from `onStartInputView` to grab
 * anything the user copied while the keyboard was dismissed (Android 10+ only fires the
 * listener while the IME is active, so copies made outside the keyboard are otherwise lost).
 *
 * **Own-paste filter**: Call [setLastPasted] just before committing text so the listener
 * knows to ignore the next clipboard event caused by our own paste.
 */
class ClipboardHistoryManager(
    private val context: Context,
    private val dao: ClipDao,
    private val scope: CoroutineScope
) {
    private val systemClipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Text that we just pasted ourselves — suppress the echo clip event for it. */
    @Volatile private var lastPastedText: String? = null

    /** Deduplication window: if same text saved within this many ms, skip. */
    private val DEDUP_WINDOW_MS = 60_000L

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = systemClipboard.primaryClip ?: return@OnPrimaryClipChangedListener

        // 🔒 Security fix: never log passwords or 2FA codes.
        // Password managers (1Password, Bitwarden, Samsung Pass) mark sensitive
        // clipboard data using EXTRA_IS_SENSITIVE on Android 13+ (API 33).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) {
                return@OnPrimaryClipChangedListener
            }
        }

        if (isImageClip(clip)) {
            val item = clip.getItemAt(0)
            val uri = item.uri ?: run {
                val text = item.text?.toString() ?: item.coerceToText(context)?.toString()
                if (text != null && text.startsWith("content://")) Uri.parse(text) else null
            }
            if (uri != null) saveImageIfNew(uri)
            return@OnPrimaryClipChangedListener
        }

        val text = clip.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return@OnPrimaryClipChangedListener

        if (isMediaUri(text)) {
            val uri = Uri.parse(text)
            saveImageIfNew(uri)
            return@OnPrimaryClipChangedListener
        }

        // Ignore our own paste echoes
        if (text == lastPastedText) {
            lastPastedText = null
            return@OnPrimaryClipChangedListener
        }

        saveIfNew(text)
    }

    fun register() {
        systemClipboard.addPrimaryClipChangedListener(listener)
    }

    fun unregister() {
        systemClipboard.removePrimaryClipChangedListener(listener)
    }

    /**
     * Call this from [KeyboardService.onStartInputView] to capture any clipboard content
     * copied while the keyboard was not active (Android 10+ security limitation).
     */
    fun captureCurrentClipboard() {
        val clip = try { systemClipboard.primaryClip } catch (_: Exception) { null } ?: return

        // 🔒 Security fix: same sensitive-data guard as the live listener.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
        }

        if (isImageClip(clip)) {
            val item = clip.getItemAt(0)
            val uri = item.uri ?: run {
                val text = try { item.text?.toString() ?: item.coerceToText(context)?.toString() } catch (_: Exception) { null }
                if (text != null && text.startsWith("content://")) Uri.parse(text) else null
            }
            if (uri != null) saveImageIfNew(uri)
            return
        }

        val text = try {
            clip.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null } ?: return

        if (isMediaUri(text)) {
            val uri = Uri.parse(text)
            saveImageIfNew(uri)
            return
        }

        saveIfNew(text)
    }

    /**
     * Notify the manager of text we're about to paste so it can suppress the
     * echo clipboard event that Android fires when we call commitText on a paste.
     */
    fun setLastPasted(text: String) {
        lastPastedText = text
    }

    /** Returns recent clips (pinned first, then newest-first, max 30). */
    suspend fun getRecentClips(): List<ClipEntry> = dao.getRecent()

    suspend fun togglePin(id: Int)  = dao.togglePin(id)
    
    suspend fun deleteClip(id: Int) {
        val entry = dao.getById(id)
        if (entry?.isImage == true) {
            File(entry.text).delete()
        }
        dao.delete(id)
    }
    
    suspend fun clearAll() {
        val all = dao.getAll()
        all.forEach { if (it.isImage) File(it.text).delete() }
        dao.clearAll()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    // ── Image / media clip detection ─────────────────────────────────────────

    /** Returns true when the clip contains an image MIME type (e.g. screenshots). */
    private fun isImageClip(clip: ClipData): Boolean {
        val desc = clip.description
        for (i in 0 until desc.mimeTypeCount) {
            val mime = desc.getMimeType(i)
            if (mime.startsWith("image/")) return true
        }
        return false
    }

    /**
     * Returns true when [text] looks like a raw `content://media/…` URI —
     * the form that [android.content.ClipData.Item.coerceToText] produces
     * when it cannot read a URI as text (common on older Android versions).
     */
    private fun isMediaUri(text: String): Boolean =
        text.startsWith("content://") && !text.contains(' ')

    private val processedImageUris = mutableSetOf<String>()

    private fun saveImageIfNew(uri: Uri) {
        val uriStr = uri.toString()
        if (processedImageUris.contains(uriStr)) return
        
        scope.launch(Dispatchers.IO) {
            try {
                processedImageUris.add(uriStr)
                if (processedImageUris.size > 20) {
                    val iterator = processedImageUris.iterator()
                    iterator.next()
                    iterator.remove()
                }
                
                val clipsDir = File(context.filesDir, "clips")
                if (!clipsDir.exists()) clipsDir.mkdirs()
                val targetFile = File(clipsDir, "clip_${System.currentTimeMillis()}.png")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                dao.insert(ClipEntry(text = targetFile.absolutePath, timestamp = System.currentTimeMillis(), isImage = true))
                pruneOldEntriesWithFiles()
            } catch (e: Exception) {
                processedImageUris.remove(uriStr)
                e.printStackTrace()
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Failed to save clipboard image: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveIfNew(text: String) {
        scope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - DEDUP_WINDOW_MS
            val count  = dao.countRecentWithText(text, cutoff)
            if (count == 0) {
                dao.insert(ClipEntry(text = text, timestamp = System.currentTimeMillis()))
                pruneOldEntriesWithFiles()
            }
        }
    }
    
    private suspend fun pruneOldEntriesWithFiles() {
        val toDelete = dao.getOldEntriesToPrune()
        for (entry in toDelete) {
            if (entry.isImage) {
                File(entry.text).delete()
            }
        }
        dao.pruneOldEntries()
    }
}
