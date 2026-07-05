package com.draftkeys.keyboard.clipboard

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single entry in the clipboard history.
 *
 * Stored in Room so history persists across device restarts.
 * Capped at [MAX_ENTRIES] non-pinned entries; pinned entries are never auto-expired.
 *
 * @param id        Auto-generated Row ID.
 * @param text      The clipboard text content.
 * @param timestamp Unix timestamp (ms) when this clip was recorded.
 * @param isPinned  If true, this clip is never auto-deleted.
 */
@Entity(tableName = "clipboard_history")
data class ClipEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    val timestamp: Long,
    val isPinned: Boolean = false
) {
    companion object {
        /** Maximum number of non-pinned entries kept in history. */
        const val MAX_ENTRIES = 30
    }
}
