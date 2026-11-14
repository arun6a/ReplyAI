package com.replyai.app.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import com.replyai.app.notifications.ReplyAINotificationListener

/**
 * ToolExecutor — registry + dispatcher for LLM tool calls.
 *
 * Each tool is a suspend lambda: `(ToolCall) -> ToolResult`.
 * The agent loop calls [execute] with the ToolCall returned by the LLM.
 *
 * The 8 built-in tools:
 *  - replyToNotification : sends a reply to the most recent notification from a
 *                          given app (delegates to ReplyAINotificationListener,
 *                          which is built by another agent in parallel).
 *  - webSearch           : placeholder (will be filled by another module).
 *  - fetchPageText       : placeholder.
 *  - recallKnowledge     : placeholder (will use VectorStore from another module).
 *  - sendSMS             : uses android.telephony.SmsManager.
 *  - makePhoneCall       : uses Intent.ACTION_CALL.
 *  - analyzeImage        : placeholder (will use v4.1.0 vision capability).
 *  - complete            : signals the loop should end.
 *
 * The placeholder tools return a [ToolResult] with success=false and an
 * informative message, so the LLM gets a clean "not yet implemented"
 * signal rather than a hard error.
 */
class ToolExecutor(private val context: Context) {

    /**
     * Result of executing a tool. [data] carries any structured output the
     * caller (e.g. the agent loop) may need to inspect.
     *
     * For the "complete" tool, [data] contains [COMPLETE_SIGNAL] → true so the
     * loop can detect completion without string-matching on [message].
     */
    data class ToolResult(
        val success: Boolean,
        val message: String,
        val data: Map<String, Any> = emptyMap(),
    ) {
        companion object {
            /** Sentinel key placed in [data] by the "complete" tool. */
            const val COMPLETE_SIGNAL = "__replyai_complete__"

            /** Returns true if this result is the completion signal. */
            fun ToolResult.isComplete(): Boolean =
                data[COMPLETE_SIGNAL] == true
        }
    }

    private val tools: Map<String, suspend (ToolCall) -> ToolResult> = mapOf(
        "replyToNotification" to { call -> replyToNotification(call) },
        "webSearch" to { _ ->
            ToolResult(success = false, message = "Web search not yet implemented")
        },
        "fetchPageText" to { _ ->
            ToolResult(success = false, message = "Fetch page text not yet implemented")
        },
        "recallKnowledge" to { _ ->
            ToolResult(success = false, message = "Knowledge recall not yet implemented")
        },
        "sendSMS" to { call -> sendSMS(call) },
        "makePhoneCall" to { call -> makePhoneCall(call) },
        "analyzeImage" to { _ ->
            ToolResult(success = false, message = "Image analysis not yet implemented")
        },
        "complete" to { call -> complete(call) },
    )

    /** Execute a tool call by name. Returns a failure result for unknown tools. */
    suspend fun execute(call: ToolCall): ToolResult {
        val handler = tools[call.name]
            ?: return ToolResult(success = false, message = "Unknown tool: ${call.name}")
        return try {
            handler(call)
        } catch (e: Exception) {
            Log.e(TAG, "Tool ${call.name} threw", e)
            ToolResult(success = false, message = "Error in ${call.name}: ${e.message}")
        }
    }

    /** Names of all registered tools (used to build ToolDefinitions / for UI). */
    fun registeredTools(): List<String> = tools.keys.toList()

    // ----------------------------------------------------------------------
    // Tool implementations
    // ----------------------------------------------------------------------

    /**
     * replyToNotification — delegates to the active NotificationListener.
     *
     * ReplyAINotificationListener is built by another agent in parallel.
     * Assumed API surface:
     *   companion object { var instance: ReplyAINotificationListener? }
     *   fun replyToNotification(packageName: String, replyText: String): Boolean
     *
     * If the listener isn't running (user hasn't granted notification access),
     * returns a failure result with a helpful message.
     */
    private suspend fun replyToNotification(call: ToolCall): ToolResult {
        val pkg = call.args["package"] as? String
            ?: call.args["packageName"] as? String
            ?: ""
        val replyText = call.args["replyText"] as? String
            ?: call.args["text"] as? String
            ?: call.args["message"] as? String
            ?: ""
        if (pkg.isBlank() || replyText.isBlank()) {
            return ToolResult(
                success = false,
                message = "replyToNotification requires 'package' and 'replyText' args",
            )
        }

        val listener = ReplyAINotificationListener.instance
        if (listener == null) {
            return ToolResult(
                success = false,
                message = "Notification listener not running. " +
                    "Grant notification access in Settings and try again.",
            )
        }

        return try {
            val sent = listener.replyToNotification(pkg, replyText)
            ToolResult(
                success = sent,
                message = if (sent) "Reply sent to $pkg" else "Failed to send reply to $pkg",
                data = mapOf("package" to pkg, "replyText" to replyText),
            )
        } catch (e: Exception) {
            Log.e(TAG, "replyToNotification error", e)
            ToolResult(success = false, message = "replyToNotification error: ${e.message}")
        }
    }

    /**
     * sendSMS — uses android.telephony.SmsManager to send an SMS.
     * Requires the SEND_SMS permission (declared in the manifest).
     */
    private suspend fun sendSMS(call: ToolCall): ToolResult {
        val number = call.args["number"] as? String
            ?: call.args["phone"] as? String
            ?: call.args["to"] as? String
            ?: ""
        val text = call.args["text"] as? String
            ?: call.args["message"] as? String
            ?: call.args["body"] as? String
            ?: ""
        if (number.isBlank() || text.isBlank()) {
            return ToolResult(
                success = false,
                message = "sendSMS requires 'number' and 'text' args",
            )
        }

        return try {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            sms.sendTextMessage(number, null, text, null, null)
            ToolResult(
                success = true,
                message = "SMS sent to $number",
                data = mapOf("number" to number, "text" to text),
            )
        } catch (e: Exception) {
            Log.e(TAG, "sendSMS failed", e)
            ToolResult(success = false, message = "SMS failed: ${e.message}")
        }
    }

    /**
     * makePhoneCall — launches an ACTION_CALL intent.
     * Requires the CALL_PHONE permission (declared in the manifest).
     */
    private suspend fun makePhoneCall(call: ToolCall): ToolResult {
        val number = call.args["number"] as? String
            ?: call.args["phone"] as? String
            ?: call.args["to"] as? String
            ?: ""
        if (number.isBlank()) {
            return ToolResult(success = false, message = "makePhoneCall requires 'number' arg")
        }

        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(
                success = true,
                message = "Calling $number",
                data = mapOf("number" to number),
            )
        } catch (e: Exception) {
            Log.e(TAG, "makePhoneCall failed", e)
            ToolResult(success = false, message = "Call failed: ${e.message}")
        }
    }

    /**
     * complete — signals the agent loop should end.
     * [data] carries COMPLETE_SIGNAL=true so the loop can detect completion
     * without parsing [message].
     */
    private suspend fun complete(call: ToolCall): ToolResult {
        val success = (call.args["success"] as? Boolean) ?: true
        val reason = (call.args["reason"] as? String) ?: ""
        return ToolResult(
            success = success,
            message = reason.ifBlank { "Task complete" },
            data = mapOf(ToolResult.COMPLETE_SIGNAL to true),
        )
    }

    companion object {
        private const val TAG = "ToolExecutor"
    }
}
