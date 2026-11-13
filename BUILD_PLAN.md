# ReplyAI — Build Plan

**Started:** 2026-11-13
**Target v1.0 ship:** ~3 weeks of focused work
**Approach:** Fresh repo, selectively adapt Aira modules, build net-new ReplyAI-unique code

---

## Build phases

### Phase 0: Project setup (Day 1) — DONE
- [x] Create GitHub repo `arun6a/ReplyAI`
- [x] Clone locally
- [x] ARCHITECTURE.md committed
- [x] BUILD_PLAN.md (this file)
- [ ] Project skeleton: Gradle + manifest + theme + 4 empty screens
- [ ] Copy logo as app icon source

### Phase 1: Core engine (Days 2-5)
Adapt Aira's proven modules into `com.replyai.app` package.

- [ ] `ReplyAIDatabase.kt` — SQLite schema (bots, knowledge_files, chunks, conversations, messages, action_log, daily_costs) + FTS5
- [ ] `NotificationListenerService.kt` — adapted from Aira's NotificationListener:412, RemoteInput-based reply
- [ ] `RuleEngine.kt` — adapted from Aira's, simplified (no DIRECT/SMART split — everything is SMART in ReplyAI)
- [ ] `ConversationMemory.kt` — per-contact last-N exchanges, injected into prompt
- [ ] `LLMClient.kt` — multi-provider chat (OpenAI, Gemini, Anthropic, Groq, OpenRouter) — copy from Aira, trim
- [ ] `ToolExecutor.kt` — registers ReplyAI's tools (replyToNotification, webSearch, fetchPageText, recallKnowledge, sendSMS, makePhoneCall, analyzeImage, transcribeAudio)
- [ ] `ConsentManager.kt` + `ConsentOverlay.kt` — for sensitive action approval
- [ ] `SafetyRules.kt` — quiet hours, rate limits, cost caps, escalation keywords

**Deliverable:** A working auto-reply engine that can be invoked manually via ADB for testing. No UI yet.

### Phase 2: Knowledge & RAG (Days 6-9)
The novel subsystem — Aira doesn't have most of this.

- [ ] `EmbeddingProvider.kt` interface
- [ ] `GeminiEmbeddings.kt` (default — 768-dim, free tier)
- [ ] `OpenAIEmbeddings.kt` (1536-dim, $0.02/1M tokens)
- [ ] `OnDeviceEmbeddings.kt` (384-dim, MiniLM via ONNX Runtime, no key needed)
- [ ] `Chunker.kt` — document → ~200-token chunks with 50-token overlap
- [ ] `VectorStore.kt` — SQLite + cosine similarity over chunk embeddings
- [ ] `IntentClassifier.kt` — decides web vs vector vs FTS5 vs direct per message
- [ ] `WebSearchGrounding.kt` — `site:xyz.com <query>` → fetch → extract
- [ ] `KnowledgeManager.kt` — uploads, chunking, embedding, storage

**Deliverable:** Knowledge pipeline working end-to-end. Can upload a PDF, embed it, query it.

### Phase 3: UI — 4 screens (Days 10-14)
Professional, modern, Material 3 + Compose.

- [ ] Theme: emerald accent (#059669 — matches logo), light default, dark mode
- [ ] `MainActivity.kt` + navigation
- [ ] `DashboardScreen.kt` — live reply feed + 3 stat cards + master toggle
- [ ] `BotsScreen.kt` — bot list + per-bot toggle + 4 template cards
- [ ] `BotDetailScreen.kt` — edit bot config (target apps, system prompt, knowledge, escalation rules, quiet hours)
- [ ] `KnowledgeScreen.kt` — upload files, view chunks, test retrieval, per-bot scope
- [ ] `BotBuilderChatScreen.kt` — conversational bot creation (the differentiator)
- [ ] `SettingsScreen.kt` — LLM API keys, embeddings provider, master config

**Deliverable:** Full app, navigable. Bot Builder chat creates working bots.

### Phase 4: Templates + dashboard polish (Days 15-17)
- [ ] `BotTemplates.kt` — 4 templates:
  - Personal Assistant (friendly, deflects spam, escalates personal contacts)
  - Business Bot (professional, RAG from company website + uploaded docs)
  - Customer Support (formal, FAQ-first, ticket-creation language)
  - Away Responder (simple "I'm away until X")
- [ ] Dashboard feedback loop — "would you have replied differently?" + trains bot
- [ ] Per-conversation thread view (full history per contact)
- [ ] Cost tracker + daily cap enforcement
- [ ] App icon variants (mipmap-mdpi through xxxhdpi) generated from logo

**Deliverable:** v1.0 feature-complete.

### Phase 5: Testing + polish (Days 18-21)
- [ ] Extract pure logic to `util/` objects (same pattern as Aira)
- [ ] 300+ JVM unit tests
- [ ] Mutation testing on critical paths (IntentClassifier, VectorStore, SafetyRules)
- [ ] Onboarding flow (3 steps: permissions → API key → first bot)
- [ ] Privacy policy + terms of service (required for Play Store)
- [ ] Play Store listing assets (screenshots, description, feature graphic)
- [ ] v1.0 APK build + ship

**Deliverable:** v1.0 shipped to Play Store (or APK shared for beta).

---

## Parallel track: Aira monitoring

While building ReplyAI, I continue monitoring Aira:

- [ ] Fetch Aira commits at start of each session
- [ ] Audit each new commit for regressions + integration issues
- [ ] Run Aira's JVM test suite periodically (when toolchain survives)
- [ ] Report findings to user + relay to other AI via CONVERSATION_MEMORY.md

**Time allocation per session:**
- ~70% ReplyAI build
- ~20% Aira monitoring
- ~10% research/exploration as needed

---

## Milestones

| Milestone | Target | Status |
|-----------|--------|--------|
| M0: Repo + skeleton | Day 1 | 🔄 In progress |
| M1: Engine working (ADB-invocable) | Day 5 | ⏳ |
| M2: RAG pipeline working | Day 9 | ⏳ |
| M3: UI navigable, bot builder creates bots | Day 14 | ⏳ |
| M4: v1.0 feature-complete | Day 17 | ⏳ |
| M5: v1.0 shipped | Day 21 | ⏳ |

---

## Risk register

| Risk | Mitigation |
|------|-----------|
| Sandbox resets wipe toolchain | Commit frequently; rebuild toolchain as needed (5-10 min) |
| WhatsApp ToS grey area | Clear onboarding warning; rate limit; never spam |
| LLM cost runaway | Per-bot daily cap (default $2); per-contact rate limit (default 10/day) |
| Embedding quality issues | User can switch providers; on-device fallback always available |
| Notification reply format changes per app | Each app adapter isolated in `app_adapters/` for easy updates |
| Multi-platform maintenance burden | Start with WhatsApp + SMS + Telegram; add others by request |

---

## Commit conventions

- `v0.X.0: <feature>` — version bumps for significant work
- `wip: <description>` — work-in-progress commits
- `fix: <description>` — bug fixes
- `docs: <description>` — documentation only
- `test: <description>` — test additions

---

**End of BUILD_PLAN.md.**
