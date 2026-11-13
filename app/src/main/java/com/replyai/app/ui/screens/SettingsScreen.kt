package com.replyai.app.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GitHub
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.replyai.app.embeddings.EmbeddingProviderFactory
import com.replyai.app.llm.LLMClient
import com.replyai.app.llm.LLMProvider
import com.replyai.app.llm.LLMSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings — four sections of app-wide configuration.
 *
 *   1. LLM Provider (provider dropdown, API key, model, custom endpoint, "test connection")
 *   2. Embeddings Provider (dropdown, API key, "test embeddings")
 *   3. Permissions (notification listener, battery optimization, overlay)
 *   4. About (version, build, GitHub link)
 *
 * "Test connection" calls LLMClient.chat("hello"); "Test embeddings" calls
 * EmbeddingProviderFactory.create().embed("hello world") and reports the vector
 * dimensionality.
 */
@Composable
fun SettingsScreen() {
    val viewModel: SettingsViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { LlmSection(state, viewModel) }
        item { EmbeddingsSection(state, viewModel) }
        item { PermissionsSection() }
        item { AboutSection() }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

// ------------------------------------------------------------------
// Section 1: LLM
// ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SettingsCard("LLM Provider", "Used to generate auto-replies") {
        DropdownField(
            selected = state.llmProvider.displayName,
            label = "Provider",
            options = LLMProvider.entries.map { it.displayName },
            onSelect = { name -> LLMProvider.entries.first { it.displayName == name }?.let { viewModel.setLlmProvider(it) } },
        )
        Spacer(Modifier.height(12.dp))
        if (state.llmProvider.needsApiKey) {
            SecretTextField(state.llmApiKey, viewModel::onLlmApiKeyChange, "API key")
            Spacer(Modifier.height(12.dp))
        }
        OutlinedTextField(
            value = state.llmModel,
            onValueChange = viewModel::onLlmModelChange,
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            modelHint(state.llmProvider),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (state.llmProvider == LLMProvider.CUSTOM) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.llmEndpoint,
                onValueChange = viewModel::onLlmEndpointChange,
                label = { Text("Custom endpoint URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        TestButton(
            testing = state.llmTesting,
            status = state.llmTestStatus,
            message = state.llmTestMessage,
            label = "Test connection",
            onClick = viewModel::testLlmConnection,
        )
    }
}

// ------------------------------------------------------------------
// Section 2: Embeddings
// ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbeddingsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SettingsCard("Embeddings Provider", "Used for RAG knowledge retrieval") {
        val options = listOf("Gemini (default)", "OpenAI", "On-device")
        DropdownField(
            selected = embeddingDisplayName(state.embeddingProvider),
            label = "Provider",
            options = options,
            onSelect = { viewModel.setEmbeddingProvider(it) },
        )
        Text(
            "Active provider: ${EmbeddingProviderFactory.activeProviderName()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
        if (state.embeddingProvider != "ondevice") {
            SecretTextField(state.embeddingApiKey, viewModel::onEmbeddingApiKeyChange, "API key (shared with LLM if same provider)")
            Spacer(Modifier.height(12.dp))
        }
        TestButton(
            testing = state.embeddingTesting,
            status = state.embeddingTestStatus,
            message = state.embeddingTestMessage,
            label = "Test embeddings",
            onClick = viewModel::testEmbeddings,
        )
    }
}

// ------------------------------------------------------------------
// Section 3: Permissions
// ------------------------------------------------------------------

@Composable
private fun PermissionsSection() {
    val context = LocalContext.current
    SettingsCard("Permissions", "Required for ReplyAI to function") {
        PermissionRow(Icons.Outlined.NotificationsActive, "Notification access", "Reads incoming messages from WhatsApp, Telegram, etc.") {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Spacer(Modifier.height(8.dp))
        PermissionRow(Icons.Outlined.BatteryFull, "Battery optimization", "Prevents Android from killing the listener service.") {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Spacer(Modifier.height(8.dp))
            PermissionRow(Icons.Outlined.Lock, "Overlay permission", "Shows consent dialogs over other apps.") {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, subtitle: String, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        onClick = onOpen,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).background(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onOpen, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) { Text("Open") }
        }
    }
}

// ------------------------------------------------------------------
// Section 4: About
// ------------------------------------------------------------------

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val pkg = remember { try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null } }
    val version = pkg?.versionName ?: "?"
    val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg?.longVersionCode?.toString() ?: "?"
    else @Suppress("DEPRECATION") pkg?.versionCode?.toString() ?: "?"

    SettingsCard("About", null) {
        AboutRow("App", "ReplyAI")
        AboutRow("Version", version)
        AboutRow("Build", build)
        Spacer(Modifier.height(12.dp))
        Card(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/replyai/app")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GitHub, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("View on GitHub", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------------------
// Shared building blocks
// ------------------------------------------------------------------

@Composable
private fun SettingsCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(selected: String, label: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt -> DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false }) }
        }
    }
}

@Composable
private fun SecretTextField(value: String, onValueChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Lock, if (visible) "Hide" else "Show", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

enum class TestStatus { IDLE, SUCCESS, FAIL }

@Composable
private fun StatusPill(status: TestStatus) {
    if (status == TestStatus.IDLE) return
    val (color, icon, label) = when (status) {
        TestStatus.SUCCESS -> Triple(MaterialTheme.colorScheme.primary, Icons.Filled.CheckCircle, "OK")
        TestStatus.FAIL -> Triple(MaterialTheme.colorScheme.error, Icons.Filled.Error, "Failed")
        TestStatus.IDLE -> return
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TestButton(testing: Boolean, status: TestStatus, message: String?, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onClick, enabled = !testing) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Testing…")
            } else {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
        Spacer(Modifier.width(12.dp))
        StatusPill(status)
    }
    if (message != null) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (status == TestStatus.FAIL) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun modelHint(provider: LLMProvider): String = when (provider) {
    LLMProvider.OPENAI -> "e.g. gpt-4o-mini"
    LLMProvider.GEMINI -> "e.g. gemini-1.5-flash"
    LLMProvider.ANTHROPIC -> "e.g. claude-3-5-sonnet-20241022"
    LLMProvider.GROQ -> "e.g. llama-3.3-70b-versatile"
    LLMProvider.OPENROUTER -> "e.g. google/gemini-2.5-flash"
    LLMProvider.CUSTOM -> "Your custom model id"
}

private fun embeddingDisplayName(key: String): String = when (key) {
    "gemini" -> "Gemini (default)"
    "openai" -> "OpenAI"
    "ondevice" -> "On-device"
    else -> key
}

// ------------------------------------------------------------------
// ViewModel + state
// ------------------------------------------------------------------

data class SettingsUiState(
    val llmProvider: LLMProvider = LLMProvider.OPENROUTER,
    val llmApiKey: String = "",
    val llmModel: String = "",
    val llmEndpoint: String = "",
    val llmTesting: Boolean = false,
    val llmTestStatus: TestStatus = TestStatus.IDLE,
    val llmTestMessage: String? = null,
    val embeddingProvider: String = "gemini",
    val embeddingApiKey: String = "",
    val embeddingTesting: Boolean = false,
    val embeddingTestStatus: TestStatus = TestStatus.IDLE,
    val embeddingTestMessage: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    init { EmbeddingProviderFactory.init(app) }

    private val _uiState = MutableStateFlow(loadInitial(app))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun loadInitial(context: android.content.Context): SettingsUiState {
        val provider = LLMSettings.getProvider(context)
        val activeEmb = EmbeddingProviderFactory.activeProviderName()
        return SettingsUiState(
            llmProvider = provider,
            llmApiKey = LLMSettings.getApiKey(context),
            llmModel = LLMSettings.getModel(context),
            llmEndpoint = LLMSettings.getCustomEndpoint(context),
            embeddingProvider = activeEmb,
            embeddingApiKey = when (activeEmb) {
                "gemini" -> EmbeddingProviderFactory.getGeminiKey()
                "openai" -> EmbeddingProviderFactory.getOpenAIKey()
                else -> ""
            },
        )
    }

    // ----- LLM -----

    fun setLlmProvider(p: LLMProvider) {
        LLMSettings.setProvider(getApplication(), p)
        _uiState.value = _uiState.value.copy(llmProvider = p, llmTestStatus = TestStatus.IDLE, llmTestMessage = null)
    }

    fun onLlmApiKeyChange(v: String) {
        LLMSettings.setApiKey(getApplication(), v)
        // Share with embeddings if same provider is configured.
        when (_uiState.value.llmProvider) {
            LLMProvider.GEMINI -> EmbeddingProviderFactory.setGeminiKey(v)
            LLMProvider.OPENAI -> EmbeddingProviderFactory.setOpenAIKey(v)
            else -> {}
        }
        _uiState.value = _uiState.value.copy(llmApiKey = v)
    }

    fun onLlmModelChange(v: String) {
        LLMSettings.setModel(getApplication(), v)
        _uiState.value = _uiState.value.copy(llmModel = v)
    }

    fun onLlmEndpointChange(v: String) {
        LLMSettings.setCustomEndpoint(getApplication(), v)
        _uiState.value = _uiState.value.copy(llmEndpoint = v)
    }

    fun testLlmConnection() {
        _uiState.value = _uiState.value.copy(llmTesting = true, llmTestStatus = TestStatus.IDLE, llmTestMessage = null)
        viewModelScope.launch {
            try {
                val resp = LLMClient(getApplication()).chat(
                    userMessage = "hello",
                    systemPrompt = "You are a connection-test assistant. Reply briefly.",
                )
                val ok = !resp.reply.startsWith("Error") && !resp.reply.startsWith("No API key") &&
                    !resp.reply.startsWith("No model") && !resp.reply.startsWith("No custom endpoint") && resp.reply.isNotBlank()
                _uiState.value = _uiState.value.copy(
                    llmTesting = false,
                    llmTestStatus = if (ok) TestStatus.SUCCESS else TestStatus.FAIL,
                    llmTestMessage = if (ok) "Reply: ${resp.reply.take(120)}" else resp.reply,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(llmTesting = false, llmTestStatus = TestStatus.FAIL, llmTestMessage = "Error: ${e.message}")
            }
        }
    }

    // ----- Embeddings -----

    fun setEmbeddingProvider(name: String) {
        val key = name.lowercase().substringBefore(" (")
        _uiState.value = _uiState.value.copy(
            embeddingProvider = key,
            embeddingApiKey = when (key) {
                "gemini" -> EmbeddingProviderFactory.getGeminiKey()
                "openai" -> EmbeddingProviderFactory.getOpenAIKey()
                else -> ""
            },
            embeddingTestStatus = TestStatus.IDLE,
            embeddingTestMessage = null,
        )
    }

    fun onEmbeddingApiKeyChange(v: String) {
        when (_uiState.value.embeddingProvider) {
            "gemini" -> EmbeddingProviderFactory.setGeminiKey(v)
            "openai" -> EmbeddingProviderFactory.setOpenAIKey(v)
        }
        _uiState.value = _uiState.value.copy(embeddingApiKey = v)
    }

    fun testEmbeddings() {
        _uiState.value = _uiState.value.copy(embeddingTesting = true, embeddingTestStatus = TestStatus.IDLE, embeddingTestMessage = null)
        viewModelScope.launch {
            try {
                val provider = EmbeddingProviderFactory.create()
                val vec = provider.embed("hello world")
                _uiState.value = _uiState.value.copy(
                    embeddingTesting = false,
                    embeddingTestStatus = if (vec.isEmpty()) TestStatus.FAIL else TestStatus.SUCCESS,
                    embeddingTestMessage = if (vec.isEmpty()) "Provider returned an empty vector."
                    else "Provider: ${provider.name} • Dimensions: ${vec.size}",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(embeddingTesting = false, embeddingTestStatus = TestStatus.FAIL, embeddingTestMessage = "Error: ${e.message}")
            }
        }
    }
}
