package com.replyai.app.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.replyai.app.bots.BotManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BotBuilderScreen — the conversational bot-creation flow.
 *
 * This is ReplyAI's differentiator vs. form-based competitors. Instead of
 * editing a wall of settings, the user chats with an AI "assistant" that
 * walks them through setup one question at a time.
 *
 * v1 uses a deterministic state machine (no LLM call yet). Each user turn
 * advances the state, with a canned assistant reply that confirms what was
 * collected and asks the next question. After the knowledge question, the
 * assistant generates a sample reply so the user can preview the bot's voice
 * before activating.
 *
 * The "Create bot" button at the bottom persists the collected config via
 * [BotManager.createBot] and routes back to the previous screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotBuilderScreen(
    onBack: () -> Unit = {},
    onBotCreated: (Long) -> Unit = {},
) {
    val viewModel: BotBuilderViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    // Auto-scroll to the latest message.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Build a bot", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (state.canCreate) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Button(
                        onClick = { viewModel.createBot(onCreated = onBotCreated) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .navigationBarsPadding(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Filled.SmartToy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Create bot", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Type your answer…") },
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions.Default,
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                val text = draft.trim()
                                if (text.isNotEmpty()) {
                                    viewModel.send(text)
                                    draft = ""
                                    keyboard?.hide()
                                }
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.messages, key = { it.id }) { msg ->
                ChatBubble(msg)
            }
            // Bottom padding so the input bar doesn't cover the last bubble.
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ------------------------------------------------------------------
// Chat bubble
// ------------------------------------------------------------------

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            // Assistant avatar.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 16.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 16.dp,
                    bottomEnd = 16.dp,
                    bottomStart = 16.dp,
                ),
                color = if (isUser) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Text(
                formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

// ------------------------------------------------------------------
// ViewModel + state
// ------------------------------------------------------------------

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val timestamp: Long,
)

enum class BuilderStep {
    GREETING,
    ASK_USE_CASE,
    ASK_TONE,
    ASK_APPS,
    ASK_KNOWLEDGE,
    SHOW_SAMPLE,
    READY,
}

data class BotBuilderUiState(
    val messages: List<ChatMessage> = emptyList(),
    val step: BuilderStep = BuilderStep.GREETING,
    val useCase: String = "",
    val tone: String = "Friendly",
    val apps: List<String> = listOf("com.whatsapp"),
    val includeKnowledge: Boolean = false,
    val knowledgeMode: String = "",
) {
    val canCreate: Boolean get() = step == BuilderStep.READY
}

class BotBuilderViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(BotBuilderUiState())
    val uiState: StateFlow<BotBuilderUiState> = _uiState.asStateFlow()

    private var msgSeq = 0L
    private fun nextMsgId(): Long = ++msgSeq

    init {
        // Seed the conversation with the assistant's greeting.
        post(
            ChatRole.ASSISTANT,
            "Hi! I'll help you set up your bot. What kind of messages do you want it " +
                "to handle? (e.g. 'customer inquiries', 'personal WhatsApp', 'after-hours replies')",
        )
        _uiState.value = _uiState.value.copy(step = BuilderStep.ASK_USE_CASE)
    }

    fun send(text: String) {
        post(ChatRole.USER, text)
        when (_uiState.value.step) {
            BuilderStep.ASK_USE_CASE -> handleUseCase(text)
            BuilderStep.ASK_TONE -> handleTone(text)
            BuilderStep.ASK_APPS -> handleApps(text)
            BuilderStep.ASK_KNOWLEDGE -> handleKnowledge(text)
            else -> {
                // After READY — let user re-affirm; suggest tapping Create.
                post(ChatRole.ASSISTANT, "Looks good! Tap 'Create bot' below to activate your bot.")
            }
        }
    }

    private fun handleUseCase(text: String) {
        val useCase = text.take(200)
        _uiState.value = _uiState.value.copy(useCase = useCase)
        post(
            ChatRole.ASSISTANT,
            "Got it — \"${useCase.take(80)}\". What tone should replies use? " +
                "(Professional / Friendly / Casual / Formal)",
        )
        _uiState.value = _uiState.value.copy(step = BuilderStep.ASK_TONE)
    }

    private fun handleTone(text: String) {
        val tone = detectTone(text)
        _uiState.value = _uiState.value.copy(tone = tone)
        post(
            ChatRole.ASSISTANT,
            "Tone set to $tone. Which apps should I watch? " +
                "(WhatsApp, Telegram, SMS, Slack, Discord, Messenger, Instagram)",
        )
        _uiState.value = _uiState.value.copy(step = BuilderStep.ASK_APPS)
    }

    private fun handleApps(text: String) {
        val apps = detectApps(text)
        if (apps.isEmpty()) {
            post(
                ChatRole.ASSISTANT,
                "I didn't catch any apps. Try again — WhatsApp, Telegram, SMS, Slack, etc.",
            )
            return
        }
        _uiState.value = _uiState.value.copy(apps = apps)
        val appNames = apps.joinToString(", ") { appNameForPackage(it) }
        post(
            ChatRole.ASSISTANT,
            "Watching: $appNames. Want to add knowledge files or use your website for grounding? " +
                "(Yes files / Yes website / No)",
        )
        _uiState.value = _uiState.value.copy(step = BuilderStep.ASK_KNOWLEDGE)
    }

    private fun handleKnowledge(text: String) {
        val mode = detectKnowledgeMode(text)
        _uiState.value = _uiState.value.copy(
            includeKnowledge = mode.isNotEmpty(),
            knowledgeMode = mode,
        )
        val sample = generateSampleReply(
            useCase = _uiState.value.useCase,
            tone = _uiState.value.tone,
            hasKnowledge = mode.isNotEmpty(),
        )
        post(
            ChatRole.ASSISTANT,
            "Here's a sample reply for 'do you have X?':\n\n\"$sample\"",
        )
        post(
            ChatRole.ASSISTANT,
            "Look good? Tap 'Create bot' to activate.",
        )
        _uiState.value = _uiState.value.copy(step = BuilderStep.READY)
    }

    fun createBot(onCreated: (Long) -> Unit) {
        val s = _uiState.value
        val prompt = buildSystemPrompt(s.useCase, s.tone, s.includeKnowledge, s.knowledgeMode)
        viewModelScope.launch {
            val id = BotManager.createBot(
                context = getApplication(),
                name = deriveBotName(s.useCase, s.tone),
                targetApps = s.apps,
                systemPrompt = prompt,
                personality = s.tone,
                enabled = true,
            )
            if (id > 0) onCreated(id)
        }
    }

    private fun post(role: ChatRole, text: String) {
        val msg = ChatMessage(
            id = nextMsgId(),
            role = role,
            text = text,
            timestamp = System.currentTimeMillis(),
        )
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + msg)
    }
}

// ------------------------------------------------------------------
// Detection helpers
// ------------------------------------------------------------------

private fun detectTone(text: String): String {
    val t = text.lowercase(Locale.US)
    return when {
        t.contains("professional") || t.contains("business") -> "Professional"
        t.contains("friendly") || t.contains("warm") || t.contains("casual") || t.contains("chill") -> {
            if (t.contains("casual")) "Casual" else "Friendly"
        }
        t.contains("casual") -> "Casual"
        t.contains("formal") || t.contains("strict") -> "Formal"
        else -> "Friendly"
    }
}

private fun detectApps(text: String): List<String> {
    val t = text.lowercase(Locale.US)
    val apps = mutableListOf<String>()
    if (t.contains("whatsapp")) {
        if (t.contains("business") || t.contains("w4b")) apps.add("com.whatsapp.w4b")
        else apps.add("com.whatsapp")
    }
    if (t.contains("telegram")) apps.add("com.telegram")
    if (t.contains("sms") || t.contains("text message")) apps.add("com.androidmessaging")
    if (t.contains("slack")) apps.add("com.slack")
    if (t.contains("discord")) apps.add("com.discord")
    if (t.contains("messenger")) apps.add("com.facebook.orca")
    if (t.contains("instagram") || t.contains("insta")) apps.add("com.instagram.android")
    // Default if nothing recognised but user clearly responded.
    if (apps.isEmpty() && (t.contains("all") || t.contains("every"))) {
        apps.add("com.whatsapp")
        apps.add("com.telegram")
    }
    return apps.distinct()
}

private fun detectKnowledgeMode(text: String): String {
    val t = text.lowercase(Locale.US)
    return when {
        t.contains("file") -> "files"
        t.contains("website") || t.contains("web") || t.contains("url") -> "website"
        t.startsWith("y") || t.contains("yes") || t.contains("sure") -> "files"
        else -> ""
    }
}

private fun generateSampleReply(
    useCase: String,
    tone: String,
    hasKnowledge: Boolean,
): String {
    val base = when (tone.lowercase(Locale.US)) {
        "professional" ->
            "Thank you for your message. Yes, we do carry that item — pricing and availability below."
        "friendly" ->
            "Hey! Great question — yes, we have that in stock. Want me to share details?"
        "casual" ->
            "Hey there — yep, got it. Want me to send over details?"
        "formal" ->
            "Good day. Thank you for your inquiry. We do offer the requested product."
        else ->
            "Yes, we have that available. I'd be happy to share more details."
    }
    return if (hasKnowledge) {
        "$base I've checked our knowledge base and can share specifics if you'd like."
    } else {
        base
    }
}

private fun buildSystemPrompt(useCase: String, tone: String, hasKnowledge: Boolean, mode: String): String {
    val sb = StringBuilder()
    sb.append("You are a $tone auto-reply assistant. ")
    sb.append("Your job: handle ${useCase.ifBlank { "incoming messages on behalf of the user" }}. ")
    sb.append("Keep replies short, on-topic, and in a $tone tone. ")
    if (hasKnowledge && mode == "files") {
        sb.append("Always check uploaded knowledge files before answering; cite them when relevant. ")
    } else if (hasKnowledge && mode == "website") {
        sb.append("Use web search against the company website for product/pricing/FAQ questions. ")
    }
    sb.append("Deflect spam politely. If a message looks urgent or out of scope, suggest escalating to a human.")
    return sb.toString()
}

private fun deriveBotName(useCase: String, tone: String): String {
    val firstWord = useCase.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.replaceFirstChar { it.uppercase() }
        ?: "ReplyAI"
    return "$firstWord Bot (${tone})"
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
