package com.replyai.app.llm

/**
 * ToolDefinitions — JSON-schema definitions for the 8 built-in tools,
 * suitable for sending to any LLM that supports native function calling
 * (OpenAI, Gemini, Anthropic, Groq, OpenRouter, Custom).
 *
 * Each definition is a [ToolDefinition] with a [Map]<String, Any> parameters
 * field that mirrors the JSON Schema shape:
 *   { "type":"object", "properties":{...}, "required":[...] }
 *
 * The LLMClient converts each [ToolDefinition] to the provider-specific
 * request format (OpenAI: tools[].function.parameters; Gemini:
 * tools[].function_declarations.parameters; Anthropic: tools[].input_schema).
 */
object ToolDefinitions {

    val REPLY_TO_NOTIFICATION = ToolDefinition(
        name = "replyToNotification",
        description = "Send an auto-reply to the most recent notification from a given app " +
            "(e.g. WhatsApp, Telegram, SMS). Requires the package name of the source app and " +
            "the text to send as a reply.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "package" to mapOf(
                    "type" to "string",
                    "description" to "Android package name of the source app, e.g. com.whatsapp",
                ),
                "replyText" to mapOf(
                    "type" to "string",
                    "description" to "The reply text to send back to the contact.",
                ),
            ),
            "required" to listOf("package", "replyText"),
        ),
    )

    val WEB_SEARCH = ToolDefinition(
        name = "webSearch",
        description = "Search the web for current information. Returns a list of result " +
            "snippets with titles and URLs. Use this when the user asks about something " +
            "outside your training data or that needs up-to-date facts.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "The search query.",
                ),
                "maxResults" to mapOf(
                    "type" to "integer",
                    "description" to "Maximum number of results to return (default 5).",
                ),
            ),
            "required" to listOf("query"),
        ),
    )

    val FETCH_PAGE_TEXT = ToolDefinition(
        name = "fetchPageText",
        description = "Fetch the visible text content of a web page at the given URL. " +
            "Useful for reading an article or extracting context after a webSearch.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "url" to mapOf(
                    "type" to "string",
                    "description" to "Absolute URL to fetch.",
                ),
            ),
            "required" to listOf("url"),
        ),
    )

    val RECALL_KNOWLEDGE = ToolDefinition(
        name = "recallKnowledge",
        description = "Search the local knowledge base (uploaded documents) for chunks " +
            "relevant to the query. Returns the most similar text chunks. Use this when " +
            "the user asks about something documented in their uploaded files.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "Natural-language query.",
                ),
                "topK" to mapOf(
                    "type" to "integer",
                    "description" to "Max chunks to return (default 5).",
                ),
            ),
            "required" to listOf("query"),
        ),
    )

    val SEND_SMS = ToolDefinition(
        name = "sendSMS",
        description = "Send an SMS message to a phone number. Requires SEND_SMS permission.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "number" to mapOf(
                    "type" to "string",
                    "description" to "Recipient phone number (E.164 or local format).",
                ),
                "text" to mapOf(
                    "type" to "string",
                    "description" to "Message body.",
                ),
            ),
            "required" to listOf("number", "text"),
        ),
    )

    val MAKE_PHONE_CALL = ToolDefinition(
        name = "makePhoneCall",
        description = "Place a phone call to the given number. Requires the CALL_PHONE permission.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "number" to mapOf(
                    "type" to "string",
                    "description" to "Phone number to call.",
                ),
            ),
            "required" to listOf("number"),
        ),
    )

    val ANALYZE_IMAGE = ToolDefinition(
        name = "analyzeImage",
        description = "Run a vision-capable LLM on the given image and return a textual " +
            "description or answer to the question. Use when the user asks about the " +
            "contents of an image.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "imagePath" to mapOf(
                    "type" to "string",
                    "description" to "Absolute path or content URI of the image.",
                ),
                "question" to mapOf(
                    "type" to "string",
                    "description" to "Question to answer about the image.",
                ),
            ),
            "required" to listOf("imagePath", "question"),
        ),
    )

    val COMPLETE = ToolDefinition(
        name = "complete",
        description = "Signal that the agent's task is complete and the conversation loop " +
            "should end. Always call this when the user's request has been fulfilled.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "success" to mapOf(
                    "type" to "boolean",
                    "description" to "Whether the task completed successfully.",
                ),
                "reason" to mapOf(
                    "type" to "string",
                    "description" to "Short summary of what was done.",
                ),
            ),
            "required" to listOf("success"),
        ),
    )

    /** All built-in tools in a stable order. */
    val ALL: List<ToolDefinition> = listOf(
        REPLY_TO_NOTIFICATION,
        WEB_SEARCH,
        FETCH_PAGE_TEXT,
        RECALL_KNOWLEDGE,
        SEND_SMS,
        MAKE_PHONE_CALL,
        ANALYZE_IMAGE,
        COMPLETE,
    )
}
