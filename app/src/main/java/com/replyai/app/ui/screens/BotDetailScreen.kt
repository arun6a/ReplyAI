package com.replyai.app.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.replyai.app.bots.Bot
import com.replyai.app.bots.BotManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * BotDetailScreen — edit a single bot's configuration.
 *
 * Loaded via [BotManager.getBot] using the bot id passed as a nav argument.
 * Save calls [BotManager.updateBot]; Delete calls [BotManager.deleteBot]
 * after a confirmation dialog (delete cascades to knowledge_files + chats).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BotDetailScreen(
    botId: Long,
    onBack: () -> Unit = {},
    onDeleted: () -> Unit = {},
) {
    val viewModel: BotDetailViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(botId) { viewModel.load(botId) }

    var showDelete by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Edit bot", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { viewModel.save(onSaved = onBack) }, enabled = state.canSave) {
                        Text("Save", fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        val bot = state.bot
        if (bot == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("Bot not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionTitle("Name")
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text("Bot name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SectionTitle("Target apps")
                Text(
                    "Pick which messaging apps this bot watches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TARGET_APPS.forEach { (pkg, label) ->
                        FilterChip(
                            selected = bot.targetApps.contains(pkg),
                            onClick = { viewModel.toggleApp(pkg) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item {
                SectionTitle("System prompt")
                OutlinedTextField(
                    value = state.systemPrompt,
                    onValueChange = viewModel::onSystemPromptChange,
                    label = { Text("Instructions the AI follows when replying") },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
            }
            item {
                SectionTitle("Personality")
                PersonalityDropdown(state.personality, viewModel::onPersonalityChange)
            }
            item {
                SectionTitle("Confidence threshold")
                Text(
                    "Below this confidence, ReplyAI will skip the message instead of replying.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = state.confidenceThreshold,
                        onValueChange = viewModel::onConfidenceChange,
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${(state.confidenceThreshold * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(48.dp),
                    )
                }
            }
            item {
                SectionTitle("Quiet hours")
                Text(
                    "Auto-reply is paused during these hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    QuietHourButton("Starts", state.quietStartHour, state.quietStartMinute, { showStart = true }, Modifier.weight(1f))
                    QuietHourButton("Ends", state.quietEndHour, state.quietEndMinute, { showEnd = true }, Modifier.weight(1f))
                }
            }
            item {
                SectionTitle("Daily caps")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.dailyMessageCap,
                        onValueChange = viewModel::onMessageCapChange,
                        label = { Text("Messages / day") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.dailyCostCap,
                        onValueChange = viewModel::onCostCapChange,
                        label = { Text("Cost / day") },
                        prefix = { Text("$") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                SectionTitle("Company URL (optional)")
                OutlinedTextField(
                    value = state.companyUrl,
                    onValueChange = viewModel::onCompanyUrlChange,
                    label = { Text("https://your-company.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Used for web-search grounding when the bot needs live data (pricing, FAQs, contact info).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { showDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete bot")
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete this bot?") },
            text = { Text("This permanently deletes the bot and all of its knowledge files, conversations and messages. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { showDelete = false; viewModel.delete(onDeleted = onDeleted) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
    if (showStart) {
        TimePickerDialog(state.quietStartHour, state.quietStartMinute, { h, m -> viewModel.onQuietStartChange(h, m); showStart = false }, { showStart = false })
    }
    if (showEnd) {
        TimePickerDialog(state.quietEndHour, state.quietEndMinute, { h, m -> viewModel.onQuietEndChange(h, m); showEnd = false }, { showEnd = false })
    }
}

// ------------------------------------------------------------------
// Small section helpers
// ------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalityDropdown(selected: String?, onChange: (String) -> Unit) {
    val options = listOf("Professional", "Friendly", "Casual", "Formal")
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected ?: "—",
            onValueChange = {},
            readOnly = true,
            label = { Text("Personality") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onChange(opt); expanded = false })
            }
        }
    }
}

@Composable
private fun QuietHourButton(
    label: String, hour: Int, minute: Int, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(String.format("%02d:%02d", hour, minute), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(initialHour: Int, initialMinute: Int, onConfirm: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Select time", style = MaterialTheme.typography.titleMedium)
                TimePicker(state = state, colors = TimePickerDefaults.colors())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// ViewModel
// ------------------------------------------------------------------

data class BotDetailUiState(
    val loading: Boolean = true,
    val bot: Bot? = null,
    val name: String = "",
    val systemPrompt: String = "",
    val personality: String? = null,
    val confidenceThreshold: Float = 0.7f,
    val quietStartHour: Int = 22,
    val quietStartMinute: Int = 0,
    val quietEndHour: Int = 7,
    val quietEndMinute: Int = 0,
    val dailyMessageCap: String = "10",
    val dailyCostCap: String = "2.00",
    val companyUrl: String = "",
) {
    val canSave: Boolean get() = name.isNotBlank() && systemPrompt.isNotBlank()
}

class BotDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(BotDetailUiState())
    val uiState: StateFlow<BotDetailUiState> = _uiState.asStateFlow()

    fun load(botId: Long) {
        viewModelScope.launch {
            val bot = BotManager.getBot(getApplication(), botId)
            if (bot == null) {
                _uiState.value = _uiState.value.copy(loading = false, bot = null)
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                loading = false,
                bot = bot,
                name = bot.name,
                systemPrompt = bot.systemPrompt,
                personality = bot.personality,
                confidenceThreshold = bot.confidenceThreshold,
                quietStartHour = bot.quietHoursStart.coerceIn(0, 23),
                quietStartMinute = 0,
                quietEndHour = bot.quietHoursEnd.coerceIn(0, 23),
                quietEndMinute = 0,
                dailyMessageCap = bot.dailyMessageCap.toString(),
                dailyCostCap = String.format(java.util.Locale.US, "%.2f", bot.dailyCostCapCents / 100.0),
                companyUrl = bot.companyUrl.orEmpty(),
            )
        }
    }

    fun onNameChange(v: String) { _uiState.value = _uiState.value.copy(name = v) }
    fun onSystemPromptChange(v: String) { _uiState.value = _uiState.value.copy(systemPrompt = v) }
    fun onPersonalityChange(v: String) { _uiState.value = _uiState.value.copy(personality = v) }
    fun onConfidenceChange(v: Float) { _uiState.value = _uiState.value.copy(confidenceThreshold = v) }
    fun onQuietStartChange(h: Int, m: Int) { _uiState.value = _uiState.value.copy(quietStartHour = h, quietStartMinute = m) }
    fun onQuietEndChange(h: Int, m: Int) { _uiState.value = _uiState.value.copy(quietEndHour = h, quietEndMinute = m) }
    fun onMessageCapChange(v: String) {
        _uiState.value = _uiState.value.copy(dailyMessageCap = v.filter { it.isDigit() }.ifEmpty { "0" })
    }
    fun onCostCapChange(v: String) {
        var cleaned = v.filter { it.isDigit() || it == '.' }
        val firstDot = cleaned.indexOf('.')
        if (firstDot >= 0) {
            cleaned = cleaned.substring(0, firstDot + 1) + cleaned.substring(firstDot + 1).filter { it.isDigit() }
            val parts = cleaned.split(".")
            if (parts.size == 2 && parts[1].length > 2) cleaned = parts[0] + "." + parts[1].take(2)
        }
        _uiState.value = _uiState.value.copy(dailyCostCap = cleaned)
    }
    fun onCompanyUrlChange(v: String) { _uiState.value = _uiState.value.copy(companyUrl = v) }

    fun toggleApp(pkg: String) {
        val current = _uiState.value.bot ?: return
        val newList = if (current.targetApps.contains(pkg)) current.targetApps - pkg else current.targetApps + pkg
        _uiState.value = _uiState.value.copy(bot = current.copy(targetApps = newList))
    }

    fun save(onSaved: () -> Unit) {
        val s = _uiState.value
        val bot = s.bot ?: return
        val updated = bot.copy(
            name = s.name.trim(),
            systemPrompt = s.systemPrompt,
            personality = s.personality,
            confidenceThreshold = s.confidenceThreshold,
            quietHoursStart = s.quietStartHour,
            quietHoursEnd = s.quietEndHour,
            dailyMessageCap = s.dailyMessageCap.toIntOrNull() ?: 10,
            dailyCostCapCents = ((s.dailyCostCap.toDoubleOrNull() ?: 0.0) * 100).toInt(),
            companyUrl = s.companyUrl.trim().ifBlank { null },
        )
        viewModelScope.launch {
            BotManager.updateBot(getApplication(), updated)
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = _uiState.value.bot?.id ?: return
        viewModelScope.launch {
            BotManager.deleteBot(getApplication(), id)
            onDeleted()
        }
    }
}

internal val TARGET_APPS: List<Pair<String, String>> = listOf(
    "com.whatsapp" to "WhatsApp",
    "com.whatsapp.w4b" to "WhatsApp Business",
    "com.telegram" to "Telegram",
    "com.androidmessaging" to "SMS",
    "com.slack" to "Slack",
    "com.discord" to "Discord",
    "com.facebook.orca" to "Messenger",
    "com.instagram.android" to "Instagram",
)
