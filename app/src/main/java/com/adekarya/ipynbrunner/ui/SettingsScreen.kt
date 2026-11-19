package com.adekarya.ipynbrunner.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adekarya.ipynbrunner.AppUiState
import com.adekarya.ipynbrunner.AppViewModel
import com.adekarya.ipynbrunner.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: AppUiState,
    viewModel: AppViewModel,
) {
    val settings = state.settings
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var kaggleUser by remember(settings.kaggleUsername) { mutableStateOf(settings.kaggleUsername) }
    var kaggleKey by remember { mutableStateOf("") }
    var clientId by remember(settings.colabClientId) { mutableStateOf(settings.colabClientId) }
    var clientSecret by remember { mutableStateOf("") }
    var quotaProject by remember(settings.colabQuotaProject) { mutableStateOf(settings.colabQuotaProject) }
    var showKaggleHelp by remember { mutableStateOf(false) }
    var showColabHelp by remember { mutableStateOf(false) }

    fun saveAll() {
        viewModel.saveCredentials(
            kaggleUsername = kaggleUser,
            kaggleKey = kaggleKey,
            colabClientId = clientId,
            colabClientSecret = clientSecret,
            quotaProject = quotaProject,
        )
        kaggleKey = ""
        clientSecret = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.openHome() }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Kembali")
                    }
                },
                title = { Text("Pengaturan") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "Hubungkan akun cloud Anda. Kredensial hanya disimpan di perangkat ini dan dienkripsi dengan Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                SettingsCard(
                    icon = Icons.Outlined.VpnKey,
                    title = "Kaggle",
                    subtitle = "API v1 · token KGAT_ atau username + key",
                    status = if (settings.hasKaggleKey && settings.kaggleUsername.isNotBlank()) "Siap" else "Belum lengkap",
                    statusOk = settings.hasKaggleKey && settings.kaggleUsername.isNotBlank(),
                ) {
                    OutlinedTextField(
                        value = kaggleUser,
                        onValueChange = { kaggleUser = it },
                        label = { Text("Username Kaggle") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = kaggleKey,
                        onValueChange = { kaggleKey = it },
                        label = { Text("API key / token") },
                        placeholder = {
                            Text(
                                if (settings.hasKaggleKey) "Tersimpan — isi untuk mengganti"
                                else "KGAT_… atau key legacy",
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (settings.hasKaggleKey) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4ADE80),
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "API key tersimpan di Keystore",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { viewModel.clearKaggleKey() }) {
                                Text("Hapus key", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Text(
                                "Buat dari kaggle.com → Settings → API → Create New Token.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saveAll() }, modifier = Modifier.weight(1f)) { Text("Simpan") }
                        TextButton(onClick = { showKaggleHelp = !showKaggleHelp }) {
                            Text(if (showKaggleHelp) "Tutup panduan" else "Panduan")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            saveAll()
                            viewModel.testKaggleCredentials()
                        },
                        enabled = !state.kaggleChecking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.kaggleChecking) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Simpan & tes kredensial")
                    }
                    state.kaggleCheck?.let { result ->
                        Text(
                            result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.startsWith("✓")) Color(0xFF4ADE80)
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (showKaggleHelp) {
                        HelpText(
                            listOf(
                                "Kaggle → Settings → API → Create New Token. Token baru berawalan KGAT_ dan dikirim aplikasi sebagai Bearer.",
                                "Token KGAT_ ditempel di kolom API key/token. Username tetap diisi karena dipakai sebagai pemilik slug kernel.",
                                "Jika memakai kredensial legacy: unduh kaggle.json, lalu isi username dan key dari file itu (Basic auth).",
                                "Tombol Simpan & tes memanggil endpoint daftar kernel pribadi untuk memastikan kredensial benar-benar diterima.",
                                "Jangan bagikan token/key ke siapa pun. Jika bocor, buat ulang dari halaman Settings Kaggle.",
                            ),
                        )
                    }
                }
            }

            item {
                SettingsCard(
                    icon = Icons.Outlined.Cloud,
                    title = "Google Colab",
                    subtitle = "Colab API v1beta · OAuth 2.0",
                    status = if (settings.colabSignedIn) "Terhubung" else "Belum login",
                    statusOk = settings.colabSignedIn,
                ) {
                    if (settings.colabSignedIn) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Cloud,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        settings.colabAccount ?: "Akun Google terhubung",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Token OAuth tersimpan terenkripsi.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { viewModel.signOutColab() }) {
                                    Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Putuskan")
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        label = { Text("OAuth Client ID") },
                        placeholder = { Text("xxxxx.apps.googleusercontent.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it },
                        label = { Text("OAuth Client secret") },
                        placeholder = {
                            Text(if (settings.hasColabClientSecret) "Tersimpan — isi untuk mengganti" else "Client secret (Desktop app)")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = quotaProject,
                        onValueChange = { quotaProject = it },
                        label = { Text("Quota project (project ID)") },
                        placeholder = { Text("proyek-google-cloud-anda") },
                        supportingText = {
                            Text("Project yang mengaktifkan Colab API dan menagih kuota.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { saveAll() }, modifier = Modifier.weight(1f)) {
                            Text("Simpan kredensial")
                        }
                        Button(
                            onClick = {
                                saveAll()
                                viewModel.signInColab(context)
                            },
                            enabled = !state.colabBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.colabBusy) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(if (settings.colabSignedIn) "Login ulang" else "Hubungkan")
                        }
                    }
                    Text(
                        "Tombol hubungkan menyimpan kredensial lalu membuka browser untuk consent Google. Kembali ke aplikasi setelah selesai.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showColabHelp = !showColabHelp }) {
                        Text(if (showColabHelp) "Tutup panduan Google Cloud" else "Panduan Google Cloud")
                    }
                    if (showColabHelp) {
                        HelpText(
                            listOf(
                                "Buat project di console.cloud.google.com, lalu aktifkan \"Colab API\" (colaboratory.googleapis.com).",
                                "Konfigurasi OAuth consent screen (External), tambahkan scope https://www.googleapis.com/auth/colaboratory, dan daftarkan email Anda sebagai test user.",
                                "Buat Credentials → OAuth client ID → tipe \"Desktop app\". Salin Client ID dan secret.",
                                "Isi quota project dengan project ID. Project ini dipakai pada header x-goog-user-project saat memanggil Colab API.",
                                "Tekan Hubungkan, setujui akses di browser, lalu kembali ke aplikasi. Jika muncul error 403, periksa API, scope, dan test user.",
                            ),
                        )
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SectionHeader(
                        title = "Runtime tersimpan",
                        subtitle = settings.colabRuntimeName?.let { name ->
                            "${settings.colabRuntimeLabel ?: "Colab"} · ${name.removePrefix("runtimes/")}"
                        } ?: "Belum ada runtime. Runtime dibuat otomatis saat run pertama.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.refreshColabSpecs() },
                            enabled = settings.colabSignedIn && !state.colabBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.colabBusy) {
                                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            }
                            Spacer(Modifier.width(5.dp))
                            Text("Muat tipe runtime")
                        }
                        TextButton(
                            onClick = { viewModel.stopColabRuntime() },
                            enabled = settings.colabRuntimeName != null && !state.colabBusy,
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Hentikan runtime", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (state.colabSpecs.isNotEmpty()) {
                        val eligible = state.colabSpecs.filter { it.eligible }
                        if (eligible.isEmpty()) {
                            Text(
                                "Tidak ada tipe runtime yang eligible untuk akun ini.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                eligible.forEach { spec ->
                                    FilterChip(
                                        selected = spec.key == settings.colabSpec,
                                        onClick = { viewModel.selectColabSpec(spec.key) },
                                        label = { Text(spec.key.label) },
                                    )
                                }
                            }
                        }
                    }
                    HintBlock(
                        icon = Icons.Outlined.WarningAmber,
                        title = "Batas keep-alive",
                        text = "Heartbeat dan cell loop mengurangi idle timeout, tetapi tidak menjamin sesi abadi. Kuota, batas GPU, dan kebijakan Google Colab tetap berlaku. Hentikan runtime dari halaman ini bila tidak dipakai.",
                    )
                }
            }

            item {
                SettingsCard(
                    icon = Icons.Outlined.Security,
                    title = "Keamanan & data",
                    subtitle = "Penyimpanan lokal",
                    status = null,
                    statusOk = true,
                ) {
                    Text(
                        "API key Kaggle dan token OAuth Google disimpan via Android Keystore (AES-GCM). " +
                            "Token tidak pernah dikirim ke server lain selain endpoint resmi Kaggle/Google. " +
                            "Notebook disimpan sebagai file privat di folder aplikasi; ekspor ke penyimpanan memakai file picker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Notebook Runner ${BuildConfig.VERSION_NAME} · ${BuildConfig.APPLICATION_ID}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { uriHandler.openUri("https://www.kaggle.com/docs/api") }) {
                            Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Kaggle API")
                        }
                        TextButton(onClick = { uriHandler.openUri("https://developers.google.com/colab") }) {
                            Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Colab API")
                        }
                    }
                }
            }

            item {
                Text(
                    "Dibuat untuk penggunaan pribadi. Patuhi Terms of Service Kaggle dan Google Colab. " +
                        "Jangan gunakan keep-alive untuk menyalahgunakan kuota atau menjalankan beban kerja yang dilarang.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    status: String?,
    statusOk: Boolean,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.padding(7.dp).size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status != null) {
                    StatusPill(
                        text = status,
                        icon = if (statusOk) Icons.Outlined.CheckCircle else Icons.Outlined.CloudOff,
                        tint = if (statusOk) Color(0xFF4ADE80) else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun HelpText(steps: List<String>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(20.dp),
                    )
                    Text(
                        step,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HintBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
