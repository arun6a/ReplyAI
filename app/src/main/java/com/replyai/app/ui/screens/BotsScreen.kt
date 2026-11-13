package com.replyai.app.ui.screens

import android.app.Application
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.replyai.app.bots.Bot
import com.replyai.app.bots.BotManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Bots — main bot management screen.
 *
 * Top: 4 template cards in a horizontal LazyRow ("Personal Assistant",
 * "Business Bot", "Customer Support", "Away Responder"). One tap on a
 * template creates a bot via [BotManager.createBot] and routes the user to
 * [BotDetailScreen] for fine-tuning.
 *
 * Below: list of configured bots (LazyColumn). Each row shows name, target
 * apps as chips, status Switch, "Last replied: X" (when available), and an
 * edit button → BotDetailScreen.
 *
 * FAB: "+ Custom Bot" → [BotBuilderScreen] (the conversational builder).
 */
@Composable
fun BotsScreen(
    onEditBot: (Long) -> Unit = {},
    onBuildCustomBot: () -> Unit = {},
) {
    val viewModel: BotsViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    // Refresh on entry.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onBuildCustomBot,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Custom Bot") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Start from a template",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(BotTemplates.ALL, key = { it.name }) { template ->
                        TemplateCard(template) {
                            viewModel.createFromTemplate(template, onCreated = onEditBot)
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Your bots",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${state.bots.size} configured",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.bots.isEmpty()) {
                item { EmptyBotsCard() }
            } else {
                items(state.bots, key = { it.id }) { bot ->
                    BotRowCard(
                        bot = bot,
                        onToggle = { viewModel.toggleBot(bot.id, it) },
                        onEdit = { onEditBot(bot.id) },
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ------------------------------------------------------------------
// Template card
// ------------------------------------------------------------------

@Composable
private fun TemplateCard(template: BotTemplates.Template, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                template.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                template.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                template.suggestedApps.take(2).forEach { pkg ->
                    AppChip(pkg)
                }
                if (template.suggestedApps.size > 2) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            "+${template.suggestedApps.size - 2}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppChip(pkg: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            appNameForPackage(pkg),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------
// Empty state
// ------------------------------------------------------------------

@Composable
private fun EmptyBotsCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text(
                "No bots yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Pick a template above or tap 'Custom Bot' to build one with our AI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ------------------------------------------------------------------
// Bot row card
// ------------------------------------------------------------------

@Composable
private fun BotRowCard(
    bot: Bot,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (bot.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = if (bot.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    bot.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (bot.targetApps.isEmpty()) {
                        Text(
                            "No apps selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        bot.targetApps.take(3).forEach { AppChip(it) }
                        if (bot.targetApps.size > 3) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    "+${bot.targetApps.size - 3}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Last replied: ${relativeTime(bot.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = bot.enabled,
                onCheckedChange = onToggle,
            )
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit bot",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// ViewModel
// ------------------------------------------------------------------

data class BotsUiState(
    val bots: List<Bot> = emptyList(),
    val creatingFromTemplate: Boolean = false,
)

class BotsViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(BotsUiState())
    val uiState: StateFlow<BotsUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val bots = BotManager.listBots(getApplication())
            _uiState.value = _uiState.value.copy(bots = bots)
        }
    }

    fun toggleBot(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            BotManager.toggleBot(getApplication(), id, enabled)
            refresh()
        }
    }

    fun createFromTemplate(
        template: BotTemplates.Template,
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            val id = BotManager.createBot(
                context = getApplication(),
                name = template.name,
                targetApps = template.suggestedApps,
                systemPrompt = template.systemPrompt,
                personality = template.personality,
                enabled = true,
            )
            if (id > 0) {
                refresh()
                onCreated(id)
            }
        }
    }
}

// ------------------------------------------------------------------
// Helpers (package-private so Dashboard / BotDetail can reuse)
// ------------------------------------------------------------------

internal fun appNameForPackage(pkg: String?): String {
    if (pkg.isNullOrBlank()) return "Unknown"
    return when {
        pkg.contains("whatsapp.w4b", ignoreCase = true) -> "WhatsApp Business"
        pkg.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
        pkg.contains("telegram", ignoreCase = true) -> "Telegram"
        pkg.contains("slack", ignoreCase = true) -> "Slack"
        pkg.contains("discord", ignoreCase = true) -> "Discord"
        pkg.contains("messenger", ignoreCase = true) -> "Messenger"
        pkg.contains("instagram", ignoreCase = true) -> "Instagram"
        pkg.contains("androidmessaging", ignoreCase = true) -> "SMS"
        pkg.contains("sms", ignoreCase = true) -> "SMS"
        else -> pkg.substringAfterLast('.').take(12)
    }
}

internal fun relativeTime(timestamp: Long): String {
    val delta = System.currentTimeMillis() - timestamp
    return when {
        delta < 0 -> "just now"
        delta < TimeUnit.MINUTES.toMillis(1) -> "just now"
        delta < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(delta)}m ago"
        delta < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(delta)}h ago"
        delta < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(delta)}d ago"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(timestamp))
    }
}
