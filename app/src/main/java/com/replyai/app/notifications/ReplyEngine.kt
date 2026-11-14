package com.replyai.app.notifications

import android.content.Context
import android.content.SharedPreferences
import android.telephony.SmsManager
import android.util.Log
import com.replyai.app.util.LogCapture
import com.replyai.app.bots.Bot
import com.replyai.app.bots.BotManager
import com.replyai.app.data.ReplyAIDatabase
import com.replyai.app.embeddings.EmbeddingProviderFactory
import com.replyai.app.embeddings.VectorStore
import com.replyai.app.engine.ConversationMemory
import com.replyai.app.engine.IntentClassifier
import com.replyai.app.engine.WebSearchGrounding
import com.replyai.app.llm.LLMClient
import com.replyai.app.safety.SafetyRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ReplyEngine — the orchestrator.
 *
 * Called by [ReplyAINotificationListener.onNotificationPosted] (via a launched
 * coroutine) for every incoming message from a supported messaging app. It:
 *   1. Finds enabled bots targeting the app.
 *   2. Enforces a per-contact cooldown (5 min); queues messages that arrive
 *      during cooldown.
 *   3. Persists the incoming message to [ConversationMemory] (which writes
 *      both the in-memory cache AND the `messages` table in [ReplyAIDatabase]).
 *   4. Runs [SafetyRules] (quiet hours, rate limit, escalation keywords, spam).
 *   5. Asks [IntentClassifier] which retrieval mode to use.
 *   6. Builds an LLM prompt (system prompt + history + retrieved knowledge) and
 *      calls [LLMClient].
 *   7. Sends the LLM's reply via `ReplyAINotificationListener.replyToNotification`.
 *   8. Persists the outgoing message + writes to `action_log`.
 *
 * The bot/DB/engine classes used here were built by parallel agents and use
 * these ACTUAL signatures:
 *
 *   com.replyai.app.bots.BotManager   (object)
 *       fun listBots(context: Context): List<Bot>
 *
 *   com.replyai.app.data.ReplyAIDatabase   (singleton via getInstance())
 *       fun logAction(botId, actionType, app, contact, summary, details): Long
 *
 *   com.replyai.app.engine.ConversationMemory   (object)
 *       fun addIncoming(context, botId, contactKey, contactName, text)
 *       fun addOutgoing(context, botId, contactKey, text)
 *       fun getHistoryForPrompt(context, botId, contactKey, maxExchanges = 10): String
 *
 *   com.replyai.app.engine.IntentClassifier   (object)
 *       suspend fun classify(message: String, bot: Bot, context: Context?): RetrievalMode
 *       enum class RetrievalMode { DIRECT, FTS5, VECTOR, WEB_SEARCH }  (nested)
 *
 *   com.replyai.app.engine.WebSearchGrounding   (object)
 *       suspend fun searchSite(siteUrl: String, query: String): String?
 *       suspend fun fetchPageText(url: String): String?
 *
 *   com.replyai.app.embeddings.EmbeddingProviderFactory   (object)
 *       fun create(): EmbeddingProvider    // has suspend embed(text): FloatArray
 *
 *   com.replyai.app.embeddings.VectorStore   (object)
 *       fun searchSimilar(queryEmbedding: FloatArray, botId: Long, limit: Int = 5): List<SearchResult>
 *
 *   com.replyai.app.llm.LLMClient   (class — constructed with context)
 *       suspend fun chat(userMessage, systemPrompt, tools, toolFilter): LLMResponse
 *
 *   com.replyai.app.safety.SafetyRules   (object)
 *       fun isQuietHours(bot, now = LocalDateTime.now()): Boolean
 *       fun isRateLimited(bot, contactKey, repliesToday): Boolean
 *       fun shouldEscalate(message): Boolean
 *       fun isSpam(message): Boolean
 */
object ReplyEngine {

    private const val TAG = "ReplyEngine"

    /**
     * Cooldown window: don't reply to the same contact twice within this period.
     *
     * Why 5 seconds:
     *   - The dedup (seenNotificationHashes) handles duplicate NOTIFICATION POSTS
     *     (WhatsApp fires onNotificationPosted 3-4× per real message, 1-3s apart).
     *   - The title='You' skip in NotificationListener handles the self-reply loop.
     *   - This cooldown is ONLY a safety net for true same-message double-sends
     *     (user double-taps send, or a spammer hits the same message rapidly).
     *   - 5s is short enough to NOT block legitimate follow-up messages.
     *
     * The "reply in progress" lock ([repliesInProgress]) handles the case where
     * a follow-up message arrives WHILE the LLM is preparing a reply for the
     * same contact. That message gets queued + processed immediately when the
     * in-flight reply completes — no 5s wait.
     */
    private const val COOLDOWN_MS = 5_000L  // 5 seconds (anti-spam safety net only)

    /**
     * Per-contact "reply in progress" tracking.
     * Key: `"com.whatsapp:Mom"`. Value: present while a reply is being prepared/sent.
     *
     * This is the PRIMARY mechanism for handling concurrent messages from the
     * same sender. When a reply is in progress:
     *   - New messages from the SAME sender → queued (combined with existing)
     *   - New messages from DIFFERENT senders → processed independently
     *
     * When the in-flight reply completes, [processPendingReplies] is called
     * immediately — no cooldown wait. The queued message gets processed right
     * away with full conversation context (the just-sent reply is in history).
     */
    private val repliesInProgress = mutableSetOf<String>()

    /**
     * LLM serialization Mutex — ensures only ONE LLM call runs at a time.
     *
     * Why: when 10-20 notifications arrive at once (airplane mode recovery,
     * group chat burst), launching 10-20 concurrent LLM calls:
     *   1. Hits Gemini/OpenAI free-tier rate limits (15 RPM) → most fail
     *   2. Wastes paid-tier budget on parallel calls
     *   3. Sends 10-20 replies in rapid succession → looks spammy → WhatsApp
     *      might flag the account
     *
     * Serializing LLM calls naturally spaces out replies (~1.5s each = 10
     * replies spread over 15s, not all at once). This is gentler on the LLM
     * provider + gentler on WhatsApp's anti-spam heuristics.
     *
     * The Mutex is global (not per-contact) — even different contacts' replies
     * are serialized. This is intentional: rate limits are per-account, not
     * per-conversation.
     */
    private val llmMutex = Mutex()

    /**
     * Burst detection — tracks how many notifications arrived in the last
     * BURST_WINDOW_MS. If > BURST_THRESHOLD, we're in "burst mode" (airplane
     * mode recovery, group chat burst, etc.).
     *
     * In burst mode, we add a small delay (REPLY_SPACING_MS) between replies
     * to avoid spamming recipients. Normal mode has no delay.
     */
    private const val BURST_WINDOW_MS = 10_000L  // 10 seconds
    private const val BURST_THRESHOLD = 3         // 3+ notifications in 10s = burst
    private const val REPLY_SPACING_MS = 2000L   // 2s between replies in burst mode
    private val recentNotificationTimes = mutableListOf<Long>()

    /**
     * Notification-key dedup window.
     *
     * WhatsApp fires `onNotificationPosted` 3-4× per real message (summary,
     * per-conversation, group refresh, badge update). Each fires a separate
     * handleIncoming coroutine. Without dedup they all race past the cooldown
     * check before any of them sets it, producing "4 replies for 1 message".
     *
     * Fix: hash(package + sender + message) tracked for DEDUP_WINDOW_MS.
     * Duplicate hashes are skipped. Complements the per-contact cooldown.
     */
    private const val DEDUP_WINDOW_MS = 10_000L  // 10 seconds

    /** Recently-seen notification hashes → timestamp. */
    private val seenNotificationHashes = mutableMapOf<Int, Long>()

    /** SharedPreferences file + key for the optional owner-phone escalation target. */
    private const val PREFS_NAME = "replyai_settings"
    private const val KEY_OWNER_PHONE = "owner_phone"

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

    /** Application context — set once via [init], then used by every helper. */
    @Volatile
    private var appContext: Context? = null

    /**
     * One-time initialization. Call from [com.replyai.app.ReplyAIApp.onCreate]
     * (or any other entry point) before the notification listener fires.
     */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ctx(): Context =
        appContext ?: error("ReplyEngine.init(context) must be called first")

    /**
     * Main entry point. Called by the NotificationListener in a coroutine.
     *
     * @param packageName source app (e.g. "com.whatsapp")
     * @param sender      sender title extracted from the notification
     * @param message     message body extracted from the notification
     */
    suspend fun handleIncoming(packageName: String, sender: String, message: String) {
        val now = System.currentTimeMillis()

        // Step 0 (DEDUP): Block the same notification from being processed twice.
        // WhatsApp fires onNotificationPosted 3-4× per real message (summary,
        // per-conversation, group refresh, badge update). Each one launches a
        // separate coroutine here; without dedup they all pass the cooldown
        // check before any of them sets it, producing "4 replies for 1 message".
        //
        // Key is a hash of (package + sender + message). If we've seen this
        // exact notification within DEDUP_WINDOW_MS, skip it silently.
        val notifHash = "$packageName|$sender|$message".hashCode()
        synchronized(seenNotificationHashes) {
            val lastSeen = seenNotificationHashes[notifHash]
            if (lastSeen != null && now - lastSeen < DEDUP_WINDOW_MS) {
                LogCapture.d(TAG, "Dedup: skipping duplicate notification from " +
                    "$packageName/$sender (seen ${now - lastSeen}ms ago)")
                // Periodic cleanup: prune stale entries (older than 2× window)
                if (seenNotificationHashes.size > 100) {
                    val cutoff = now - (DEDUP_WINDOW_MS * 2)
                    seenNotificationHashes.entries.removeAll { it.value < cutoff }
                }
                return
            }
            seenNotificationHashes[notifHash] = now
        }

        // Track notification timing for burst detection.
        // If 3+ notifications arrive within 10s, we're in "burst mode"
        // (airplane mode recovery, group chat). Burst mode adds a 2s delay
        // between replies to avoid spamming recipients.
        val isBurst = synchronized(recentNotificationTimes) {
            recentNotificationTimes.add(now)
            val cutoff = now - BURST_WINDOW_MS
            recentNotificationTimes.removeAll { it < cutoff }
            recentNotificationTimes.size > BURST_THRESHOLD
        }
        if (isBurst) {
            LogCapture.i(TAG, "Burst mode: ${recentNotificationTimes.size} notifications in ${BURST_WINDOW_MS / 1000}s — spacing replies")
        }

        val convKey = conversationKey(packageName, sender)
        LogCapture.i(TAG, "Incoming from $convKey: '${preview(message)}'")

        // Step 1: Find enabled bots whose target_apps contains packageName.
        val bots = findBotsForPackage(packageName)
        if (bots.isEmpty()) {
            LogCapture.d(TAG, "No enabled bot configured for $packageName — ignoring")
            return
        }
        // v1.0: first matching bot wins. (Multi-bot-per-app is a future enhancement.)
        val bot = bots.first()

        // Step 2: Concurrency control — check if a reply is already in progress
        // for this contact. If so, queue this message (it'll be processed
        // immediately when the in-flight reply completes, with full context).
        //
        // This is the PRIMARY concurrency mechanism:
        //   - Different sender → repliesInProgress doesn't have this key → process independently
        //   - Same sender, reply in flight → queue (combined with existing pending)
        //   - Same sender, no reply in flight → proceed
        //
        // Also check the anti-spam cooldown (5s) as a safety net for true
        // same-message double-sends (user double-taps send button).
        val lockKey = "$packageName:$sender"
        val shouldQueue: Boolean
        synchronized(repliesInProgress) {
            val inProgress = lockKey in repliesInProgress
            val now2 = System.currentTimeMillis()
            val lastReply = lastReplyTime[lockKey] ?: 0L
            val inCooldown = now2 - lastReply < COOLDOWN_MS
            shouldQueue = inProgress || inCooldown
            if (inProgress) {
                LogCapture.i(TAG, "Reply in progress for $lockKey — queuing")
            } else if (inCooldown) {
                val remainingSec = (COOLDOWN_MS - (now2 - lastReply)) / 1000
                LogCapture.i(TAG, "Anti-spam cooldown for $lockKey (${remainingSec}s left) — queuing")
            }
        }
        if (shouldQueue) {
            queuePending(packageName, sender, message, now)
            return
        }

        // Mark this contact as "reply in progress" so concurrent messages queue.
        // Released in processReply's finally block.
        synchronized(repliesInProgress) { repliesInProgress.add(lockKey) }
        LogCapture.d(TAG, "Lock acquired for $lockKey")

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
        val lockKey = "$packageName:$sender"
        try {
            processReplyInternal(packageName, sender, message, bot, convKey)
        } finally {
            // ALWAYS release the in-progress lock + set anti-spam cooldown,
            // even if the reply failed (safety rule block, LLM error, etc.).
            // This prevents a stuck lock that would queue all future messages.
            synchronized(repliesInProgress) {
                repliesInProgress.remove(lockKey)
            }
            lastReplyTime[lockKey] = System.currentTimeMillis()
            LogCapture.d(TAG, "Lock released for $lockKey")
            // Process any messages that were queued while we were busy.
            processPendingReplies()
        }
    }

    private suspend fun processReplyInternal(
        packageName: String,
        sender: String,
        message: String,
        bot: Bot,
        convKey: String,
    ) {
        val context = ctx()

        // Step 3: Save incoming message to ConversationMemory (which writes both
        // the in-memory cache and the `messages` table in ReplyAIDatabase).
        try {
            ConversationMemory.addIncoming(context, bot.id, convKey, sender, message)
        } catch (e: Exception) {
            LogCapture.w(TAG, "Failed to persist incoming message for $convKey", e)
        }

        // Step 4: Safety rules (quiet hours, rate limit, escalation, spam).
        //   - isQuietHours  → skip + log
        //   - isRateLimited  → skip + log
        //   - shouldEscalate → escalate to owner + skip
        //   - isSpam         → skip silently
        try {
            if (SafetyRules.isSpam(message)) {
                LogCapture.i(TAG, "Blocked by safety rules: spam detected")
                logAction(bot.id, "blocked", packageName, sender, "Blocked: spam")
                return
            }
            if (SafetyRules.shouldEscalate(message)) {
                val reason = "Escalation keywords detected"
                LogCapture.w(TAG, "Escalated by safety rules: $reason")
                escalateToOwner(bot, packageName, sender, message, reason)
                logAction(bot.id, "escalated", packageName, sender,
                    "Escalated: $reason | msg: ${preview(message)}")
                return
            }
            if (SafetyRules.isQuietHours(bot)) {
                val reason = "Quiet hours"
                LogCapture.i(TAG, "Blocked by safety rules: $reason")
                logAction(bot.id, "blocked", packageName, sender, "Blocked: $reason")
                return
            }
            // Rate-limit check — count today's outgoing replies for this contact.
            val repliesToday = countRepliesToday(bot.id, convKey)
            if (SafetyRules.isRateLimited(bot, convKey, repliesToday)) {
                val reason = "Daily reply cap reached ($repliesToday/${bot.dailyMessageCap})"
                LogCapture.i(TAG, "Blocked by safety rules: $reason")
                logAction(bot.id, "blocked", packageName, sender, "Blocked: $reason")
                return
            }
        } catch (e: Exception) {
            LogCapture.w(TAG, "SafetyRules check failed — defaulting to Allow", e)
            // Fall through to LLM call.
        }

        // Step 5: Intent classification → retrieval mode.
        val retrievalMode = try {
            IntentClassifier.classify(message, bot, context)
        } catch (e: Exception) {
            LogCapture.w(TAG, "IntentClassifier failed — defaulting to DIRECT", e)
            IntentClassifier.RetrievalMode.DIRECT
        }
        LogCapture.i(TAG, "Retrieval mode for $convKey: $retrievalMode")

        // Step 6: Build LLM context + call LLMClient.
        val history = try {
            ConversationMemory.getHistoryForPrompt(context, bot.id, convKey)
        } catch (e: Exception) {
            LogCapture.w(TAG, "getHistoryForPrompt failed", e)
            ""
        }

        val retrievedContext = try {
            retrieveKnowledge(retrievalMode, bot, message)
        } catch (e: Exception) {
            LogCapture.w(TAG, "retrieveKnowledge failed", e)
            ""
        }

        val systemPrompt = buildSystemPrompt(bot, history, retrievedContext)
        val llm = LLMClient(context)

        // Serialize LLM calls via a global Mutex. This prevents 10-20 concurrent
        // LLM calls when notifications burst in (airplane mode recovery).
        // Benefits:
        //   1. Respects LLM provider rate limits (Gemini 15 RPM free tier)
        //   2. Spaces out replies (1.5s each, not all at once) — gentler on
        //      WhatsApp's anti-spam heuristics
        //   3. One failure doesn't cascade (each call is independent)
        val response = try {
            llmMutex.withLock {
                // In burst mode, add a small delay before the LLM call to
                // space out replies. This is in addition to the Mutex
                // serialization (which already ensures one-at-a-time).
                if (isBurstMode()) {
                    LogCapture.d(TAG, "Burst mode: waiting ${REPLY_SPACING_MS}ms before LLM call for $convKey")
                    delay(REPLY_SPACING_MS)
                }
                llm.chat(
                    userMessage = "$sender: $message",
                    systemPrompt = systemPrompt,
                )
            }
        } catch (e: Exception) {
            LogCapture.e(TAG, "LLMClient.chat failed for $convKey", e)
            logAction(bot.id, "error", packageName, sender, "LLM failed: ${e.message}")
            return
        }
        val replyText = response.reply

        if (replyText.isBlank()) {
            LogCapture.w(TAG, "LLM returned empty reply — not sending")
            logAction(bot.id, "error", packageName, sender, "Empty LLM reply")
            return
        }

        // Step 7: Send reply via replyToNotification.
        val listener = ReplyAINotificationListener.instance
        if (listener == null) {
            LogCapture.e(TAG, "NotificationListener instance is null — cannot reply")
            logAction(bot.id, "error", packageName, sender, "Listener not connected")
            return
        }

        val sent = try {
            listener.replyToNotification(packageName, replyText, sender)
        } catch (e: Exception) {
            LogCapture.e(TAG, "replyToNotification threw", e)
            false
        }

        // Step 8: Save outgoing message + log to action_log.
        try {
            if (sent) {
                ConversationMemory.addOutgoing(context, bot.id, convKey, replyText)
                logAction(bot.id, "reply_sent", packageName, sender,
                    "Reply (${retrievalMode.name}): ${preview(replyText)}")
                LogCapture.i(TAG, "Reply sent to $convKey: '${preview(replyText)}'")
            } else {
                logAction(bot.id, "error", packageName, sender,
                    "replyToNotification returned false")
                LogCapture.w(TAG, "replyToNotification returned false for $convKey")
            }
        } catch (e: Exception) {
            LogCapture.w(TAG, "Failed to persist outgoing/log for $convKey", e)
        }
        // processPendingReplies() is called by the finally block in processReply wrapper.
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
            // A pending reply is ready to process if:
            //   - No reply is currently in progress for that contact (lock released)
            //   - The anti-spam cooldown has expired (5s safety net)
            // The in-progress lock is the primary check — when a reply completes,
            // the lock is released + processPendingReplies is called, so queued
            // messages process IMMEDIATELY (no cooldown wait).
            synchronized(repliesInProgress) {
                toProcess = pendingReplies.filter { p ->
                    val key = "${p.packageName}:${p.sender}"
                    val inProgress = key in repliesInProgress
                    val inCooldown = now - (lastReplyTime[key] ?: 0L) < COOLDOWN_MS
                    !inProgress && !inCooldown
                }
            }
            if (toProcess.isEmpty()) return
            pendingReplies.removeAll(toProcess)
        }
        LogCapture.i(TAG, "Processing ${toProcess.size} pending reply(ies) from queue")
        for (pending in toProcess) {
            replayScope.launch {
                try {
                    val bots = findBotsForPackage(pending.packageName)
                    if (bots.isEmpty()) return@launch
                    val convKey = conversationKey(pending.packageName, pending.sender)
                    // Acquire the lock before processing (handleIncoming normally
                    // does this, but we're bypassing it for queued messages).
                    val lockKey = "${pending.packageName}:${pending.sender}"
                    synchronized(repliesInProgress) { repliesInProgress.add(lockKey) }
                    processReply(
                        pending.packageName,
                        pending.sender,
                        pending.message,
                        bots.first(),
                        convKey,
                    )
                } catch (e: Exception) {
                    LogCapture.e(TAG, "Failed to process pending reply for " +
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
                LogCapture.i(TAG, "Combined pending message for $packageName:$sender " +
                    "(now '${preview(pendingReplies[idx].message)}')")
            } else {
                pendingReplies.add(PendingReply(packageName, sender, message, now))
                LogCapture.i(TAG, "Queued new pending message for $packageName:$sender")
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
     * Check if we're currently in "burst mode" — 3+ notifications in the last
     * 10 seconds. Used to decide whether to add a delay between replies.
     */
    private fun isBurstMode(): Boolean {
        val now = System.currentTimeMillis()
        return synchronized(recentNotificationTimes) {
            val cutoff = now - BURST_WINDOW_MS
            recentNotificationTimes.removeAll { it < cutoff }
            recentNotificationTimes.size > BURST_THRESHOLD
        }
    }

    /**
     * Find all enabled bots whose `targetApps` contains [packageName].
     * Wrapped in try/catch so a DB failure doesn't crash the listener.
     */
    private fun findBotsForPackage(packageName: String): List<Bot> {
        return try {
            BotManager.listBots(ctx()).filter { it.enabled && packageName in it.targetApps }
        } catch (e: Exception) {
            LogCapture.w(TAG, "findBotsForPackage failed for $packageName", e)
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
            if (appContext == null) return false
            BotManager.listBots(appContext!!).any {
                it.enabled && packageName in it.targetApps
            }
        } catch (e: Exception) {
            // On DB failure, return false so we don't spin up coroutines that
            // would just error out. The next notification will retry.
            false
        }
    }

    /**
     * Retrieve knowledge for the LLM, based on the intent-classified mode.
     *
     *   DIRECT      → no retrieval.
     *   FTS5         → keyword search via [ReplyAIDatabase.searchChunksFts].
     *   VECTOR       → semantic search via [EmbeddingProviderFactory] + [VectorStore].
     *   WEB_SEARCH   → site-scoped Google SERP via [WebSearchGrounding].
     */
    private suspend fun retrieveKnowledge(
        mode: IntentClassifier.RetrievalMode,
        bot: Bot,
        query: String,
    ): String {
        return when (mode) {
            IntentClassifier.RetrievalMode.DIRECT -> ""
            IntentClassifier.RetrievalMode.FTS5 -> {
                val db = ReplyAIDatabase.getInstance()
                db.searchChunksFts(query, limit = 5)
                    .joinToString("\n\n") { (chunk, fileName) ->
                        "[$fileName] ${chunk.text}"
                    }
                    .ifEmpty { "" }
            }
            IntentClassifier.RetrievalMode.VECTOR -> {
                val provider = EmbeddingProviderFactory.create()
                val queryEmb = provider.embed(query)
                val results = VectorStore.searchSimilar(queryEmb, bot.id, limit = 5)
                results.joinToString("\n\n") { "[${it.fileName}] ${it.text}" }
                    .ifEmpty { "" }
            }
            IntentClassifier.RetrievalMode.WEB_SEARCH -> {
                val site = bot.companyUrl ?: return ""
                WebSearchGrounding.searchSite(site, query) ?: ""
            }
        }
    }

    /**
     * Build the system prompt for the LLM by combining the bot's base prompt +
     * personality + history + retrieved knowledge.
     */
    private fun buildSystemPrompt(
        bot: Bot,
        history: String,
        retrievedContext: String,
    ): String {
        val sb = StringBuilder()
        sb.append(bot.systemPrompt.trim())
        sb.append("\n\n")
        if (!bot.personality.isNullOrBlank()) {
            sb.append("Personality: ").append(bot.personality.trim()).append("\n\n")
        }
        sb.append(
            "You are an auto-reply bot running on the user's Android phone. " +
                "Reply concisely (1-3 sentences). Never invent contact names, " +
                "phone numbers, or URLs. If you don't know, say so.\n",
        )
        if (history.isNotBlank() && history != "(no previous conversation)") {
            sb.append("\nPrevious conversation:\n").append(history).append('\n')
        }
        if (retrievedContext.isNotBlank()) {
            sb.append("\nRelevant knowledge:\n").append(retrievedContext).append('\n')
        }
        return sb.toString()
    }

    /**
     * Count today's outgoing replies for (botId, contactKey) — used for the
     * per-contact daily rate-limit check. Reads directly from the messages
     * table in [ReplyAIDatabase] via the conversations lookup.
     *
     * Defensive: returns 0 on any failure so the rate limiter doesn't block
     * forever on a DB error.
     */
    private fun countRepliesToday(botId: Long, contactKey: String): Int {
        return try {
            val db = ReplyAIDatabase.getInstance()
            val conv = db.getConversation(botId, contactKey)
            val msgs = db.getConversationMessages(conv.id, limit = 100)
            val startOfDay = startOfTodayMillis()
            msgs.count { it.direction == "outgoing" && it.timestamp >= startOfDay }
        } catch (e: Exception) {
            LogCapture.w(TAG, "countRepliesToday failed — assuming 0", e)
            0
        }
    }

    private fun startOfTodayMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Escalate to the bot owner by sending an SMS with the offending message.
     * Uses the `owner_phone` SharedPreferences key as the destination. If no
     * owner phone is configured, the escalation is logged but no SMS is sent.
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
            prefs().getString(KEY_OWNER_PHONE, null)
        } catch (e: Exception) {
            LogCapture.w(TAG, "owner_phone unavailable", e)
            null
        }
        if (ownerPhone.isNullOrBlank()) {
            LogCapture.w(TAG, "No owner phone configured — escalation SMS skipped " +
                "(reason: $reason)")
            return
        }
        try {
            val appLabel = NotificationAdapter.getDisplayName(packageName)
            val smsText = "ReplyAI escalation [$appLabel] from $sender: " +
                "${preview(message, 60)} (reason: $reason)"
            val mgr = SmsManager.getDefault()
            mgr.sendTextMessage(ownerPhone, null, smsText, null, null)
            LogCapture.i(TAG, "Escalation SMS sent to $ownerPhone")
        } catch (e: Exception) {
            LogCapture.e(TAG, "Failed to send escalation SMS to $ownerPhone", e)
        }
    }

    private fun prefs(): SharedPreferences =
        ctx().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Safe wrapper around `ReplyAIDatabase.logAction` (never throws). */
    private fun logAction(
        botId: Long,
        actionType: String,
        packageName: String,
        contact: String,
        summary: String,
    ) {
        try {
            ReplyAIDatabase.getInstance()
                .logAction(botId, actionType, packageName, contact, summary, null)
        } catch (e: Exception) {
            LogCapture.w(TAG, "logAction failed (type=$actionType pkg=$packageName)", e)
        }
    }
}
