package com.replyai.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.replyai.app.bots.BotManager
import com.replyai.app.data.ReplyAIDatabase
import com.replyai.app.embeddings.EmbeddingProviderFactory
import com.replyai.app.embeddings.VectorStore
import com.replyai.app.knowledge.Chunker
import com.replyai.app.knowledge.DocumentParser
import com.replyai.app.knowledge.WebsiteCrawler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Knowledge — manage RAG knowledge files per bot.
 *
 * Top: bot picker dropdown. Below: list of files with size + chunk count +
 * delete button. Two action cards: "Test retrieval" (embed a query, run
 * VectorStore.searchSimilar, show top-5 chunks with scores) and "Add website
 * as knowledge" (fetch URL via WebSearchGrounding, chunk + embed + store).
 *
 * FAB launches ActivityResultContracts.GetContent for text/markdown uploads.
 * Selected file is chunked (Chunker), embedded (EmbeddingProviderFactory),
 * and persisted (VectorStore).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(onOpenFile: (Long) -> Unit = {}) {
    val viewModel: KnowledgeViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.load() }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.uploadFile(context, uri) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (state.selectedBotId != null) filePicker.launch("*/*") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Upload file") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Knowledge base", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Pick the bot whose knowledge files you want to manage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = state.selectedBotName ?: "Select a bot…",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        state.bots.forEach { bot ->
                            DropdownMenuItem(text = { Text(bot.name) }, onClick = { viewModel.selectBot(bot.id); expanded = false })
                        }
                    }
                }
            }

            if (state.selectedBotId == null) {
                item { EmptyCard("Pick a bot above to start managing its knowledge.") }
            } else {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            "${state.files.sumOf { it.chunkCount }} chunks • ${state.files.size} files",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.uploading) item { UploadProgressCard(state.uploadStatus) }
                if (state.crawling) item { CrawlProgressCard(state.crawlStatus) }
                if (state.files.isEmpty() && !state.uploading && !state.crawling) {
                    item { EmptyCard("No knowledge files yet. Tap 'Upload file' to add a document, or crawl a website below.") }
                } else {
                    items(state.files, key = { it.id }) { file ->
                        KnowledgeFileCard(file, onOpen = { onOpenFile(file.id) }, onDelete = { viewModel.deleteFile(file.id) })
                    }
                }
                item { TestRetrievalCard(state, viewModel) }
                item { CrawlWebsiteCard(state, viewModel) }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ------------------------------------------------------------------
// Cards
// ------------------------------------------------------------------

@Composable
private fun KnowledgeFileCard(
    file: ReplyAIDatabase.KnowledgeFile,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val isWeb = file.sourceType == "web" || file.sourceType == "web_crawl"
            Box(
                modifier = Modifier.size(40.dp).background(
                    color = if (isWeb) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isWeb) Icons.Filled.Public else Icons.Filled.Description,
                    contentDescription = null,
                    tint = if (isWeb) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.fileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagText(formatSize(file.fileSize))
                    TagText("${file.chunkCount} chunks")
                }
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Filled.Visibility, "View chunks", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { confirm = true }) {
                Icon(Icons.Filled.Delete, "Delete file", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete file?") },
            text = { Text("Removes ${file.fileName} and all of its ${file.chunkCount} chunks.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onDelete() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TagText(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun UploadProgressCard(status: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CrawlProgressCard(status: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TestRetrievalCard(state: KnowledgeUiState, viewModel: KnowledgeViewModel) {
    SettingsCard("Test retrieval", "Embed a test query and see the top-5 chunks retrieved from this bot's knowledge base.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.testQuery,
            onValueChange = viewModel::onTestQueryChange,
            label = { Text("Test query") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.runTestRetrieval() },
            enabled = !state.testing && state.testQuery.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Retrieve")
        }
        if (state.testing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        state.testResults.forEachIndexed { idx, r -> TestResultRow(idx + 1, r) }
        if (state.testResults.isNotEmpty()) {
            Text(
                "Scores are cosine similarity (0-1, higher is better).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TestResultRow(rank: Int, result: VectorStore.SearchResult) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                    Text(
                        "#$rank",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    result.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(String.format("%.2f", result.score), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(result.text.take(160), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CrawlWebsiteCard(state: KnowledgeUiState, viewModel: KnowledgeViewModel) {
    SettingsCard(
        "Crawl website",
        "Fetch the homepage and its linked pages, extract text from each, and store them as separate knowledge files. Up to 20 pages.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Public, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(8.dp))
            Text(
                "Each crawled page becomes its own knowledge file.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.crawlUrl,
            onValueChange = viewModel::onCrawlUrlChange,
            label = { Text("https://example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.crawlWebsite() },
            enabled = !state.crawling && state.crawlUrl.isNotBlank() && state.selectedBotId != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.crawling) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (state.crawling) "Crawling…" else "Crawl site")
        }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// ------------------------------------------------------------------
// ViewModel
// ------------------------------------------------------------------

data class KnowledgeUiState(
    val bots: List<com.replyai.app.bots.Bot> = emptyList(),
    val selectedBotId: Long? = null,
    val selectedBotName: String? = null,
    val files: List<ReplyAIDatabase.KnowledgeFile> = emptyList(),
    val uploading: Boolean = false,
    val uploadStatus: String = "",
    val testQuery: String = "",
    val testing: Boolean = false,
    val testResults: List<VectorStore.SearchResult> = emptyList(),
    val crawlUrl: String = "",
    val crawling: Boolean = false,
    val crawlStatus: String = "",
)

class KnowledgeViewModel(app: Application) : AndroidViewModel(app) {

    init { EmbeddingProviderFactory.init(app) }

    private val _uiState = MutableStateFlow(KnowledgeUiState())
    val uiState: StateFlow<KnowledgeUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val bots = BotManager.listBots(getApplication())
            val selBot = bots.firstOrNull { it.id == _uiState.value.selectedBotId } ?: bots.firstOrNull()
            _uiState.value = _uiState.value.copy(
                bots = bots,
                selectedBotId = selBot?.id,
                selectedBotName = selBot?.name,
            )
            refreshFiles()
        }
    }

    fun selectBot(id: Long) {
        val bot = _uiState.value.bots.firstOrNull { it.id == id }
        _uiState.value = _uiState.value.copy(selectedBotId = id, selectedBotName = bot?.name, testResults = emptyList())
        refreshFiles()
    }

    private fun refreshFiles() {
        val botId = _uiState.value.selectedBotId ?: return
        viewModelScope.launch {
            val files = try { ReplyAIDatabase.getInstance().listKnowledgeFiles(botId) } catch (_: Exception) { emptyList() }
            _uiState.value = _uiState.value.copy(files = files)
        }
    }

    fun uploadFile(context: android.content.Context, uri: Uri) {
        val botId = _uiState.value.selectedBotId ?: return
        _uiState.value = _uiState.value.copy(uploading = true, uploadStatus = "Reading file…")
        viewModelScope.launch {
            try {
                val (name, ext, text) = withContext(Dispatchers.IO) { readUriWithParser(context, uri) }
                if (ext == null) {
                    _uiState.value = _uiState.value.copy(
                        uploading = false,
                        uploadStatus = "Unsupported file type: $name. Supported: txt, md, csv, json, html.",
                    )
                    return@launch
                }
                if (text.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(uploading = false, uploadStatus = "File is empty or unreadable.")
                    return@launch
                }
                _uiState.value = _uiState.value.copy(uploadStatus = "Chunking (${text.length} chars)…")
                val chunks = Chunker.chunk(text)
                val db = ReplyAIDatabase.getInstance()
                val fileId = db.saveKnowledgeFile(botId, name, text.length.toLong(), "file", uri.toString())
                _uiState.value = _uiState.value.copy(uploadStatus = "Embedding ${chunks.size} chunks…")
                val provider = EmbeddingProviderFactory.create()
                chunks.forEachIndexed { idx, chunkText ->
                    val embedding = try { provider.embed(chunkText) } catch (e: Exception) {
                        android.util.Log.w("KnowledgeVM", "Embed failed for chunk $idx: ${e.message}")
                        FloatArray(0)
                    }
                    VectorStore.storeEmbedding(fileId, idx, chunkText, embedding, provider.name)
                }
                db.updateChunkCount(fileId, chunks.size)
                _uiState.value = _uiState.value.copy(uploading = false, uploadStatus = "Uploaded $name (${chunks.size} chunks).")
                refreshFiles()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(uploading = false, uploadStatus = "Upload failed: ${e.message}")
            }
        }
    }

    fun onTestQueryChange(v: String) { _uiState.value = _uiState.value.copy(testQuery = v) }

    fun runTestRetrieval() {
        val botId = _uiState.value.selectedBotId ?: return
        val query = _uiState.value.testQuery.trim()
        if (query.isBlank()) return
        _uiState.value = _uiState.value.copy(testing = true, testResults = emptyList())
        viewModelScope.launch {
            try {
                val provider = EmbeddingProviderFactory.create()
                val embedding = provider.embed(query)
                val results = VectorStore.searchSimilar(embedding, botId, limit = 5)
                _uiState.value = _uiState.value.copy(testing = false, testResults = results)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(testing = false)
            }
        }
    }

    fun onCrawlUrlChange(v: String) { _uiState.value = _uiState.value.copy(crawlUrl = v) }

    /**
     * Multi-page crawl: fetch the homepage + linked pages via WebsiteCrawler,
     * save each successfully crawled page as its own knowledge_file with
     * source_type="web_crawl", then chunk + embed every one.
     */
    fun crawlWebsite() {
        val botId = _uiState.value.selectedBotId ?: return
        val url = _uiState.value.crawlUrl.trim()
        if (url.isBlank()) return
        _uiState.value = _uiState.value.copy(crawling = true, crawlStatus = "Fetching $url …")
        viewModelScope.launch {
            try {
                val pages = WebsiteCrawler.crawlSite(
                    startUrl = url,
                    maxPages = 20,
                    maxDepth = 2,
                ) { fetchedUrl, count ->
                    _uiState.value = _uiState.value.copy(crawlStatus = "($count/20) $fetchedUrl")
                }
                if (pages.isEmpty()) {
                    _uiState.value = _uiState.value.copy(crawling = false, crawlStatus = "", crawlUrl = "")
                    return@launch
                }
                val db = ReplyAIDatabase.getInstance()
                val provider = EmbeddingProviderFactory.create()
                pages.forEachIndexed { i, page ->
                    _uiState.value = _uiState.value.copy(crawlStatus = "Storing ${i + 1}/${pages.size}: ${page.title.ifBlank { page.url }}")
                    val safeName = (page.title.ifBlank { page.url }).take(80).replace("/", "_").trim().ifEmpty { "page_$i" }
                    val fileId = db.saveKnowledgeFile(botId, safeName, page.text.length.toLong(), "web_crawl", page.url)
                    val chunks = Chunker.chunk(page.text)
                    chunks.forEachIndexed { idx, chunkText ->
                        val embedding = try { provider.embed(chunkText) } catch (e: Exception) {
                            android.util.Log.w("KnowledgeVM", "embed failed chunk $idx of ${page.url}: ${e.message}")
                            FloatArray(0)
                        }
                        VectorStore.storeEmbedding(fileId, idx, chunkText, embedding, provider.name)
                    }
                    db.updateChunkCount(fileId, chunks.size)
                }
                _uiState.value = _uiState.value.copy(crawling = false, crawlStatus = "", crawlUrl = "")
                refreshFiles()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(crawling = false, crawlStatus = "Crawl failed: ${e.message}")
            }
        }
    }

    fun deleteFile(id: Long) {
        viewModelScope.launch {
            ReplyAIDatabase.getInstance().deleteKnowledgeFile(id)
            refreshFiles()
        }
    }
}

// ------------------------------------------------------------------
// Helpers
// ------------------------------------------------------------------

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    return if (kb < 1024) String.format(java.util.Locale.US, "%.1f KB", kb)
    else String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
}

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
    var name: String? = null
    try {
        resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
    } catch (_: Exception) { name = null }
    return name
}

/** Supported upload extensions (lowercase, no dot). */
private val SUPPORTED_UPLOAD_EXTENSIONS = setOf(
    "txt", "md", "markdown", "csv", "tsv", "json", "log", "xml", "yaml", "yml",
    "html", "htm", "xhtml",
    "pdf", "docx", "rtf",
)

/**
 * Copy the picked Uri to a cache file, then route through [DocumentParser].
 * Returns (displayName, extension-or-null-if-unsupported, parsedText-or-null).
 *
 * `suspend` because [DocumentParser.extractText] is suspend (PDF / DOCX
 * parsing is potentially slow). Caller runs this inside
 * `withContext(Dispatchers.IO)`.
 */
private suspend fun readUriWithParser(
    context: android.content.Context,
    uri: Uri,
): Triple<String, String?, String?> {
    val resolver = context.contentResolver
    val displayName = queryDisplayName(resolver, uri) ?: "uploaded_file.txt"
    val ext = displayName.substringAfterLast('.', "").lowercase()
    if (ext.isNotEmpty() && ext !in SUPPORTED_UPLOAD_EXTENSIONS) {
        return Triple(displayName, null, null)
    }
    val cacheFile = java.io.File(context.cacheDir, "upload_${System.currentTimeMillis()}_$displayName")
    try {
        resolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return Triple(displayName, ext.ifEmpty { null }, null)
    } catch (_: Exception) {
        return Triple(displayName, ext.ifEmpty { null }, null)
    }
    val text = try {
        DocumentParser.extractText(cacheFile.absolutePath)
    } catch (_: Exception) {
        null
    }
    try { cacheFile.delete() } catch (_: Exception) {}
    return Triple(displayName, ext.ifEmpty { null }, text)
}
