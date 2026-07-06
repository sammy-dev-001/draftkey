package com.draftkeys.keyboard.clipboard

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for [ClipEntry] — clipboard history persistence.
 */
@Dao
interface ClipDao {

    /** Insert a new clip. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ClipEntry)

    /**
     * Returns true if an identical text was already saved within the last [withinMs] ms.
     * Used to prevent duplicates when the same content is copied twice or when our own
     * paste action re-triggers the clipboard listener.
     */
    @Query("""
        SELECT COUNT(*) FROM clipboard_history
        WHERE text = :text
          AND timestamp >= :afterTimestamp
    """)
    suspend fun countRecentWithText(text: String, afterTimestamp: Long): Int

    /**
     * Returns up to [ClipEntry.MAX_ENTRIES] recent clips, pinned entries first.
     * Within each group, newest first.
     */
    @Query("""
        SELECT * FROM clipboard_history
        ORDER BY isPinned DESC, timestamp DESC
        LIMIT ${ClipEntry.MAX_ENTRIES}
    """)
    suspend fun getRecent(): List<ClipEntry>

    /** Toggle pin status of a clip. */
    @Query("UPDATE clipboard_history SET isPinned = NOT isPinned WHERE id = :id")
    suspend fun togglePin(id: Int)

    /** Delete a single clip by ID. */
    @Query("DELETE FROM clipboard_history WHERE id = :id")
    suspend fun delete(id: Int)

    /** Get a single clip by ID (useful for file cleanup before deletion). */
    @Query("SELECT * FROM clipboard_history WHERE id = :id")
    suspend fun getById(id: Int): ClipEntry?

    /** Get entries that are about to be pruned, so we can delete their files. */
    @Query("""
        SELECT * FROM clipboard_history
        WHERE isPinned = 0
          AND id NOT IN (
              SELECT id FROM clipboard_history
              WHERE isPinned = 0
              ORDER BY timestamp DESC
              LIMIT ${ClipEntry.MAX_ENTRIES}
          )
    """)
    suspend fun getOldEntriesToPrune(): List<ClipEntry>

    /**
     * Prune the oldest non-pinned entries so we never exceed [ClipEntry.MAX_ENTRIES].
     * Keeps the newest [ClipEntry.MAX_ENTRIES] non-pinned rows, deletes the rest.
     */
    @Query("""
        DELETE FROM clipboard_history
        WHERE isPinned = 0
          AND id NOT IN (
              SELECT id FROM clipboard_history
              WHERE isPinned = 0
              ORDER BY timestamp DESC
              LIMIT ${ClipEntry.MAX_ENTRIES}
          )
    """)
    suspend fun pruneOldEntries()

    /** Clear all non-pinned clips. */
    @Query("DELETE FROM clipboard_history WHERE isPinned = 0")
    suspend fun clearUnpinned()

    /** Get all unpinned clips. */
    @Query("SELECT * FROM clipboard_history WHERE isPinned = 0")
    suspend fun getAllUnpinned(): List<ClipEntry>

    /** Get all clips. */
    @Query("SELECT * FROM clipboard_history")
    suspend fun getAll(): List<ClipEntry>

    /** Clear all clips including pinned. */
    @Query("DELETE FROM clipboard_history")
    suspend fun clearAll()
}
