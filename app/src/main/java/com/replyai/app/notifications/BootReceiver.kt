package com.replyai.app.notifications

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * BootReceiver — re-binds the [ReplyAINotificationListener] after the phone
 * reboots.
 *
 * A `NotificationListenerService` is system-bound: you cannot `startService()`
 * it directly. After a reboot the system will only re-bind the listener IF it
 * was still enabled in `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`. Even
 * then, some OEMs (MIUI, EMUI) lazy-bind it and only fire on the first
 * notification — which is too late for the user's first incoming message.
 *
 * So on `BOOT_COMPLETED` we call
 * [NotificationListenerService.requestRebind] — the public-API equivalent of
 * the (internal) `toggleNotificationListenerService()` — which forces the
 * system to re-bind our listener immediately.
 *
 * Registered in `AndroidManifest.xml` with the `RECEIVE_BOOT_COMPLETED`
 * permission + intent-filter.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReplyAIBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Ignoring unrelated action: $action")
            return
        }
        Log.i(TAG, "BOOT_COMPLETED — requesting NotificationListener rebind")

        // Only rebind if the user previously granted notification access.
        // If they haven't, requestRebind would be a no-op (and spamming it
        // every boot is pointless). The user must grant access in Settings.
        if (!isNotificationListenerEnabled(context)) {
            Log.w(TAG, "NotificationListener not enabled in settings — " +
                "user must grant notification access. Skipping rebind.")
            return
        }

        try {
            // Force the system to re-bind our listener. This is the public
            // API (since API 24) for the same thing the internal
            // `toggleNotificationListenerService()` does.
            val component = ComponentName(context, ReplyAINotificationListener::class.java)
            NotificationListenerService.requestRebind(component)
            Log.i(TAG, "Rebind requested for ReplyAINotificationListener")
        } catch (e: Exception) {
            // SecurityException / IllegalStateException can happen on some OEMs
            // if the component isn't allowed. Just log — there's nothing else
            // we can do from a BroadcastReceiver.
            Log.e(TAG, "requestRebind failed", e)
        }
    }

    /**
     * Check whether the user has granted notification access to our listener.
     * Reads the flat string of enabled listener components from Settings.Secure.
     */
    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ReplyAINotificationListener::class.java)
            .flattenToString()
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: ""
        return flat.split(":").any { it.equals(expected, ignoreCase = true) }
    }
}
