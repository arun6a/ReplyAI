package com.replyai.app.embeddings

import android.content.Context

/**
 * EmbeddingProviderFactory — picks the best available [EmbeddingProvider]
 * based on which API key the user has configured in Settings.
 *
 * Preference order (ARCHITECTURE.md section 9):
 *   1. GeminiEmbeddings   — if a Gemini API key is set (default, free tier)
 *   2. OpenAIEmbeddings   — else if an OpenAI API key is set
 *   3. OnDeviceEmbeddings — else (always available, no key needed; Phase 2 stub)
 *
 * Keys are stored in SharedPreferences so they survive app restarts and can
 * be edited from the Settings screen without touching the database. Call
 * [init] once from Application.onCreate() before [create] is used.
 */
object EmbeddingProviderFactory {

    private const val PREFS_NAME = "replyai_settings"
    private const val KEY_GEMINI = "gemini_api_key"
    private const val KEY_OPENAI = "openai_api_key"

    @Volatile private var appContext: Context? = null

    /** Call once from Application.onCreate(). Safe to call multiple times. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ctx(): Context =
        appContext ?: error("EmbeddingProviderFactory.init(context) must be called first")

    private fun prefs() = ctx().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the best available embedding provider. Gemini wins over OpenAI
     * wins over OnDevice. Falls back to [OnDeviceEmbeddings] when no cloud
     * key is configured — note that stub throws NotImplementedError until
     * Phase 2 lands.
     */
    fun create(): EmbeddingProvider {
        val prefs = prefs()
        val geminiKey = prefs.getString(KEY_GEMINI, "").orEmpty()
        if (geminiKey.isNotEmpty()) return GeminiEmbeddings(geminiKey)
        val openaiKey = prefs.getString(KEY_OPENAI, "").orEmpty()
        if (openaiKey.isNotEmpty()) return OpenAIEmbeddings(openaiKey)
        return OnDeviceEmbeddings(ctx())
    }

    // === Setters (used by Settings screen) ===

    fun setGeminiKey(key: String?) {
        prefs().edit().putString(KEY_GEMINI, key).apply()
    }

    fun setOpenAIKey(key: String?) {
        prefs().edit().putString(KEY_OPENAI, key).apply()
    }

    // === Getters (used by Settings screen + diagnostics) ===

    fun getGeminiKey(): String = prefs().getString(KEY_GEMINI, "").orEmpty()

    fun getOpenAIKey(): String = prefs().getString(KEY_OPENAI, "").orEmpty()

    fun hasGeminiKey(): Boolean = getGeminiKey().isNotEmpty()

    fun hasOpenAIKey(): Boolean = getOpenAIKey().isNotEmpty()

    /**
     * Human-readable name of the provider that [create] would currently
     * return. Used by the Settings screen to show "Active: Gemini" etc.
     */
    fun activeProviderName(): String = when {
        hasGeminiKey() -> "gemini"
        hasOpenAIKey() -> "openai"
        else -> "ondevice"
    }
}
