package com.replyai.app.bots

/**
 * Bot — a configured auto-reply rule.
 *
 * Maps 1:1 to the `bots` table in ReplyAIDatabase:
 *
 *   CREATE TABLE bots (
 *       id                   INTEGER PRIMARY KEY AUTOINCREMENT,
 *       name                 TEXT    NOT NULL,
 *       target_apps          TEXT    NOT NULL,        -- JSON array: ["com.whatsapp","com.telegram"]
 *       system_prompt        TEXT    NOT NULL,
 *       personality          TEXT,                     -- "professional" | "friendly" | ...
 *       enabled              INTEGER DEFAULT 1,
 *       confidence_threshold REAL    DEFAULT 0.7,
 *       quiet_hours_start    INTEGER DEFAULT 22,
 *       quiet_hours_end      INTEGER DEFAULT 7,
 *       daily_message_cap    INTEGER DEFAULT 10,
 *       daily_cost_cap_cents INTEGER DEFAULT 200,
 *       company_url          TEXT,                     -- for web-search grounding (nullable)
 *       created_at           INTEGER NOT NULL,
 *       updated_at           INTEGER NOT NULL
 *   );
 *
 * `target_apps` is stored as a JSON array string in SQLite; this data class
 * exposes it as a `List<String>` for ergonomics. See [BotManager] for the
 * (de)serialisation logic.
 *
 * See ARCHITECTURE.md section 7.
 */
data class Bot(
    val id: Long,
    val name: String,
    val targetApps: List<String>,
    val systemPrompt: String,
    val personality: String?,
    val enabled: Boolean,
    val confidenceThreshold: Float,
    val quietHoursStart: Int,
    val quietHoursEnd: Int,
    val dailyMessageCap: Int,
    val dailyCostCapCents: Int,
    val companyUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
