package com.adekarya.ipynbrunner

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adekarya.ipynbrunner.data.AppSettings
import com.adekarya.ipynbrunner.data.RecentNotebook
import com.adekarya.ipynbrunner.data.colab.ColabAuthException
import com.adekarya.ipynbrunner.data.model.Backend
import com.adekarya.ipynbrunner.data.model.CellType
import com.adekarya.ipynbrunner.data.model.ColabRunOptions
import com.adekarya.ipynbrunner.data.model.ColabRuntimeSpec
import com.adekarya.ipynbrunner.data.model.ColabSpecKey
import com.adekarya.ipynbrunner.data.model.KaggleRunOptions
import com.adekarya.ipynbrunner.data.model.NotebookCell
import com.adekarya.ipynbrunner.data.model.NotebookDocument
import com.adekarya.ipynbrunner.data.network.ApiException
import com.adekarya.ipynbrunner.data.run.RunEvent
import com.adekarya.ipynbrunner.data.run.RunRequest
import com.adekarya.ipynbrunner.data.run.RunState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.UnknownHostException

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Notebook : Screen
}

data class AppUiState(
    val screen: Screen = Screen.Home,
    val recents: List<RecentNotebook> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val notebook: NotebookDocument? = null,
    val currentRecent: RecentNotebook? = null,
    val dirty: Boolean = false,
    val loading: Boolean = false,
    val loadingText: String? = null,
    val message: String? = null,
    val run: RunState = RunState(),
    val colabSpecs: List<ColabRuntimeSpec> = emptyList(),
    val colabBusy: Boolean = false,
) {
    val isCurrentNotebookRunning: Boolean
        get() = run.active && currentRecent != null && run.notebookId == currentRecent.id
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as RunnerApp).container
    private val store = container.notebookStore
    private val settingsStore = container.settings
    private val oauth = container.oauth
    private val colabApi = container.colabApi
    private val runController = container.runController

    private val _state = MutableStateFlow(AppUiState(settings = settingsStore.load()))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var autoSave: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.state.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            runController.state.collect { run ->
                _state.update { it.copy(run = run) }
            }
        }
        viewModelScope.launch {
            runController.events.collect(::handleRunEvent)
        }
        refreshRecents()
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun refreshRecents() {
        viewModelScope.launch {
            val items = store.recents()
            _state.update { it.copy(recents = items) }
            items.firstOrNull()?.let { latest ->
                // Keep the recents card's stats in sync after a save.
                if (latest.id == _state.value.currentRecent?.id) {
                    _state.update { it.copy(currentRecent = latest) }
                }
            }
        }
    }

    fun openHome() {
        requestSave()
        _state.update { it.copy(screen = Screen.Home) }
        refreshRecents()
    }

    fun closeNotebook() {
        if (_state.value.isCurrentNotebookRunning) {
            snackbar("Hentikan proses yang sedang berjalan terlebih dahulu.")
            return
        }
        val current = _state.value
        val document = current.notebook
        val recent = current.currentRecent
        _state.update {
            it.copy(screen = Screen.Home, notebook = null, currentRecent = null, dirty = false)
        }
        if (document != null && recent != null) {
            viewModelScope.launch {
                runCatching { store.save(document, recent) }
                    .onFailure { snackbar("Gagal menyimpan: ${friendly(it)}") }
                refreshRecents()
            }
        } else {
            refreshRecents()
        }
    }

    fun openSettings() {
        requestSave()
        _state.update { it.copy(screen = Screen.Settings) }
        if (_state.value.settings.colabSignedIn && _state.value.colabSpecs.isEmpty()) {
            refreshColabSpecs()
        }
    }

    fun openActiveRunNotebook() {
        val run = _state.value.run
        val id = run.notebookId ?: return
        if (_state.value.currentRecent?.id == id) {
            _state.update { it.copy(screen = Screen.Notebook) }
            return
        }
        val recent = _state.value.recents.firstOrNull { it.id == id } ?: return
        openRecent(recent)
    }

    fun importNotebook(uri: android.net.Uri) {
        if (_state.value.isCurrentNotebookRunning) {
            snackbar("Hentikan proses yang sedang berjalan terlebih dahulu.")
            return
        }
        viewModelScope.launch {
            setLoading("Mengimpor notebook...")
            runCatching { store.importFromUri(uri) }
                .onSuccess { recent ->
                    openRecentInternal(recent)
                    snackbar("Notebook diimpor.")
                }
                .onFailure { snackbar("Impor gagal: ${friendly(it)}") }
            setLoading(null)
        }
    }

    fun createNotebook(name: String? = null) {
        if (_state.value.isCurrentNotebookRunning) {
            snackbar("Hentikan proses yang sedang berjalan terlebih dahulu.")
            return
        }
        viewModelScope.launch {
            setLoading("Membuat notebook...")
            runCatching { store.createNew(name) }
                .onSuccess { openRecentInternal(it) }
                .onFailure { snackbar("Gagal membuat notebook: ${friendly(it)}") }
            setLoading(null)
        }
    }

    fun openRecent(recent: RecentNotebook) {
        if (_state.value.isCurrentNotebookRunning) {
            if (_state.value.currentRecent?.id == recent.id) return
            snackbar("Hentikan proses yang sedang berjalan terlebih dahulu.")
            return
        }
        viewModelScope.launch {
            setLoading("Membuka notebook...")
            saveQuietly()
            runCatching { store.load(recent) }
                .onSuccess { document ->
                    _state.update {
                        it.copy(
                            screen = Screen.Notebook,
                            notebook = document,
                            currentRecent = recent,
                            dirty = false,
                            colabSpecs = it.colabSpecs,
                        )
                    }
                }
                .onFailure { snackbar("Gagal membuka notebook: ${friendly(it)}") }
            setLoading(null)
        }
    }

    private suspend fun openRecentInternal(recent: RecentNotebook) {
        val document = store.load(recent)
        _state.update {
            it.copy(
                screen = Screen.Notebook,
                notebook = document,
                currentRecent = recent,
                dirty = false,
            )
        }
        refreshRecents()
    }

    fun deleteCurrentNotebook() {
        val recent = _state.value.currentRecent ?: return
        if (_state.value.isCurrentNotebookRunning) {
            snackbar("Hentikan proses yang sedang berjalan terlebih dahulu.")
            return
        }
        viewModelScope.launch {
            runCatching { store.delete(recent) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            screen = Screen.Home,
                            notebook = null,
                            currentRecent = null,
                            dirty = false,
                        )
                    }
                    refreshRecents()
                    snackbar("Notebook dihapus dari perangkat.")
                }
                .onFailure { snackbar("Gagal menghapus: ${friendly(it)}") }
        }
    }

    fun deleteRecent(recent: RecentNotebook) {
        val run = _state.value.run
        if (run.active && run.notebookId == recent.id) {
            snackbar("Notebook ini sedang dijalankan. Hentikan prosesnya terlebih dahulu.")
            return
        }
        viewModelScope.launch {
            runCatching { store.delete(recent) }
                .onSuccess {
                    if (_state.value.currentRecent?.id == recent.id) {
                        _state.update {
                            it.copy(notebook = null, currentRecent = null, dirty = false)
                        }
                    }
                    refreshRecents()
                    snackbar("Notebook dihapus dari perangkat.")
                }
                .onFailure { snackbar("Gagal menghapus: ${friendly(it)}") }
        }
    }

    fun exportNotebook(uri: android.net.Uri) {
        val document = _state.value.notebook ?: return
        viewModelScope.launch {
            runCatching { store.export(document, uri) }
                .onSuccess { snackbar("Notebook diekspor.") }
                .onFailure { snackbar("Ekspor gagal: ${friendly(it)}") }
        }
    }

    fun saveNow() {
        requestSave()
    }

    private fun requestSave() {
        viewModelScope.launch { saveQuietly() }
    }

    private suspend fun saveQuietly() {
        val current = _state.value
        val document = current.notebook ?: return
        val recent = current.currentRecent ?: return
        runCatching {
            val updated = store.save(document, recent)
            _state.update { it.copy(currentRecent = updated, dirty = false) }
            refreshRecents()
        }.onFailure { snackbar("Gagal menyimpan: ${friendly(it)}") }
    }

    private fun scheduleSave() {
        autoSave?.cancel()
        autoSave = viewModelScope.launch {
            delay(700)
            saveQuietly()
        }
    }

    fun updateCellSource(cellId: String, source: String) {
        if (!canEdit()) return
        _state.update { current ->
            val notebook = current.notebook ?: return@update current
            current.copy(
                notebook = notebook.updateCell(cellId) {
                    it.copy(source = source)
                },
                dirty = true,
            )
        }
        scheduleSave()
    }

    fun addCell(type: CellType, afterCellId: String? = null) {
        if (!canEdit()) return
        _state.update { current ->
            val notebook = current.notebook ?: return@update current
            val cell = when (type) {
                CellType.MARKDOWN -> NotebookCell.newMarkdown("## Judul\n\nTeks markdown...")
                else -> NotebookCell.newCode("")
            }
            current.copy(
                notebook = notebook.addCell(cell, afterCellId ?: notebook.cells.lastOrNull()?.id),
                dirty = true,
            )
        }
        scheduleSave()
    }

    fun deleteCell(cellId: String) {
        if (!canEdit()) return
        _state.update { current ->
            val notebook = current.notebook ?: return@update current
            if (notebook.cells.size <= 1) return@update current
            current.copy(notebook = notebook.deleteCell(cellId), dirty = true)
        }
        scheduleSave()
    }

    fun moveCell(cellId: String, delta: Int) {
        if (!canEdit()) return
        _state.update { current ->
            val notebook = current.notebook ?: return@update current
            current.copy(notebook = notebook.moveCell(cellId, delta), dirty = true)
        }
        scheduleSave()
    }

    fun insertKeepAliveCell() {
        if (!canEdit()) return
        _state.update { current ->
            val notebook = current.notebook ?: return@update current
            current.copy(notebook = notebook.insertKeepAliveCell(), dirty = true)
        }
        scheduleSave()
        snackbar("Cell keep-alive ditambahkan di awal notebook.")
    }

    fun clearOutputs() {
        if (!canEdit()) return
        _state.update { current ->
            val notebook = current.notebook ?: return@update current
            current.copy(notebook = notebook.clearOutputs(), dirty = true)
        }
        scheduleSave()
        snackbar("Semua output dibersihkan.")
    }

    fun startRun(
        backend: Backend,
        kaggleOptions: KaggleRunOptions,
        colabOptions: ColabRunOptions,
    ) {
        val current = _state.value
        val notebook = current.notebook ?: return
        val recent = current.currentRecent ?: return
        if (current.run.active) {
            snackbar("Masih ada proses aktif. Akhiri sesi terlebih dahulu.")
            return
        }
        if (backend == Backend.KAGGLE && (current.settings.kaggleUsername.isBlank() ||
                !current.settings.hasKaggleKey)
        ) {
            snackbar("Isi username dan API key Kaggle di Pengaturan terlebih dahulu.")
            return
        }
        if (backend == Backend.COLAB && !current.settings.colabSignedIn) {
            snackbar("Hubungkan akun Google Colab di Pengaturan terlebih dahulu.")
            return
        }

        settingsStore.updateRunDefaults(
            backend = backend,
            spec = colabOptions.spec,
            keepAliveCell = colabOptions.keepAliveCell,
            keepAliveAfterRun = colabOptions.keepAliveAfterRun,
            kaggleGpu = kaggleOptions.useGpu,
            kaggleInternet = kaggleOptions.enableInternet,
            kagglePrivate = kaggleOptions.isPrivate,
        )

        val cleared = notebook.clearOutputs()
        _state.update { it.copy(notebook = cleared, dirty = true) }
        viewModelScope.launch {
            saveQuietly()
            runController.start(
                RunRequest(
                    notebookId = recent.id,
                    notebook = cleared,
                    backend = backend,
                    kaggleOptions = kaggleOptions,
                    colabOptions = colabOptions,
                ),
            )
        }
    }

    fun cancelRun() = runController.cancel()

    fun clearLogs() = runController.clearLogs()

    fun saveCredentials(
        kaggleUsername: String,
        kaggleKey: String,
        colabClientId: String,
        colabClientSecret: String,
        quotaProject: String,
    ) {
        settingsStore.setKaggleUsername(kaggleUsername)
        if (kaggleKey.isNotBlank()) settingsStore.setKaggleKey(kaggleKey)
        settingsStore.setColabClientId(colabClientId)
        if (colabClientSecret.isNotBlank()) settingsStore.setColabClientSecret(colabClientSecret)
        settingsStore.setColabQuotaProject(quotaProject)
        snackbar("Pengaturan disimpan.")
    }

    fun clearKaggleKey() {
        settingsStore.setKaggleKey(null)
        snackbar("API key Kaggle dihapus.")
    }

    fun signInColab(context: android.content.Context) {
        if (_state.value.colabBusy) return
        val s = _state.value.settings
        if (s.colabClientId.isBlank()) {
            snackbar("Isi OAuth Client ID terlebih dahulu.")
            return
        }
        if (s.colabQuotaProject.isBlank()) {
            snackbar("Isi quota project Google Cloud terlebih dahulu.")
            return
        }
        _state.update { it.copy(colabBusy = true) }
        viewModelScope.launch {
            runCatching { oauth.authenticate(context) }
                .onSuccess {
                    snackbar("Akun Google Colab terhubung.")
                    refreshColabSpecs()
                }
                .onFailure { snackbar("Login Colab gagal: ${friendly(it)}") }
            _state.update { it.copy(colabBusy = false) }
        }
    }

    fun signOutColab() {
        if (_state.value.run.active) {
            snackbar("Akhiri sesi yang berjalan terlebih dahulu.")
            return
        }
        settingsStore.clearColabTokens()
        _state.update { it.copy(colabSpecs = emptyList()) }
        snackbar("Akun Colab diputuskan. Runtime yang masih aktif dapat dihentikan lewat Google Colab.")
    }

    fun refreshColabSpecs() {
        if (!_state.value.settings.colabSignedIn) return
        viewModelScope.launch {
            _state.update { it.copy(colabBusy = true) }
            runCatching { colabApi.listRuntimeSpecs() }
                .onSuccess { specs ->
                    _state.update { it.copy(colabSpecs = specs) }
                    val currentSpec = _state.value.settings.colabSpec
                    val eligible = specs.filter { it.eligible }
                    if (eligible.isNotEmpty() && eligible.none { it.key == currentSpec }) {
                        val preferred = eligible.firstOrNull {
                            it.key.variant == "VARIANT_CPU" && it.key.accelerator == "NONE"
                        } ?: eligible.first()
                        settingsStore.updateRunDefaults(spec = preferred.key)
                    }
                }
                .onFailure { snackbar("Gagal memuat runtime Colab: ${friendly(it)}") }
            _state.update { it.copy(colabBusy = false) }
        }
    }

    fun selectColabSpec(spec: ColabSpecKey) {
        settingsStore.updateRunDefaults(spec = spec)
    }

    fun stopColabRuntime() {
        val s = _state.value.settings
        val name = s.colabRuntimeName ?: run {
            snackbar("Tidak ada runtime Colab tersimpan.")
            return
        }
        viewModelScope.launch {
            if (_state.value.run.active) {
                runController.cancel()
                delay(1_500)
            }
            _state.update { it.copy(colabBusy = true) }
            runCatching { colabApi.deleteRuntime(name) }
                .onSuccess {
                    settingsStore.setColabRuntime(null, null)
                    snackbar("Runtime Colab dihentikan.")
                }
                .onFailure { snackbar("Gagal menghentikan runtime: ${friendly(it)}") }
            _state.update { it.copy(colabBusy = false) }
        }
    }

    fun setLastBackend(backend: Backend) = settingsStore.updateRunDefaults(backend = backend)

    private fun canEdit(): Boolean {
        val current = _state.value
        return !(current.run.active && current.currentRecent?.id == current.run.notebookId)
    }

    private fun handleRunEvent(event: RunEvent) {
        val current = _state.value
        if (event.notebookId != current.currentRecent?.id) return
        when (event) {
            is RunEvent.CellStarted -> {
                // Run state already tracks the active cell; nothing else to update.
            }

            is RunEvent.CellOutputs -> {
                val notebook = current.notebook ?: return
                _state.update {
                    it.copy(
                        notebook = notebook.updateCell(event.cellId) { cell ->
                            cell.copy(
                                outputs = event.outputs,
                                executionCount = event.executionCount,
                            )
                        },
                        dirty = true,
                    )
                }
                scheduleSave()
            }

            is RunEvent.NotebookSynced -> {
                val notebook = current.notebook ?: return
                val merged = notebook.withRemoteOutputs(event.remoteCells)
                _state.update { it.copy(notebook = merged, dirty = true) }
                scheduleSave()
            }

            is RunEvent.KeepAliveStarted -> {
                snackbar("Notebook selesai. Sesi Colab tetap aktif di background.")
            }

            is RunEvent.Finished -> {
                val notebook = _state.value.notebook
                if (notebook != null) {
                    viewModelScope.launch {
                        val recent = _state.value.currentRecent ?: return@launch
                        runCatching {
                            val updated = store.save(notebook, recent)
                            _state.update { it.copy(currentRecent = updated, dirty = false) }
                            refreshRecents()
                        }
                    }
                }
                snackbar(event.message)
            }
        }
    }

    private fun snackbar(text: String) {
        _state.update { it.copy(message = text) }
    }

    private fun setLoading(text: String?) {
        _state.update { it.copy(loading = text != null, loadingText = text) }
    }

    private fun friendly(error: Throwable): String = when (error) {
        is ColabAuthException -> error.message ?: "Autentikasi Colab gagal."
        is ApiException -> error.message
        is UnknownHostException -> "Tidak ada koneksi internet."
        else -> error.message ?: error.javaClass.simpleName
    }
}
