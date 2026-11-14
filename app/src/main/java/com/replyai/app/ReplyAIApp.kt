package com.replyai.app

import android.app.Application
import com.replyai.app.data.ReplyAIDatabase
import com.replyai.app.embeddings.EmbeddingProviderFactory
import com.replyai.app.notifications.ReplyEngine

class ReplyAIApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize the database singleton on app start
        ReplyAIDatabase.init(this)
        // EmbeddingProviderFactory needs the app context to read SharedPreferences
        EmbeddingProviderFactory.init(this)
        // ReplyEngine holds an app-context reference for DB + LLM client access
        ReplyEngine.init(this)
    }
}
