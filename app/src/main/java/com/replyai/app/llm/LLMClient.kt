package com.replyai.app.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LLMClient — multi-provider chat client.
 *
 * Reads provider + apiKey + model from SharedPreferences ("replyai_prefs")
 * via [LLMSettings] and routes the request to the correct API:
 *  - OpenAI-compatible (OpenAI, Groq, OpenRouter, Custom) → POST /chat/completions
 *  - Gemini → POST /models/{model}:generateContent?key=...
 *  - Anthropic → POST /messages
 *
 * All responses are normalized into an [LLMResponse]. Errors are returned as
 * a response with the error message in [LLMResponse.reply] rather than
 * throwing, so callers can simply render the text to the user.
 *
 * Adapted from Aira's LLMClient — trimmed to ReplyAI's six-provider set and
 * 8-tool surface; binding-failover / token-budget retry machinery lives in
 * the engine module, not here.
 */
class LLMClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Send a chat request to the configured LLM provider.
     *
     * @param userMessage  the user's input
     * @param systemPrompt the system instructions
     * @param tools        tool definitions offered to the LLM. Empty = no tools sent.
     * @param toolFilter   optional allow-list of tool names; if non-null only
     *                     tools whose name is in this list are sent.
     * @return [LLMResponse] — never throws; errors land in [LLMResponse.reply].
     */
    suspend fun chat(
        userMessage: String,
        systemPrompt: String,
        tools: List<ToolDefinition> = emptyList(),
        toolFilter: List<String>? = null,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val provider = LLMSettings.getProvider(context)
        val apiKey = LLMSettings.getApiKey(context)
        val model = LLMSettings.getModel(context)

        if (provider.needsApiKey && apiKey.isBlank()) {
            return@withContext LLMResponse(
                "No API key set for ${provider.displayName}. Open Settings → LLM to add your API key.",
            )
        }
        if (model.isBlank()) {
            return@withContext LLMResponse(
                "No model set for ${provider.displayName}. Open Settings → LLM to pick a model.",
            )
        }

        val effectiveTools = when {
            toolFilter == null -> tools
            toolFilter.isEmpty() -> emptyList()
            else -> tools.filter { it.name in toolFilter }
        }

        try {
            return@withContext when (provider) {
                LLMProvider.GEMINI ->
                    callGemini(apiKey, model, userMessage, systemPrompt, effectiveTools)
                LLMProvider.ANTHROPIC ->
                    callAnthropic(apiKey, model, userMessage, systemPrompt, effectiveTools)
                LLMProvider.OPENAI, LLMProvider.GROQ,
                LLMProvider.OPENROUTER, LLMProvider.CUSTOM ->
                    callOpenAICompatible(provider, apiKey, model, userMessage, systemPrompt, effectiveTools)
            }
        } catch (e: Exception) {
            Log.e(TAG, "chat failed", e)
            return@withContext LLMResponse("Error: ${e.message ?: "Unknown error"}. Please try again.")
        }
    }

    // ----------------------------------------------------------------------
    // OpenAI-compatible providers (OpenAI, Groq, OpenRouter, Custom)
    // ----------------------------------------------------------------------

    private fun callOpenAICompatible(
        provider: LLMProvider, apiKey: String, model: String,
        userMessage: String, systemPrompt: String, tools: List<ToolDefinition>,
    ): LLMResponse {
        val endpoint = if (provider == LLMProvider.CUSTOM) {
            val custom = LLMSettings.getCustomEndpoint(context)
            if (custom.isBlank()) {
                return LLMResponse("No custom endpoint configured for the CUSTOM provider.")
            }
            custom
        } else {
            provider.endpoint
        }

        val body = buildOpenAIRequestBody(model, userMessage, systemPrompt, tools)
        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                if (provider == LLMProvider.OPENROUTER) {
                    header("HTTP-Referer", "https://github.com/replyai/app")
                    header("X-Title", "ReplyAI")
                }
            }
            .post(body.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return LLMResponse("API error ${resp.code}: ${respBody.take(300)}")
            }
            return parseOpenAIResponse(respBody)
        }
    }

    private fun buildOpenAIRequestBody(
        model: String, userMessage: String, systemPrompt: String, tools: List<ToolDefinition>,
    ): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            if (tools.isNotEmpty()) {
                put("tools", buildOpenAIToolsArray(tools))
                put("tool_choice", "auto")
            }
        }
        return body.toString()
    }

    private fun buildOpenAIToolsArray(tools: List<ToolDefinition>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            val fn = JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                put("parameters", JSONObject(t.parameters))
            }
            arr.put(JSONObject().apply { put("type", "function"); put("function", fn) })
        }
        return arr
    }

    private fun parseOpenAIResponse(responseBody: String): LLMResponse {
        val json = JSONObject(responseBody)
        val usage = json.optJSONObject("usage")
        val tokensIn = usage?.optInt("prompt_tokens", 0) ?: 0
        val tokensOut = usage?.optInt("completion_tokens", 0) ?: 0

        val message = json.optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("message")
            ?: return LLMResponse("No response from AI", tokensIn = tokensIn, tokensOut = tokensOut)

        val content = extractMessageContent(message)
        val toolCalls = mutableListOf<ToolCall>()
        val nativeCalls = message.optJSONArray("tool_calls")
        if (nativeCalls != null) {
            for (i in 0 until nativeCalls.length()) {
                val tc = nativeCalls.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name", "")
                val id = tc.optString("id", "call_$i")
                if (name.isNotEmpty()) {
                    toolCalls.add(ToolCall(id, name, parseArgsJson(fn.optString("arguments", "{}"))))
                }
            }
        }
        val reply = content.ifBlank { if (toolCalls.isEmpty()) "(empty response)" else "Executing..." }
        return LLMResponse(reply, toolCalls, tokensIn, tokensOut)
    }

    /**
     * OpenAI/Groq return content as a plain string; some OpenRouter passthroughs
     * (Anthropic Claude, DeepSeek R1) return an array of {type,text} blocks.
     * Skip reasoning/thinking blocks to keep the visible reply clean.
     */
    private fun extractMessageContent(message: JSONObject): String {
        val asStr = message.optString("content", "")
        if (asStr.isNotEmpty()) return asStr
        val arr = message.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val block = arr.optJSONObject(i) ?: continue
            val type = block.optString("type", "text")
            if (type == "thinking" || type == "reasoning") continue
            val text = block.optString("text", "")
            if (text.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
            }
        }
        return sb.toString()
    }

    private fun parseArgsJson(argsStr: String): Map<String, Any> {
        if (argsStr.isBlank()) return emptyMap()
        val out = mutableMapOf<String, Any>()
        return try {
            val obj = JSONObject(argsStr)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                normalizeJsonValue(obj.get(k))?.let { out[k] = it }
            }
            out
        } catch (_: Exception) {
            parseArgsAsKeyValue(argsStr)
        }
    }

    private fun parseArgsAsKeyValue(argsStr: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        argsStr.split(",").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = pair.substring(0, idx).trim()
                val v = pair.substring(idx + 1).trim().trim('"', '\'')
                if (k.isNotEmpty()) out[k] = v
            }
        }
        return out
    }

    // ----------------------------------------------------------------------
    // Gemini
    // ----------------------------------------------------------------------

    private fun callGemini(
        apiKey: String, model: String, userMessage: String,
        systemPrompt: String, tools: List<ToolDefinition>,
    ): LLMResponse {
        val effectiveModel = model.removePrefix("google/")
        val url = LLMProvider.GEMINI.endpoint.replace("{model}", effectiveModel) + "?key=$apiKey"
        val body = buildGeminiRequestBody(userMessage, systemPrompt, tools)
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return LLMResponse("Gemini error ${resp.code}: ${respBody.take(300)}")
            }
            return parseGeminiResponse(respBody)
        }
    }

    private fun buildGeminiRequestBody(
        userMessage: String, systemPrompt: String, tools: List<ToolDefinition>,
    ): String {
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("maxOutputTokens", 8192)
                put("topP", 0.9)
                put("topK", 40)
            })
            if (tools.isNotEmpty()) {
                val declarations = JSONArray()
                for (t in tools) {
                    declarations.put(JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", JSONObject(t.parameters))
                    })
                }
                put("tools", JSONArray().put(JSONObject().put("function_declarations", declarations)))
            }
        }
        return body.toString()
    }

    private fun parseGeminiResponse(responseBody: String): LLMResponse {
        val json = JSONObject(responseBody)
        val usage = json.optJSONObject("usageMetadata")
        val tokensIn = usage?.optInt("promptTokenCount", 0) ?: 0
        val tokensOut = usage?.optInt("candidatesTokenCount", 0) ?: 0

        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")

        if (parts == null || parts.length() == 0) {
            val blockReason = json.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
            val finishReason = json.optJSONArray("candidates")
                ?.optJSONObject(0)?.optString("finishReason", "") ?: ""
            val msg = when {
                blockReason.isNotEmpty() -> "Gemini prompt blocked: $blockReason"
                finishReason == "SAFETY" -> "Gemini response blocked by safety filter"
                finishReason == "MAX_TOKENS" -> "Gemini response cut off (max tokens)"
                finishReason.isNotEmpty() -> "Gemini returned no content (finishReason=$finishReason)"
                else -> "Gemini returned no content."
            }
            return LLMResponse(msg, tokensIn = tokensIn, tokensOut = tokensOut)
        }

        val replySb = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val fn = part.optJSONObject("functionCall")
            if (fn != null) {
                val name = fn.optString("name", "")
                val argsObj = fn.optJSONObject("args") ?: JSONObject()
                val args = mutableMapOf<String, Any>()
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    normalizeJsonValue(argsObj.get(k))?.let { args[k] = it }
                }
                if (name.isNotEmpty()) toolCalls.add(ToolCall("call_$i", name, args))
            } else {
                val text = part.optString("text", "")
                if (text.isNotEmpty()) {
                    if (replySb.isNotEmpty()) replySb.append('\n')
                    replySb.append(text)
                }
            }
        }
        val reply = replySb.toString().ifBlank {
            if (toolCalls.isEmpty()) "(empty response)" else "Executing..."
        }
        return LLMResponse(reply, toolCalls, tokensIn, tokensOut)
    }

    // ----------------------------------------------------------------------
    // Anthropic
    // ----------------------------------------------------------------------

    private fun callAnthropic(
        apiKey: String, model: String, userMessage: String,
        systemPrompt: String, tools: List<ToolDefinition>,
    ): LLMResponse {
        val body = buildAnthropicRequestBody(userMessage, systemPrompt, model, tools)
        val request = Request.Builder()
            .url(LLMProvider.ANTHROPIC.endpoint)
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return LLMResponse("Anthropic error ${resp.code}: ${respBody.take(300)}")
            }
            return parseAnthropicResponse(respBody)
        }
    }

    private fun buildAnthropicRequestBody(
        userMessage: String, systemPrompt: String, model: String, tools: List<ToolDefinition>,
    ): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
        }
        val body = JSONObject().apply {
            put("model", model)
            // Anthropic puts the system prompt at the top level (not in messages).
            put("system", systemPrompt)
            put("messages", messages)
            // Anthropic REQUIRES max_tokens (unlike OpenAI where it's optional).
            put("max_tokens", 4096)
            if (tools.isNotEmpty()) {
                val arr = JSONArray()
                for (t in tools) {
                    arr.put(JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        // Anthropic uses "input_schema" (not "parameters").
                        put("input_schema", JSONObject(t.parameters))
                    })
                }
                put("tools", arr)
                put("tool_choice", JSONObject().put("type", "auto"))
            }
        }
        return body.toString()
    }

    private fun parseAnthropicResponse(responseBody: String): LLMResponse {
        val json = JSONObject(responseBody)
        val usage = json.optJSONObject("usage")
        val tokensIn = usage?.optInt("input_tokens", 0) ?: 0
        val tokensOut = usage?.optInt("output_tokens", 0) ?: 0

        val content = json.optJSONArray("content")
            ?: run {
                val err = json.optJSONObject("error")?.optString("message", "Unknown Anthropic error")
                return LLMResponse("Error: $err", tokensIn = tokensIn, tokensOut = tokensOut)
            }

        val replySb = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type", "")) {
                "text" -> {
                    val text = block.optString("text", "")
                    if (text.isNotEmpty()) {
                        if (replySb.isNotEmpty()) replySb.append('\n')
                        replySb.append(text)
                    }
                }
                "tool_use" -> {
                    val name = block.optString("name", "")
                    val input = block.optJSONObject("input") ?: JSONObject()
                    val args = mutableMapOf<String, Any>()
                    val keys = input.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        normalizeJsonValue(input.get(k))?.let { args[k] = it }
                    }
                    val id = block.optString("id", "call_$i")
                    if (name.isNotEmpty()) toolCalls.add(ToolCall(id, name, args))
                }
                // "thinking" blocks are Claude's internal reasoning — skip.
            }
        }
        val reply = replySb.toString().ifBlank {
            if (toolCalls.isEmpty()) "(empty response)" else "Executing..."
        }
        return LLMResponse(reply, toolCalls, tokensIn, tokensOut)
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /**
     * Normalize org.json values to plain Kotlin types so tool handlers can
     * safely cast (as? String, as? Int, as? Map, as? List) without hitting
     * JSONObject / JSONArray.
     */
    private fun normalizeJsonValue(v: Any?): Any? = when (v) {
        is JSONObject -> {
            val map = mutableMapOf<String, Any>()
            val keys = v.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                normalizeJsonValue(v.get(k))?.let { map[k] = it }
            }
            map
        }
        is JSONArray -> {
            val list = mutableListOf<Any>()
            for (i in 0 until v.length()) {
                normalizeJsonValue(v.opt(i))?.let { list.add(it) }
            }
            list
        }
        JSONObject.NULL -> null
        else -> v
    }

    companion object {
        private const val TAG = "LLMClient"
    }
}
