package com.replyai.app.bots

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray

/**
 * BotManager — singleton CRUD layer over the `bots` table.
 *
 * Why this exists as an `object` with explicit `context` parameters:
 *   The placeholder [com.replyai.app.data.ReplyAIDatabase] is being built in
 *   parallel by another agent. To keep this file compilable + functional
 *   standalone, BotManager opens the *same* `replyai.db` file via a private
 *   SQLiteOpenHelper. The schema below matches ARCHITECTURE.md section 7
 *   exactly, so when the parallel DB layer lands, both writers coexist
 *   without conflict (everything uses `CREATE TABLE IF NOT EXISTS`).
 *
 * Methods:
 *   - [createBot]    insert a new bot, return its row id
 *   - [getBot]       fetch a single bot by id, or null
 *   - [listBots]     list all bots, newest-first
 *   - [updateBot]   mutate an existing bot
 *   - [deleteBot]   delete a bot (cascade handled by FK in DB schema)
 *   - [toggleBot]   flip the `enabled` flag, return the new value
 *
 * Thread-safety: a single shared [SQLiteOpenHelper] backs all calls;
 * SQLite serialises writes internally. Read calls are non-blocking.
 */
object BotManager {

    private const val TAG = "BotManager"
    private const val DB_NAME = "replyai.db"
    private const val DB_VERSION = 1

    const val TABLE_BOTS = "bots"

    // Column names — kept in sync with ARCHITECTURE.md section 7.
    private const val C_ID = "id"
    private const val C_NAME = "name"
    private const val C_TARGET_APPS = "target_apps"
    private const val C_SYSTEM_PROMPT = "system_prompt"
    private const val C_PERSONALITY = "personality"
    private const val C_ENABLED = "enabled"
    private const val C_CONFIDENCE_THRESHOLD = "confidence_threshold"
    private const val C_QUIET_HOURS_START = "quiet_hours_start"
    private const val C_QUIET_HOURS_END = "quiet_hours_end"
    private const val C_DAILY_MESSAGE_CAP = "daily_message_cap"
    private const val C_DAILY_COST_CAP_CENTS = "daily_cost_cap_cents"
    private const val C_COMPANY_URL = "company_url"
    private const val C_CREATED_AT = "created_at"
    private const val C_UPDATED_AT = "updated_at"

    // ------------------------------------------------------------------
    // Schema
    // ------------------------------------------------------------------

    private val CREATE_BOTS_SQL = """
        CREATE TABLE IF NOT EXISTS $TABLE_BOTS (
            $C_ID                   INTEGER PRIMARY KEY AUTOINCREMENT,
            $C_NAME                 TEXT    NOT NULL,
            $C_TARGET_APPS          TEXT    NOT NULL,
            $C_SYSTEM_PROMPT        TEXT    NOT NULL,
            $C_PERSONALITY          TEXT,
            $C_ENABLED              INTEGER DEFAULT 1,
            $C_CONFIDENCE_THRESHOLD REAL    DEFAULT 0.7,
            $C_QUIET_HOURS_START    INTEGER DEFAULT 22,
            $C_QUIET_HOURS_END      INTEGER DEFAULT 7,
            $C_DAILY_MESSAGE_CAP    INTEGER DEFAULT 10,
            $C_DAILY_COST_CAP_CENTS INTEGER DEFAULT 200,
            $C_COMPANY_URL          TEXT,
            $C_CREATED_AT           INTEGER NOT NULL,
            $C_UPDATED_AT           INTEGER NOT NULL
        )
    """.trimIndent()

    /**
     * Private SQLiteOpenHelper. Singleton via [Holder]; one per process.
     * Opens the same `replyai.db` that [com.replyai.app.data.ReplyAIDatabase]
     * uses, so tables are shared.
     *
     * `onOpen` re-runs the CREATE IF NOT EXISTS so the bots table is
     * guaranteed to exist even when another helper (ConversationMemory's,
     * or the parallel ReplyAIDatabase) was the first to open the file and
     * increment user_version past our onCreate.
     */
    private class BotDbHelper(ctx: Context) :
        SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_BOTS_SQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            // Idempotent: just re-run CREATE IF NOT EXISTS.
            db.execSQL(CREATE_BOTS_SQL)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            // Ensure bots exists even if another helper opened this DB first.
            db.execSQL(CREATE_BOTS_SQL)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    @Volatile
    private var helper: SQLiteOpenHelper? = null

    private fun dbHelper(context: Context): SQLiteOpenHelper {
        // Double-checked locking — standard pattern for lazy singleton.
        val existing = helper
        if (existing != null) return existing
        synchronized(this) {
            val again = helper
            if (again != null) return again
            val h = BotDbHelper(context.applicationContext)
            helper = h
            return h
        }
    }

    private fun db(context: Context, writable: Boolean): SQLiteDatabase {
        return if (writable) dbHelper(context).writableDatabase
        else dbHelper(context).readableDatabase
    }

    // ------------------------------------------------------------------
    // JSON helpers for target_apps column (List<String> ↔ JSON string)
    // ------------------------------------------------------------------

    private fun encodeApps(apps: List<String>): String {
        val arr = JSONArray()
        apps.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeApps(raw: String): List<String> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode target_apps: $raw", e)
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Row → Bot
    // ------------------------------------------------------------------

    private fun rowToBot(c: android.database.Cursor): Bot {
        return Bot(
            id = c.getLong(c.getColumnIndexOrThrow(C_ID)),
            name = c.getString(c.getColumnIndexOrThrow(C_NAME)),
            targetApps = decodeApps(c.getString(c.getColumnIndexOrThrow(C_TARGET_APPS))),
            systemPrompt = c.getString(c.getColumnIndexOrThrow(C_SYSTEM_PROMPT)),
            personality = c.getString(c.getColumnIndexOrThrow(C_PERSONALITY))?.takeIf { it.isNotBlank() },
            enabled = c.getInt(c.getColumnIndexOrThrow(C_ENABLED)) != 0,
            confidenceThreshold = c.getFloat(c.getColumnIndexOrThrow(C_CONFIDENCE_THRESHOLD)),
            quietHoursStart = c.getInt(c.getColumnIndexOrThrow(C_QUIET_HOURS_START)),
            quietHoursEnd = c.getInt(c.getColumnIndexOrThrow(C_QUIET_HOURS_END)),
            dailyMessageCap = c.getInt(c.getColumnIndexOrThrow(C_DAILY_MESSAGE_CAP)),
            dailyCostCapCents = c.getInt(c.getColumnIndexOrThrow(C_DAILY_COST_CAP_CENTS)),
            companyUrl = c.getString(c.getColumnIndexOrThrow(C_COMPANY_URL))?.takeIf { it.isNotBlank() },
            createdAt = c.getLong(c.getColumnIndexOrThrow(C_CREATED_AT)),
            updatedAt = c.getLong(c.getColumnIndexOrThrow(C_UPDATED_AT)),
        )
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    /**
     * Create a new bot. Returns the new row id, or -1 on failure.
     */
    fun createBot(
        context: Context,
        name: String,
        targetApps: List<String>,
        systemPrompt: String,
        personality: String? = null,
        enabled: Boolean = true,
        confidenceThreshold: Float = 0.7f,
        quietHoursStart: Int = 22,
        quietHoursEnd: Int = 7,
        dailyMessageCap: Int = 10,
        dailyCostCapCents: Int = 200,
        companyUrl: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put(C_NAME, name)
            put(C_TARGET_APPS, encodeApps(targetApps))
            put(C_SYSTEM_PROMPT, systemPrompt)
            put(C_PERSONALITY, personality)
            put(C_ENABLED, if (enabled) 1 else 0)
            put(C_CONFIDENCE_THRESHOLD, confidenceThreshold)
            put(C_QUIET_HOURS_START, quietHoursStart)
            put(C_QUIET_HOURS_END, quietHoursEnd)
            put(C_DAILY_MESSAGE_CAP, dailyMessageCap)
            put(C_DAILY_COST_CAP_CENTS, dailyCostCapCents)
            put(C_COMPANY_URL, companyUrl)
            put(C_CREATED_AT, now)
            put(C_UPDATED_AT, now)
        }
        return try {
            val id = db(context, writable = true).insert(TABLE_BOTS, null, cv)
            if (id < 0) Log.e(TAG, "createBot: insert returned -1")
            id
        } catch (e: Exception) {
            Log.e(TAG, "createBot failed", e)
            -1L
        }
    }

    /**
     * Fetch a single bot by id. Returns null if not found.
     */
    fun getBot(context: Context, id: Long): Bot? {
        return try {
            db(context, writable = false).query(
                TABLE_BOTS, null, "$C_ID = ?",
                arrayOf(id.toString()),
                null, null, null,
            ).use { c ->
                if (c.moveToFirst()) rowToBot(c) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBot($id) failed", e)
            null
        }
    }

    /**
     * List all bots, newest-first (by created_at desc).
     */
    fun listBots(context: Context): List<Bot> {
        return try {
            db(context, writable = false).query(
                TABLE_BOTS, null, null, null,
                null, null, "$C_CREATED_AT DESC",
            ).use { c ->
                val out = ArrayList<Bot>(c.count)
                while (c.moveToNext()) out.add(rowToBot(c))
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "listBots failed", e)
            emptyList()
        }
    }

    /**
     * Update an existing bot. All fields are taken from [bot] (including id).
     * Returns true on success.
     */
    fun updateBot(context: Context, bot: Bot): Boolean {
        val cv = ContentValues().apply {
            put(C_NAME, bot.name)
            put(C_TARGET_APPS, encodeApps(bot.targetApps))
            put(C_SYSTEM_PROMPT, bot.systemPrompt)
            put(C_PERSONALITY, bot.personality)
            put(C_ENABLED, if (bot.enabled) 1 else 0)
            put(C_CONFIDENCE_THRESHOLD, bot.confidenceThreshold)
            put(C_QUIET_HOURS_START, bot.quietHoursStart)
            put(C_QUIET_HOURS_END, bot.quietHoursEnd)
            put(C_DAILY_MESSAGE_CAP, bot.dailyMessageCap)
            put(C_DAILY_COST_CAP_CENTS, bot.dailyCostCapCents)
            put(C_COMPANY_URL, bot.companyUrl)
            put(C_UPDATED_AT, System.currentTimeMillis())
        }
        return try {
            val rows = db(context, writable = true).update(
                TABLE_BOTS, cv, "$C_ID = ?", arrayOf(bot.id.toString()),
            )
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "updateBot(${bot.id}) failed", e)
            false
        }
    }

    /**
     * Delete a bot by id. Returns true if a row was removed.
     *
     * Note: cascading deletes (knowledge_files, conversations, messages)
     * are the database's responsibility via `ON DELETE CASCADE` foreign keys
     * in the parallel [com.replyai.app.data.ReplyAIDatabase] schema.
     */
    fun deleteBot(context: Context, id: Long): Boolean {
        return try {
            val rows = db(context, writable = true).delete(
                TABLE_BOTS, "$C_ID = ?", arrayOf(id.toString()),
            )
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "deleteBot($id) failed", e)
            false
        }
    }

    /**
     * Flip the `enabled` flag on a bot. Returns the new value (or null if the
     * bot doesn't exist).
     */
    fun toggleBot(context: Context, id: Long, enabled: Boolean): Boolean? {
        val cv = ContentValues().apply {
            put(C_ENABLED, if (enabled) 1 else 0)
            put(C_UPDATED_AT, System.currentTimeMillis())
        }
        return try {
            val rows = db(context, writable = true).update(
                TABLE_BOTS, cv, "$C_ID = ?", arrayOf(id.toString()),
            )
            if (rows > 0) enabled else null
        } catch (e: Exception) {
            Log.e(TAG, "toggleBot($id) failed", e)
            null
        }
    }
}
