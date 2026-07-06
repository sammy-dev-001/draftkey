package com.draftkeys.keyboard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.draftkeys.keyboard.clipboard.ClipDao
import com.draftkeys.keyboard.clipboard.ClipEntry
import com.draftkeys.keyboard.prediction.BigramDao
import com.draftkeys.keyboard.prediction.BigramEntity
import com.draftkeys.keyboard.prediction.PersonalWordDao
import com.draftkeys.keyboard.prediction.PersonalWordEntity

/**
 * DraftDatabase — the single Room database for DraftKeys.
 *
 * Schema version history:
 *  v1 — Initial: `drafts` table (DraftEntity)
 *  v2 — Added `clipboard_history` (ClipEntry) + `personal_words` (PersonalWordEntity)
 *  v3 — Added `bigrams` (BigramEntity)
 *  v4 — Added `isImage` to `clipboard_history`
 *
 * The Singleton pattern ensures only one database connection is open at a time.
 */
@Database(
    entities = [
        DraftEntity::class,
        ClipEntry::class,
        PersonalWordEntity::class,
        BigramEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class DraftDatabase : RoomDatabase() {

    abstract fun draftDao(): DraftDao
    abstract fun clipDao(): ClipDao
    abstract fun personalWordDao(): PersonalWordDao
    abstract fun bigramDao(): BigramDao

    companion object {
        @Volatile
        private var INSTANCE: DraftDatabase? = null

        /**
         * Migration from v1 → v2: add clipboard_history and personal_words tables.
         * Users upgrading from the original DraftKeys build keep all their drafts.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS clipboard_history (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        text      TEXT    NOT NULL,
                        timestamp INTEGER NOT NULL,
                        isPinned  INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS personal_words (
                        word      TEXT    PRIMARY KEY NOT NULL,
                        frequency INTEGER NOT NULL DEFAULT 1,
                        lastUsed  INTEGER NOT NULL
                    )"""
                )
            }
        }

        /**
         * Migration from v2 → v3: add bigrams table for next-word prediction.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS bigrams (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        word1     TEXT    NOT NULL,
                        word2     TEXT    NOT NULL,
                        frequency INTEGER NOT NULL DEFAULT 1,
                        lastUsed  INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bigrams_word1_word2 ON bigrams (word1, word2)")
            }
        }

        /**
         * Migration from v3 → v4: add isImage to clipboard_history.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN isImage INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): DraftDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DraftDatabase::class.java,
                    "draft_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
