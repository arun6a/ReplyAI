# ReplyAI

AI auto-reply bot for WhatsApp, Telegram, SMS, Slack, and any app with notification reply support.

## What it does

ReplyAI listens to incoming message notifications and auto-replies using a multimodal LLM grounded by:
- **Live web search** — for dynamic info (prices, availability, today's specials)
- **Vector RAG** — for uploaded documents (PDFs, FAQs, knowledge files)
- **FTS5 keyword search** — fallback for text-only knowledge
- **Direct reply** — for greetings, small talk, off-topic

The killer feature: **conversational bot setup.** You talk to AI to create your bot — no forms, no keyword lists. "I run a bakery, use my menu PDF for knowledge, reply professionally to WhatsApp customers" → bot created.

## Tech stack

- **Platform:** Android (min SDK 26, target 34)
- **Language:** Kotlin 1.9+
- **UI:** Jetpack Compose + Material 3
- **DB:** SQLite + FTS5 + vector embeddings
- **LLM:** BYO-key — OpenAI, Gemini, Anthropic, Groq, OpenRouter
- **Embeddings:** Gemini (default), OpenAI, on-device MiniLM (fallback)
- **Package:** `com.replyai.app`

## Status

**v0.1.0 — initial design + project skeleton** (2026-11-13)

See [ARCHITECTURE.md](ARCHITECTURE.md) for full design + [BUILD_PLAN.md](BUILD_PLAN.md) for the build roadmap.

## Building

```bash
# Clone
git clone https://github.com/arun6a/ReplyAI.git
cd ReplyAI

# Set up Android SDK
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Build debug APK
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# Run JVM unit tests
./gradlew testDebugUnitTest
```

## License

MIT — see LICENSE (to be added)

## Origin

Built alongside [Aira](https://github.com/arun6a/ai-agent-v2) — Aira's proven auto-reply engine (NotificationListener RemoteInput reply, per-contact cooldown, conversation memory) is the foundation for ReplyAI.
