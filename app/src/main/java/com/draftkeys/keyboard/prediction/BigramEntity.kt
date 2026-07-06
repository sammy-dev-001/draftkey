package com.draftkeys.keyboard.prediction

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * BigramEntity — represents a next-word transition frequency.
 * Used for dynamic N-gram next-word prediction.
 */
@Entity(
    tableName = "bigrams",
    indices = [Index(value = ["word1", "word2"], unique = true)]
)
data class BigramEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val word1: String,
    val word2: String,
    val frequency: Int = 1,
    val lastUsed: Long = System.currentTimeMillis()
)
