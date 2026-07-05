package com.draftkeys.keyboard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DraftDao (Data Access Object) defines all the SQL operations we can do.
 *
 * We never write raw SQL in our app code — we write it here as annotations,
 * and Room generates the actual implementation at compile time.
 *
 * All functions are "suspend" = they run on background threads (coroutines).
 */
@Dao
interface DraftDao {

    /**
     * Saves a draft to the database.
     * If a draft with the same ID exists, it gets replaced.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraft(draft: DraftEntity)

    /**
     * Gets the most recently saved draft for a specific app.
     *
     * ORDER BY timestamp DESC → most recent first
     * LIMIT 1                 → only get the single latest draft
     *
     * Returns null if no draft exists for that app.
     */
    @Query("SELECT * FROM drafts WHERE appPackageName = :packageName ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestDraft(packageName: String): DraftEntity?

    /**
     * Gets all saved drafts across all apps, newest first.
     * Used in the draft history screen (Phase 2).
     */
    @Query("SELECT * FROM drafts ORDER BY timestamp DESC")
    suspend fun getAllDrafts(): List<DraftEntity>

    /**
     * Gets all drafts for a specific app.
     */
    @Query("SELECT * FROM drafts WHERE appPackageName = :packageName ORDER BY timestamp DESC")
    suspend fun getDraftsForApp(packageName: String): List<DraftEntity>

    /**
     * Deletes all drafts for a specific app.
     * Useful for a "Clear WhatsApp drafts" option.
     */
    @Query("DELETE FROM drafts WHERE appPackageName = :packageName")
    suspend fun clearDraftsForApp(packageName: String)

    /**
     * Deletes all saved drafts from the database completely.
     */
    @Query("DELETE FROM drafts")
    suspend fun clearAllDrafts()

    /**
     * Deletes a specific draft by its ID.
     */
    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteDraftById(id: Int)
}
