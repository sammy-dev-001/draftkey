package com.draftkeys.keyboard.prediction

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A word that the user has personally typed, stored with a usage frequency count.
 *
 * The gesture decoder and prediction engine give [PersonalWordEntity] words a scoring
 * bonus proportional to [frequency], so frequently-used words rise to the top over time.
 * This is DraftKeys' "learning" mechanism — no ML required.
 *
 * @param word      The word text (primary key — one row per unique word).
 * @param frequency How many times the user has typed (or accepted) this word.
 * @param lastUsed  Unix timestamp (ms) of the most recent use — used for decay if needed later.
 */
@Entity(tableName = "personal_words")
data class PersonalWordEntity(
    @PrimaryKey
    val word: String,
    val frequency: Int   = 1,
    val lastUsed: Long   = System.currentTimeMillis()
)
