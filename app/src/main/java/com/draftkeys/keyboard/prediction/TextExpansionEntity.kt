package com.draftkeys.keyboard.prediction

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * D1 — User-defined text expansion shortcut.
 *
 * Examples:
 *   "omw"  → "On my way!"
 *   "addr" → "123 Main Street, Lagos, Nigeria"
 *   "sig"  → "Best regards,\nSammy"
 *
 * Stored in Room. Managed via Settings > Text Shortcuts screen.
 */
@Entity(tableName = "text_expansions")
data class TextExpansionEntity(
    @PrimaryKey
    val shortcut: String,           // e.g. "omw" — lowercase, no spaces
    val expansion: String,          // e.g. "On my way!" — can be multi-line
    val createdAt: Long = System.currentTimeMillis()
)
