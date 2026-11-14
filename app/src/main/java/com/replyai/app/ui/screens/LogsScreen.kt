package com.replyai.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.replyai.app.util.LogCapture
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LogsScreen — in-app log viewer for ReplyAI.
 *
 * Ported from Aira's LogsSection pattern. Shows the last 2000 log lines in a
 * scrollable list, with auto-refresh every 1 second. Buttons to share (via
 * Android share intent), copy to clipboard, or clear.
 *
 * Why: User has no logcat access on MIUI/Android 13+. This lets them see what
 * ReplyAI is doing (incoming notifications, cooldown decisions, LLM calls,
 * reply attempts) and share the log back for debugging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(LogCapture.getLines()) }
    var refreshKey by remember { mutableStateOf(0) }

    // Auto-refresh every 1s
    LaunchedEffect(refreshKey) {
        delay(1000)
        lines = LogCapture.getLines()
        refreshKey++
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs (${lines.size})") },
                actions = {
                    IconButton(onClick = {
                        val logText = LogCapture.exportToString()
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, logText)
                            putExtra(Intent.EXTRA_TITLE, "ReplyAI log")
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share log"))
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share log")
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("ReplyAI log", LogCapture.exportToString())
                        )
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy to clipboard")
                    }
                    IconButton(onClick = {
                        LogCapture.clear()
                        lines = emptyList()
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Clear logs")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Newest first
            items(lines.reversed()) { line ->
                LogLineCard(line, timeFormat)
            }
        }
    }
}

@Composable
private fun LogLineCard(
    line: com.replyai.app.util.LogCapture.LogLine,
    timeFormat: SimpleDateFormat,
) {
    val levelColor = when (line.level) {
        "E" -> MaterialTheme.colorScheme.error
        "W" -> MaterialTheme.colorScheme.tertiary
        "I" -> MaterialTheme.colorScheme.primary
        "D" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(4.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = timeFormat.format(Date(line.timestamp)),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = line.level,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = levelColor,
                )
                Text(
                    text = line.tag,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = line.message,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
