package com.replyai.app.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.replyai.app.data.ReplyAIDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Dashboard — the app's home screen.
 *
 * Three sections: master auto-reply toggle (prominent card), three stat cards
 * in a row, and a live feed of recent [ReplyAIDatabase.ActionLog] entries.
 * FAB routes to [BotBuilderScreen] for the conversational bot creation flow.
 */
@Composable
fun DashboardScreen(
    onBuildBot: () -> Unit = {},
) {
    val viewModel: DashboardViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    // Refresh on screen entry + every 5s.
    LaunchedEffect(Unit) {
        viewModel.refresh()
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            viewModel.refresh()
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onBuildBot,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Build a bot") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { MasterToggleCard(state.masterEnabled) { viewModel.setMasterEnabled(it) } }
            item { StatRow(state) }
            item {
                Text(
                    "Recent activity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state.actions.isEmpty()) {
                item { EmptyFeedCard() }
            } else {
                items(state.actions, key = { it.id }) { action ->
                    ActionLogCard(action)
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ------------------------------------------------------------------
// Master toggle card
// ------------------------------------------------------------------

@Composable
private fun MasterToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (enabled) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (enabled) "Auto-reply is ON" else "Auto-reply is OFF",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (enabled) "ReplyAI is monitoring notifications and replying for you."
                    else "Tap to start auto-replying to incoming messages.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

// ------------------------------------------------------------------
// Stats row
// ------------------------------------------------------------------

@Composable
private fun StatRow(state: DashboardUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.Chat,
            label = "Handled today",
            value = state.messagesToday.toString(),
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.Bolt,
            label = "Avg reply",
            value = state.avgResponseTime,
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.Payments,
            label = "Cost today",
            value = state.costToday,
        )
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ------------------------------------------------------------------
// Empty feed
// ------------------------------------------------------------------

@Composable
private fun EmptyFeedCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text(
                "No replies yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Once ReplyAI handles a message, it will show up here in real time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ------------------------------------------------------------------
// Action log item
// ------------------------------------------------------------------

@Composable
private fun ActionLogCard(action: ReplyAIDatabase.ActionLog) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                .padding(14.dp),
        ) {
            // App icon — coloured circle with first letter of app name.
            val appName = appNameForPackage(action.app)
            val iconColor = colorForPackage(action.app)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color = iconColor, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    appName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        action.contact ?: "Unknown contact",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        relativeTime(action.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    action.summary ?: "(no summary)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionBadge(action.actionType)
                    val src = parseKnowledgeSource(action.details)
                    if (src != null) {
                        Spacer(Modifier.width(6.dp))
                        KnowledgeBadge(src)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        appName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionBadge(actionType: String) {
    val (text, color) = when (actionType.lowercase()) {
        "auto_reply", "autoreply", "reply" -> "Auto-reply" to MaterialTheme.colorScheme.primary
        "skipped", "skip" -> "Skipped" to MaterialTheme.colorScheme.onSurfaceVariant
        "error", "failed" -> "Error" to MaterialTheme.colorScheme.error
        "escalated" -> "Escalated" to Color(0xFFF59E0B)
        else -> actionType to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun KnowledgeBadge(source: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ------------------------------------------------------------------
// ViewModel + state
// ------------------------------------------------------------------

data class DashboardUiState(
    val masterEnabled: Boolean = false,
    val messagesToday: Int = 0,
    val avgResponseTime: String = "—",
    val costToday: String = "$0.00",
    val actions: List<ReplyAIDatabase.ActionLog> = emptyList(),
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("replyai_prefs", android.content.Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(masterEnabled = prefs.getBoolean(KEY_MASTER, false))
    }

    fun setMasterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MASTER, enabled).apply()
        _uiState.value = _uiState.value.copy(masterEnabled = enabled)
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val db = ReplyAIDatabase.getInstance()
                val actions = db.getRecentActions(50)
                val today = todayYmd()
                val messagesToday = actions.count {
                    it.actionType.equals("auto_reply", ignoreCase = true) &&
                        isToday(it.timestamp)
                }
                val costCentsToday = sumDailyCostsForToday(db, today)
                val costStr = formatCost(costCentsToday)
                _uiState.value = _uiState.value.copy(
                    actions = actions,
                    messagesToday = messagesToday,
                    costToday = costStr,
                    avgResponseTime = "<1s", // placeholder — engine does not yet log timing
                )
            } catch (e: Exception) {
                // DB might not be ready — keep the previous state.
                android.util.Log.w("DashboardVM", "refresh failed: ${e.message}")
            }
        }
    }

    private fun sumDailyCostsForToday(db: ReplyAIDatabase, today: String): Int {
        // Aggregate daily_costs across all bots for today's date.
        var totalCents = 0
        try {
            val c = db.readableDatabase.rawQuery(
                "SELECT COALESCE(SUM(est_cost_cents), 0) FROM daily_costs WHERE date_ymd = ?",
                arrayOf(today),
            )
            c.use {
                if (it.moveToFirst()) totalCents = it.getInt(0)
            }
        } catch (_: Exception) {
            totalCents = 0
        }
        return totalCents
    }

    companion object {
        private const val KEY_MASTER = "auto_reply_master"
    }
}

// ------------------------------------------------------------------
// Helpers
// ------------------------------------------------------------------

private fun appNameForPackage(pkg: String?): String {
    if (pkg.isNullOrBlank()) return "Unknown"
    return when {
        pkg.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
        pkg.contains("telegram", ignoreCase = true) -> "Telegram"
        pkg.contains("slack", ignoreCase = true) -> "Slack"
        pkg.contains("discord", ignoreCase = true) -> "Discord"
        pkg.contains("messenger", ignoreCase = true) -> "Messenger"
        pkg.contains("instagram", ignoreCase = true) -> "Instagram"
        pkg.contains("androidmessaging", ignoreCase = true) -> "SMS"
        pkg.contains("sms", ignoreCase = true) -> "SMS"
        else -> pkg.substringAfterLast('.').take(10)
    }
}

private fun colorForPackage(pkg: String?): Color {
    if (pkg.isNullOrBlank()) return Color(0xFF6B7280)
    return when {
        pkg.contains("whatsapp", ignoreCase = true) -> Color(0xFF25D366)
        pkg.contains("telegram", ignoreCase = true) -> Color(0xFF229ED9)
        pkg.contains("slack", ignoreCase = true) -> Color(0xFF4A154B)
        pkg.contains("discord", ignoreCase = true) -> Color(0xFF5865F2)
        pkg.contains("messenger", ignoreCase = true) -> Color(0xFF0084FF)
        pkg.contains("instagram", ignoreCase = true) -> Color(0xFFE1306C)
        pkg.contains("androidmessaging", ignoreCase = true) -> Color(0xFF10B981)
        else -> Color(0xFF6B7280)
    }
}

/**
 * Best-effort parse of the action_log.details JSON for a `knowledge_source`
 * field written by the engine. Returns null if absent or unparseable.
 */
private fun parseKnowledgeSource(details: String?): String? {
    if (details.isNullOrBlank()) return null
    return try {
        val obj = org.json.JSONObject(details)
        obj.optString("knowledge_source", null as String?)
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        // details is not JSON — fall back to a literal source if it looks like one.
        if (details.length <= 24 && details.matches(Regex("[a-z_]+"))) details else null
    }
}

private fun isToday(timestamp: Long): Boolean {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
}

private fun todayYmd(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

private fun formatCost(cents: Int): String {
    val dollars = cents / 100.0
    return "$" + String.format(Locale.US, "%.2f", dollars)
}

private fun relativeTime(timestamp: Long): String {
    val delta = System.currentTimeMillis() - timestamp
    return when {
        delta < TimeUnit.MINUTES.toMillis(1) -> "just now"
        delta < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(delta)}m ago"
        delta < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(delta)}h ago"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(timestamp))
    }
}
