package com.draftkeys.keyboard.prediction

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for [PersonalWordEntity] — the user's personal learned vocabulary.
 */
@Dao
interface PersonalWordDao {

    /**
     * Insert or replace a word. Called when a word is initially learned.
     * For incrementing an existing word's frequency, use [incrementFrequency].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(word: PersonalWordEntity)

    /** Increment the frequency of an existing word, or insert it if absent. */
    @Query("""
        INSERT INTO personal_words (word, frequency, lastUsed)
        VALUES (:word, 1, :now)
        ON CONFLICT(word) DO UPDATE SET
            frequency = frequency + 1,
            lastUsed  = :now
    """)
    suspend fun learn(word: String, now: Long = System.currentTimeMillis())

    /** Returns all personal words sorted by frequency descending. */
    @Query("SELECT * FROM personal_words ORDER BY frequency DESC")
    suspend fun getAll(): List<PersonalWordEntity>

    /** Returns the frequency for a single word, or 0 if not found. */
    @Query("SELECT COALESCE((SELECT frequency FROM personal_words WHERE word = :word), 0)")
    suspend fun getFrequency(word: String): Int

    /** Bulk-fetch frequencies for a list of candidate words (used during gesture scoring). */
    @Query("SELECT * FROM personal_words WHERE word IN (:words)")
    suspend fun getFrequenciesFor(words: List<String>): List<PersonalWordEntity>

    /** Clear all learned words. */
    @Query("DELETE FROM personal_words")
    suspend fun clearAll()
}
