package com.draftkeys.keyboard.data

/**
 * DraftRepository acts as the single source of truth for draft data.
 *
 * It sits between KeyboardService (which needs to save/load drafts)
 * and DraftDao (which talks directly to the database).
 *
 * This separation means if we ever change the database or add a cache,
 * we only change this file — the rest of the app stays the same.
 */
class DraftRepository(private val dao: DraftDao) {

    /** Saves a draft. If text is empty, does nothing. */
    suspend fun saveDraft(draft: DraftEntity) {
        if (draft.textContent.isNotBlank()) {
            dao.insertDraft(draft)
        }
    }

    /** Returns the most recent draft for the given app, or null if none. */
    suspend fun getLatestDraft(packageName: String): DraftEntity? {
        return dao.getLatestDraft(packageName)
    }

    /** Returns all drafts across all apps (newest first). */
    suspend fun getAllDrafts(): List<DraftEntity> {
        return dao.getAllDrafts()
    }

    /** Returns all drafts for one specific app. */
    suspend fun getDraftsForApp(packageName: String): List<DraftEntity> {
        return dao.getDraftsForApp(packageName)
    }

    /** Clears all drafts for a specific app. */
    suspend fun clearDraftsForApp(packageName: String) {
        dao.clearDraftsForApp(packageName)
    }

    /** Clears ALL drafts from the database. */
    suspend fun clearAllDrafts() {
        dao.clearAllDrafts()
    }
}
