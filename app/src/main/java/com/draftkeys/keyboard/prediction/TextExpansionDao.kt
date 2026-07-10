package com.draftkeys.keyboard.prediction

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * D1 — DAO for [TextExpansionEntity].
 *
 * Used by [PredictionEngine.getSuggestions] to offer user-defined expansions
 * (e.g. "omw" → "On my way!") as the top suggestion when the prefix matches exactly.
 *
 * Also used by SettingsActivity > Text Shortcuts screen to list, add, and delete shortcuts.
 */
@Dao
interface TextExpansionDao {

    /** Fetch the expansion for a specific shortcut, or null if none exists. */
    @Query("SELECT * FROM text_expansions WHERE shortcut = :shortcut LIMIT 1")
    suspend fun find(shortcut: String): TextExpansionEntity?

    /** Insert or replace (allows editing an existing shortcut). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TextExpansionEntity)

    /** Delete a specific shortcut. */
    @Query("DELETE FROM text_expansions WHERE shortcut = :shortcut")
    suspend fun delete(shortcut: String)

    /** Fetch all shortcuts sorted alphabetically — for the Settings list. */
    @Query("SELECT * FROM text_expansions ORDER BY shortcut ASC")
    suspend fun getAll(): List<TextExpansionEntity>

    /** Clear all shortcuts. */
    @Query("DELETE FROM text_expansions")
    suspend fun clearAll()
}
