package com.adekarya.ipynbrunner.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.adekarya.ipynbrunner.data.AppSettings
import com.adekarya.ipynbrunner.data.kaggle.KaggleClient
import com.adekarya.ipynbrunner.data.model.Backend
import com.adekarya.ipynbrunner.data.model.ColabRunOptions
import com.adekarya.ipynbrunner.data.model.ColabRuntimeSpec
import com.adekarya.ipynbrunner.data.model.ColabSpecKey
import com.adekarya.ipynbrunner.data.model.KaggleRunOptions
import com.adekarya.ipynbrunner.data.model.NotebookDocument
import com.adekarya.ipynbrunner.data.run.RunPhase
import com.adekarya.ipynbrunner.data.run.RunState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunConfigSheet(
    notebook: NotebookDocument,
    settings: AppSettings,
    specs: List<ColabRuntimeSpec>,
    colabBusy: Boolean,
    onDismiss: () -> Unit,
    onStart: (Backend, KaggleRunOptions, ColabRunOptions) -> Unit,
    onRefreshSpecs: () -> Unit,
    onSelectSpec: (ColabSpecKey) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val baseName = notebook.fileName.removeSuffix(".ipynb")
    val defaultSlug = remember(baseName) {
        KaggleClient.slugify(baseName).ifBlank { "notebook" }
    }
    val defaultTitle = remember(baseName) {
        baseName.replace('-', ' ').replace('_', ' ').trim()
            .replaceFirstChar { it.uppercase() }
            .ifBlank { "Notebook baru" }
            .let { if (it.length >= 5) it.take(50) else "Notebook $it" }
    }

    var backend by remember { mutableStateOf(settings.lastBackend) }
    var title by remember { mutableStateOf(defaultTitle) }
    var slug by remember { mutableStateOf(defaultSlug) }
    var gpu by remember { mutableStateOf(settings.kaggleUseGpu) }
    var internet by remember { mutableStateOf(settings.kaggleInternet) }
    var private by remember { mutableStateOf(settings.kagglePrivate) }
    var timeout by remember { mutableIntStateOf(180) }
    var selectedSpec by remember { mutableStateOf(settings.colabSpec) }
    var keepAliveCell by remember { mutableStateOf(settings.keepAliveCell) }
    var keepAliveAfter by remember { mutableStateOf(settings.keepAliveAfterRun) }

    val kaggleReady = settings.kaggleUsername.isNotBlank() && settings.hasKaggleKey
    val colabReady = settings.colabSignedIn
    val kaggleValid = title.trim().length >= 5 && slug.trim().isNotBlank()
    val canStart = when (backend) {
        Backend.KAGGLE -> kaggleReady && kaggleValid
        Backend.COLAB -> colabReady
    }
    val eligibleSpecs = specs.filter { it.eligible }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Jalankan notebook", style = MaterialTheme.typography.headlineSmall)
            Text(
                notebook.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = backend == Backend.KAGGLE,
                    onClick = { backend = Backend.KAGGLE },
                    label = { Text("Kaggle") },
                    leadingIcon = { Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
                FilterChip(
                    selected = backend == Backend.COLAB,
                    onClick = { backend = Backend.COLAB },
                    label = { Text("Colab") },
                    leadingIcon = { Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
                Spacer(Modifier.weight(1f))
                if (backend == Backend.KAGGLE) {
                    StatusPill(
                        text = if (kaggleReady) "Siap" else "Belum diatur",
                        tint = if (kaggleReady) Color(0xFF4ADE80) else MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    StatusPill(
                        text = if (colabReady) "Terhubung" else "Belum login",
                        tint = if (colabReady) Color(0xFF4ADE80) else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            if (backend == Backend.KAGGLE) {
                if (!kaggleReady) {
                    HintCard(
                        icon = Icons.Outlined.WarningAmber,
                        title = "Kredensial Kaggle belum lengkap",
                        text = "Isi username dan API key Kaggle di Pengaturan. Buat API key dari halaman Settings akun Kaggle.",
                        actionLabel = "Buka Pengaturan",
                        onAction = {
                            onDismiss()
                            onOpenSettings()
                        },
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 80) title = it },
                    label = { Text("Judul kernel") },
                    supportingText = { Text("Minimal 5 karakter.") },
                    isError = title.trim().length < 5,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = slug,
                    onValueChange = { slug = it },
                    label = { Text("Slug kernel") },
                    supportingText = {
                        Text(
                            if (settings.kaggleUsername.isNotBlank()) {
                                "Target: ${settings.kaggleUsername}/${slug.trim()}"
                            } else {
                                "Contoh: notebook-eksperimen"
                            },
                        )
                    },
                    isError = slug.trim().isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Opsi Kaggle", style = MaterialTheme.typography.titleSmall)
                ToggleRow(
                    title = "GPU T4 x2",
                    subtitle = "Kuota GPU Kaggle harus tersedia pada akun Anda.",
                    checked = gpu,
                    onCheckedChange = { gpu = it },
                )
                ToggleRow(
                    title = "Aktifkan internet",
                    subtitle = "Diperlukan untuk mengunduh paket atau dataset.",
                    checked = internet,
                    onCheckedChange = { internet = it },
                )
                ToggleRow(
                    title = "Kernel privat",
                    subtitle = "Hanya Anda yang dapat melihat kernel dan outputnya.",
                    checked = private,
                    onCheckedChange = { private = it },
                )
                Text("Batas waktu sesi", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(60, 180, 360, 720).forEach { minutes ->
                        FilterChip(
                            selected = timeout == minutes,
                            onClick = { timeout = minutes },
                            label = { Text("$minutes m") },
                        )
                    }
                }
                Text(
                    "Notebook diunggah sebagai versi kernel baru. Output akan diunduh kembali dan digabungkan ke sel yang cocok.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (!colabReady) {
                    HintCard(
                        icon = Icons.Outlined.WarningAmber,
                        title = "Akun Colab belum terhubung",
                        text = "Buat OAuth Client ID (Desktop app) dan isi quota project di Pengaturan, lalu hubungkan akun Google dengan scope colaboratory.",
                        actionLabel = "Buka Pengaturan",
                        onAction = {
                            onDismiss()
                            onOpenSettings()
                        },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            settings.colabAccount ?: "Akun Google terhubung",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        if (settings.colabRuntimeName != null) {
                            StatusPill(
                                text = settings.colabRuntimeLabel ?: "Runtime tersimpan",
                                icon = Icons.Outlined.Memory,
                                tint = Color(0xFF4ADE80),
                            )
                        }
                    }
                }

                SectionHeader(
                    title = "Tipe runtime",
                    subtitle = if (eligibleSpecs.isEmpty()) {
                        "Muat daftar dari Colab API atau gunakan pilihan tersimpan."
                    } else {
                        "Pilihan yang tersedia untuk akun Anda."
                    },
                    trailing = {
                        TextButton(onClick = onRefreshSpecs, enabled = !colabBusy) {
                            if (colabBusy) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("Muat ulang")
                        }
                    },
                )
                val chips = buildList {
                    if (eligibleSpecs.none { it.key == selectedSpec }) {
                        add(ColabRuntimeSpec(selectedSpec, eligible = true))
                    }
                    addAll(eligibleSpecs)
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chips.forEach { spec ->
                        FilterChip(
                            selected = spec.key == selectedSpec,
                            onClick = {
                                selectedSpec = spec.key
                                onSelectSpec(spec.key)
                            },
                            label = { Text(spec.key.label) },
                        )
                    }
                }

                HorizontalGap()
                ToggleRow(
                    title = "Cell keep-alive di kernel",
                    subtitle = "Menjalankan cell loop Python 45 detik di background sebelum cell notebook. File asli tidak diubah.",
                    checked = keepAliveCell,
                    onCheckedChange = { keepAliveCell = it },
                )
                ToggleRow(
                    title = "Jaga sesi setelah selesai",
                    subtitle = "Aplikasi mengirim heartbeat ke kernel dan membiarkan runtime tetap hidup sampai Anda menekan Akhiri.",
                    checked = keepAliveAfter,
                    onCheckedChange = { keepAliveAfter = it },
                )
                if (!keepAliveCell && !keepAliveAfter) {
                    HintCard(
                        icon = Icons.Outlined.Info,
                        title = "Tanpa keep-alive",
                        text = "Kernel akan dihentikan setelah semua sel selesai. Runtime Colab dapat menganggur dan dihentikan otomatis oleh Google.",
                    )
                }
                HintCard(
                    icon = Icons.Outlined.Info,
                    title = "Batas Google tetap berlaku",
                    text = "Keep-alive hanya mengurangi idle timeout. Kuota GPU/CPU, batas sesi, dan kebijakan Colab dapat menghentikan runtime kapan saja.",
                )
            }

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    onStart(
                        backend,
                        KaggleRunOptions(
                            title = title.trim(),
                            slug = slug.trim(),
                            useGpu = gpu,
                            enableInternet = internet,
                            isPrivate = private,
                            timeoutMinutes = timeout,
                        ),
                        ColabRunOptions(
                            spec = selectedSpec,
                            keepAliveCell = keepAliveCell,
                            keepAliveAfterRun = keepAliveAfter,
                        ),
                    )
                },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (backend == Backend.KAGGLE) "Jalankan di Kaggle" else "Jalankan di Colab")
            }
            if (!canStart) {
                Text(
                    when {
                        backend == Backend.KAGGLE && !kaggleReady -> "Lengkapi kredensial Kaggle untuk melanjutkan."
                        backend == Backend.KAGGLE && !kaggleValid -> "Isi judul (min. 5 karakter) dan slug kernel."
                        else -> "Hubungkan akun Colab untuk melanjutkan."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HorizontalGap() {
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun HintCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(7.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunLogSheet(
    run: RunState,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val accent = phaseColor(run.phase, MaterialTheme.colorScheme.secondary)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Log eksekusi", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        run.notebookName.ifBlank { "Belum ada proses" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(
                    text = if (run.phase == RunPhase.IDLE) "Siaga" else phaseLabel(run.phase),
                    icon = if (run.active) Icons.Outlined.Terminal else null,
                    tint = accent,
                )
            }

            if (run.active) {
                if (run.phase == RunPhase.KEEP_ALIVE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(Color(0xFF4ADE80), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Heartbeat aktif setiap 45 detik",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4ADE80),
                        )
                    }
                } else {
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
            }

            if (run.message.isNotBlank()) {
                Text(
                    run.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoRow("Backend", run.backend?.label ?: "-", Modifier.weight(1f))
                Text(
                    formatDuration(run.startedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (run.logs.isEmpty()) {
                    Text(
                        "Belum ada log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        items(run.logs) { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 17.sp,
                                ),
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(run.logs.joinToString("\n")))
                        Toast.makeText(context, "Log disalin.", Toast.LENGTH_SHORT).show()
                    },
                    enabled = run.logs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Salin log") }
                TextButton(onClick = onClear, enabled = run.logs.isNotEmpty()) { Text("Hapus") }
                if (run.active) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (run.phase == RunPhase.KEEP_ALIVE) "Akhiri" else "Stop")
                    }
                } else {
                    Button(onClick = onDismiss) { Text("Tutup") }
                }
            }

            if (run.active && run.phase != RunPhase.KEEP_ALIVE && run.backend == Backend.COLAB) {
                Text(
                    "Setelah selesai, runtime dapat tetap hidup jika opsi keep-alive setelah run aktif.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
