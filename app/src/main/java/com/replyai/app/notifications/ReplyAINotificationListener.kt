package com.replyai.app.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.replyai.app.util.LogCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ReplyAINotificationListener — the system-bound NotificationListenerService
 * that receives every posted notification and (for supported messaging apps)
 * forwards the message to [ReplyEngine] for an auto-reply decision.
 *
 * Adapted from Aira's `com.ai.agent.rules.NotificationListener` (v4.0.67+):
 *   - `onNotificationPosted` filters by package, extracts title+text, then
 *     invokes `ReplyEngine.handleIncoming` in a coroutine.
 *   - `replyToNotification` finds the notification's RemoteInput reply action
 *     and sends the reply text via `RemoteInput.addResultsToIntent`.
 *   - Per-contact cooldown + pending-reply queue live in [ReplyEngine], not here.
 *
 * Lifecycle notes:
 *   - `instance` is set in `onListenerConnected` / cleared in `onListenerDisconnected`,
 *     so `ReplyEngine` can reach the live listener to send replies.
 *   - On disconnect we proactively `requestRebind` so the system reconnects us
 *     after app updates / OOM kills (the official keep-alive API since API 24).
 *
 * This class is declared in `AndroidManifest.xml` with the
 * `BIND_NOTIFICATION_LISTENER_SERVICE` permission + the standard intent-filter.
 * The user must grant notification access in system Settings before it connects.
 */
class ReplyAINotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "ReplyAIListener"

        /**
         * The currently-connected listener instance, or `null` if the listener
         * is not bound. [ReplyEngine] reads this to call [replyToNotification].
         */
        @Volatile
        var instance: ReplyAINotificationListener? = null
            private set

        /**
         * Messaging packages we auto-reply to. A notification from any other
         * package is silently ignored (we don't want to "reply" to Gmail or
         * a game push, even if a bot is misconfigured).
         *
         * NOTE: `com.instagram.android` is intentionally NOT in this list —
         * Instagram DM notifications generally don't expose a RemoteInput reply
         * action, so auto-reply wouldn't work anyway. It IS in
         * [NotificationAdapter] for nice icon labelling if it ever shows up.
         */
        private val SUPPORTED_PACKAGES: Set<String> = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.telegram",
            "com.facebook.orca",
            "com.androidmessaging",
            "com.slack",
            "com.discord",
        )

        /**
         * Coroutine scope for dispatching into [ReplyEngine]. Uses a
         * [SupervisorJob] so one failed reply doesn't cancel sibling coroutines.
         */
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        LogCapture.i(TAG, "Connected — ready to receive notifications")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        LogCapture.w(TAG, "Disconnected — requesting rebind")
        // Proactively ask the system to reconnect us. Android sometimes
        // unbinds the listener (app update, OOM, settings change); this is
        // the official keep-alive API since API 24.
        try {
            requestRebind(ComponentName(this, ReplyAINotificationListener::class.java))
        } catch (e: Exception) {
            LogCapture.e(TAG, "requestRebind failed", e)
        }
    }

    /**
     * Handle a newly-posted notification.
     *
     * Pipeline:
     *   1. Skip if not from a supported messaging app.
     *   2. Extract `title` (sender) + `text` (message body) from the notification extras.
     *   3. Skip if both are empty (e.g. a "you have 3 unread" summary notification).
     *   4. Skip if no bot is configured for this package (cheap sync DB check).
     *   5. Launch a coroutine → `ReplyEngine.handleIncoming(packageName, title, text)`.
     *
     * CRITICAL: `ReplyEngine.handleIncoming` is `suspend` (it does LLM I/O),
     * so it MUST be invoked from a coroutine — never directly on the
     * notification-posted thread (which is the main thread).
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: return
        if (packageName !in SUPPORTED_PACKAGES) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        // Some apps post summary notifications ("3 new messages") with no body.
        // Skip those — there's no specific message to reply to.
        if (title.isEmpty() && text.isEmpty()) return

        // CRITICAL: Skip OUTGOING message notifications.
        // WhatsApp marks messages sent by the user (i.e., our own auto-replies)
        // with title="You". If we don't skip these, ReplyAI sees its own reply
        // as an incoming message → replies to it → that reply becomes another
        // "You" notification → infinite self-reply loop.
        //
        // This was the root cause of the "4 replies for 1 message" bug in the
        // user's testing. The dedup fixed duplicate INCOMING notifications, but
        // the self-reply loop was a separate issue.
        if (title.equals("You", ignoreCase = true)) {
            LogCapture.d(TAG, "Skipping outgoing message notification (title='You')")
            return
        }

        // CRITICAL: Skip summary/counter notifications.
        // WhatsApp posts "N new messages" notifications that have no real
        // message content — they're just unread counters. Replying to them
        // sends a reply to the conversation list, not a specific message.
        if (text.matches(Regex("""\d+\s+new messages?""", RegexOption.IGNORE_CASE))) {
            LogCapture.d(TAG, "Skipping summary notification: '$text'")
            return
        }

        // CRITICAL: Skip stale notifications (airplane mode recovery).
        // When the phone reconnects after hours offline, WhatsApp delivers all
        // queued notifications at once. A message from 5 hours ago like "are
        // you free tonight?" doesn't need a reply at 3am.
        //
        // sbn.postTime is when the notification was originally posted (epoch ms).
        // We skip if older than MAX_NOTIFICATION_AGE_HOURS (default 2h).
        val postTime = sbn.postTime
        val ageMs = System.currentTimeMillis() - postTime
        val maxAgeMs = 2 * 60 * 60 * 1000L  // 2 hours
        if (ageMs > maxAgeMs) {
            val ageHours = ageMs / (60 * 60 * 1000)
            LogCapture.i(TAG, "Skipping stale notification from $packageName/$title " +
                "(${ageHours}h old — posted at ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(postTime))})")
            return
        }

        LogCapture.d(TAG, "Posted from $packageName: title='$title' text='${take(text, 80)}'" +
            (if (ageMs > 60_000) " (${ageMs / 60_000}min old)" else ""))

        // Cheap sync check: only launch the coroutine if a bot is configured.
        // Avoids waking up ReplyEngine for every WhatsApp notification when the
        // user hasn't set up any bots yet.
        if (!ReplyEngine.hasBotForPackage(packageName)) {
            LogCapture.d(TAG, "No bot configured for $packageName — skipping")
            return
        }

        // Dispatch to ReplyEngine on a background coroutine.
        engineScope.launch {
            try {
                ReplyEngine.handleIncoming(packageName, title, text)
            } catch (e: Exception) {
                LogCapture.e(TAG, "ReplyEngine.handleIncoming failed for $packageName", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // No-op for now — pending-reply queue in ReplyEngine handles replay.
    }

    /**
     * Reply to a notification via RemoteInput.
     *
     * Adapted from Aira's `NotificationListener.replyToNotification` (line 412).
     *
     * Finds the reply action in [activeNotifications] for the given package and
     * sends [replyText] through its RemoteInput result key. If [senderName] is
     * provided, only notifications whose title/text contain that sender are
     * targeted — this lets us reply to a SPECIFIC conversation rather than the
     * most-recent one (the Aira v4.0.67 per-contact fix).
     *
     * @param packageName  target app package (e.g. "com.whatsapp")
     * @param replyText    the text to send as a reply
     * @param senderName   optional sender name to target a specific conversation;
     *                      if `null`/blank, the first matching notification is used
     * @return `true` if the reply was sent successfully, `false` otherwise
     *         (no active notification, no reply action, or an exception)
     */
    fun replyToNotification(packageName: String, replyText: String, senderName: String? = null): Boolean {
        try {
            val allNotifs = activeNotifications ?: return false
            LogCapture.d(TAG, "replyToNotification: ${allNotifs.size} total active notifications, looking for $packageName" +
                (if (!senderName.isNullOrBlank()) " sender='$senderName'" else ""))

            var matchingNotifs = allNotifs.filter { it.packageName == packageName }
            if (matchingNotifs.isEmpty()) {
                LogCapture.w(TAG, "No active notifications from $packageName (total active: ${allNotifs.size}, packages: ${allNotifs.map { it.packageName }.distinct()})")
                return false
            }

            // Log ALL notifications from this package with their action details for debugging
            for (sbn in matchingNotifs) {
                val n = sbn.notification
                val title = n?.extras?.getString(Notification.EXTRA_TITLE)?.toString() ?: ""
                @Suppress("DEPRECATION")
                val actionCount = n?.actions?.size ?: -1
                val hasRemoteInputs = n?.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
                LogCapture.d(TAG, "  Notif: title='$title' actions=$actionCount hasReplyAction=$hasRemoteInputs")
            }

            // If a sender name was provided, narrow to that sender's notification.
            if (!senderName.isNullOrBlank()) {
                val senderNotifs = matchingNotifs.filter { sbn ->
                    val title = sbn.notification?.extras
                        ?.getString(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val body = sbn.notification?.extras
                        ?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                    title.contains(senderName, ignoreCase = true) ||
                        body.contains(senderName, ignoreCase = true)
                }
                if (senderNotifs.isNotEmpty()) {
                    LogCapture.i(TAG, "Found ${senderNotifs.size} notification(s) from '$senderName' in $packageName")
                    matchingNotifs = senderNotifs
                } else {
                    // Fallback: reply to the first available notification rather
                    // than silently dropping the reply. (Better than no reply.)
                    LogCapture.w(TAG, "No notification from '$senderName' in $packageName — trying all ${matchingNotifs.size} notifications from this package")
                }
            }

            // Find the first notification that has a RemoteInput reply action.
            // Try sender-filtered first, then fall back to ALL notifications from
            // this package (MIUI sometimes strips reply actions from some
            // notifications but not others).
            val triedNotifs = mutableListOf<StatusBarNotification>()
            for (sbn in matchingNotifs) {
                triedNotifs.add(sbn)
                if (tryReplyOnNotification(sbn, packageName, replyText, senderName)) {
                    return true
                }
            }

            // Fallback: if sender-filtered notifications had no reply action,
            // try ALL notifications from this package (the reply action might
            // be on a different notification — e.g., a summary notification on MIUI).
            if (!senderName.isNullOrBlank() && matchingNotifs != allNotifs.filter { it.packageName == packageName }) {
                val allFromPackage = allNotifs.filter { it.packageName == packageName }
                for (sbn in allFromPackage) {
                    if (sbn in triedNotifs) continue
                    LogCapture.i(TAG, "Fallback: trying other notification from $packageName")
                    if (tryReplyOnNotification(sbn, packageName, replyText, null)) {
                        return true
                    }
                }
            }

            LogCapture.w(TAG, "No reply action found in $packageName notifications (checked ${triedNotifs.size} notif(s), none had RemoteInput action)")
            return false
        } catch (e: Exception) {
            LogCapture.e(TAG, "Failed to reply to notification", e)
            return false
        }
    }

    /**
     * Try to find a RemoteInput reply action on a single notification + send the reply.
     * Returns true if the reply was sent.
     */
    private fun tryReplyOnNotification(
        sbn: StatusBarNotification,
        packageName: String,
        replyText: String,
        senderName: String?,
    ): Boolean {
        val notification = sbn.notification ?: return false
        @Suppress("DEPRECATION")
        val actions = notification.actions ?: run {
            LogCapture.d(TAG, "  Notification has null actions array")
            return false
        }
        if (actions.isEmpty()) {
            LogCapture.d(TAG, "  Notification has 0 actions")
            return false
        }

        for (action in actions) {
            val remoteInputs = action.remoteInputs
            if (remoteInputs.isNullOrEmpty()) {
                LogCapture.d(TAG, "  Action '${action.title}' has no remoteInputs")
                continue
            }

            LogCapture.i(TAG, "Found reply action in $packageName notification (action title: '${action.title}')")

            val replyIntent = Intent().apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            val results = Bundle()
            for (remoteInput in remoteInputs) {
                results.putCharSequence(remoteInput.resultKey, replyText)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.RemoteInput.addResultsToIntent(remoteInputs, replyIntent, results)
            }

            action.actionIntent?.send(this, 0, replyIntent)
            LogCapture.i(TAG, "Replied to $packageName" +
                (if (!senderName.isNullOrBlank()) " ($senderName)" else "") +
                ": ${take(replyText, 80)}")
            return true
        }
        LogCapture.d(TAG, "  Notification has ${actions.size} action(s) but none with remoteInputs")
        return false
    }

    private fun take(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max) + "..."
}
