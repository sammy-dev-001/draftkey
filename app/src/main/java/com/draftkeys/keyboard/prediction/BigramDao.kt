package com.draftkeys.keyboard.prediction

import androidx.room.Dao
import androidx.room.Query

/**
 * DAO for [BigramEntity] — tracks N-gram transitions for next-word prediction.
 */
@Dao
interface BigramDao {

    /** 
     * Increment the frequency of an existing bigram transition, or insert it if absent.
     * Uses the composite unique constraint on (word1, word2).
     */
    @Query("""
        INSERT INTO bigrams (word1, word2, frequency, lastUsed)
        VALUES (:word1, :word2, 1, :now)
        ON CONFLICT(word1, word2) DO UPDATE SET
            frequency = frequency + 1,
            lastUsed  = :now
    """)
    suspend fun learn(word1: String, word2: String, now: Long = System.currentTimeMillis())

    /** 
     * Fetch the top N most frequent next words following [word1].
     */
    @Query("SELECT word2 FROM bigrams WHERE word1 = :word1 ORDER BY frequency DESC LIMIT :limit")
    suspend fun getPredictions(word1: String, limit: Int = 3): List<String>

    /** Clear all learned bigrams. */
    @Query("DELETE FROM bigrams")
    suspend fun clearAll()
}
