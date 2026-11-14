package com.replyai.app.ui.screens

/**
 * BotTemplates — four pre-built bot configurations.
 *
 * Each template captures the most common ReplyAI use-case in a single tap:
 *   - PERSONAL_ASSISTANT — friendly bot for personal WhatsApp / SMS
 *   - BUSINESS_BOT       — professional bot for business inquiries + web RAG
 *   - CUSTOMER_SUPPORT   — formal bot for support / FAQ / ticket creation
 *   - AWAY_RESPONDER     — minimal "I'm away" auto-responder
 *
 * Used by [BotsScreen] as one-tap template cards. Selecting a template
 * calls [com.replyai.app.bots.BotManager.createBot] with the template's
 * fields pre-filled, then routes the user to [BotDetailScreen] to fine-tune.
 *
 * `suggestedApps` uses real Android package names so the new bot starts in
 * a runnable state — no extra setup needed.
 */
object BotTemplates {

    data class Template(
        val name: String,
        val description: String,
        val systemPrompt: String,
        val personality: String,
        val suggestedApps: List<String>,
    )

    val PERSONAL_ASSISTANT = Template(
        name = "Personal Assistant",
        description = "Friendly bot for personal WhatsApp messages",
        systemPrompt = "You are a friendly personal assistant replying on behalf of the user. " +
            "Keep replies short, warm, and helpful. Deflect spam politely. If the message seems " +
            "urgent, suggest the user call back later.",
        personality = "Friendly",
        suggestedApps = listOf("com.whatsapp", "com.androidmessaging"),
    )

    val BUSINESS_BOT = Template(
        name = "Business Bot",
        description = "Professional bot for business inquiries",
        systemPrompt = "You are a professional assistant for a business. Reply formally using " +
            "the company knowledge base. For product/price questions, use web search against the " +
            "company website. For policy questions, use uploaded knowledge files. Always offer to " +
            "escalate to a human for complex issues.",
        personality = "Professional",
        suggestedApps = listOf("com.whatsapp.w4b", "com.telegram"),
    )

    val CUSTOMER_SUPPORT = Template(
        name = "Customer Support",
        description = "Formal bot for customer support",
        systemPrompt = "You are a formal customer support agent. FAQ-first approach: always " +
            "check knowledge files before answering. Use ticket-creation language ('I've created " +
            "ticket #1234 for you'). Escalate any complaint, refund request, or legal matter to " +
            "a human immediately.",
        personality = "Formal",
        suggestedApps = listOf("com.whatsapp.w4b", "com.slack"),
    )

    val AWAY_RESPONDER = Template(
        name = "Away Responder",
        description = "Simple 'I'm away' auto-responder",
        systemPrompt = "You are a simple auto-responder. Reply with a brief 'I'm away until " +
            "[time]' message. If the message seems urgent, provide the user's phone number for " +
            "emergencies.",
        personality = "Casual",
        suggestedApps = listOf("com.whatsapp", "com.telegram", "com.androidmessaging"),
    )

    val ALL: List<Template> = listOf(
        PERSONAL_ASSISTANT,
        BUSINESS_BOT,
        CUSTOMER_SUPPORT,
        AWAY_RESPONDER,
    )
}
