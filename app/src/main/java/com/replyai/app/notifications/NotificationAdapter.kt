package com.replyai.app.notifications

import com.replyai.app.R

/**
 * NotificationAdapter — maps a notification's `packageName` to a human-readable
 * display name + an icon resource.
 *
 * Used by:
 *   - Dashboard / UI to render per-app rows with the right icon + label.
 *   - ReplyEngine to format escalation SMS messages (`"WhatsApp: Mom said..."`).
 *
 * For unknown packages this returns `null`; the caller falls back to a generic
 * representation (e.g. the raw package name + the default app icon). The
 * NotificationListener still works for unknown packages — they just don't get a
 * pretty icon.
 *
 * The supported set intentionally includes `com.instagram.android` even though the
 * NotificationListener's reply filter doesn't include Instagram (Instagram DM
 * notifications don't expose a RemoteInput reply action on most builds). It's
 * listed here so the dashboard can still label Instagram notifications nicely
 * if/when they appear.
 */
object NotificationAdapter {

    /** Internal app metadata record. */
    private data class AppMeta(val displayName: String, val iconRes: Int)

    /**
     * Lookup table of supported messaging packages → (display name, icon resource).
     *
     * Keys mirror `ReplyAINotificationListener.SUPPORTED_PACKAGES` plus
     * `com.instagram.android` (icon-only, no auto-reply).
     */
    private val APP_MAP: Map<String, AppMeta> = mapOf(
        "com.whatsapp"            to AppMeta("WhatsApp",          R.drawable.ic_whatsapp),
        "com.whatsapp.w4b"         to AppMeta("WhatsApp Business",  R.drawable.ic_whatsapp_business),
        "com.telegram"             to AppMeta("Telegram",           R.drawable.ic_telegram),
        "com.facebook.orca"        to AppMeta("Messenger",         R.drawable.ic_messenger),
        "com.androidmessaging"     to AppMeta("SMS",                R.drawable.ic_sms),
        "com.slack"                to AppMeta("Slack",              R.drawable.ic_slack),
        "com.discord"              to AppMeta("Discord",            R.drawable.ic_discord),
        "com.instagram.android"    to AppMeta("Instagram",          R.drawable.ic_instagram),
    )

    /**
     * Resolve app info for a notification package.
     *
     * @param packageName the `StatusBarNotification.packageName` (e.g. "com.whatsapp")
     * @return `Pair(displayName, iconRes)` or `null` if the package is not a
     *         known messaging app. Callers should fall back to the raw package
     *         name + a generic icon when `null`.
     */
    fun getAppInfo(packageName: String): Pair<String, Int>? {
        val meta = APP_MAP[packageName] ?: return null
        return Pair(meta.displayName, meta.iconRes)
    }

    /**
     * Convenience: just the display name. Falls back to the raw package name
     * for unknown apps (so log lines + escalation SMS are still readable).
     */
    fun getDisplayName(packageName: String): String =
        APP_MAP[packageName]?.displayName ?: packageName

    /**
     * Convenience: just the icon resource. Falls back to `0` (no resource) for
     * unknown apps; callers should treat `0` as "use default icon".
     */
    fun getIconRes(packageName: String): Int =
        APP_MAP[packageName]?.iconRes ?: 0

    /** All package keys this adapter knows about (for iteration / sanity checks). */
    val knownPackages: Set<String> get() = APP_MAP.keys
}
