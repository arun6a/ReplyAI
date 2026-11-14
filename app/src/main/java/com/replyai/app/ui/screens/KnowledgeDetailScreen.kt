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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.replyai.app.data.ReplyAIDatabase
import com.replyai.app.embeddings.EmbeddingProviderFactory
import com.replyai.app.embeddings.VectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * KnowledgeDetailScreen — view + edit the chunks of a single knowledge file.
 *
 * Top bar: back, filename, Edit toggle, Re-embed, Save.
 * Body (view mode): list of chunks — each shows index, text (truncated to
 *                   200 chars), and embedding status (✓ / ✗).
 * Body (edit mode): each chunk becomes an editable OutlinedTextField so the
 *                   user can fix OCR errors or trim irrelevant content. A
 *                   per-chunk delete button + an "Add chunk" button at the
 *                   bottom allow manual knowledge entry.
 *
 * "Re-embed" re-runs the current embedding provider over every chunk's text
 * and updates the stored vectors. "Save" persists all text edits to the DB.
 *
 * Reached via the `knowledge_detail/{fileId}` nav route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeDetailScreen(
    fileId: Long,
    onBack: () -> Unit = {},
) {
    val viewModel: KnowledgeDetailViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(fileId) { viewModel.load(fileId) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.fileName.ifEmpty { "Knowledge file" },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${state.chunks.size} chunks",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (state.editMode) {
                        IconButton(
                            onClick = { viewModel.save() },
                            enabled = !state.working,
                        ) {
                            Icon(Icons.Filled.Save, "Save")
                        }
                    } else {
                        IconButton(
                            onClick = { viewModel.toggleEdit() },
                            enabled = !state.working,
                        ) {
                            Icon(Icons.Filled.Edit, "Edit")
                        }
                        IconButton(
                            onClick = { viewModel.reEmbed() },
                            enabled = !state.working && state.chunks.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.AutoFixHigh, "Re-embed")
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (state.fileMissing) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("Knowledge file not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (state.working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.status.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        state.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).imePadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.chunks.isEmpty()) {
                    item { EmptyChunksCard(state.editMode) }
                } else {
                    items(state.chunks, key = { it.localId }) { chunk ->
                        ChunkCard(
                            chunk = chunk,
                            editMode = state.editMode,
                            onTextChange = { viewModel.updateChunkText(chunk.localId, it) },
                            onDelete = { viewModel.deleteChunk(chunk.localId) },
                        )
                    }
                }
                if (state.editMode) {
                    item {
                        OutlinedButton(
                            onClick = { viewModel.addChunk() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add chunk")
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }

            if (state.editMode) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.toggleEdit() },
                            modifier = Modifier.weight(1f),
                            enabled = !state.working,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cancel")
                        }
                        Button(
                            onClick = { viewModel.save() },
                            modifier = Modifier.weight(1f),
                            enabled = !state.working,
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Cards
// ------------------------------------------------------------------

@Composable
private fun ChunkCard(
    chunk: ChunkDraft,
    editMode: Boolean,
    onTextChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                    Text(
                        "#${chunk.index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                EmbeddingBadge(chunk.hasEmbedding, chunk.provider)
                Spacer(Modifier.weight(1f))
                if (editMode) {
                    var confirm by remember { mutableStateOf(false) }
                    IconButton(onClick = { confirm = true }) {
                        Icon(Icons.Filled.Delete, "Delete chunk", tint = MaterialTheme.colorScheme.error)
                    }
                    if (confirm) {
                        AlertDialog(
                            onDismissRequest = { confirm = false },
                            title = { Text("Delete chunk?") },
                            text = { Text("This chunk's text + embedding will be removed.") },
                            confirmButton = {
                                TextButton(
                                    onClick = { confirm = false; onDelete() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) { Text("Delete") }
                            },
                            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                        )
                    }
                }
            }
            if (editMode) {
                OutlinedTextField(
                    value = chunk.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    placeholder = { Text("Chunk text…") },
                )
            } else {
                Text(
                    chunk.text.take(200) + if (chunk.text.length > 200) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmbeddingBadge(hasEmbedding: Boolean, provider: String?) {
    val color = if (hasEmbedding) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.12f)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Icon(
                if (hasEmbedding) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                if (hasEmbedding) (provider ?: "embedded") else "no embedding",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun EmptyChunksCard(editMode: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            if (editMode) "No chunks yet. Tap \"Add chunk\" to enter knowledge manually."
            else "This file has no chunks yet. Switch to Edit mode to add some.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }
}

// ------------------------------------------------------------------
// ViewModel
// ------------------------------------------------------------------

/** Mutable, UI-facing representation of a chunk. [localId] is a stable list key. */
data class ChunkDraft(
    val localId: Long,
    val dbId: Long,         // 0 when the chunk hasn't been persisted yet
    val fileId: Long,
    val index: Int,
    val text: String,
    val hasEmbedding: Boolean,
    val provider: String?,
)

data class KnowledgeDetailUiState(
    val loading: Boolean = true,
    val fileMissing: Boolean = false,
    val fileId: Long = 0L,
    val fileName: String = "",
    val sourceType: String = "",
    val chunks: List<ChunkDraft> = emptyList(),
    val editMode: Boolean = false,
    val working: Boolean = false,
    val status: String = "",
)

class KnowledgeDetailViewModel(app: Application) : AndroidViewModel(app) {

    init { EmbeddingProviderFactory.init(app) }

    private val _uiState = MutableStateFlow(KnowledgeDetailUiState())
    val uiState: StateFlow<KnowledgeDetailUiState> = _uiState.asStateFlow()

    /** Monotonic key source for chunks that don't yet have a DB id. */
    private var nextLocalId: Long = -1L

    fun load(fileId: Long) {
        viewModelScope.launch {
            _uiState.value = KnowledgeDetailUiState(loading = true, fileId = fileId)
            val db = ReplyAIDatabase.getInstance()
            val (file, chunks) = withContext(Dispatchers.IO) {
                val f = listKnowledgeFile(db, fileId)
                val c = db.getChunksByFile(fileId)
                f to c
            }
            if (file == null) {
                _uiState.value = KnowledgeDetailUiState(loading = false, fileMissing = true, fileId = fileId)
                return@launch
            }
            val drafts = chunks.mapIndexed { i, c ->
                ChunkDraft(
                    localId = c.id,
                    dbId = c.id,
                    fileId = fileId,
                    index = i,
                    text = c.text,
                    hasEmbedding = c.embedding != null,
                    provider = c.embeddingProvider,
                )
            }
            _uiState.value = KnowledgeDetailUiState(
                loading = false,
                fileId = fileId,
                fileName = file.fileName,
                sourceType = file.sourceType,
                chunks = drafts,
            )
        }
    }

    fun toggleEdit() {
        _uiState.value = _uiState.value.copy(editMode = !_uiState.value.editMode, status = "")
    }

    fun updateChunkText(localId: Long, text: String) {
        val cur = _uiState.value
        _uiState.value = cur.copy(
            chunks = cur.chunks.map { if (it.localId == localId) it.copy(text = text) else it },
        )
    }

    fun deleteChunk(localId: Long) {
        val cur = _uiState.value
        val target = cur.chunks.firstOrNull { it.localId == localId } ?: return
        if (target.dbId != 0L) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { ReplyAIDatabase.getInstance().deleteChunk(target.dbId) }
            }
        }
        val remaining = cur.chunks.filterNot { it.localId == localId }
            .mapIndexed { i, c -> c.copy(index = i) }
        _uiState.value = cur.copy(chunks = remaining)
    }

    fun addChunk() {
        val cur = _uiState.value
        val draft = ChunkDraft(
            localId = nextLocalId--,
            dbId = 0L,
            fileId = cur.fileId,
            index = cur.chunks.size,
            text = "",
            hasEmbedding = false,
            provider = null,
        )
        _uiState.value = cur.copy(chunks = cur.chunks + draft)
    }

    /** Persist every chunk's text + re-index. New chunks are inserted. */
    fun save() {
        val cur = _uiState.value
        if (cur.working) return
        val toSave = cur.chunks
        if (toSave.isEmpty()) {
            _uiState.value = cur.copy(editMode = false, status = "Saved (0 chunks).")
            return
        }
        _uiState.value = cur.copy(working = true, status = "Saving ${toSave.size} chunks…")
        viewModelScope.launch {
            try {
                val db = ReplyAIDatabase.getInstance()
                withContext(Dispatchers.IO) {
                    toSave.forEachIndexed { i, draft ->
                        val text = draft.text.trim()
                        if (draft.dbId == 0L) {
                            if (text.isNotEmpty()) {
                                db.saveChunk(cur.fileId, i, text, null, null)
                            }
                        } else {
                            if (text.isEmpty()) {
                                db.deleteChunk(draft.dbId)
                            } else {
                                db.updateChunkText(draft.dbId, text)
                            }
                        }
                    }
                    db.updateChunkCount(cur.fileId, toSave.count { it.text.isNotBlank() })
                }
                _uiState.value = cur.copy(
                    working = false,
                    editMode = false,
                    status = "Saved ${toSave.size} chunks.",
                )
                load(cur.fileId)
            } catch (e: Exception) {
                _uiState.value = cur.copy(working = false, status = "Save failed: ${e.message}")
            }
        }
    }

    /** Re-embed every chunk's current text with the active provider. */
    fun reEmbed() {
        val cur = _uiState.value
        if (cur.working || cur.chunks.isEmpty()) return
        _uiState.value = cur.copy(working = true, status = "Re-embedding ${cur.chunks.size} chunks…")
        viewModelScope.launch {
            try {
                val provider = EmbeddingProviderFactory.create()
                val db = ReplyAIDatabase.getInstance()
                var done = 0
                for (draft in cur.chunks) {
                    if (draft.dbId == 0L) continue // skip unsaved new chunks
                    val text = draft.text.trim()
                    if (text.isEmpty()) continue
                    val emb = try {
                        provider.embed(text)
                    } catch (e: Exception) {
                        android.util.Log.w("KnowledgeDetailVM", "embed failed chunk ${draft.dbId}: ${e.message}")
                        FloatArray(0)
                    }
                    val bytes = encodeEmbedding(emb)
                    withContext(Dispatchers.IO) {
                        db.updateChunkEmbedding(draft.dbId, bytes, provider.name)
                    }
                    done++
                    _uiState.value = _uiState.value.copy(status = "Re-embedding… $done/${cur.chunks.size}")
                }
                _uiState.value = _uiState.value.copy(
                    working = false,
                    status = "Re-embedded $done chunks with ${provider.name}.",
                )
                load(cur.fileId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(working = false, status = "Re-embed failed: ${e.message}")
            }
        }
    }

    // --- helpers ---

    /** listKnowledgeFiles is per-bot; we don't have botId here, so read directly via a raw lookup. */
    private fun listKnowledgeFile(db: ReplyAIDatabase, fileId: Long): ReplyAIDatabase.KnowledgeFile? {
        // Walk every bot's knowledge_files list and find the one whose id matches.
        // Cheaper than adding a new DB method; bots list is tiny (<10 typically).
        for (bot in db.listBots()) {
            val match = db.listKnowledgeFiles(bot.id).firstOrNull { it.id == fileId }
            if (match != null) return match
        }
        return null
    }

    private fun encodeEmbedding(values: FloatArray): ByteArray? {
        if (values.isEmpty()) return null
        val buffer = java.nio.ByteBuffer.allocate(values.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in values) buffer.putFloat(v)
        return buffer.array()
    }
}
