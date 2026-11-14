# ReplyAI — Architecture

**Version:** 0.1.0 (initial design)
**Last updated:** 2026-11-13
**Status:** Active development

---

## 1. Purpose

ReplyAI is an Android app that auto-replies to messages (WhatsApp, Telegram, SMS, Slack, Instagram DM, and any app with a notification reply action) using a multimodal LLM grounded by both live web search and vector-RAG over user-uploaded documents.

**One-line pitch:** "A WhatsApp bot for everyone. Set it up in 30 seconds via conversation."

## 2. Differentiators vs. existing auto-responders

| Competitor | Approach | ReplyAI's advantage |
|-----------|----------|---------------------|
| AutoResponder for WA | Rule-based (keyword → fixed reply) | AI + RAG + conversation memory |
| Watomatic | Simple "I'm busy" auto-responder | LLM-generated contextual replies |
| SMS Auto Reply | Per-contact rules, no AI | AI handles unknown intents + escalates |
| IFTTT + ChatGPT | Webhook → ChatGPT → reply | Conversational setup, multi-app, dashboard |

**The wedge:** Conversational bot-builder. User talks to AI to create the bot. No forms, no keyword lists.

## 3. Tech stack

- **Framework:** Android SDK 34, Kotlin 1.9+, Jetpack Compose, Material 3
- **Build:** Gradle 8.10+ (Kotlin DSL)
- **Min SDK:** 26 (Android 8.0+ — covers ~95% of devices)
- **Target SDK:** 34
- **Package:** `com.replyai.app`
- **Database:** SQLite via custom AgentDatabase (FTS5 + vector BLOB columns)
- **DI:** Manual (singleton objects, like Aira)
- **Concurrency:** Kotlin Coroutines + Flow
- **Networking:** OkHttp (same as Aira)
- **LLM providers:** BYO-key — OpenAI, Gemini, Anthropic, Groq, OpenRouter, custom OpenAI-compatible
- **Embeddings providers:** Gemini (default), OpenAI, on-device MiniLM (fallback)
- **Tests:** JUnit 4 (same extraction pattern as Aira)

## 4. Reusable logic from Aira (concept-level, rewritten in `com.replyai.app`)

These are the proven algorithms Aira has shipped. ReplyAI rewrites them in its own package, keeping the logic but simplifying the structure.

| Module | Source in Aira | Why reuse |
|--------|---------------|-----------|
| NotificationListener reply | `NotificationListener.kt:412` `replyToNotification()` | RemoteInput-based reply — proven on WhatsApp + Telegram + SMS |
| Per-contact cooldown | `NotificationListener.kt` v4.0.67 | Prevents reply spam to same contact |
| Double-reply prevention | `RuleEngine.kt` v4.0.93 | Tracks if replyToNotification already succeeded |
| Pending reply queue | `NotificationListener.kt` v4.0.67 | Messages arriving during cooldown get queued |
| Per-contact conversation memory | `ConversationMemory.kt` | Last N exchanges per contact, injected into prompt |
| SMART rule execution | `RuleEngine.kt` | LLM loop with tools, calls replyToNotification + complete() |
| Consent overlay | `ConsentManager.kt` + `ConsentOverlay.kt` | For sensitive action approval |
| FTS5 knowledge | `AgentDatabase.kt` v3.4.0 | Keyword search over uploaded text |
| LLMClient | `LLMClient.kt` | Multi-provider chat + streaming |
| Tool executor pattern | `ToolExecutor.kt` | LLM calls tools (webSearch, replyToNotification, etc.) |
| ConsentDialog | `ConsentDialogOverlay.kt` | Compose UI for consent |

**What ReplyAI does NOT inherit from Aira:**
- Terminal system (no Linux env needed)
- MCP client (out of scope for v1.0)
- Hardware tools (NFC, IR, BLE — not relevant for an auto-reply bot)
- Accessibility service (not needed — we use NotificationListener, not screen-reading)
- Skills system (different abstraction — ReplyAI has "bots" not "skills")
- 12-level capability system (ReplyAI has a single mode per bot)
- Sub-agents (overkill for v1.0 — single agent loop per bot is enough)

## 5. Net-new modules (ReplyAI-unique)

| Module | Purpose | Est. effort |
|--------|---------|-------------|
| `EmbeddingProvider` interface + 3 impls | Pluggable embeddings (Gemini/OpenAI/on-device) | 2 days |
| `VectorStore` | SQLite + cosine similarity over chunk embeddings | 1 day |
| `Chunker` | Document → ~200-token chunks with 50-token overlap | 0.5 day |
| `BotBuilder` (conversational setup) | Chat screen where AI creates bots from natural language | 3 days |
| `BotTemplates` | 4 pre-built templates (Personal / Business / Support / Away) | 1 day |
| `DashboardScreen` | Live feed of replies + stats | 2 days |
| `BotsScreen` | List of configured bots + per-bot settings | 1 day |
| `KnowledgeScreen` | Upload files, view chunks, test retrieval | 1.5 days |
| `IntentClassifier` | Decides web-search vs vector-RAG vs direct reply per message | 1 day |
| `WebSearchGrounding` | `site:xyz.com <query>` → fetch page → extract answer | 1 day |
| `ActionLogger` + dashboard data layer | Records every reply for the dashboard | 1 day |
| `ReplyAIDatabase` | Extends Aira's pattern + embeddings table + per-bot knowledge | 1 day |
| `SafetyRules` | Quiet hours, rate limits, cost caps, escalation keywords | 1 day |
| `CostTracker` | Per-bot daily cost tracking, pause on cap exceed | 0.5 day |
| App icon + brand assets | From the logo, generate mipmap variants | 0.5 day |

**Total net-new: ~17 days.** Plus ~3 days adapting Aira modules. **~3 weeks at full pace** for v1.0.

## 6. Architecture diagram

```
┌─────────────────────────────────────────────────────────────┐
│                      ReplyAI App                             │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────┐    ┌─────────────────┐                │
│  │   Dashboard     │    │     Bots        │                │
│  │  (live feed)    │    │  (list + edit)  │                │
│  └────────┬────────┘    └────────┬────────┘                │
│           │                      │                          │
│           ▼                      ▼                          │
│  ┌──────────────────────────────────────────┐              │
│  │       Bot Builder (conversational)        │              │
│  │   "Talk to AI to create your bot"         │              │
│  └────────────────────┬─────────────────────┘              │
│                       │                                      │
│           ┌───────────┴───────────┐                          │
│           ▼                       ▼                          │
│  ┌─────────────────┐    ┌─────────────────┐                │
│  │   Knowledge     │    │   Settings       │                │
│  │ (upload + RAG)  │    │ (API keys etc.)  │                │
│  └────────┬────────┘    └─────────────────┘                │
│           │                                                  │
└───────────┼──────────────────────────────────────────────────┘
            │
            ▼
┌───────────────────────────────────────────────────────────┐
│                    Core engine                             │
├───────────────────────────────────────────────────────────┤
│                                                            │
│  NotificationListener ──► RuleEngine ──► LLMClient        │
│         │                  │    ▲          │               │
│         │                  ▼    │          ▼               │
│         │            IntentClassifier  ToolExecutor         │
│         │                  │                │               │
│         │         ┌────────┴───────┐        │               │
│         │         ▼                ▼        │               │
│         │    webSearch      VectorStore    │               │
│         │    (live RAG)     (doc RAG)       │               │
│         │                                    │               │
│         ▼                                    ▼               │
│  replyToNotification ◄────────────────────┘                 │
│  (RemoteInput reply via Notification.Action)               │
│                                                            │
│  ConversationMemory (per-contact, last N exchanges)        │
│  ActionLogger (every reply → dashboard)                    │
│  SafetyRules (quiet hours, rate limits, cost cap)         │
│                                                            │
└───────────────────────────────────────────────────────────┘
```

## 7. Data model

### SQLite tables (extends Aira's schema)

```sql
-- Bots (a bot = a configured auto-reply rule)
CREATE TABLE bots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    target_apps TEXT NOT NULL,        -- JSON array: ["com.whatsapp", "com.telegram"]
    system_prompt TEXT NOT NULL,
    personality TEXT,                  -- "professional", "friendly", etc.
    enabled INTEGER DEFAULT 1,
    confidence_threshold REAL DEFAULT 0.7,
    quiet_hours_start INTEGER DEFAULT 22,
    quiet_hours_end INTEGER DEFAULT 7,
    daily_message_cap INTEGER DEFAULT 10,
    daily_cost_cap_cents INTEGER DEFAULT 200,  -- $2.00
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Knowledge files (uploaded by user)
CREATE TABLE knowledge_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bot_id INTEGER NOT NULL,           -- per-bot knowledge
    file_name TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    source_type TEXT NOT NULL,         -- "upload" | "web_crawl"
    source_url TEXT,                   -- for web_crawl
    chunk_count INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (bot_id) REFERENCES bots(id) ON DELETE CASCADE
);

-- Document chunks (vector + FTS5)
CREATE TABLE chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id INTEGER NOT NULL,
    chunk_index INTEGER NOT NULL,
    text TEXT NOT NULL,
    embedding BLOB,                   -- FloatArray as bytes (4 bytes per float × dimensions)
    embedding_provider TEXT,           -- "gemini" | "openai" | "ondevice"
    FOREIGN KEY (file_id) REFERENCES knowledge_files(id) ON DELETE CASCADE
);

-- FTS5 for keyword search (complements vector search)
CREATE VIRTUAL TABLE chunks_fts USING fts5(
    text,
    content='chunks', content_rowid='rowid'
);

-- Conversation memory (per-contact)
CREATE TABLE conversations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bot_id INTEGER NOT NULL,
    contact_key TEXT NOT NULL,         -- "com.whatsapp:Mom"
    contact_name TEXT,
    last_message_at INTEGER,
    FOREIGN KEY (bot_id) REFERENCES bots(id) ON DELETE CASCADE
);

CREATE TABLE messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id INTEGER NOT NULL,
    direction TEXT NOT NULL,           -- "incoming" | "outgoing"
    text TEXT NOT NULL,
    knowledge_source TEXT,            -- "web:xyz.com" | "doc:menu.pdf" | "none"
    tool_calls TEXT,                   -- JSON of tools invoked
    cost_cents INTEGER DEFAULT 0,
    timestamp INTEGER NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);

-- Action log (for dashboard + feedback)
CREATE TABLE action_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bot_id INTEGER,
    action_type TEXT NOT NULL,        -- "reply_sent" | "escalated" | "blocked" | "error"
    app TEXT,
    contact TEXT,
    summary TEXT,
    details TEXT,                     -- JSON
    timestamp INTEGER NOT NULL
);

-- Daily cost tracking
CREATE TABLE daily_costs (
    date_ymd TEXT PRIMARY KEY,        -- "2026-11-13"
    bot_id INTEGER,
    tokens_used INTEGER DEFAULT 0,
    est_cost_cents INTEGER DEFAULT 0
);
```

### Cosine similarity (computed in Kotlin, not SQL)

```kotlin
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    return dot / (sqrt(normA) * sqrt(normB))
}
```

For larger knowledge bases (>10K chunks), we'd add approximate nearest neighbor (ANN) via FAISS or hnswlib. For v1.0, brute-force cosine over the bot's chunks (typically <500) is fine — ~5ms on a phone.

## 8. The 4 retrieval modes

ReplyAI supports **4 ways** to retrieve knowledge for a reply:

| Mode | When used | How it works |
|------|----------|--------------|
| **1. Direct reply (no retrieval)** | Greetings, small talk, off-topic | AI replies from system prompt alone |
| **2. FTS5 keyword search** | User has only text files, no embeddings configured | SQLite FTS5 MATCH query on chunks |
| **3. Vector search (Gemini/OpenAI embeddings)** | Semantic questions, paraphrased queries | Cosine similarity over chunk embeddings |
| **4. Live web search** | Dynamic info (prices, availability, today's specials) | `webSearch(site:xyz.com <query>)` + `fetchPageText(top URL)` |

**Intent classifier** picks the mode per incoming message:

```kotlin
suspend fun classifyIntent(message: String, bot: Bot): RetrievalMode {
    // 1. If greeting/small talk → DIRECT
    if (isGreeting(message)) return RetrievalMode.DIRECT
    // 2. If asks about today/now/price/availability → WEB_SEARCH
    if (DynamicKeywords.any { message.contains(it) }) return RetrievalMode.WEB_SEARCH
    // 3. If asks about policy/FAQ/specs → VECTOR_SEARCH (if embeddings configured)
    //    else FTS5 (if any knowledge files uploaded)
    if (bot.hasEmbeddings) return RetrievalMode.VECTOR_SEARCH
    if (bot.hasKnowledgeFiles) return RetrievalMode.FTS5
    // 4. Default: web search (if company URL configured), else direct
    return if (bot.companyUrl != null) RetrievalMode.WEB_SEARCH else RetrievalMode.DIRECT
}
```

## 9. Embeddings API design

```kotlin
interface EmbeddingProvider {
    val name: String
    val dimensions: Int
    val maxTokensPerChunk: Int
    suspend fun embed(text: String): FloatArray
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
}

class GeminiEmbeddings(private val apiKey: String) : EmbeddingProvider {
    override val name = "gemini"
    override val dimensions = 768
    override val maxTokensPerChunk = 2048
    // POST https://generativelanguage.googleapis.com/v1beta/models/embedding-001:embedContent
}

class OpenAIEmbeddings(private val apiKey: String) : EmbeddingProvider {
    override val name = "openai"
    override val dimensions = 1536
    override val maxTokensPerChunk = 8191
    // POST https://api.openai.com/v1/embeddings
}

class OnDeviceEmbeddings(private val context: Context) : EmbeddingProvider {
    override val name = "ondevice"
    override val dimensions = 384
    override val maxTokensPerChunk = 256
    // Uses ONNX Runtime + all-MiniLM-L6-v2 model (bundled in assets/)
}
```

**Selection logic:**
1. If user configured Gemini API key → use GeminiEmbeddings (default)
2. Else if user configured OpenAI API key → use OpenAIEmbeddings
3. Else → use OnDeviceEmbeddings (always available, no key needed)

User can override in Settings → Embeddings.

## 10. Safety + privacy

- **Per-contact rate limit:** default 10 replies/day per contact (configurable)
- **Per-bot daily cost cap:** default $2.00, pause bot on exceed
- **Quiet hours:** default 22:00-07:00 = different auto-reply or off
- **Approval mode:** for first N messages with a new contact (default N=0; user can enable)
- **Escalation keywords:** "urgent", "legal", "emergency", "complaint", "speak to human" → notify user immediately, don't auto-reply
- **Always log:** every reply in action_log + dashboard with feedback loop
- **Local encryption:** SQLCipher for the database (optional, off by default for v1.0)
- **No telemetry:** default off, opt-in only in v1.0
- **Clear data deletion:** delete bot → delete all its knowledge + conversations + logs

## 11. WhatsApp ToS considerations

Auto-replying to WhatsApp *personal* accounts is a grey area in WhatsApp's ToS (they want business bots to use their Business API). However:
- Apps like AutoResponder for WA, Watomatic, Tasker have done notification-based auto-reply for years without WhatsApp action
- The user's phone is replying, not a bot connecting to WhatsApp's servers
- We mitigate: rate limit (max 1 reply per contact per 5 min), never reply in groups unless @mentioned, clear warning in onboarding

**User assumes the risk.** ReplyAI provides the tool; the user chooses how to use it.

## 12. Testing strategy

Same pattern as Aira collab sessions:
1. Extract pure logic into `util/` objects (no Android dependencies)
2. JVM unit tests with JUnit 4
3. Test the engine, not the UI (UI is hard to JVM-test)
4. Document GAPs found during extraction

**Priority test targets:**
- `IntentClassifier` — does it pick the right retrieval mode?
- `Chunker` — correct chunk sizes + overlap?
- `VectorStore.cosineSimilarity` — math correct?
- `SafetyRules` — quiet hours, rate limits, cost caps enforced?
- `BotBuilder` prompt — does AI produce valid bot configs from natural language?
- `ConversationMemory` — per-contact history correctly scoped?
- `WebSearchGrounding` — site-scoped search query correct?

Target: **300+ JVM tests** for v1.0.

---

**End of ARCHITECTURE.md.** See BUILD_PLAN.md for the step-by-step build order.
