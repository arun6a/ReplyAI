package com.replyai.app.safety

import com.replyai.app.bots.Bot
import java.time.LocalDateTime

/**
 * SafetyRules — the guardrails that keep an auto-reply bot from embarrassing
 * the user (or running up a bill).
 *
 * Pure logic, no Android dependencies beyond [Bot]. Safe to unit-test on JVM.
 *
 * Rules implemented:
 *   1. [isQuietHours]   — true if the bot's quiet-hours window contains `now`,
 *                        correctly handling overnight wrap-around (e.g. 22→7).
 *   2. [isRateLimited]  — true if the per-contact daily reply cap has been hit.
 *   3. [shouldEscalate] — true if the incoming message looks urgent / legal /
 *                        complaint — bot should NOT auto-reply, just notify the user.
 *   4. [isSpam]         — true if the incoming message looks like spam
 *                        (ALL CAPS + !!!, repeated chars, URL-only).
 *
 * These checks are called by [com.replyai.app.engine.RuleEngine] before
 * calling the LLM, and by the notification listener when deciding whether to
 * even wake the engine up.
 *
 * See ARCHITECTURE.md section 10 (Safety + privacy).
 */
object SafetyRules {

    // ------------------------------------------------------------------
    // Quiet hours
    // ------------------------------------------------------------------

    /**
     * True if [now] falls inside [bot]'s quiet-hours window.
     *
     * Handles overnight wrap-around: e.g. start=22, end=7 means
     * "quiet from 22:00 tonight to 07:00 tomorrow". For start<end (e.g.
     * start=1, end=5) the window is a simple range the same day.
     *
     * If start==end the window is empty (no quiet hours).
     */
    fun isQuietHours(bot: Bot, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val start = bot.quietHoursStart
        val end = bot.quietHoursEnd
        if (start == end) return false
        val h = now.hour
        return if (start < end) {
            // Same-day window: e.g. 1..5
            h in start until end
        } else {
            // Overnight window: e.g. 22..7 → [22,24) ∪ [0,7)
            h >= start || h < end
        }
    }

    // ------------------------------------------------------------------
    // Rate limiting
    // ------------------------------------------------------------------

    /**
     * True if the bot has already sent [repliesToday] replies to [contactKey]
     * today and is therefore at its per-contact daily cap.
     *
     * [contactKey] is accepted for API symmetry (callers usually have it on
     * hand) but the cap is currently per-bot, not per-(bot,contact). If we
     * later want per-contact caps, the signature is already correct.
     */
    fun isRateLimited(bot: Bot, contactKey: String, repliesToday: Int): Boolean {
        if (bot.dailyMessageCap <= 0) return false // 0 = unlimited
        return repliesToday >= bot.dailyMessageCap
    }

    // ------------------------------------------------------------------
    // Escalation
    // ------------------------------------------------------------------

    /**
     * Substrings that should ALWAYS escalate to a human — never auto-reply.
     * Matched case-insensitively as whole-word boundaries where it matters.
     */
    private val ESCALATION_KEYWORDS = listOf(
        "urgent",
        "emergency",
        "legal",
        "complaint",
        "speak to human",
        "speak to a human",
        "talk to human",
        "talk to a human",
        "sue",
        "lawyer",
        "attorney",
        "police",
        "hospital",
        "ambulance",
        "threat",
        "blackmail",
        "lawsuit",
    )

    /**
     * True if the message contains any escalation keyword.
     *
     * We do a case-insensitive substring search bounded by non-letter
     * characters (so "lawsuit" inside "mylawsuit" still matches but
     * "sue" inside "issue" does NOT — it must be a standalone word).
     */
    fun shouldEscalate(message: String): Boolean {
        val lower = message.lowercase().trim()
        if (lower.isEmpty()) return false
        for (kw in ESCALATION_KEYWORDS) {
            // Phrase matchers ("speak to human") can be a plain contains() check.
            if (kw.contains(' ')) {
                if (lower.contains(kw)) return true
                continue
            }
            // Single-word matchers: require a word boundary on each side so
            // "sue" doesn't match inside "issue", "tissue", "ensure", etc.
            val pattern = Regex("(?<![a-z])" + Regex.escape(kw) + "(?![a-z])")
            if (pattern.containsMatchIn(lower)) return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // Spam detection
    // ------------------------------------------------------------------

    /** Minimum length before we bother classifying a message as spam. */
    private const val SPAM_MIN_LEN = 4

    /** Regex for "URL-only" messages: http(s)://... optionally surrounded by spaces. */
    private val URL_ONLY_REGEX = Regex(
        "^\\s*https?://\\S+\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** A run of the same character repeated 8+ times (e.g. "AAAAAAAA" or "!!!!!!!!!"). */
    private val REPEATED_CHAR_REGEX = Regex("(.)\\1{7,}")

    /** Regex for short "money words" typically screamed in spam. */
    private val SPAM_WORDS_REGEX = Regex(
        "\\b(free|win|winner|won|prize|cash|money|loan|crypto|bitcoin|viagra|casino|bonus|earn|income|rich)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Heuristic spam detector. Returns true if ANY of the following holds:
     *   - Message is a bare URL (no other content).
     *   - Message contains a run of 8+ identical characters
     *     ("AAAAAAAA", "!!!!!!!!", "$$$$$$$$").
     *   - Message is at least [SPAM_MIN_LEN] chars AND either:
     *       * upper-case ratio >= 0.6 AND contains '!' (the "FREE iPhone!!!"
     *         pattern), OR
     *       * upper-case ratio >= 0.8 without punctuation (the "SCAM ALERT"
     *         all-caps pattern), AND contains a spam word.
     *
     * Conservative: a normal short uppercase abbreviation like "OK" or "BRB"
     * will NOT trigger because it's < SPAM_MIN_LEN chars / has no spam word.
     */
    fun isSpam(message: String): Boolean {
        val text = message.trim()
        if (text.length < SPAM_MIN_LEN) return false

        // URL-only.
        if (URL_ONLY_REGEX.matches(text)) return true

        // 8+ repeated chars.
        if (REPEATED_CHAR_REGEX.containsMatchIn(text)) return true

        // ALL CAPS + (!!) detection.
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val upperRatio = letters.count { it.isUpperCase() }.toFloat() / letters.length.toFloat()
        val hasBang = text.any { it == '!' }
        val hasSpamWord = SPAM_WORDS_REGEX.containsMatchIn(text)

        if (upperRatio >= 0.6f && hasBang) {
            // "FREE iPhone!!!" pattern — even without a spam keyword, treat
            // uppercase-heavy + exclamation as spam.
            return true
        }
        if (upperRatio >= 0.8f && hasSpamWord) {
            // "SCAM ALERT CRYPTO" pattern.
            return true
        }
        return false
    }
}
