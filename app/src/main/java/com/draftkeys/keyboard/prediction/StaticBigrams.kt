package com.draftkeys.keyboard.prediction

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Provides access to the pre-packaged massive bigram database generated from the Norvig corpus.
 * Uses raw SQLite to avoid fighting with Room schema migrations.
 */
class StaticBigrams(private val context: Context) {

    private var db: SQLiteDatabase? = null
    private var isLoaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext
        try {
            val dbFile = context.getDatabasePath("norvig_bigrams.db")
            if (!dbFile.exists()) {
                dbFile.parentFile?.mkdirs()
                context.assets.open("bigrams.db").use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            isLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
            // If the asset is missing, it will gracefully fallback to an empty provider.
        }
    }

    suspend fun getPredictions(word1: String, limit: Int = 3): List<String> = withContext(Dispatchers.Default) {
        if (!isLoaded) return@withContext emptyList()
        val database = db ?: return@withContext emptyList()
        val results = mutableListOf<String>()
        try {
            val cursor = database.rawQuery(
                "SELECT word2 FROM bigrams WHERE word1 = ? ORDER BY frequency DESC LIMIT ?",
                arrayOf(word1.lowercase(), limit.toString())
            )
            while (cursor.moveToNext()) {
                results.add(cursor.getString(0))
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext results
    }

    fun close() {
        db?.close()
        db = null
        isLoaded = false
    }
}
