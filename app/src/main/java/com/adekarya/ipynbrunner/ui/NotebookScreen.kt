package com.adekarya.ipynbrunner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adekarya.ipynbrunner.AppUiState
import com.adekarya.ipynbrunner.AppViewModel
import com.adekarya.ipynbrunner.data.model.CellType
import com.adekarya.ipynbrunner.data.model.KeepAliveCell
import com.adekarya.ipynbrunner.data.model.NotebookCell
import com.adekarya.ipynbrunner.data.model.NotebookDocument
import com.adekarya.ipynbrunner.data.run.RunPhase
import com.adekarya.ipynbrunner.data.run.RunState
import com.adekarya.ipynbrunner.ui.theme.MonoStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(
    state: AppUiState,
    notebook: NotebookDocument,
    viewModel: AppViewModel,
    onExport: () -> Unit,
) {
    val run = state.run
    val isThisRun = run.active && run.notebookId == state.currentRecent?.id
    val running = isThisRun && run.phase != RunPhase.KEEP_ALIVE
    val keepingAlive = isThisRun && run.phase == RunPhase.KEEP_ALIVE
    val accent = phaseColor(run.phase, MaterialTheme.colorScheme.primary)

    var showRun by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var editingCellId by remember { mutableStateOf<String?>(null) }
    var collapsedOutputs by remember(notebook.fileName) { mutableStateOf<Set<String>>(emptySet()) }
    val listState = rememberLazyListState()

    LaunchedEffect(run.activeCellId) {
        val id = run.activeCellId ?: return@LaunchedEffect
        val index = notebook.cells.indexOfFirst { it.id == id }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeNotebook() }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Kembali")
                    }
                },
                title = {
                    Column {
                        Text(
                            notebook.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append("${notebook.cells.size} cell")
                                if (state.dirty) append(" · belum disimpan")
                                if (keepingAlive) append(" · keep-alive")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (keepingAlive) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (isThisRun) {
                        FilledIconButton(
                            onClick = { viewModel.cancelRun() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(
                                Icons.Outlined.StopCircle,
                                contentDescription = if (keepingAlive) "Akhiri sesi" else "Hentikan",
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    } else {
                        FilledIconButton(
                            onClick = { showRun = true },
                            enabled = !run.active,
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Jalankan")
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Simpan sekarang") },
                                leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.saveNow()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Ekspor .ipynb") },
                                leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onExport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Sisipkan cell keep-alive") },
                                leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.insertKeepAliveCell()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Bersihkan semua output") },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.clearOutputs()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Hapus notebook", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showDelete = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (isThisRun) {
                ExtendedFloatingActionButton(
                    onClick = { showLogs = true },
                    icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                    text = { Text(if (keepingAlive) "Sesi aktif" else "Log proses") },
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = { showRun = true },
                    icon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    text = { Text("Jalankan") },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (isThisRun) {
                RunStrip(
                    run = run,
                    accent = accent,
                    keepingAlive = keepingAlive,
                    onLogs = { showLogs = true },
                    onStop = { viewModel.cancelRun() },
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(notebook.cells, key = { _, cell -> cell.id }) { index, cell ->
                    val isActive = isThisRun && run.activeCellId == cell.id
                    CellCard(
                        cell = cell,
                        index = index,
                        active = isActive,
                        outputsCollapsed = cell.id in collapsedOutputs,
                        editable = !running,
                        onEdit = { editingCellId = cell.id },
                        onToggleOutputs = {
                            collapsedOutputs = if (cell.id in collapsedOutputs) {
                                collapsedOutputs - cell.id
                            } else {
                                collapsedOutputs + cell.id
                            }
                        },
                        onMoveUp = { viewModel.moveCell(cell.id, -1) },
                        onMoveDown = { viewModel.moveCell(cell.id, 1) },
                        onDelete = { viewModel.deleteCell(cell.id) },
                        onAddCodeBelow = { viewModel.addCell(CellType.CODE, cell.id) },
                        onAddMarkdownBelow = { viewModel.addCell(CellType.MARKDOWN, cell.id) },
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.addCell(CellType.CODE) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Kode")
                        }
                        OutlinedButton(
                            onClick = { viewModel.addCell(CellType.MARKDOWN) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Markdown")
                        }
                    }
                }

                item {
                    Text(
                        "Ketuk cell untuk mengedit. Output dari Kaggle/Colab akan menggantikan output lokal setelah run selesai.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (showRun) {
        RunConfigSheet(
            notebook = notebook,
            settings = state.settings,
            specs = state.colabSpecs,
            colabBusy = state.colabBusy,
            onDismiss = { showRun = false },
            onStart = { backend, kaggle, colab ->
                showRun = false
                viewModel.startRun(backend, kaggle, colab)
            },
            onRefreshSpecs = { viewModel.refreshColabSpecs() },
            onSelectSpec = { viewModel.selectColabSpec(it) },
            onOpenSettings = { viewModel.openSettings() },
        )
    }

    if (showLogs) {
        RunLogSheet(
            run = run,
            onDismiss = { showLogs = false },
            onStop = { viewModel.cancelRun() },
            onClear = { viewModel.clearLogs() },
        )
    }

    val editingCell = notebook.cells.firstOrNull { it.id == editingCellId }
    if (editingCell != null) {
        CellEditorSheet(
            cell = editingCell,
            index = notebook.cells.indexOf(editingCell),
            onDismiss = { editingCellId = null },
            onSave = { source ->
                viewModel.updateCellSource(editingCell.id, source)
                editingCellId = null
            },
            onDelete = {
                viewModel.deleteCell(editingCell.id)
                editingCellId = null
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Hapus notebook?") },
            text = {
                Text(
                    "\"${notebook.fileName}\" akan dihapus dari penyimpanan aplikasi. " +
                        "Ekspor dahulu jika ingin menyimpan salinannya.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.deleteCurrentNotebook()
                }) {
                    Text("Hapus", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Batal") }
            },
        )
    }
}

@Composable
private fun RunStrip(
    run: RunState,
    accent: Color,
    keepingAlive: Boolean,
    onLogs: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = accent.copy(alpha = 0.1f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (keepingAlive) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                    Spacer(Modifier.width(7.dp))
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = accent,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    run.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onLogs) { Text("Log") }
                TextButton(onClick = onStop) {
                    Text(if (keepingAlive) "Akhiri" else "Stop", color = MaterialTheme.colorScheme.error)
                }
            }
            if (run.phase != RunPhase.KEEP_ALIVE && run.phase != RunPhase.STARTING) {
                val progress = run.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CellCard(
    cell: NotebookCell,
    index: Int,
    active: Boolean,
    outputsCollapsed: Boolean,
    editable: Boolean,
    onEdit: () -> Unit,
    onToggleOutputs: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onAddCodeBelow: () -> Unit,
    onAddMarkdownBelow: () -> Unit,
) {
    val isKeepAlive = cell.source.contains(KeepAliveCell.MARKER)
    val typeLabel = when (cell.cellType) {
        CellType.CODE -> "Kode"
        CellType.MARKDOWN -> "Markdown"
        CellType.RAW, CellType.UNKNOWN -> "Raw"
    }
    val typeColor = when (cell.cellType) {
        CellType.CODE -> MaterialTheme.colorScheme.primary
        CellType.MARKDOWN -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.tertiary
    }
    var showActions by remember(cell.id) { mutableStateOf(false) }

    Card(
        onClick = { if (editable) onEdit() },
        modifier = Modifier.fillMaxWidth(),
        border = if (active) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
            },
        ),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (cell.cellType == CellType.CODE) {
                        "[${cell.executionCount ?: " "}]"
                    } else {
                        "#${index + 1}"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    typeLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = typeColor,
                )
                if (isKeepAlive) {
                    Spacer(Modifier.width(6.dp))
                    StatusPill(text = "keep-alive", tint = Color(0xFF4ADE80))
                }
                Spacer(Modifier.weight(1f))
                if (active) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (editable) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Edit cell",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { showActions = true }, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = "Aksi cell",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                            DropdownMenuItem(
                                text = { Text("Pindah ke atas") },
                                leadingIcon = { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null) },
                                onClick = {
                                    showActions = false
                                    onMoveUp()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Pindah ke bawah") },
                                leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) },
                                onClick = {
                                    showActions = false
                                    onMoveDown()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Tambah kode di bawah") },
                                leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                                onClick = {
                                    showActions = false
                                    onAddCodeBelow()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Tambah markdown di bawah") },
                                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                                onClick = {
                                    showActions = false
                                    onAddMarkdownBelow()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Hapus cell", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showActions = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.size(4.dp))

            when (cell.cellType) {
                CellType.MARKDOWN -> {
                    if (cell.source.isBlank()) {
                        Text(
                            "(markdown kosong — ketuk untuk mengedit)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic,
                        )
                    } else {
                        MarkdownText(
                            source = cell.source,
                            modifier = Modifier.padding(end = 8.dp),
                            compact = true,
                        )
                    }
                }

                else -> {
                    if (cell.source.isBlank()) {
                        Text(
                            "(cell kosong — ketuk untuk mengedit)",
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic,
                        )
                    } else {
                        Text(
                            text = cell.source.trimEnd(),
                            style = MonoStyle,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }

            if (cell.outputs.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onToggleOutputs, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text(
                            if (outputsCollapsed) {
                                "Tampilkan ${cell.outputs.size} output"
                            } else {
                                "Sembunyikan ${cell.outputs.size} output"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "keluaran",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (!outputsCollapsed) {
                    CellOutputs(
                        outputs = cell.outputs,
                        modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CellEditorSheet(
    cell: NotebookCell,
    index: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(cell.id) { mutableStateOf(cell.source) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (cell.cellType == CellType.CODE) "Edit cell kode" else "Edit markdown",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Cell #${index + 1} · ${cell.cellType.wire}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismiss) { Text("Batal") }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 420.dp),
                textStyle = if (cell.cellType == CellType.CODE) {
                    MonoStyle.copy(fontSize = 14.sp, lineHeight = 21.sp)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = if (cell.cellType == CellType.CODE) KeyboardType.Ascii else KeyboardType.Text,
                ),
                placeholder = {
                    Text(
                        if (cell.cellType == CellType.CODE) "print(\"Halo\")" else "## Judul",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(draft) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Simpan")
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Tutup") }
            }

            if (cell.cellType == CellType.MARKDOWN) {
                Text(
                    "Gunakan markdown standar: # judul, **tebal**, `kode`, > kutipan, dan daftar - item.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            TextButton(onClick = { confirmDelete = true }) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(6.dp))
                Text("Hapus cell ini", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus cell?") },
            text = { Text("Cell #${index + 1} dan outputnya akan dihapus dari notebook.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Batal") }
            },
        )
    }
}
