package com.draftkeys.keyboard.prediction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * B2 — In-memory mirror of the personal_words table.
 *
 * Eliminates the per-keystroke SQLite round-trip that happened every time
 * getSuggestions() or isKnownWord() was called.
 *
 * Lifecycle:
 *  - Call [prime] once on startup (background thread) to load the initial snapshot.
 *  - Call [learnAsync] on every word commit — it updates the DB and refreshes the cache.
 *  - All read methods ([getAll], [getFrequency], [contains]) are O(1) / O(N-personal)
 *    and never touch SQLite.
 */
class PersonalWordCache(
    private val dao: PersonalWordDao,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()

    /** Full snapshot, sorted by frequency descending. */
    private var words: List<PersonalWordEntity> = emptyList()

    /** Fast O(1) membership check. */
    private var wordSet: HashSet<String> = hashSetOf()

    // ── Initialisation ────────────────────────────────────────────────────────

    /** Load the full personal-words table into memory. Call once on startup. */
    suspend fun prime() {
        val loaded = dao.getAll()
        mutex.withLock {
            words = loaded
            wordSet = loaded.mapTo(HashSet()) { it.word }
        }
    }

    // ── Read accessors (no SQLite, always fast) ───────────────────────────────

    suspend fun getAll(): List<PersonalWordEntity> = mutex.withLock { words }

    suspend fun getFrequency(word: String): Int =
        mutex.withLock { words.firstOrNull { it.word == word }?.frequency ?: 0 }

    fun contains(word: String): Boolean = wordSet.contains(word)

    // ── Write (async — fires-and-forgets to DB, then refreshes cache) ─────────

    suspend fun learn(word: String) {
        dao.learn(word)
        refresh()
    }

    fun learnAsync(word: String) {
        scope.launch(Dispatchers.IO) {
            learn(word)
        }
    }

    fun learnBigramAsync(word1: String, word2: String, bigramDao: BigramDao) {
        scope.launch(Dispatchers.IO) {
            bigramDao.learn(word1, word2)
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun refresh() {
        val updated = dao.getAll()
        mutex.withLock {
            words = updated
            wordSet = updated.mapTo(HashSet()) { it.word }
        }
    }
}
