package com.adekarya.ipynbrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adekarya.ipynbrunner.AppUiState
import com.adekarya.ipynbrunner.AppViewModel
import com.adekarya.ipynbrunner.data.RecentNotebook
import com.adekarya.ipynbrunner.data.run.RunPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onImport: () -> Unit,
) {
    var showLogs by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RecentNotebook?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("notebook-baru") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Icon(
                                Icons.Outlined.DataObject,
                                contentDescription = null,
                                modifier = Modifier.padding(6.dp).size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Notebook Runner", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Kaggle & Colab dari Android",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.openSettings() }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Pengaturan")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { HeroCard(onImport = onImport, onCreate = { showCreate = true }) }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPill(
                        text = if (state.settings.hasKaggleKey && state.settings.kaggleUsername.isNotBlank()) {
                            "Kaggle siap"
                        } else {
                            "Kaggle belum diatur"
                        },
                        icon = if (state.settings.hasKaggleKey) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                        tint = if (state.settings.hasKaggleKey) Color(0xFF4ADE80) else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.clickable { viewModel.openSettings() },
                    )
                    StatusPill(
                        text = if (state.settings.colabSignedIn) "Colab terhubung" else "Colab belum login",
                        icon = if (state.settings.colabSignedIn) Icons.Outlined.Cloud else Icons.Outlined.CloudOff,
                        tint = if (state.settings.colabSignedIn) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.clickable { viewModel.openSettings() },
                    )
                    if (state.settings.colabRuntimeName != null) {
                        StatusPill(
                            text = "Runtime tersimpan",
                            icon = Icons.Outlined.Memory,
                            tint = Color(0xFF4ADE80),
                        )
                    }
                }
            }

            if (state.run.active) {
                item {
                    ActiveRunCard(
                        state = state,
                        onOpen = {
                            viewModel.openActiveRunNotebook()
                        },
                        onLogs = { showLogs = true },
                        onStop = { viewModel.cancelRun() },
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Notebook saya",
                    subtitle = "${state.recents.size} notebook tersimpan di perangkat",
                )
            }

            if (state.recents.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Description,
                        title = "Belum ada notebook",
                        description = "Impor file .ipynb dari penyimpanan atau buat notebook baru untuk mulai menjalankan kode di cloud.",
                    )
                }
            } else {
                items(state.recents, key = { it.id }) { recent ->
                    RecentNotebookCard(
                        recent = recent,
                        running = state.run.active && state.run.notebookId == recent.id,
                        onClick = { viewModel.openRecent(recent) },
                        onDelete = { deleteTarget = recent },
                    )
                }
            }

            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Keep-alive, bukan keabadian", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            "Opsi keep-alive mengirim aktivitas berkala agar sesi Colab tidak cepat menganggur. " +
                                "Kuota, batas waktu, dan kebijakan Google tetap berlaku; sesi bisa berakhir kapan saja. " +
                                "Gunakan untuk eksperimen yang wajar dan patuhi ToS layanan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showLogs) {
        RunLogSheet(
            run = state.run,
            onDismiss = { showLogs = false },
            onStop = { viewModel.cancelRun() },
            onClear = { viewModel.clearLogs() },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus notebook?") },
            text = {
                Text(
                    "\"${target.displayName}\" akan dihapus dari penyimpanan aplikasi. " +
                        "File asli di penyimpanan Anda tidak terpengaruh.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRecent(target)
                    deleteTarget = null
                }) {
                    Text("Hapus", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Batal") }
            },
        )
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Notebook baru") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Beri nama file notebook. Ekstensi .ipynb ditambahkan otomatis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("Nama file") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreate = false
                    viewModel.createNotebook(newName.ifBlank { null })
                    newName = "notebook-baru"
                }) { Text("Buat") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Batal") }
            },
        )
    }
}

@Composable
private fun HeroCard(onImport: () -> Unit, onCreate: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primaryContainer
    val tertiary = MaterialTheme.colorScheme.tertiaryContainer
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                Brush.linearGradient(
                    listOf(
                        primary,
                        MaterialTheme.colorScheme.surfaceVariant,
                        tertiary.copy(alpha = 0.55f),
                    ),
                ),
            )
            .padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Jalankan notebook\ndi cloud.",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Edit .ipynb di ponsel, lalu eksekusi dengan GPU Kaggle atau runtime Colab. " +
                    "Output dan log kembali ke perangkat Anda.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Impor .ipynb")
                }
                OutlinedButton(onClick = onCreate, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Baru")
                }
            }
        }
    }
}

@Composable
private fun ActiveRunCard(
    state: AppUiState,
    onOpen: () -> Unit,
    onLogs: () -> Unit,
    onStop: () -> Unit,
) {
    val run = state.run
    val accent = phaseColor(run.phase, MaterialTheme.colorScheme.secondary)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(accent, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (run.phase == RunPhase.KEEP_ALIVE) "Sesi keep-alive" else "Proses aktif",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
                Spacer(Modifier.weight(1f))
                StatusPill(text = phaseLabel(run.phase), tint = accent)
            }
            Text(
                run.notebookName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                run.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (run.active && run.phase != RunPhase.KEEP_ALIVE) {
                val progress = run.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Buka")
                }
                OutlinedButton(onClick = onLogs, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Log")
                }
                TextButton(onClick = onStop) {
                    Text(
                        if (run.phase == RunPhase.KEEP_ALIVE) "Akhiri" else "Stop",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentNotebookCard(
    recent: RecentNotebook,
    running: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    recent.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${recent.cellCount} cell · ${formatBytes(recent.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        " · ${relativeTime(recent.lastOpened)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (running) {
                    StatusPill(
                        text = "Sedang berjalan",
                        icon = Icons.Outlined.PlayArrow,
                        tint = Color(0xFF4ADE80),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Hapus",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
