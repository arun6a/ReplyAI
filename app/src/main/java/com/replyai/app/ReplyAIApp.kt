package com.replyai.app

import android.app.Application
import android.os.Environment
import com.replyai.app.data.ReplyAIDatabase
import com.replyai.app.embeddings.EmbeddingProviderFactory
import com.replyai.app.knowledge.DocumentParser
import com.replyai.app.notifications.ReplyEngine
import com.replyai.app.util.LogCapture
import java.io.File

class ReplyAIApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize the database singleton on app start
        ReplyAIDatabase.init(this)

        // Initialize the embeddings factory with app context (so it can read
        // SharedPreferences + assets to pick the right provider)
        EmbeddingProviderFactory.init(this)

        // Initialize the ReplyEngine (needs context for ConversationMemory
        // persistence + BotManager + LLMClient reads)
        ReplyEngine.init(this)

        // Initialize the DocumentParser's PDFBox resource loader so .pdf
        // knowledge uploads work. (Other formats don't need init; PDF does
        // because PDFBox ships native libraries that must be unpacked once.)
        DocumentParser.init(this)

        // Enable file logging to /Aira/replyai.log so the user can collect
        // full-loop logs for debugging (no logcat access needed on MIUI).
        // Stored on shared external storage so it's accessible via file
        // managers + share intents.
        try {
            val dir = File(
                Environment.getExternalStorageDirectory(),
                "Aira"
            )
            if (!dir.exists()) dir.mkdirs()
            LogCapture.logFilePath = File(dir, "replyai.log").absolutePath
            LogCapture.i("ReplyAIApp", "ReplyAI started — logs at ${LogCapture.logFilePath}")
        } catch (e: Exception) {
            // Fallback: in-memory only
            android.util.Log.w("ReplyAIApp", "Could not enable file logging: ${e.message}")
        }
    }
}
