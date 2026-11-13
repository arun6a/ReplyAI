package com.replyai.app.notifications

import android.telephony.SmsManager
import android.util.Log
import com.replyai.app.bots.Bot
import com.replyai.app.data.ReplyAIDatabase
import com.replyai.app.engine.ConversationMemory
import com.replyai.app.engine.IntentClassifier
import com.replyai.app.engine.RetrievalMode
import com.replyai.app.knowledge.KnowledgeManager
import com.replyai.app.llm.LLMClient
import com.replyai.app.safety.SafetyConfig
import com.replyai.app.safety.SafetyRules
import com.replyai.app.safety.SafetyVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ReplyEngine — the orchestrator.
 *
 * Called by [ReplyAINotificationListener.onNotificationPosted] (via a launched
 * coroutine) for every incoming message from a supported messaging app. It:
 *   1. Finds enabled bots targeting the app.
 *   2. Enforces a per-contact cooldown (5 min); queues messages that arrive
 *      during cooldown.
 *   3. Persists the incoming message to [ConversationMemory] + [ReplyAIDatabase].
 *   4. Runs [SafetyRules] (quiet hours, rate limit, escalation keywords).
 *   5. Asks [IntentClassifier] which retrieval mode to use.
 *   6. Calls [LLMClient] with system prompt + history + retrieved knowledge.
 *   7. Sends the LLM's reply via `ReplyAINotificationListener.replyToNotification`.
 *   8. Persists the outgoing message + writes to `action_log`.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EXTERNAL DEPENDENCIES (built by parallel agents; see ARCHITECTURE.md §7, §8)
 * ─────────────────────────────────────────────────────────────────────────────
 *   com.replyai.app.bots.Bot
 *       data class Bot(
 *           val id: Long,
 *           val name: String,
 *           val targetApps: List<String>,        // ["com.whatsapp","com.telegram"]
 *           val systemPrompt: String,
 *           val personality: String?,
 *           val enabled: Boolean,
 *           val confidenceThreshold: Double,
 *           val quietHoursStart: Int,             // 0..23
 *           val quietHoursEnd: Int,               // 0..23
 *           val dailyMessageCap: Int,
 *           val dailyCostCapCents: Int,
 *           val createdAt: Long,
 *           val updatedAt: Long,
 *       )
 *
 *   com.replyai.app.data.ReplyAIDatabase   (object)
 *       fun getEnabledBots(): List<Bot>
 *       fun saveIncomingMessage(botId, convKey, sender, text, timestamp)
 *       fun saveOutgoingMessage(botId, convKey, text, retrievalMode, timestamp)
 *       fun logAction(botId, actionType, app, contact, summary)
 *
 *   com.replyai.app.engine.ConversationMemory   (object)
 *       fun addIncoming(convKey: String, text: String)
 *       fun addOutgoing(convKey: String, text: String)
 *       fun getHistoryForPrompt(convKey: String): String
 *
 *   com.replyai.app.engine.IntentClassifier   (object)
 *       suspend fun classify(message: String, bot: Bot): RetrievalMode
 *
 *   com.replyai.app.engine.RetrievalMode   (enum class)
 *       DIRECT, FTS5, VECTOR, WEB_SEARCH
 *
 *   com.replyai.app.knowledge.KnowledgeManager   (object)
 *       suspend fun searchFts(botId: Long, query: String): String
 *       suspend fun searchVector(botId: Long, query: String): String
 *       suspend fun searchWeb(bot: Bot, query: String): String
 *
 *   com.replyai.app.llm.LLMClient   (object)
 *       suspend fun chat(
 *           systemPrompt: String,
 *           history: String,
 *           userMessage: String,
 *           retrievalContext: String,
 *           mode: RetrievalMode,
 *       ): String
 *
 *   com.replyai.app.safety.SafetyRules   (object)
 *       suspend fun check(bot: Bot, convKey: String, message: String): SafetyVerdict
 *
 *   com.replyai.app.safety.SafetyVerdict   (sealed class)
 *       object Allow : SafetyVerdict
 *       data class Block(val reason: String) : SafetyVerdict
 *       data class Escalate(val reason: String) : SafetyVerdict
 *
 *   com.replyai.app.safety.SafetyConfig   (object)
 *       val ownerPhone: String?      // destination for escalation SMS
 * ─────────────────────────────────────────────────────────────────────────────
 */
object ReplyEngine {

    private const val TAG = "ReplyEngine"

    /** Cooldown window: don't reply to the same contact twice within 5 minutes. */
    private const val COOLDOWN_MS = 5 * 60 * 1000L

    /**
     * Per-contact cooldown tracking.
     * Key: `"com.whatsapp:Mom"` (package + sanitized sender).
     * Value: last-reply-timestamp in ms.
     *
     * Per-contact (not per-package) so Mom and Dad can both get replies inside
     * the cooldown window. (Aira v4.0.67 lesson.)
     */
    private val lastReplyTime = mutableMapOf<String, Long>()

    /**
     * Messages that arrived during cooldown. Replayed by [processPendingReplies]
     * once the cooldown expires. Multiple messages from the same sender get
     * combined into a single pending entry (Aira pattern).
     */
    private val pendingReplies = mutableListOf<PendingReply>()

    /**
     * A queued message waiting for its contact's cooldown to expire.
     *
     * @param packageName source app (e.g. "com.whatsapp")
     * @param sender      sender title (e.g. "Mom"); blank for unknown
     * @param message     combined message body (multiple queued messages are
     *                     joined with `\n`)
     * @param arrivedAt   timestamp of the most recent message in this entry
     */
    data class PendingReply(
        val packageName: String,
        val sender: String,
        val message: String,
        val arrivedAt: Long,
    )

    /** Scope for replaying pending replies asynchronously. */
    private val replayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Main entry point. Called by the NotificationListener in a coroutine.
     *
     * @param packageName source app (e.g. "com.whatsapp")
     * @param sender      sender title extracted from the notification
     * @param message     message body extracted from the notification
     */
    suspend fun handleIncoming(packageName: String, sender: String, message: String) {
        val now = System.currentTimeMillis()
        val convKey = conversationKey(packageName, sender)
        Log.i(TAG, "Incoming from $convKey: '${preview(message)}'")

        // Step 1: Find enabled bots whose target_apps contains packageName.
        val bots = findBotsForPackage(packageName)
        if (bots.isEmpty()) {
            Log.d(TAG, "No enabled bot configured for $packageName — ignoring")
            return
        }
        // v1.0: first matching bot wins. (Multi-bot-per-app is a future enhancement.)
        val bot = bots.first()

        // Step 2: Cooldown check. If within 5 min of last reply, queue + return.
        val cooldownKey = "$packageName:$sender"
        val lastReply = lastReplyTime[cooldownKey] ?: 0L
        if (now - lastReply < COOLDOWN_MS) {
            val remainingSec = (COOLDOWN_MS - (now - lastReply)) / 1000
            Log.i(TAG, "Cooldown active for $cooldownKey (${remainingSec}s left) — queuing")
            queuePending(packageName, sender, message, now)
            return
        }

        // Steps 3-8.
        processReply(packageName, sender, message, bot, convKey)
    }

    /**
     * Steps 3-8 of the pipeline. Factored out so [processPendingReplies] can
     * replay queued messages through the same path.
     *
     * Assumes the cooldown check has already passed for `(packageName, sender)`.
     */
    private suspend fun processReply(
        packageName: String,
        sender: String,
        message: String,
        bot: Bot,
        convKey: String,
    ) {
        val now = System.currentTimeMillis()

        // Step 3: Save incoming message to ConversationMemory + database.
        try {
            ConversationMemory.addIncoming(convKey, "$sender: $message")
            ReplyAIDatabase.saveIncomingMessage(bot.id, convKey, sender, message, now)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist incoming message for $convKey", e)
        }

        // Step 4: Safety rules (quiet hours, rate limit, escalation keywords).
        val verdict = try {
            SafetyRules.check(bot, convKey, message)
        } catch (e: Exception) {
            Log.w(TAG, "SafetyRules.check failed — defaulting to Allow", e)
            SafetyVerdict.Allow
        }
        when (verdict) {
            is SafetyVerdict.Block -> {
                Log.i(TAG, "Blocked by safety rules: ${verdict.reason}")
                logAction(bot.id, "blocked", packageName, sender,
                    "Blocked: ${verdict.reason}")
                return
            }
            is SafetyVerdict.Escalate -> {
                Log.w(TAG, "Escalated by safety rules: ${verdict.reason}")
                escalateToOwner(bot, packageName, sender, message, verdict.reason)
                logAction(bot.id, "escalated", packageName, sender,
                    "Escalated: ${verdict.reason} | msg: ${preview(message)}")
                return
            }
            is SafetyVerdict.Allow -> { /* proceed to LLM */ }
        }

        // Step 5: Intent classification → retrieval mode.
        val retrievalMode = try {
            IntentClassifier.classify(message, bot)
        } catch (e: Exception) {
            Log.w(TAG, "IntentClassifier failed — defaulting to DIRECT", e)
            RetrievalMode.DIRECT
        }
        Log.i(TAG, "Retrieval mode for $convKey: $retrievalMode")

        // Step 6: Build LLM context + call LLMClient.
        val history = try {
            ConversationMemory.getHistoryForPrompt(convKey)
        } catch (e: Exception) {
            Log.w(TAG, "getHistoryForPrompt failed", e)
            ""
        }

        val retrievedContext = try {
            retrieveKnowledge(retrievalMode, bot, message)
        } catch (e: Exception) {
            Log.w(TAG, "retrieveKnowledge failed", e)
            ""
        }

        val replyText = try {
            LLMClient.chat(
                systemPrompt = bot.systemPrompt,
                history = history,
                userMessage = "$sender: $message",
                retrievalContext = retrievedContext,
                mode = retrievalMode,
            )
        } catch (e: Exception) {
            Log.e(TAG, "LLMClient.chat failed for $convKey", e)
            logAction(bot.id, "error", packageName, sender,
                "LLM failed: ${e.message}")
            return
        }

        if (replyText.isBlank()) {
            Log.w(TAG, "LLM returned empty reply — not sending")
            logAction(bot.id, "error", packageName, sender, "Empty LLM reply")
            return
        }

        // Step 7: Send reply via replyToNotification.
        // Set cooldown BEFORE sending so our own "reply sent" confirmation
        // notification doesn't trigger another reply (anti-loop).
        val cooldownKey = "$packageName:$sender"
        lastReplyTime[cooldownKey] = System.currentTimeMillis()

        val listener = ReplyAINotificationListener.instance
        if (listener == null) {
            Log.e(TAG, "NotificationListener instance is null — cannot reply")
            logAction(bot.id, "error", packageName, sender, "Listener not connected")
            return
        }

        val sent = try {
            listener.replyToNotification(packageName, replyText, sender)
        } catch (e: Exception) {
            Log.e(TAG, "replyToNotification threw", e)
            false
        }

        // Step 8: Save outgoing message + log to action_log.
        val sentAt = System.currentTimeMillis()
        try {
            if (sent) {
                ConversationMemory.addOutgoing(convKey, replyText)
                ReplyAIDatabase.saveOutgoingMessage(
                    bot.id, convKey, replyText, retrievalMode.name, sentAt)
                logAction(bot.id, "reply_sent", packageName, sender,
                    "Reply: ${preview(replyText)}")
                Log.i(TAG, "Reply sent to $convKey: '${preview(replyText)}'")
            } else {
                // Roll back the cooldown so the next incoming message gets a
                // real chance to trigger a reply (Aira v4.0.93 lesson).
                lastReplyTime[cooldownKey] = 0L
                logAction(bot.id, "error", packageName, sender,
                    "replyToNotification returned false")
                Log.w(TAG, "replyToNotification returned false for $convKey")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist outgoing/log for $convKey", e)
        }

        // Replay any queued messages whose cooldown has now expired.
        processPendingReplies()
    }

    /**
     * Replays queued messages whose cooldown has expired.
     *
     * Called after each successful reply (and can also be called periodically by
     * a WorkManager scheduler if we want eager replay). For each expired entry
     * we launch a coroutine that re-runs [processReply] for it.
     *
     * NOT a `suspend` function — it launches its own coroutines. This lets
     * non-suspend callers (e.g. a scheduler) invoke it directly.
     */
    fun processPendingReplies() {
        val toProcess: List<PendingReply>
        synchronized(pendingReplies) {
            if (pendingReplies.isEmpty()) return
            val now = System.currentTimeMillis()
            toProcess = pendingReplies.filter { p ->
                val key = "${p.packageName}:${p.sender}"
                now - (lastReplyTime[key] ?: 0L) >= COOLDOWN_MS
            }
            if (toProcess.isEmpty()) return
            pendingReplies.removeAll(toProcess)
        }
        Log.i(TAG, "Processing ${toProcess.size} pending reply(ies) from queue")
        for (pending in toProcess) {
            replayScope.launch {
                try {
                    val bots = findBotsForPackage(pending.packageName)
                    if (bots.isEmpty()) return@launch
                    val convKey = conversationKey(pending.packageName, pending.sender)
                    processReply(
                        pending.packageName,
                        pending.sender,
                        pending.message,
                        bots.first(),
                        convKey,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process pending reply for " +
                        "${pending.packageName}:${pending.sender}", e)
                }
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Add a message to the pending queue, combining with an existing entry for
     * the same `(packageName, sender)` if present (Aira v4.0.67 pattern).
     */
    private fun queuePending(packageName: String, sender: String, message: String, now: Long) {
        synchronized(pendingReplies) {
            val existing = pendingReplies.find {
                it.packageName == packageName && it.sender == sender
            }
            if (existing != null) {
                val idx = pendingReplies.indexOf(existing)
                pendingReplies[idx] = existing.copy(
                    message = "${existing.message}\n$message",
                    arrivedAt = now,
                )
                Log.i(TAG, "Combined pending message for $packageName:$sender " +
                    "(now '${preview(pendingReplies[idx].message)}')")
            } else {
                pendingReplies.add(PendingReply(packageName, sender, message, now))
                Log.i(TAG, "Queued new pending message for $packageName:$sender")
            }
        }
    }

    /**
     * Build a stable conversation key from package + sender.
     * Sanitizes whitespace + colons so the key is filesystem/db-safe.
     */
    private fun conversationKey(packageName: String, sender: String): String {
        val safeSender = if (sender.isBlank()) "unknown"
        else sender.replace(" ", "_").replace(":", "_").trim()
        return "$packageName:$safeSender"
    }

    /** Truncate a string for safe logging (avoids 10KB log lines). */
    private fun preview(s: String, max: Int = 80): String =
        if (s.length <= max) s else s.substring(0, max) + "..."

    /**
     * Find all enabled bots whose `targetApps` contains [packageName].
     * Wrapped in try/catch so a DB failure doesn't crash the listener.
     */
    private fun findBotsForPackage(packageName: String): List<Bot> {
        return try {
            ReplyAIDatabase.getEnabledBots().filter { packageName in it.targetApps }
        } catch (e: Exception) {
            Log.w(TAG, "findBotsForPackage failed for $packageName", e)
            emptyList()
        }
    }

    /**
     * Lightweight sync check used by [ReplyAINotificationListener] to skip the
     * coroutine launch when no bot is configured for a package. Keeps the
     * notification-posted thread fast.
     */
    fun hasBotForPackage(packageName: String): Boolean {
        return try {
            ReplyAIDatabase.getEnabledBots().any { packageName in it.targetApps }
        } catch (e: Exception) {
            // On DB failure, return false so we don't spin up coroutines that
            // would just error out. The next notification will retry.
            false
        }
    }

    /**
     * Retrieve knowledge for the LLM, based on the intent-classified mode.
     * Delegates to [KnowledgeManager] (built by the knowledge agent).
     * DIRECT mode skips retrieval entirely.
     */
    private suspend fun retrieveKnowledge(
        mode: RetrievalMode,
        bot: Bot,
        query: String,
    ): String {
        return when (mode) {
            RetrievalMode.DIRECT -> ""
            RetrievalMode.FTS5 -> KnowledgeManager.searchFts(bot.id, query)
            RetrievalMode.VECTOR -> KnowledgeManager.searchVector(bot.id, query)
            RetrievalMode.WEB_SEARCH -> KnowledgeManager.searchWeb(bot, query)
        }
    }

    /**
     * Escalate to the bot owner by sending an SMS with the offending message.
     * Uses [SafetyConfig.ownerPhone] as the destination. If no owner phone is
     * configured, the escalation is logged but no SMS is sent.
     *
     * Requires the `SEND_SMS` permission (already in the manifest).
     */
    private fun escalateToOwner(
        bot: Bot,
        packageName: String,
        sender: String,
        message: String,
        reason: String,
    ) {
        val ownerPhone = try {
            SafetyConfig.ownerPhone
        } catch (e: Exception) {
            Log.w(TAG, "SafetyConfig.ownerPhone unavailable", e)
            null
        }
        if (ownerPhone.isNullOrBlank()) {
            Log.w(TAG, "No owner phone configured — escalation SMS skipped " +
                "(reason: $reason)")
            return
        }
        try {
            val appLabel = NotificationAdapter.getDisplayName(packageName)
            val smsText = "ReplyAI escalation [$appLabel] from $sender: " +
                "${preview(message, 60)} (reason: $reason)"
            val mgr = SmsManager.getDefault()
            mgr.sendTextMessage(ownerPhone, null, smsText, null, null)
            Log.i(TAG, "Escalation SMS sent to $ownerPhone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send escalation SMS to $ownerPhone", e)
        }
    }

    /** Safe wrapper around `ReplyAIDatabase.logAction` (never throws). */
    private fun logAction(
        botId: Long,
        actionType: String,
        packageName: String,
        contact: String,
        summary: String,
    ) {
        try {
            ReplyAIDatabase.logAction(botId, actionType, packageName, contact, summary)
        } catch (e: Exception) {
            Log.w(TAG, "logAction failed (type=$actionType pkg=$packageName)", e)
        }
    }
}
