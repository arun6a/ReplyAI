package com.replyai.app.data

import android.content.Context

/**
 * ReplyAIDatabase — singleton SQLite database for ReplyAI.
 *
 * Schema per ARCHITECTURE.md section 7:
 *   - bots (configured auto-reply rules)
 *   - knowledge_files (uploaded documents)
 *   - chunks (text + embedding BLOB)
 *   - chunks_fts (FTS5 virtual table for keyword search)
 *   - conversations (per-contact message history)
 *   - messages (individual messages in conversations)
 *   - action_log (for dashboard + feedback)
 *   - daily_costs (per-bot daily cost tracking)
 *
 * Phase 1 of BUILD_PLAN.md.
 * Currently a placeholder; full schema will be implemented in Phase 1.
 */
object ReplyAIDatabase {
    private const val DB_NAME = "replyai.db"
    private const val DB_VERSION = 1

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        // TODO Phase 1: SQLiteOpenHelper with full schema
    }
}
