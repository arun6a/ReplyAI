package com.replyai.app.llm

/**
 * LLMProvider — enumerates supported LLM providers.
 *
 * Each provider carries the metadata the LLMClient needs to route a request:
 *  - displayName      : human-readable name shown in the UI
 *  - endpoint         : the base API URL. For Gemini the literal "{model}"
 *                        is substituted with the model id at request time.
 *  - needsApiKey      : whether the provider requires an API key
 *  - supportsTools    : whether the provider supports native function/tool calling
 *  - supportsStreaming: whether the provider supports SSE streaming
 *
 * Endpoints are hard-coded per the spec; only the CUSTOM provider reads its
 * endpoint from SharedPreferences (see LLMSettings.getCustomEndpoint).
 */
enum class LLMProvider(
    val displayName: String,
    val endpoint: String,
    val needsApiKey: Boolean,
    val supportsTools: Boolean,
    val supportsStreaming: Boolean,
) {
    OPENAI(
        displayName = "OpenAI",
        endpoint = "https://api.openai.com/v1/chat/completions",
        needsApiKey = true,
        supportsTools = true,
        supportsStreaming = true,
    ),
    GEMINI(
        displayName = "Google Gemini",
        // {model} is replaced at request time; the API key is appended as ?key=...
        endpoint = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        needsApiKey = true,
        supportsTools = true,
        supportsStreaming = true,
    ),
    ANTHROPIC(
        displayName = "Anthropic Claude",
        endpoint = "https://api.anthropic.com/v1/messages",
        needsApiKey = true,
        supportsTools = true,
        supportsStreaming = true,
    ),
    GROQ(
        displayName = "Groq",
        endpoint = "https://api.groq.com/openai/v1/chat/completions",
        needsApiKey = true,
        supportsTools = true,
        supportsStreaming = true,
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        endpoint = "https://openrouter.ai/api/v1/chat/completions",
        needsApiKey = true,
        supportsTools = true,
        supportsStreaming = true,
    ),
    CUSTOM(
        displayName = "Custom (OpenAI-compatible)",
        endpoint = "",  // read from SharedPreferences (LLMSettings.getCustomEndpoint)
        needsApiKey = false,
        supportsTools = true,
        supportsStreaming = true,
    );

    companion object {
        /**
         * Look up a provider by its case-insensitive enum name. Used when
         * reading the stored preference string back from SharedPreferences.
         * Falls back to OPENROUTER if the stored value is unknown/missing
         * (OPENROUTER has a generous free tier and OpenAI-compatible format).
         */
        fun fromName(name: String?): LLMProvider {
            if (name.isNullOrBlank()) return OPENROUTER
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: OPENROUTER
        }
    }
}
