package com.draftkeys.keyboard

import android.app.Application
import com.draftkeys.keyboard.data.DraftDatabase
import com.draftkeys.keyboard.gesture.WordDictionary
import com.draftkeys.keyboard.prediction.StaticBigrams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DraftKeysApplication — Application-level singleton for expensive, long-lived resources.
 *
 * ## Problem Solved
 * Previously, [WordDictionary] (100K words, 4 data structures) and [StaticBigrams] (3MB Norvig
 * corpus) were instantiated fresh inside [KeyboardService.onCreate], meaning every time the
 * keyboard was toggled the entire dictionary was re-parsed from disk. This caused:
 *  - 200-400ms startup lag on first keypress
 *  - ~25MB RAM spike on each keyboard open, with GC pressure on toggle cycles
 *
 * ## Solution
 * By hosting [wordDictionary] and [staticBigrams] here, they are loaded once for the lifetime
 * of the process and shared across all [KeyboardService] instances. [DraftDatabase] was already
 * a Singleton; this ensures the same pattern for all heavy resources.
 *
 * ## Lifecycle
 *  - [appScope] uses [SupervisorJob] — child failures don't propagate or cancel siblings.
 *  - The scope is never cancelled — it lives as long as the OS process (correct for app-level).
 */
class DraftKeysApplication : Application() {

    /** Application-wide coroutine scope for startup tasks. */
    val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Shared dictionary — loaded once, reused by every KeyboardService instance. */
    lateinit var wordDictionary: WordDictionary
        private set

    /** Shared static bigrams corpus — loaded once on app start. */
    lateinit var staticBigrams: StaticBigrams
        private set

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this

        // Initialise heavy resources once at process start
        wordDictionary = WordDictionary(this)
        staticBigrams  = StaticBigrams(this)

        appScope.launch {
            val dictJob  = launch { wordDictionary.load() }
            val bigramJob = launch { staticBigrams.load() }
            dictJob.join()
            launch { wordDictionary.buildIndex() }
            bigramJob.join()
        }

        // Ensure the DB singleton is primed — triggers Room's deferred init
        DraftDatabase.getInstance(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        staticBigrams.close()
    }

    companion object {
        @Volatile
        private var INSTANCE: DraftKeysApplication? = null

        /** Access the application singleton from any context. */
        fun get(): DraftKeysApplication =
            checkNotNull(INSTANCE) { "DraftKeysApplication not initialised" }
    }
}
