package com.replyai.app.engine

import android.content.Context
import android.util.Log
import com.replyai.app.bots.Bot
import com.replyai.app.llm.LLMClient
import com.replyai.app.llm.ToolDefinitions
import com.replyai.app.llm.ToolExecutor
import com.replyai.app.llm.ToolCall
import com.replyai.app.safety.SafetyRules
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * RuleEngine — the SMART agent loop that turns an incoming notification into
 * a context-aware reply.
 *
 * Flow (see ARCHITECTURE.md section 6 + Aira RuleEngine.kt v4.0.93):
 *
 *   1. Save the incoming message to [ConversationMemory].
 *   2. Build the prompt:
 *        system_prompt  = bot.systemPrompt + personality + retrieval hints
 *        user_message   = formatted conversation history (last 10 exchanges)
 *                       + the new incoming message
 *   3. Call [LLMClient.chat] with the available tools ([ToolDefinitions.ALL]).
 *   4. Loop up to [MAX_ITERATIONS] times:
 *        - For each ToolCall the LLM emitted:
 *            * Anti-double-reply: if [hasReplied] is already true, filter out
 *              any further `replyToNotification` calls and force `complete`.
 *            * Otherwise execute via [ToolExecutor.execute].
 *            * If the call was `replyToNotification` and it succeeded,
 *              capture the reply text + set [hasReplied] = true.
 *            * If the call was `complete`, break out of the loop.
 *        - If no tool calls were returned, the model is done — break.
 *        - Otherwise, feed the tool results back into the conversation and
 *          loop again.
 *   5. Track total tokens (in + out) across all LLM calls and estimate the
 *      USD cost at $0.001 per 1K tokens (per task spec).
 *   6. Persist the outgoing reply to [ConversationMemory].
 *   7. Return the reply text, or null if nothing was sent.
 *
 * Why an object: there's only one engine per process; the bot's per-contact
 * state lives in [ConversationMemory], not in the engine itself. Safe to
 * call concurrently for different (bot, contact) pairs.
 *
 * See ARCHITECTURE.md section 4 ("SMART rule execution") + section 10 (safety).
 */
object RuleEngine {

    private const val TAG = "RuleEngine"

    /** Max LLM round-trips per incoming message. */
    private const val MAX_ITERATIONS = 5

    /** Cost model: $0.001 per 1K tokens (input + output). Task-spec v1. */
    private const val COST_PER_1K_TOKENS_USD = 0.001
    private const val CENTS_PER_DOLLAR = 100

    /** The 8 built-in tools we always offer to the LLM. */
    private val AVAILABLE_TOOLS = ToolDefinitions.ALL

    /** Tool name constants — keep in sync with [ToolDefinitions]. */
    private const val TOOL_REPLY = "replyToNotification"
    private const val TOOL_COMPLETE = "complete"

    /**
     * Build the user-facing prompt by prepending the conversation history to
     * the new message. Hook used by [execute] — also useful for tests.
     */
    private fun buildUserMessage(
        historyForPrompt: String,
        sender: String,
        incomingMessage: String,
    ): String {
        val sb = StringBuilder()
        if (historyForPrompt.isNotBlank() && historyForPrompt != "(no previous conversation)") {
            sb.append("Conversation so far:\n")
            sb.append(historyForPrompt)
            sb.append("\n\n")
        }
        sb.append("New incoming message from ")
        sb.append(sender)
        sb.append(":\n")
        sb.append(incomingMessage.trim())
        sb.append("\n\n")
        sb.append(
            "Decide how to respond. If a reply is appropriate, call the " +
                "`replyToNotification` tool with the package name and reply text. " +
                "When done, call `complete(success=true, reason=...)`. " +
                "Do NOT call `replyToNotification` more than once.",
        )
        return sb.toString()
    }

    /**
     * Build the system prompt: bot's base prompt + personality + retrieval hints.
     */
    private fun buildSystemPrompt(bot: Bot, retrievalHints: String): String {
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
        if (retrievalHints.isNotBlank()) {
            sb.append("\n").append(retrievalHints).append('\n')
        }
        return sb.toString()
    }

    /**
     * Inspect the incoming message + bot config to figure out which retrieval
     * hints to embed in the system prompt. Pure-string hints — the LLM then
     * picks the right tool to call.
     */
    private suspend fun buildRetrievalHints(
        context: Context,
        bot: Bot,
        incomingMessage: String,
    ): String {
        val mode = IntentClassifier.classify(incomingMessage, bot, context)
        return when (mode) {
            IntentClassifier.RetrievalMode.DIRECT ->
                "Retrieval hint: this looks like a greeting / small talk — reply directly."
            IntentClassifier.RetrievalMode.FTS5 ->
                "Retrieval hint: call `recallKnowledge` with the user's question " +
                    "to find relevant chunks in the uploaded knowledge base."
            IntentClassifier.RetrievalMode.VECTOR ->
                "Retrieval hint: call `recallKnowledge` with the user's question " +
                    "to find semantically similar chunks in the uploaded knowledge base."
            IntentClassifier.RetrievalMode.WEB_SEARCH -> {
                val site = bot.companyUrl
                if (site.isNullOrBlank()) {
                    "Retrieval hint: use `webSearch` for current information."
                } else {
                    "Retrieval hint: use `webSearch` (scoped to $site) and optionally " +
                        "`fetchPageText` on the top result for current information."
                }
            }
        }
    }

    /**
     * Extract the reply text from a successful replyToNotification tool call.
     * Mirrors the keys [ToolExecutor.replyToNotification] accepts.
     */
    private fun extractReplyText(call: ToolCall): String? {
        return call.args["replyText"] as? String
            ?: call.args["text"] as? String
            ?: call.args["message"] as? String
    }

    /**
     * MAIN ENTRY POINT.
     *
     * @param context         Android context (for DB + LLM client)
     * @param bot             the bot that fired
     * @param packageName     source app package, e.g. "com.whatsapp"
     * @param sender          display name of the contact, e.g. "Mom"
     * @param incomingMessage the text the contact sent
     * @return the reply text the bot sent, or null if no reply was sent
     *         (e.g. quiet hours, rate-limited, spam, escalation).
     */
    suspend fun execute(
        context: Context,
        bot: Bot,
        packageName: String,
        sender: String,
        incomingMessage: String,
    ): String? = withContext(Dispatchers.IO) {

        val contactKey = "$packageName:$sender"

        Log.i(TAG, "=== RULE EXECUTE ===")
        Log.i(TAG, "Bot: ${bot.name} (id=${bot.id})")
        Log.i(TAG, "Contact: $contactKey")
        Log.i(TAG, "Incoming: ${incomingMessage.take(120)}")

        // ------------------------------------------------------------
        // Pre-flight safety checks (no LLM call if these fail)
        // ------------------------------------------------------------

        if (!bot.enabled) {
            Log.i(TAG, "Bot disabled — skipping")
            return@withContext null
        }

        if (SafetyRules.shouldEscalate(incomingMessage)) {
            Log.i(TAG, "Escalation keywords detected — not auto-replying")
            ConversationMemory.addIncoming(
                context, bot.id, contactKey, sender, incomingMessage,
            )
            return@withContext null
        }

        if (SafetyRules.isSpam(incomingMessage)) {
            Log.i(TAG, "Spam detected — not auto-replying")
            return@withContext null
        }

        if (SafetyRules.isQuietHours(bot)) {
            Log.i(TAG, "Quiet hours — not auto-replying")
            ConversationMemory.addIncoming(
                context, bot.id, contactKey, sender, incomingMessage,
            )
            return@withContext null
        }

        // Save incoming message to memory (so subsequent calls in this loop
        // see the latest turn, and so the dashboard has a record).
        ConversationMemory.addIncoming(
            context, bot.id, contactKey, sender, incomingMessage,
        )

        // ------------------------------------------------------------
        // Build prompt
        // ------------------------------------------------------------

        val historyForPrompt = ConversationMemory.getHistoryForPrompt(
            context, bot.id, contactKey, maxExchanges = 10,
        )
        val retrievalHints = try {
            buildRetrievalHints(context, bot, incomingMessage)
        } catch (e: Exception) {
            Log.w(TAG, "Retrieval hint build failed; continuing without", e)
            ""
        }
        val systemPrompt = buildSystemPrompt(bot, retrievalHints)
        val userMessage = buildUserMessage(historyForPrompt, sender, incomingMessage)

        // ------------------------------------------------------------
        // Agent loop
        // ------------------------------------------------------------

        val llm = LLMClient(context)
        val toolExec = ToolExecutor(context)

        // Conversation grows across iterations: each round's tool results are
        // appended so the LLM can reason over them next round.
        val conversation = StringBuilder(userMessage)

        var totalTokensIn = 0
        var totalTokensOut = 0
        var hasReplied = false
        var sentReplyText: String? = null
        var forceCompleteNext = false

        for (iteration in 1..MAX_ITERATIONS) {
            Log.i(TAG, "--- iteration $iteration/$MAX_ITERATIONS ---")

            // If we already replied, inject a "stop now" reminder and force
            // the next round to be the last.
            if (forceCompleteNext || hasReplied) {
                conversation.append(
                    "\n\nYou already sent a reply via replyToNotification. " +
                        "Do NOT reply again. Call `complete(success=true, " +
                        "reason=\"Reply sent\")` NOW.",
                )
            }

            val response = try {
                llm.chat(
                    userMessage = conversation.toString(),
                    systemPrompt = systemPrompt,
                    tools = AVAILABLE_TOOLS,
                    toolFilter = null, // null = send all tools
                )
            } catch (e: Exception) {
                Log.e(TAG, "LLM chat threw on iteration $iteration", e)
                break
            }

            totalTokensIn += response.tokensIn
            totalTokensOut += response.tokensOut
            Log.i(TAG, "LLM reply: ${response.reply.take(120)}")
            Log.i(TAG, "LLM tool calls: ${response.toolCalls.map { it.name }}")

            // No tool calls → model is done talking.
            if (response.toolCalls.isEmpty()) {
                Log.i(TAG, "No tool calls — done")
                break
            }

            // Anti-double-reply: filter out duplicate replyToNotification calls.
            val filteredCalls = if (hasReplied) {
                response.toolCalls.filter { it.name != TOOL_REPLY }
            } else {
                response.toolCalls
            }

            // If ALL tool calls were blocked replyToNotification (i.e. we
            // already replied and the model is trying again), force-complete.
            if (filteredCalls.isEmpty() && response.toolCalls.isNotEmpty()) {
                Log.i(TAG, "All tool calls were duplicate replyToNotification — force complete")
                break
            }

            // Execute each tool call in order.
            var didComplete = false
            val toolResultsBlock = StringBuilder()
            toolResultsBlock.append("\n\n[Iteration $iteration tool results]:\n")

            for (call in filteredCalls) {
                val result = try {
                    toolExec.execute(call)
                } catch (e: Exception) {
                    Log.e(TAG, "Tool ${call.name} threw", e)
                    com.replyai.app.llm.ToolExecutor.ToolResult(
                        success = false,
                        message = "Exception in ${call.name}: ${e.message}",
                    )
                }

                Log.i(
                    TAG,
                    "Tool ${call.name}: ${if (result.success) "ok" else "FAIL"} " +
                        "${result.message.take(200)}",
                )

                toolResultsBlock
                    .append("- ")
                    .append(call.name)
                    .append("(")
                    .append(call.args.entries.joinToString(",") { "${it.key}=${it.value}" })
                    .append(")\n")
                    .append("  result: ")
                    .append(if (result.success) "SUCCESS" else "FAILURE")
                    .append(" — ")
                    .append(result.message.take(400))
                    .append('\n')

                when {
                    result.data[ToolExecutor.ToolResult.COMPLETE_SIGNAL] == true -> {
                        didComplete = true
                        Log.i(TAG, "complete() called: ${result.message}")
                    }
                    call.name == TOOL_REPLY && result.success -> {
                        hasReplied = true
                        sentReplyText = extractReplyText(call)
                        Log.i(TAG, "Reply sent via replyToNotification: ${sentReplyText?.take(120)}")
                    }
                    call.name == TOOL_REPLY && !result.success -> {
                        Log.w(TAG, "replyToNotification failed: ${result.message}")
                    }
                }
            }

            if (didComplete) break
            if (hasReplied) {
                // Reply sent — next iteration must be the last (force complete).
                forceCompleteNext = true
            }

            // Feed tool results back into the conversation for the next round.
            conversation.append(toolResultsBlock)
            conversation.append(
                "\nBased on these results, decide the next step. " +
                    "If done, call `complete(success=true)`.",
            )

            if (iteration == MAX_ITERATIONS) {
                Log.w(TAG, "Reached MAX_ITERATIONS=$MAX_ITERATIONS — stopping")
            }
        }

        // ------------------------------------------------------------
        // Cost tracking (log-only for now; daily_costs table is owned by
        // the parallel DB agent)
        // ------------------------------------------------------------

        val totalTokens = totalTokensIn + totalTokensOut
        val estCostCents = ((totalTokens / 1000.0) * COST_PER_1K_TOKENS_USD * CENTS_PER_DOLLAR).toInt()
        Log.i(
            TAG,
            "Tokens in=$totalTokensIn out=$totalTokensOut total=$totalTokens " +
                "estCost=${estCostCents}c",
        )

        // ------------------------------------------------------------
        // Persist outgoing reply to memory
        // ------------------------------------------------------------

        val reply = sentReplyText
        if (!reply.isNullOrBlank()) {
            ConversationMemory.addOutgoing(context, bot.id, contactKey, reply)
        }

        Log.i(TAG, "=== RULE DONE: ${if (reply != null) "replied" else "no reply"} ===")
        reply
    }
}
