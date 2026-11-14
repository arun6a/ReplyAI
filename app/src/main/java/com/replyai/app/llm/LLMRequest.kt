package com.replyai.app.llm

/**
 * LLM request/response data classes.
 *
 * These mirror the OpenAI chat-completions message schema but are kept
 * provider-agnostic — the LLMClient translates them to each provider's
 * native format (OpenAI, Gemini, Anthropic).
 */

/**
 * A single chat message.
 * @param role    one of "system" | "user" | "assistant" | "tool"
 * @param content the message text (or JSON string for tool results, depending on role)
 */
data class ChatMessage(
    val role: String,
    val content: String,
)

/**
 * A tool call requested by the LLM.
 * @param id   the call id assigned by the LLM (used to match tool results back
 *             to the call in multi-turn tool conversations). When the LLM
 *             doesn't supply one, the client synthesises "call_<index>".
 * @param name the tool name (must match a ToolDefinition.name)
 * @param args the arguments parsed from the LLM's JSON arguments field.
 *             Values are normalised to plain Kotlin types (String / Int / Boolean /
 *             Map<String,Any> / List<Any>) — never JSONObject/JSONArray.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val args: Map<String, Any>,
)

/**
 * Schema definition for a tool that can be offered to the LLM.
 *
 * @param name        the tool name (stable identifier the LLM will emit in ToolCall)
 * @param description human-readable description shown to the LLM
 * @param parameters  JSON-schema-like Map, e.g.
 *                    {"type":"object","properties":{...},"required":[...]}
 *                    The LLMClient converts this to a JSONObject when building
 *                    the request body for each provider.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
)

/**
 * LLM response — reply text + any tool calls + token usage.
 *
 * The client never throws on API errors; instead it returns an LLMResponse
 * with the error message in [reply] and empty [toolCalls], so the caller can
 * simply render the text to the user.
 *
 * @param reply     the visible reply text (may be empty when the LLM only
 *                  emitted tool calls)
 * @param toolCalls tool calls the LLM wants executed (may be empty)
 * @param tokensIn  prompt tokens billed for this call (0 if unknown)
 * @param tokensOut completion tokens billed for this call (0 if unknown)
 */
data class LLMResponse(
    val reply: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
)
