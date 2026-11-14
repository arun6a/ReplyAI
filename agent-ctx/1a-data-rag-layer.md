# Task 1a — Data + RAG Layer

**Agent:** data-rag-layer (Z.ai Code)
**Date:** 2026-11-13
**Status:** COMPLETE
**Task ID:** 1a

## Goal

Build the data + RAG layer for ReplyAI: SQLite database with full schema,
text chunker, embedding providers (Gemini / OpenAI / on-device stub),
vector store with cosine similarity, and a provider factory.

## Files created (all under `app/src/main/java/com/replyai/app/`)

| # | Path | Lines | Purpose |
|---|------|-------|---------|
| 1 | `data/ReplyAIDatabase.kt` | 392 | Singleton SQLiteOpenHelper. Full schema (8 tables: bots, knowledge_files, chunks, chunks_fts, conversations, messages, action_log, daily_costs). FK cascade deletes enabled. FTS5 mirror of chunks.text with sync triggers. 7 data classes + 17 CRUD methods + cursor helpers. |
| 2 | `knowledge/Chunker.kt` | 67 | Splits text into ~200-token chunks with 50-token overlap. 1 token ~= 4 chars. Word-boundary snapping so words are never split mid-token. |
| 3 | `embeddings/EmbeddingProvider.kt` | 36 | Interface: `name`, `dimensions`, `maxTokensPerChunk`, suspend `embed(text)`, suspend `embedBatch(texts)`. |
| 4 | `embeddings/GeminiEmbeddings.kt` | 75 | POST to `embedding-001:embedContent` with `?key=API_KEY`. dimensions=768, maxTokens=2048. Parses `embedding.values`. Sequential batch (single-text endpoint). |
| 5 | `embeddings/OpenAIEmbeddings.kt` | 96 | POST to `/v1/embeddings` with `model=text-embedding-3-small`, Bearer auth. dimensions=1536, maxTokens=8191. Parses `data[0].embedding`. Real batch endpoint for `embedBatch`. |
| 6 | `embeddings/OnDeviceEmbeddings.kt` | 45 | Phase 2 stub. dimensions=384, maxTokens=256. Throws `NotImplementedError`. KDoc documents the ONNX Runtime + MiniLM plan. |
| 7 | `embeddings/VectorStore.kt` | 110 | `storeEmbedding`, `searchSimilar` (brute-force cosine over bot's chunks, ~5 ms for <500 chunks), `cosineSimilarity` pure math. Little-endian FloatArray<->ByteArray codec is private to this file. `SearchResult` data class. |
| 8 | `embeddings/EmbeddingProviderFactory.kt` | 80 | Reads Gemini/OpenAI keys from SharedPreferences (`replyai_settings`). Preference order: Gemini -> OpenAI -> OnDevice. Provides set/get helpers + `activeProviderName()`. Requires `init(context)` to be called before `create()`. |

**Totals:** 8 files, 901 lines. All files under the 400-line limit.

## Key design decisions

1. **Singleton pattern:** `ReplyAIDatabase` is a `class` (not `object`) with a
   private constructor and a `companion object` holding `init(context)` +
   `getInstance()`. Double-checked locking on the instance. The existing
   `ReplyAIApp.onCreate()` already calls `ReplyAIDatabase.init(this)`, so no
   app-side wiring change was needed.

2. **FTS5 mirror of chunks.text:** External-content table
   (`content='chunks', content_rowid='rowid'`) with porter + unicode61
   tokenizer and AFTER INSERT/DELETE/UPDATE triggers. FK cascade deletes on
   chunks fire the AFTER DELETE trigger, so deleting a bot or knowledge file
   keeps the FTS index consistent. `searchChunksFts` falls back to LIKE on
   older SQLite that lacks FTS5.

3. **Cosine similarity in Kotlin, not SQL:** Per ARCHITECTURE.md section 7,
   brute-force scan over the bot's chunks is fine for v1.0 (<500 chunks per
   bot). VectorStore loads all embedded chunks via `listKnowledgeFiles` +
   `getChunksByFile`, computes cosine in a tight loop, returns top-k. The
   embedding BLOB format is little-endian FloatArray (4 bytes per float).

4. **Provider selection by configured key:** `EmbeddingProviderFactory` reads
   `gemini_api_key` / `openai_api_key` from SharedPreferences. Gemini wins over
   OpenAI wins over OnDevice. No database dependency for key storage — keys
   live in prefs so the Settings screen can edit them without touching the DB.

5. **Chunker word-boundary snapping:** Slides a `targetTokens * 4`-char window
   over the text, extending the end past any non-whitespace so words are
   never split. The next chunk's start advances by `step = target - overlap`
   chars, then snaps forward to a word start. Guard ensures forward progress
   on degenerate input (single very long word).

## API surface exposed to subsequent tasks

```kotlin
// Database
ReplyAIDatabase.init(context)                  // call from Application.onCreate()
ReplyAIDatabase.getInstance()
  .createBot(bot) / getBot(id) / listBots() / updateBot(bot) / deleteBot(id)
  .saveKnowledgeFile(botId, name, size, sourceType, sourceUrl?)
  .listKnowledgeFiles(botId) / deleteKnowledgeFile(id) / updateChunkCount(fileId, n)
  .saveChunk(fileId, idx, text, embedding?, provider?) / getChunksByFile(fileId)
  .searchChunksFts(query, limit)               // returns List<Pair<Chunk, fileName>>
  .getConversation(botId, contactKey)          // get-or-create
  .saveMessage(convId, dir, text, knowledgeSource?, toolCalls?, costCents)
  .getConversationMessages(convId, limit)
  .logAction(botId?, type, app?, contact?, summary?, details?)
  .getRecentActions(limit) / addDailyCost(dateYmd, botId, tokens, cents) / getDailyCost(dateYmd, botId)

// Chunking
Chunker.chunk(text, targetTokens=200, overlapTokens=50): List<String>

// Embeddings
EmbeddingProviderFactory.init(context)
EmbeddingProviderFactory.create(): EmbeddingProvider
provider.embed(text): FloatArray   // suspend
provider.embedBatch(texts): List<FloatArray>

// Vector store
VectorStore.storeEmbedding(fileId, idx, text, embedding, provider)
VectorStore.searchSimilar(queryEmbedding, botId, limit=5): List<SearchResult>
VectorStore.cosineSimilarity(a, b): Float
```

## Things the next agent should know

- `ReplyAIDatabase.getInstance()` throws `IllegalStateException` if
  `init(context)` was not called first. `ReplyAIApp.onCreate()` already does
  this, so app-context code is safe; tests need to call `init()` manually or
  construct the helper directly.
- `EmbeddingProviderFactory` is NOT yet wired into `ReplyAIApp.onCreate()`.
  A future agent wiring up the Settings screen should add
  `EmbeddingProviderFactory.init(this)` to `ReplyAIApp.onCreate()` so
  `create()` works without each caller passing context.
- `OnDeviceEmbeddings.embed(...)` throws `NotImplementedError`. The factory
  returns it when no cloud key is configured, so callers of `create()` must
  be ready to handle this until Phase 2 lands the ONNX implementation.
- The `chunks_fts` table is created best-effort: if the device's SQLite
  lacks FTS5, `createChunksFts` logs a warning and `searchChunksFts` falls
  back to LIKE. Don't assume FTS5 is always present.
- `searchChunksFts` returns `List<Pair<Chunk, String>>` (chunk + file name)
  rather than just text, so the RAG pipeline can populate the
  `messages.knowledge_source` field with `doc:<file_name>`.
- `daily_costs` has a composite primary key `(date_ymd, bot_id)`.
  `addDailyCost` uses INSERT OR IGNORE + UPDATE inside a transaction (the
  pre-3.24 UPSERT pattern) because minSdk=26 may ship an older SQLite
  without `ON CONFLICT ... DO UPDATE`.

## Verification

- File count: 8 (matches the spec).
- All files under 400 lines (largest: `ReplyAIDatabase.kt` at 392).
- All packages declared correctly (`com.replyai.app.data`,
  `com.replyai.app.knowledge`, `com.replyai.app.embeddings`).
- No emojis in code.
- OkHttp + org.json + kotlinx.coroutines used per build.gradle.kts.
- Could not run a full Gradle compile (no Android SDK in this sandbox);
  code was reviewed manually for syntax + type correctness. The one
  uncertain spot — `execSQL(sql, arrayOf<Any?>(...))` for the daily-cost
  UPSERT — uses `Array<Any?>` to match the Java `Object[]` parameter
  exactly, avoiding any platform-type variance ambiguity.
