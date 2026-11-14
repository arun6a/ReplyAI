package com.replyai.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray

/**
 * ReplyAIDatabase — singleton SQLite database for ReplyAI.
 *
 * Schema (ARCHITECTURE.md section 7): bots, knowledge_files, chunks (+ FTS5
 * mirror), conversations, messages, action_log, daily_costs. Foreign-key
 * cascade deletes are enabled so removing a bot cleans up its knowledge files,
 * chunks, conversations and messages automatically.
 */
class ReplyAIDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "ReplyAIDatabase"
        private const val DB_NAME = "replyai.db"
        private const val DB_VERSION = 1
        @Volatile private var instance: ReplyAIDatabase? = null
        private var appContext: Context? = null

        /** Call once from Application.onCreate() before any other access. */
        fun init(context: Context) {
            if (appContext == null) appContext = context.applicationContext
            getInstance()
        }

        /** Lazy singleton accessor. Throws if [init] has not been called. */
        fun getInstance(): ReplyAIDatabase {
            val ctx = appContext ?: error("ReplyAIDatabase.init(context) must be called first")
            return instance ?: synchronized(this) {
                instance ?: ReplyAIDatabase(ctx).also { instance = it }
            }
        }
    }

    data class Bot(
        val id: Long, val name: String, val targetApps: List<String>, val systemPrompt: String,
        val personality: String?, val enabled: Boolean, val confidenceThreshold: Float,
        val quietHoursStart: Int, val quietHoursEnd: Int, val dailyMessageCap: Int,
        val dailyCostCapCents: Int, val companyUrl: String?, val createdAt: Long, val updatedAt: Long,
    )
    data class KnowledgeFile(
        val id: Long, val botId: Long, val fileName: String, val fileSize: Long,
        val sourceType: String, val sourceUrl: String?, val chunkCount: Int, val createdAt: Long,
    )
    data class Chunk(
        val id: Long, val fileId: Long, val chunkIndex: Int, val text: String,
        val embedding: ByteArray?, val embeddingProvider: String?,
    )
    data class Conversation(
        val id: Long, val botId: Long, val contactKey: String, val contactName: String?, val lastMessageAt: Long?,
    )
    data class Message(
        val id: Long, val conversationId: Long, val direction: String, val text: String,
        val knowledgeSource: String?, val toolCalls: String?, val costCents: Int, val timestamp: Long,
    )
    data class ActionLog(
        val id: Long, val botId: Long?, val actionType: String, val app: String?,
        val contact: String?, val summary: String?, val details: String?, val timestamp: Long,
    )
    data class DailyCost(val dateYmd: String, val botId: Long?, val tokensUsed: Int, val estCostCents: Int)

    // === Schema ===

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bots (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, target_apps TEXT NOT NULL, system_prompt TEXT NOT NULL, personality TEXT, enabled INTEGER DEFAULT 1, confidence_threshold REAL DEFAULT 0.7, quiet_hours_start INTEGER DEFAULT 22, quiet_hours_end INTEGER DEFAULT 7, daily_message_cap INTEGER DEFAULT 10, daily_cost_cap_cents INTEGER DEFAULT 200, company_url TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE knowledge_files (id INTEGER PRIMARY KEY AUTOINCREMENT, bot_id INTEGER NOT NULL, file_name TEXT NOT NULL, file_size INTEGER NOT NULL, source_type TEXT NOT NULL, source_url TEXT, chunk_count INTEGER DEFAULT 0, created_at INTEGER NOT NULL, FOREIGN KEY (bot_id) REFERENCES bots(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE chunks (id INTEGER PRIMARY KEY AUTOINCREMENT, file_id INTEGER NOT NULL, chunk_index INTEGER NOT NULL, text TEXT NOT NULL, embedding BLOB, embedding_provider TEXT, FOREIGN KEY (file_id) REFERENCES knowledge_files(id) ON DELETE CASCADE)")
        createChunksFts(db)
        db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, bot_id INTEGER NOT NULL, contact_key TEXT NOT NULL, contact_name TEXT, last_message_at INTEGER, FOREIGN KEY (bot_id) REFERENCES bots(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL, direction TEXT NOT NULL, text TEXT NOT NULL, knowledge_source TEXT, tool_calls TEXT, cost_cents INTEGER DEFAULT 0, timestamp INTEGER NOT NULL, FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE action_log (id INTEGER PRIMARY KEY AUTOINCREMENT, bot_id INTEGER, action_type TEXT NOT NULL, app TEXT, contact TEXT, summary TEXT, details TEXT, timestamp INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE daily_costs (date_ymd TEXT, bot_id INTEGER, tokens_used INTEGER DEFAULT 0, est_cost_cents INTEGER DEFAULT 0, PRIMARY KEY (date_ymd, bot_id))")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.execSQL("PRAGMA foreign_keys = ON;")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only — drop everything and rebuild. No migrations yet.
        db.execSQL("DROP TABLE IF EXISTS chunks_fts")
        db.execSQL("DROP TABLE IF EXISTS chunks")
        db.execSQL("DROP TABLE IF EXISTS knowledge_files")
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS conversations")
        db.execSQL("DROP TABLE IF EXISTS action_log")
        db.execSQL("DROP TABLE IF EXISTS daily_costs")
        db.execSQL("DROP TABLE IF EXISTS bots")
        onCreate(db)
    }

    /** FTS5 external-content table over chunks.text + sync triggers (porter + unicode61). */
    private fun createChunksFts(db: SQLiteDatabase) {
        try {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(text, content='chunks', content_rowid='rowid', tokenize='porter unicode61')")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS chunks_fts_ai AFTER INSERT ON chunks BEGIN INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS chunks_fts_ad AFTER DELETE ON chunks BEGIN INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES('delete', old.id, old.text); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS chunks_fts_au AFTER UPDATE ON chunks BEGIN INSERT INTO chunks_fts(chunks_fts, rowid, text) VALUES('delete', old.id, old.text); INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text); END")
        } catch (e: Exception) {
            Log.w(TAG, "FTS5 chunks table unavailable: ${e.message}")
        }
    }

    // === Bots ===

    fun createBot(bot: Bot): Long =
        writableDatabase.insert("bots", null, botToContentValues(bot, System.currentTimeMillis(), System.currentTimeMillis()))

    fun getBot(id: Long): Bot? {
        readableDatabase.query("bots", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) return cursorToBot(c)
        }
        return null
    }

    fun listBots(): List<Bot> {
        val out = ArrayList<Bot>()
        readableDatabase.query("bots", null, null, null, null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) out.add(cursorToBot(c))
        }
        return out
    }

    fun updateBot(bot: Bot): Int {
        val values = botToContentValues(bot, bot.createdAt, System.currentTimeMillis())
        return writableDatabase.update("bots", values, "id = ?", arrayOf(bot.id.toString()))
    }

    fun deleteBot(id: Long) {
        // FK cascade handles knowledge_files + conversations; their cascades handle
        // chunks + messages. chunks_fts triggers keep FTS in sync.
        writableDatabase.delete("bots", "id = ?", arrayOf(id.toString()))
    }

    private fun botToContentValues(bot: Bot, createdAt: Long, updatedAt: Long): ContentValues =
        ContentValues().apply {
            put("name", bot.name)
            put("target_apps", JSONArray(bot.targetApps).toString())
            put("system_prompt", bot.systemPrompt)
            put("personality", bot.personality)
            put("enabled", if (bot.enabled) 1 else 0)
            put("confidence_threshold", bot.confidenceThreshold)
            put("quiet_hours_start", bot.quietHoursStart)
            put("quiet_hours_end", bot.quietHoursEnd)
            put("daily_message_cap", bot.dailyMessageCap)
            put("daily_cost_cap_cents", bot.dailyCostCapCents)
            put("company_url", bot.companyUrl)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }

    private fun cursorToBot(c: Cursor): Bot {
        val json = c.getStringByCol("target_apps")
        val apps = ArrayList<String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) apps.add(arr.getString(i))
        } catch (e: Exception) {
            Log.w(TAG, "bad target_apps JSON: $json")
        }
        return Bot(
            c.getLongByCol("id"), c.getStringByCol("name"), apps, c.getStringByCol("system_prompt"),
            c.getStringOrNull("personality"), c.getIntByCol("enabled") != 0, c.getFloatByCol("confidence_threshold"),
            c.getIntByCol("quiet_hours_start"), c.getIntByCol("quiet_hours_end"), c.getIntByCol("daily_message_cap"),
            c.getIntByCol("daily_cost_cap_cents"), c.getStringOrNull("company_url"), c.getLongByCol("created_at"), c.getLongByCol("updated_at"),
        )
    }

    // === Knowledge files ===

    fun saveKnowledgeFile(botId: Long, fileName: String, fileSize: Long, sourceType: String, sourceUrl: String?): Long =
        writableDatabase.insert("knowledge_files", null, ContentValues().apply {
            put("bot_id", botId); put("file_name", fileName); put("file_size", fileSize)
            put("source_type", sourceType); put("source_url", sourceUrl)
            put("chunk_count", 0); put("created_at", System.currentTimeMillis())
        })

    fun listKnowledgeFiles(botId: Long): List<KnowledgeFile> {
        val out = ArrayList<KnowledgeFile>()
        readableDatabase.query("knowledge_files", null, "bot_id = ?", arrayOf(botId.toString()),
            null, null, "created_at DESC").use { c -> while (c.moveToNext()) out.add(cursorToKnowledgeFile(c)) }
        return out
    }

    fun deleteKnowledgeFile(id: Long) {
        writableDatabase.delete("knowledge_files", "id = ?", arrayOf(id.toString()))
    }

    /** Refresh the cached chunk_count column after the embedding pipeline finishes. */
    fun updateChunkCount(fileId: Long, count: Int) {
        writableDatabase.update("knowledge_files", ContentValues().apply { put("chunk_count", count) }, "id = ?", arrayOf(fileId.toString()))
    }

    private fun cursorToKnowledgeFile(c: Cursor): KnowledgeFile = KnowledgeFile(
        c.getLongByCol("id"), c.getLongByCol("bot_id"), c.getStringByCol("file_name"), c.getLongByCol("file_size"),
        c.getStringByCol("source_type"), c.getStringOrNull("source_url"), c.getIntByCol("chunk_count"), c.getLongByCol("created_at"),
    )

    // === Chunks ===

    fun saveChunk(fileId: Long, chunkIndex: Int, text: String, embedding: ByteArray?, embeddingProvider: String?): Long =
        writableDatabase.insert("chunks", null, ContentValues().apply {
            put("file_id", fileId); put("chunk_index", chunkIndex); put("text", text)
            put("embedding", embedding); put("embedding_provider", embeddingProvider)
        })

    fun getChunksByFile(fileId: Long): List<Chunk> {
        val out = ArrayList<Chunk>()
        readableDatabase.query("chunks", null, "file_id = ?", arrayOf(fileId.toString()),
            null, null, "chunk_index ASC").use { c -> while (c.moveToNext()) out.add(cursorToChunk(c)) }
        return out
    }

    /**
     * Keyword search over chunk text via FTS5 BM25 ranking. Returns up to
     * [limit] chunks with their parent file's name. Falls back to LIKE when
     * FTS5 is unavailable (older SQLite).
     */
    fun searchChunksFts(query: String, limit: Int = 5): List<Pair<Chunk, String>> {
        val out = ArrayList<Pair<Chunk, String>>()
        val ftsQuery = buildFts5Query(query)
        val cols = "c.id, c.file_id, c.chunk_index, c.text, c.embedding, c.embedding_provider, kf.file_name"
        if (ftsQuery.isNotEmpty()) {
            try {
                val sql = "SELECT $cols FROM chunks_fts JOIN chunks c ON c.id = chunks_fts.rowid " +
                    "JOIN knowledge_files kf ON kf.id = c.file_id WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts) LIMIT ?"
                readableDatabase.rawQuery(sql, arrayOf(ftsQuery, limit.toString())).use { c ->
                    while (c.moveToNext()) out.add(cursorToChunk(c) to c.getStringByCol("file_name"))
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                Log.w(TAG, "FTS5 search failed, falling back to LIKE: ${e.message}")
            }
        }
        val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val sql = "SELECT $cols FROM chunks c JOIN knowledge_files kf ON kf.id = c.file_id WHERE c.text LIKE ? ESCAPE '\\' ORDER BY c.id DESC LIMIT ?"
        readableDatabase.rawQuery(sql, arrayOf(like, limit.toString())).use { c ->
            while (c.moveToNext()) out.add(cursorToChunk(c) to c.getStringByCol("file_name"))
        }
        return out
    }

    private fun cursorToChunk(c: Cursor): Chunk {
        val embIdx = c.getColumnIndex("embedding")
        val embedding = if (embIdx >= 0 && !c.isNull(embIdx)) c.getBlob(embIdx) else null
        return Chunk(
            c.getLongByCol("id"), c.getLongByCol("file_id"), c.getIntByCol("chunk_index"),
            c.getStringByCol("text"), embedding, c.getStringOrNull("embedding_provider"),
        )
    }

    // === Conversations + messages ===

    /** Get-or-create the conversation for (botId, contactKey). */
    fun getConversation(botId: Long, contactKey: String): Conversation {
        readableDatabase.query("conversations", null, "bot_id = ? AND contact_key = ?",
            arrayOf(botId.toString(), contactKey), null, null, null).use { c ->
            if (c.moveToFirst()) return cursorToConversation(c)
        }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("bot_id", botId); put("contact_key", contactKey)
            put("contact_name", null as String?); put("last_message_at", now)
        }
        val id = writableDatabase.insert("conversations", null, values)
        return Conversation(id, botId, contactKey, null, now)
    }

    fun saveMessage(conversationId: Long, direction: String, text: String, knowledgeSource: String?, toolCalls: String?, costCents: Int): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert("messages", null, ContentValues().apply {
            put("conversation_id", conversationId); put("direction", direction); put("text", text)
            put("knowledge_source", knowledgeSource); put("tool_calls", toolCalls)
            put("cost_cents", costCents); put("timestamp", now)
        })
        writableDatabase.update("conversations", ContentValues().apply { put("last_message_at", now) }, "id = ?", arrayOf(conversationId.toString()))
        return id
    }

    /** Returns the newest [limit] messages for a conversation in chronological order. */
    fun getConversationMessages(conversationId: Long, limit: Int = 50): List<Message> {
        val out = ArrayList<Message>()
        readableDatabase.query("messages", null, "conversation_id = ?", arrayOf(conversationId.toString()),
            null, null, "timestamp DESC", limit.toString()).use { c -> while (c.moveToNext()) out.add(cursorToMessage(c)) }
        out.reverse()
        return out
    }

    private fun cursorToConversation(c: Cursor): Conversation {
        val idx = c.getColumnIndex("last_message_at")
        return Conversation(
            c.getLongByCol("id"), c.getLongByCol("bot_id"), c.getStringByCol("contact_key"),
            c.getStringOrNull("contact_name"), if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else null,
        )
    }

    private fun cursorToMessage(c: Cursor): Message = Message(
        c.getLongByCol("id"), c.getLongByCol("conversation_id"), c.getStringByCol("direction"),
        c.getStringByCol("text"), c.getStringOrNull("knowledge_source"), c.getStringOrNull("tool_calls"),
        c.getIntByCol("cost_cents"), c.getLongByCol("timestamp"),
    )

    // === Action log ===

    fun logAction(botId: Long?, actionType: String, app: String?, contact: String?, summary: String?, details: String?): Long =
        writableDatabase.insert("action_log", null, ContentValues().apply {
            put("bot_id", botId); put("action_type", actionType); put("app", app)
            put("contact", contact); put("summary", summary); put("details", details)
            put("timestamp", System.currentTimeMillis())
        })

    fun getRecentActions(limit: Int = 100): List<ActionLog> {
        val out = ArrayList<ActionLog>()
        readableDatabase.query("action_log", null, null, null, null, null, "timestamp DESC", limit.toString()).use { c ->
            while (c.moveToNext()) out.add(cursorToActionLog(c))
        }
        return out
    }

    private fun cursorToActionLog(c: Cursor): ActionLog = ActionLog(
        c.getLongByCol("id"), c.getLongOrNull("bot_id"), c.getStringByCol("action_type"),
        c.getStringOrNull("app"), c.getStringOrNull("contact"), c.getStringOrNull("summary"),
        c.getStringOrNull("details"), c.getLongByCol("timestamp"),
    )

    // === Daily costs ===

    /** UPSERT: create today's row if missing, then atomically add tokens + cost. */
    fun addDailyCost(dateYmd: String, botId: Long, tokens: Int, costCents: Int) = writableDatabase.let { db ->
        db.beginTransaction()
        try {
            val seed = ContentValues().apply {
                put("date_ymd", dateYmd); put("bot_id", botId); put("tokens_used", 0); put("est_cost_cents", 0)
            }
            db.insertWithOnConflict("daily_costs", null, seed, SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL(
                "UPDATE daily_costs SET tokens_used = tokens_used + ?, est_cost_cents = est_cost_cents + ? WHERE date_ymd = ? AND bot_id = ?",
                arrayOf<Any?>(tokens, costCents, dateYmd, botId),
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun getDailyCost(dateYmd: String, botId: Long): DailyCost {
        readableDatabase.query("daily_costs", null, "date_ymd = ? AND bot_id = ?",
            arrayOf(dateYmd, botId.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) return cursorToDailyCost(c)
        }
        return DailyCost(dateYmd, botId, 0, 0)
    }

    private fun cursorToDailyCost(c: Cursor): DailyCost = DailyCost(
        c.getStringByCol("date_ymd"), c.getLongOrNull("bot_id"), c.getIntByCol("tokens_used"), c.getIntByCol("est_cost_cents"),
    )

    /** Quote each token and join with OR for FTS5 BM25 keyword search. */
    private fun buildFts5Query(query: String): String =
        query.trim()
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 }
            .joinToString(" OR ") { "\"$it\"" }
            .ifEmpty { "" }
}

// === Cursor column accessors (file-local). Wrap getColumnIndexOrThrow so the
// mapper call sites stay short and readable. ===

private fun Cursor.getStringByCol(col: String): String = getString(getColumnIndexOrThrow(col))
private fun Cursor.getLongByCol(col: String): Long = getLong(getColumnIndexOrThrow(col))
private fun Cursor.getIntByCol(col: String): Int = getInt(getColumnIndexOrThrow(col))
private fun Cursor.getFloatByCol(col: String): Float = getFloat(getColumnIndexOrThrow(col))

private fun Cursor.getStringOrNull(col: String): String? {
    val i = getColumnIndex(col)
    return if (i >= 0 && !isNull(i)) getString(i) else null
}

private fun Cursor.getLongOrNull(col: String): Long? {
    val i = getColumnIndex(col)
    return if (i >= 0 && !isNull(i)) getLong(i) else null
}
