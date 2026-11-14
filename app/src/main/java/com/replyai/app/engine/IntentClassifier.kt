package com.replyai.app.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.replyai.app.bots.Bot
import java.io.File

/**
 * IntentClassifier — picks the best [RetrievalMode] for one incoming message.
 *
 * The four modes are documented in ARCHITECTURE.md section 8:
 *
 *   DIRECT      — greetings, small talk, off-topic. No retrieval needed.
 *   FTS5        — keyword search over uploaded text (no embeddings).
 *   VECTOR      — semantic search over chunk embeddings (best quality).
 *   WEB_SEARCH  — live site-scoped Google search for dynamic info.
 *
 * Selection order (see classify() body):
 *
 *   1. Greeting?                                  → DIRECT
 *   2. Asks about dynamic info + has companyUrl?  → WEB_SEARCH
 *   3. Has knowledge files WITH embeddings?       → VECTOR
 *   4. Has knowledge files (FTS5 only)?           → FTS5
 *   5. Has companyUrl?                            → WEB_SEARCH
 *   6. Otherwise                                  → DIRECT
 *
 * The "has knowledge files?" check reads the `knowledge_files` and `chunks`
 * tables of `replyai.db` directly (read-only). Those tables are owned by
 * the parallel KnowledgeManager agent (BUILD_PLAN.md phase 2); if they
 * don't exist yet (cold start, no uploads), every query fails gracefully
 * and we fall through to step 5/6.
 *
 * Pure logic except for the table-existence check; safe to unit-test the
 * greeting + dynamic-keyword logic in isolation on JVM.
 */
object IntentClassifier {

    enum class RetrievalMode { DIRECT, FTS5, VECTOR, WEB_SEARCH }

    // ------------------------------------------------------------------
    // Greeting detection
    // ------------------------------------------------------------------

    /** Single-token greetings/acknowledgements. Lowercased, word-bounded. */
    private val SHORT_GREETING_REGEX = Regex(
        """\b(hi|hello|hey|thanks|thank you|bye|ok|okay|yes|yeah|no|nope)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Multi-word greeting phrases that may start a longer message. */
    private val GREETING_PREFIXES = listOf(
        "good morning",
        "good afternoon",
        "good evening",
        "good night",
        "howdy",
        "what's up",
        "whats up",
        "yo ",
        "hi ",
        "hello ",
        "hey ",
        "thanks ",
        "thank you ",
    )

    /**
     * True if [msg] looks like a greeting or short acknowledgement.
     *
     * Two paths:
     *   - If [msg] is < 20 chars: match the whole message against
     *     [SHORT_GREETING_REGEX] (e.g., "hi", "ok", "thanks!").
     *   - If [msg] is >= 20 chars: return true only if it starts with one
     *     of the [GREETING_PREFIXES] AND stays under ~30 chars total
     *     (so "good morning" qualifies but "good morning, can I get a quote
     *     for 5 widgets at the bulk rate" does NOT — that's a real request).
     */
    fun isGreeting(msg: String): Boolean {
        val trimmed = msg.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()

        if (trimmed.length < 20) {
            // Strip trailing punctuation so "hi!" / "thanks." match.
            val cleaned = lower.trimEnd { it == '!' || it == '.' || it == '?' || it == ',' }
            return SHORT_GREETING_REGEX.matches(cleaned) ||
                GREETING_PREFIXES.any { cleaned == it.trim() }
        }
        // Longer message: must START with a greeting phrase AND be short.
        if (trimmed.length > 30) return false
        return GREETING_PREFIXES.any { lower.startsWith(it) }
    }

    // ------------------------------------------------------------------
    // Dynamic-info detection
    // ------------------------------------------------------------------

    /** Keywords that signal "I need fresh info, not the static knowledge base". */
    private val DYNAMIC_KEYWORDS = listOf(
        "today", "now", "price", "cost", "available",
        "in stock", "hours", "open", "current",
    )

    fun asksAboutDynamicInfo(message: String): Boolean {
        val lower = message.lowercase()
        return DYNAMIC_KEYWORDS.any { lower.contains(it) }
    }

    // ------------------------------------------------------------------
    // Main entry point
    // ------------------------------------------------------------------

    /**
     * Decide how to ground the reply for [message].
     *
     * `context` is required for the DB lookups (knowledge_files / chunks).
     * If context is null we skip the knowledge checks entirely and fall
     * through to the companyUrl/DIRECT branch — useful for unit tests.
     */
    suspend fun classify(message: String, bot: Bot, context: Context? = null): RetrievalMode {
        // 1. Greeting? → DIRECT
        if (isGreeting(message)) return RetrievalMode.DIRECT

        // 2. Dynamic info + has companyUrl → WEB_SEARCH
        if (bot.companyUrl != null && asksAboutDynamicInfo(message)) {
            return RetrievalMode.WEB_SEARCH
        }

        // 3. Knowledge files with embeddings → VECTOR
        if (context != null && hasEmbeddings(context, bot.id)) {
            return RetrievalMode.VECTOR
        }

        // 4. Knowledge files (FTS5 only) → FTS5
        if (context != null && hasKnowledgeFiles(context, bot.id)) {
            return RetrievalMode.FTS5
        }

        // 5. Has companyUrl but no knowledge → WEB_SEARCH
        if (bot.companyUrl != null) return RetrievalMode.WEB_SEARCH

        // 6. Nothing configured → DIRECT
        return RetrievalMode.DIRECT
    }

    // ------------------------------------------------------------------
    // DB checks (defensive: any failure → false)
    // ------------------------------------------------------------------

    private fun dbFile(context: Context): File? {
        val f = context.getDatabasePath("replyai.db")
        return if (f != null && f.exists()) f else null
    }

    private fun hasKnowledgeFiles(context: Context, botId: Long): Boolean {
        val f = dbFile(context) ?: return false
        var db: SQLiteDatabase? = null
        var cursor: android.database.Cursor? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                f.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
            cursor = db.rawQuery(
                "SELECT COUNT(*) FROM knowledge_files WHERE bot_id = ?",
                arrayOf(botId.toString()),
            )
            cursor.moveToFirst() && cursor.getInt(0) > 0
        } catch (_: Exception) {
            false
        } finally {
            cursor?.close()
            db?.close()
        }
    }

    private fun hasEmbeddings(context: Context, botId: Long): Boolean {
        val f = dbFile(context) ?: return false
        var db: SQLiteDatabase? = null
        var cursor: android.database.Cursor? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                f.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
            cursor = db.rawQuery(
                """
                SELECT COUNT(*) FROM chunks c
                INNER JOIN knowledge_files kf ON kf.id = c.file_id
                WHERE kf.bot_id = ? AND c.embedding IS NOT NULL AND length(c.embedding) > 0
                """.trimIndent(),
                arrayOf(botId.toString()),
            )
            cursor.moveToFirst() && cursor.getInt(0) > 0
        } catch (_: Exception) {
            false
        } finally {
            cursor?.close()
            db?.close()
        }
    }
}
