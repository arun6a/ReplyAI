package com.replyai.app.llm

import android.content.Context

/**
 * LLMSettings — typed accessor for LLM configuration stored in
 * SharedPreferences ("replyai_prefs").
 *
 * Keys:
 *  - llm_provider : LLMProvider enum name (e.g. "OPENROUTER")
 *  - llm_api_key  : provider API key (plaintext — app-local only)
 *  - llm_model    : model id (e.g. "google/gemini-2.5-flash")
 *  - llm_endpoint : custom endpoint (only used by LLMProvider.CUSTOM)
 *
 * All accessors take a Context and read from the application-wide prefs so
 * they're safe to call from any thread (SharedPreferences is thread-safe).
 */
object LLMSettings {
    private const val PREFS = "replyai_prefs"
    private const val KEY_PROVIDER = "llm_provider"
    private const val KEY_API_KEY = "llm_api_key"
    private const val KEY_MODEL = "llm_model"
    private const val KEY_ENDPOINT = "llm_endpoint"

    fun getProvider(context: Context): LLMProvider {
        val name = prefs(context).getString(KEY_PROVIDER, null)
        return LLMProvider.fromName(name)
    }

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, "") ?: ""

    /** Custom endpoint — only meaningful for LLMProvider.CUSTOM. */
    fun getCustomEndpoint(context: Context): String =
        prefs(context).getString(KEY_ENDPOINT, "") ?: ""

    fun setProvider(context: Context, provider: LLMProvider) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key).apply()
    }

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, model).apply()
    }

    fun setCustomEndpoint(context: Context, endpoint: String) {
        prefs(context).edit().putString(KEY_ENDPOINT, endpoint).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
