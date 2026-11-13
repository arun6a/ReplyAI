package com.replyai.app

import android.app.Application
import com.replyai.app.data.ReplyAIDatabase

class ReplyAIApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize the database singleton on app start
        ReplyAIDatabase.init(this)
    }
}
