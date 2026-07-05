package com.draftkeys.keyboard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * DraftEntity is the data model for a saved draft.
 *
 * Room will create a SQLite table called "drafts" with these columns.
 * Each row = one saved draft.
 *
 * @param id             Auto-generated unique ID (Room handles this)
 * @param appPackageName The package ID of the app where text was typed
 *                       e.g. "com.whatsapp", "com.instagram.android"
 * @param textContent    The actual typed text we saved
 * @param timestamp      Unix timestamp (milliseconds) of when it was saved
 */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val appPackageName: String,
    val textContent: String,
    val timestamp: Long
)
