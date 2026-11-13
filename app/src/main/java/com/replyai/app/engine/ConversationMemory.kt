package com.replyai.app.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * ConversationMemory — per-contact conversation history for [RuleEngine].
 *
 * Two layers:
 *
 *   1. In-memory cache (volatile, fast): Map<String, MutableList<Pair<String, String>>>
 *      where the key is `"${botId}:${contactKey}"` and each pair is
 *      `(direction, text)` with direction ∈ {"incoming", "outgoing"}.
 *
 *   2. Persistent layer: rows in the `conversations` + `messages` tables of
 *      `replyai.db`. The schema matches ARCHITECTURE.md section 7 exactly,
 *      so this writer coexists with the parallel
 *      [com.replyai.app.data.ReplyAIDatabase] implementation.
 *
 * Public API:
 *   - [addIncoming]         save a message received from the contact.
 *   - [addOutgoing]         save a reply we sent.
 *   - [getHistoryForPrompt] return the last N exchanges formatted as a
 *                           `"User: ...\nAssistant: ..."` block for the LLM.
 *   - [clearForContact]     wipe history for one (bot, contact) pair.
 *
 * Concurrency: all mutations go through `@Synchronized` cache accessors
 * plus SQLite's internal write lock — safe to call from background
 * coroutines.
 *
 * See ARCHITECTURE.md section 4 ("Per-contact conversation memory") +
 * Aira's ConversationMemory.kt pattern (last N exchanges per contact).
 */
object ConversationMemory {

    private const val TAG = "ConversationMemory"
    private const val DB_NAME = "replyai.db"
    private const val DB_VERSION = 1

    private const val TABLE_CONVERSATIONS = "conversations"
    private const val TABLE_MESSAGES = "messages"

    private const val C_CONV_ID = "id"
    private const val C_CONV_BOT_ID = "bot_id"
    private const val C_CONV_CONTACT_KEY = "contact_key"
    private const val C_CONV_CONTACT_NAME = "contact_name"
    private const val C_CONV_LAST_AT = "last_message_at"

    private const val C_MSG_ID = "id"
    private const val C_MSG_CONV_ID = "conversation_id"
    private const val C_MSG_DIRECTION = "direction"
    private const val C_MSG_TEXT = "text"
    private const val C_MSG_KNOWLEDGE_SOURCE = "knowledge_source"
    private const val C_MSG_TOOL_CALLS = "tool_calls"
    private const val C_MSG_COST_CENTS = "cost_cents"
    private const val C_MSG_TIMESTAMP = "timestamp"

    const val DIR_INCOMING = "incoming"
    const val DIR_OUTGOING = "outgoing"

    private val CREATE_CONVERSATIONS_SQL = """
        CREATE TABLE IF NOT EXISTS $TABLE_CONVERSATIONS (
            $C_CONV_ID             INTEGER PRIMARY KEY AUTOINCREMENT,
            $C_CONV_BOT_ID         INTEGER NOT NULL,
            $C_CONV_CONTACT_KEY    TEXT    NOT NULL,
            $C_CONV_CONTACT_NAME   TEXT,
            $C_CONV_LAST_AT        INTEGER,
            FOREIGN KEY ($C_CONV_BOT_ID)
                REFERENCES bots(id) ON DELETE CASCADE
        )
    """.trimIndent()

    private val CREATE_MESSAGES_SQL = """
        CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
            $C_MSG_ID               INTEGER PRIMARY KEY AUTOINCREMENT,
            $C_MSG_CONV_ID          INTEGER NOT NULL,
            $C_MSG_DIRECTION        TEXT    NOT NULL,
            $C_MSG_TEXT             TEXT    NOT NULL,
            $C_MSG_KNOWLEDGE_SOURCE TEXT,
            $C_MSG_TOOL_CALLS       TEXT,
            $C_MSG_COST_CENTS       INTEGER DEFAULT 0,
            $C_MSG_TIMESTAMP        INTEGER NOT NULL,
            FOREIGN KEY ($C_MSG_CONV_ID)
                REFERENCES conversations(id) ON DELETE CASCADE
        )
    """.trimIndent()

    private val CREATE_INDEX_CONV_BOT_CONTACT = """
        CREATE INDEX IF NOT EXISTS idx_conv_bot_contact
        ON $TABLE_CONVERSATIONS($C_CONV_BOT_ID, $C_CONV_CONTACT_KEY)
    """.trimIndent()

    private val CREATE_INDEX_MSG_CONV_TS = """
        CREATE INDEX IF NOT EXISTS idx_msg_conv_ts
        ON $TABLE_MESSAGES($C_MSG_CONV_ID, $C_MSG_TIMESTAMP)
    """.trimIndent()

    /**
     * Bots table schema — duplicated from [com.replyai.app.bots.BotManager]
     * so this helper can guarantee the `bots(id)` parent row exists for the
     * `conversations.bot_id` FK, even when ConversationMemory opens the
     * database before BotManager ever has. Uses IF NOT EXISTS → no-op if
     * BotManager already created it.
     */
    private const val TABLE_BOTS = "bots"
    private val CREATE_BOTS_SQL = """
        CREATE TABLE IF NOT EXISTS $TABLE_BOTS (
            id                   INTEGER PRIMARY KEY AUTOINCREMENT,
            name                 TEXT    NOT NULL,
            target_apps          TEXT    NOT NULL,
            system_prompt        TEXT    NOT NULL,
            personality          TEXT,
            enabled              INTEGER DEFAULT 1,
            confidence_threshold REAL    DEFAULT 0.7,
            quiet_hours_start    INTEGER DEFAULT 22,
            quiet_hours_end      INTEGER DEFAULT 7,
            daily_message_cap    INTEGER DEFAULT 10,
            daily_cost_cap_cents INTEGER DEFAULT 200,
            company_url          TEXT,
            created_at           INTEGER NOT NULL,
            updated_at           INTEGER NOT NULL
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // DB helper (private — same shared replyai.db as BotManager)
    // ------------------------------------------------------------------

    private class ConvDbHelper(ctx: Context) :
        SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            // Create ALL tables we depend on (own + bots parent for FK).
            // IF NOT EXISTS makes this safe even if BotManager already ran.
            db.execSQL(CREATE_BOTS_SQL)
            db.execSQL(CREATE_CONVERSATIONS_SQL)
            db.execSQL(CREATE_MESSAGES_SQL)
            db.execSQL(CREATE_INDEX_CONV_BOT_CONTACT)
            db.execSQL(CREATE_INDEX_MSG_CONV_TS)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            // Idempotent: re-run all CREATE IF NOT EXISTS.
            onCreate(db)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            // onCreate only runs once per file (version-gated). If BotManager
            // (or the parallel ReplyAIDatabase) opened the DB first and
            // incremented user_version, our onCreate wouldn't have run — so
            // ensure our tables exist every time the DB is opened.
            db.execSQL(CREATE_BOTS_SQL)
            db.execSQL(CREATE_CONVERSATIONS_SQL)
            db.execSQL(CREATE_MESSAGES_SQL)
            db.execSQL(CREATE_INDEX_CONV_BOT_CONTACT)
            db.execSQL(CREATE_INDEX_MSG_CONV_TS)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            // Required so `ON DELETE CASCADE` actually fires.
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    @Volatile
    private var helper: SQLiteOpenHelper? = null

    private fun dbHelper(context: Context): SQLiteOpenHelper {
        val existing = helper
        if (existing != null) return existing
        synchronized(this) {
            val again = helper
            if (again != null) return again
            val h = ConvDbHelper(context.applicationContext)
            helper = h
            return h
        }
    }

    private fun db(context: Context, writable: Boolean): SQLiteDatabase {
        return if (writable) dbHelper(context).writableDatabase
        else dbHelper(context).readableDatabase
    }

    // ------------------------------------------------------------------
    // In-memory cache
    //
    //   key   = "${botId}:${contactKey}"
    //   value = MutableList<Pair<direction, text>>
    // ------------------------------------------------------------------

    private val cache = HashMap<String, MutableList<Pair<String, String>>>()

    private fun cacheKey(botId: Long, contactKey: String): String = "$botId:$contactKey"

    @Synchronized
    private fun cachePush(botId: Long, contactKey: String, direction: String, text: String) {
        val key = cacheKey(botId, contactKey)
        val list = cache.getOrPut(key) { mutableListOf() }
        list.add(direction to text)
        // Hard cap on in-memory size to keep the cache bounded.
        while (list.size > 40) list.removeAt(0)
    }

    @Synchronized
    private fun cacheSnapshot(
        botId: Long,
        contactKey: String,
        maxExchanges: Int,
    ): List<Pair<String, String>> {
        val list = cache[cacheKey(botId, contactKey)] ?: return emptyList()
        // 1 exchange = 1 incoming + 1 outgoing, so maxExchanges*2 entries.
        val take = (maxExchanges * 2).coerceAtLeast(0)
        return if (list.size <= take) list.toList() else list.takeLast(take)
    }

    @Synchronized
    private fun cacheClear(botId: Long, contactKey: String) {
        cache.remove(cacheKey(botId, contactKey))
    }

    @Synchronized
    private fun cacheReplace(
        botId: Long,
        contactKey: String,
        rows: List<Pair<String, String>>,
    ) {
        cache[cacheKey(botId, contactKey)] = rows.toMutableList()
    }

    // ------------------------------------------------------------------
    // Conversations table get-or-create
    // ------------------------------------------------------------------

    private fun getOrCreateConversation(
        context: Context,
        botId: Long,
        contactKey: String,
        contactName: String?,
    ): Long {
        val db = db(context, writable = true)
        // Look for an existing row first.
        db.query(
            TABLE_CONVERSATIONS,
            arrayOf(C_CONV_ID),
            "$C_CONV_BOT_ID = ? AND $C_CONV_CONTACT_KEY = ?",
            arrayOf(botId.toString(), contactKey),
            null, null, null, "1",
        ).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        // Insert a new conversation row.
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put(C_CONV_BOT_ID, botId)
            put(C_CONV_CONTACT_KEY, contactKey)
            put(C_CONV_CONTACT_NAME, contactName)
            put(C_CONV_LAST_AT, now)
        }
        return db.insert(TABLE_CONVERSATIONS, null, cv)
    }

    private fun touchConversationLastAt(context: Context, convId: Long) {
        try {
            val cv = ContentValues().apply {
                put(C_CONV_LAST_AT, System.currentTimeMillis())
            }
            db(context, writable = true).update(
                TABLE_CONVERSATIONS, cv, "$C_CONV_ID = ?", arrayOf(convId.toString()),
            )
        } catch (e: Exception) {
            Log.w(TAG, "touchConversationLastAt failed", e)
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Save a message received from the contact.
     */
    fun addIncoming(
        context: Context,
        botId: Long,
        contactKey: String,
        contactName: String?,
        text: String,
    ) {
        addMessage(context, botId, contactKey, contactName, DIR_INCOMING, text)
    }

    /**
     * Save a reply we sent to the contact.
     */
    fun addOutgoing(
        context: Context,
        botId: Long,
        contactKey: String,
        text: String,
    ) {
        addMessage(context, botId, contactKey, null, DIR_OUTGOING, text)
    }

    private fun addMessage(
        context: Context,
        botId: Long,
        contactKey: String,
        contactName: String?,
        direction: String,
        text: String,
    ) {
        try {
            val convId = getOrCreateConversation(context, botId, contactKey, contactName)
            if (convId < 0) {
                Log.e(TAG, "addMessage: getOrCreateConversation returned -1 for $botId/$contactKey")
                return
            }
            val cv = ContentValues().apply {
                put(C_MSG_CONV_ID, convId)
                put(C_MSG_DIRECTION, direction)
                put(C_MSG_TEXT, text)
                put(C_MSG_TIMESTAMP, System.currentTimeMillis())
            }
            db(context, writable = true).insert(TABLE_MESSAGES, null, cv)
            touchConversationLastAt(context, convId)
        } catch (e: Exception) {
            Log.e(TAG, "addMessage($direction) failed", e)
        }
        // Cache update always runs (even if DB write failed) so the very next
        // LLM call inside the same RuleEngine loop still sees the latest turn.
        cachePush(botId, contactKey, direction, text)
    }

    /**
     * Return the last [maxExchanges] exchanges (1 exchange = 1 incoming + 1
     * outgoing) as a formatted block ready to inject into an LLM prompt.
     *
     * Format:
     *   User: ...
     *   Assistant: ...
     *   User: ...
     *
     * Returns "(no previous conversation)" when there's no history.
     *
     * The cache is checked first. On a cold start (cache empty) we fall back
     * to the DB and warm the cache.
     */
    fun getHistoryForPrompt(
        context: Context,
        botId: Long,
        contactKey: String,
        maxExchanges: Int = 10,
    ): String {
        var rows = cacheSnapshot(botId, contactKey, maxExchanges)
        if (rows.isEmpty()) {
            rows = loadFromDb(context, botId, contactKey, maxExchanges)
            if (rows.isNotEmpty()) cacheReplace(botId, contactKey, rows)
        }
        if (rows.isEmpty()) return "(no previous conversation)"

        val sb = StringBuilder()
        for ((direction, text) in rows) {
            val speaker = if (direction == DIR_INCOMING) "User" else "Assistant"
            sb.append("$speaker: ")
            sb.append(text.replace('\n', ' ').trim().take(800))
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    /**
     * Wipe the in-memory cache + DB rows for one (bot, contact) pair.
     */
    fun clearForContact(context: Context, botId: Long, contactKey: String) {
        cacheClear(botId, contactKey)
        try {
            val db = db(context, writable = true)
            val convIds = ArrayList<Long>()
            db.query(
                TABLE_CONVERSATIONS, arrayOf(C_CONV_ID),
                "$C_CONV_BOT_ID = ? AND $C_CONV_CONTACT_KEY = ?",
                arrayOf(botId.toString(), contactKey),
                null, null, null,
            ).use { c ->
                while (c.moveToNext()) convIds.add(c.getLong(0))
            }
            if (convIds.isEmpty()) return
            convIds.forEach { id ->
                db.delete(TABLE_MESSAGES, "$C_MSG_CONV_ID = ?", arrayOf(id.toString()))
            }
            db.delete(
                TABLE_CONVERSATIONS,
                "$C_CONV_BOT_ID = ? AND $C_CONV_CONTACT_KEY = ?",
                arrayOf(botId.toString(), contactKey),
            )
        } catch (e: Exception) {
            Log.e(TAG, "clearForContact failed", e)
        }
    }

    // ------------------------------------------------------------------
    // DB loaders
    // ------------------------------------------------------------------

    private fun loadFromDb(
        context: Context,
        botId: Long,
        contactKey: String,
        maxExchanges: Int,
    ): List<Pair<String, String>> {
        val convId = try {
            db(context, writable = false).query(
                TABLE_CONVERSATIONS, arrayOf(C_CONV_ID),
                "$C_CONV_BOT_ID = ? AND $C_CONV_CONTACT_KEY = ?",
                arrayOf(botId.toString(), contactKey),
                null, null, null, "1",
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
        } catch (e: Exception) {
            Log.w(TAG, "loadFromDb: conversation lookup failed", e)
            return emptyList()
        }
        if (convId < 0) return emptyList()

        return try {
            val take = (maxExchanges * 2).coerceAtLeast(0)
            db(context, writable = false).query(
                TABLE_MESSAGES,
                arrayOf(C_MSG_DIRECTION, C_MSG_TEXT),
                "$C_MSG_CONV_ID = ?",
                arrayOf(convId.toString()),
                null, null,
                "$C_MSG_TIMESTAMP ASC",  // oldest first
                take.toString(),
            ).use { c ->
                val out = ArrayList<Pair<String, String>>(c.count)
                while (c.moveToNext()) {
                    val dir = c.getString(0)
                    val txt = c.getString(1)
                    out.add(dir to txt)
                }
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadFromDb: messages query failed", e)
            emptyList()
        }
    }
}
