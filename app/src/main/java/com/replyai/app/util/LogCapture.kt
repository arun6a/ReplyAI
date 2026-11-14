package com.replyai.app.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * LogCapture — in-memory ring buffer + file-backed log for ReplyAI.
 *
 * Ported from Aira's com.ai.agent.util.LogCapture (v4.0.40).
 *
 * Why: User has no logcat access on MIUI/Android 13+. This captures every log
 * line in-memory so they can be viewed in-app (Settings → Logs) and exported
 * to a file the user can share back for debugging.
 *
 * Usage — call [i] / [w] / [e] / [d] instead of (or alongside) `Log.i(...)`:
 *   LogCapture.i("ReplyEngine", "Incoming from com.whatsapp:Mom: 'hi'")
 *   LogCapture.w("ReplyAINotificationListener", "requestRebind failed")
 *
 * Each call:
 *   1. Writes to system logcat (so ADB users still see it)
 *   2. Appends to the ring buffer (last 2000 lines, in-memory)
 *   3. If [enableFileLogging] is true, appends to /Aira/replyai.log
 *
 * The in-memory buffer is fast (no I/O on the hot path). The file is opt-in
 * (set in Settings) so we don't write to storage during normal use.
 */
object LogCapture {
    private const val MAX_LINES = 2000

    data class LogLine(
        val timestamp: Long,
        val tag: String,
        val level: String,
        val message: String,
    )

    private val buffer = ConcurrentLinkedDeque<LogLine>()

    /** File path for persistent logging. Null = disabled. Set by Settings. */
    @Volatile
    var logFilePath: String? = null

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** All log lines currently in the buffer (oldest first). */
    fun getLines(): List<LogLine> = buffer.toList()

    /** Clear the in-memory buffer. */
    fun clear() = buffer.clear()

    fun i(tag: String, msg: String) = log("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t)
    fun d(tag: String, msg: String) = log("D", tag, msg, null)

    private fun log(level: String, tag: String, msg: String, t: Throwable?) {
        val now = System.currentTimeMillis()
        val line = LogLine(now, tag, level, msg)
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.pollFirst()

        // Also write to logcat
        when (level) {
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            "E" -> Log.e(tag, msg, t)
            "D" -> Log.d(tag, msg)
        }

        // Optional file write
        logFilePath?.let { path ->
            try {
                val time = dateFormat.format(Date(now))
                val lineStr = "$time $level/$tag: $msg" +
                    (t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: "") + "\n"
                File(path).appendText(lineStr)
            } catch (_: Exception) {
                // Don't let logging crash the app — silently drop
            }
        }
    }

    /** Export the current buffer to a single string (for share-to-AI). */
    fun exportToString(): String {
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        for (line in buffer) {
            sb.append(df.format(Date(line.timestamp)))
                .append(" ").append(line.level).append("/").append(line.tag)
                .append(": ").append(line.message).append("\n")
        }
        return sb.toString()
    }
}
