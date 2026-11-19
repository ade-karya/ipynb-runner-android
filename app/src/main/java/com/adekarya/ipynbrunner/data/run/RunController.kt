package com.adekarya.ipynbrunner.data.run

import android.content.Context
import com.adekarya.ipynbrunner.data.SettingsStore
import com.adekarya.ipynbrunner.data.colab.ColabApi
import com.adekarya.ipynbrunner.data.colab.ColabAuthException
import com.adekarya.ipynbrunner.data.colab.ColabOAuth
import com.adekarya.ipynbrunner.data.colab.JupyterClient
import com.adekarya.ipynbrunner.data.kaggle.KaggleClient
import com.adekarya.ipynbrunner.data.model.CellType
import com.adekarya.ipynbrunner.data.model.KeepAliveCell
import com.adekarya.ipynbrunner.data.model.NotebookCell
import com.adekarya.ipynbrunner.data.model.NotebookDocument
import com.adekarya.ipynbrunner.data.model.outputType
import com.adekarya.ipynbrunner.data.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Application-scoped orchestrator. It intentionally does not live in a
 * ViewModel so rotation/backgrounding does not abort a long Kaggle or Colab
 * run. The UI observes [state] and [events].
 */
class RunController(
    private val context: Context,
    private val settings: SettingsStore,
    private val colabApi: ColabApi,
    private val oauth: ColabOAuth,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RunEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<RunEvent> = _events.asSharedFlow()

    private var runJob: Job? = null
    private var jupyter: JupyterClient? = null
    private var runNotebookId: String? = null
    @Volatile private var keepAliveMode = false

    fun start(request: RunRequest) {
        if (_state.value.active) return
        keepAliveMode = false
        runNotebookId = request.notebookId
        val title = "${request.notebook.fileName} · ${request.backend.label}"
        RunService.start(context, title, "Menyiapkan eksekusi...")
        _state.value = RunState(
            active = true,
            notebookId = request.notebookId,
            notebookName = request.notebook.fileName,
            backend = request.backend,
            phase = RunPhase.STARTING,
            message = "Menyiapkan eksekusi...",
            logs = emptyList(),
            startedAt = System.currentTimeMillis(),
        )
        runJob = scope.launch {
            try {
                appendLog("Backend: ${request.backend.label}")
                when (request.backend) {
                    com.adekarya.ipynbrunner.data.model.Backend.KAGGLE -> runKaggle(request)
                    com.adekarya.ipynbrunner.data.model.Backend.COLAB -> runColab(request)
                }
            } catch (e: CancellationException) {
                update {
                    copy(
                        active = false,
                        phase = RunPhase.CANCELLED,
                        activeCellId = null,
                        keepAlive = false,
                        message = "Dihentikan",
                    )
                }
                _events.emit(
                    RunEvent.Finished(
                        request.notebookId,
                        success = false,
                        message = "Eksekusi dihentikan.",
                    ),
                )
            } catch (e: Exception) {
                val message = humanMessage(e)
                appendLog("GAGAL: $message")
                update {
                    copy(
                        active = false,
                        phase = RunPhase.FAILED,
                        activeCellId = null,
                        keepAlive = false,
                        message = message,
                    )
                }
                _events.emit(RunEvent.Finished(request.notebookId, success = false, message = message))
            } finally {
                keepAliveMode = false
                cleanupJupyter()
                RunService.stop(context)
            }
        }
    }

    fun cancel() {
        val current = _state.value
        if (!current.active) return
        update { copy(message = "Menghentikan...") }
        val client = jupyter
        if (client != null) {
            scope.launch {
                withContext(NonCancellable) {
                    withTimeoutOrNull(15_000) { runCatching { client.interruptKernel() } }
                }
            }
        }
        runJob?.cancel()
    }

    private suspend fun runKaggle(request: RunRequest) {
        val config = settings.load()
        val apiKey = settings.kaggleKey()
        if (config.kaggleUsername.isBlank() || apiKey.isNullOrBlank()) {
            throw IllegalStateException(
                "Kredensial Kaggle belum diisi. Buka Pengaturan → Kaggle dan masukkan username + API key.",
            )
        }
        val client = KaggleClient(config.kaggleUsername.trim(), apiKey)
        val options = request.kaggleOptions
        update { copy(phase = RunPhase.UPLOADING, message = "Mengunggah notebook ke Kaggle...") }
        appendLog("Mengunggah notebook ke Kaggle...")
        val push = client.push(request.notebook, options)
        val version = push.versionNumber
        appendLog(
            "Versi ${version ?: "baru"} berhasil diunggah" +
                (if (push.url.isNotBlank()) " (${push.url})" else ""),
        )
        update { copy(phase = RunPhase.QUEUED, message = "Menunggu antrean Kaggle...") }

        var lastStatus = ""
        val finalStatus = KaggleClient.awaitCompletion(
            client = client,
            slug = options.slug,
            versionNumber = version,
            timeoutMinutes = options.timeoutMinutes,
        ) { status, _ ->
            if (status.normalized != lastStatus) {
                lastStatus = status.normalized
                val phase = if (status.normalized == "running") RunPhase.RUNNING else RunPhase.QUEUED
                update {
                    copy(
                        phase = phase,
                        message = "Kaggle: ${status.normalized}",
                    )
                }
                appendLog("Status Kaggle: ${status.normalized}")
            }
        }

        if (finalStatus.normalized != "complete") {
            throw IllegalStateException(
                "Kaggle berhenti dengan status \"${finalStatus.normalized}\"" +
                    (finalStatus.failureMessage?.let { ": $it" } ?: "."),
            )
        }

        appendLog("Kaggle selesai. Mengambil output...")
        update { copy(phase = RunPhase.RUNNING, message = "Mengunduh output Kaggle...") }
        val listing = client.output(options.slug, version)
        listing.log?.takeIf { it.isNotBlank() }?.let { log ->
            val lines = log.lines()
            appendLog("Log eksekusi Kaggle (${lines.size} baris, menampilkan akhir):")
            lines.takeLast(40).forEach { appendLog("│ $it") }
        }

        val notebookFile = listing.files.firstOrNull {
            it.fileName.endsWith(".ipynb", ignoreCase = true)
        }
        var remote: NotebookDocument? = null
        if (notebookFile != null) {
            runCatching {
                val text = client.downloadText(notebookFile.url)
                NotebookDocument.parse(notebookFile.fileName, text)
            }.onSuccess { remote = it }
                .onFailure { appendLog("Output ${notebookFile.fileName} tidak bisa dibaca: ${it.message}") }
        }
        if (remote == null) {
            runCatching {
                val source = client.pullNotebook(options.slug, version)
                if (source != null) NotebookDocument.parse("${options.slug.substringAfterLast('/')}.ipynb", source)
                else null
            }.onSuccess { pulled ->
                if (pulled != null) {
                    remote = pulled
                    appendLog("Menggunakan notebook dari kernels/pull (mungkin tanpa output).")
                }
            }.onFailure { appendLog("Kernels/pull gagal: ${it.message}") }
        }

        if (remote != null) {
            _events.emit(RunEvent.NotebookSynced(request.notebookId, remote!!.cells))
            val outputCount = remote!!.cells.sumOf { it.outputs.size }
            appendLog("Output diterapkan ke notebook ($outputCount output).")
        } else {
            appendLog("Kaggle tidak mengembalikan file .ipynb. Notebook lokal tidak diubah.")
        }

        update {
            copy(
                active = false,
                phase = RunPhase.COMPLETED,
                activeCellId = null,
                keepAlive = false,
                message = "Selesai",
                progress = 1f,
            )
        }
        _events.emit(RunEvent.Finished(request.notebookId, success = true, message = "Kaggle selesai."))
    }

    private suspend fun runColab(request: RunRequest) {
        val options = request.colabOptions
        // Refresh token early so authentication errors are shown before a VM is allocated.
        oauth.accessToken()
        appendLog("Meminta runtime Colab (${options.spec.label})...")
        update { copy(phase = RunPhase.STARTING, message = "Menyiapkan runtime Colab...") }
        val runtime = colabApi.ensureRuntime(options.spec) { message ->
            update { copy(message = message) }
        }
        appendLog("Runtime siap: ${runtime.name} (${runtime.spec?.label ?: options.spec.label})")
        update { copy(phase = RunPhase.RUNNING, message = "Menyambungkan kernel...") }

        val client = JupyterClient(runtime.connectionUrl, runtime.proxyToken)
        jupyter = client
        val kernelId = client.createKernel()
        appendLog("Kernel dibuat: $kernelId")
        client.connect(kernelId)
        appendLog("Kernel tersambung. Menjalankan notebook...")

        if (options.keepAliveCell) {
            appendLog("Menjalankan cell keep-alive (loop 45 detik)...")
            runCatching {
                client.execute(KeepAliveCell.source) {}
            }.onFailure {
                appendLog("Peringatan: cell keep-alive gagal: ${it.message}")
            }.onSuccess {
                appendLog("Cell keep-alive aktif di kernel.")
            }
        }

        val codeCells = request.notebook.cells.filter { it.cellType == CellType.CODE }
        val total = codeCells.size
        var completed = 0
        for ((index, cell) in codeCells.withIndex()) {
            _events.emit(RunEvent.CellStarted(request.notebookId, cell.id))
            update {
                copy(
                    activeCellId = cell.id,
                    message = "Menjalankan cell ${index + 1}/$total...",
                    progress = if (total == 0) 0f else index.toFloat() / total,
                )
            }
            appendLog("Cell ${index + 1}/$total dimulai.")
            val result = client.execute(cell.source) { update ->
                _events.tryEmit(
                    RunEvent.CellOutputs(
                        notebookId = request.notebookId,
                        cellId = cell.id,
                        outputs = update.outputs,
                        executionCount = update.executionCount,
                    ),
                )
            }
            _events.emit(
                RunEvent.CellOutputs(
                    notebookId = request.notebookId,
                    cellId = cell.id,
                    outputs = result.outputs,
                    executionCount = result.executionCount,
                ),
            )
            appendLog("Cell ${index + 1}/$total selesai (${result.outputs.size} output).")
            completed++
            update { copy(progress = if (total == 0) 1f else completed.toFloat() / total) }
            if (result.outputs.any { it.outputType() == "error" }) {
                val error = result.outputs.firstOrNull { it.outputType() == "error" }
                val name = error?.get("ename")?.toString()?.trim('"').orEmpty()
                throw IllegalStateException(
                    "Cell ${index + 1} gagal" + if (name.isNotBlank()) " ($name)." else ".",
                )
            }
        }

        if (options.keepAliveAfterRun) {
            keepAliveMode = true
            update {
                copy(
                    active = true,
                    phase = RunPhase.KEEP_ALIVE,
                    keepAlive = true,
                    activeCellId = null,
                    progress = 1f,
                    message = "Sesi Colab dijaga aktif. Tekan Akhiri untuk berhenti.",
                )
            }
            appendLog("Semua cell selesai. Mode keep-alive aktif (heartbeat 45 detik).")
            appendLog("Runtime tetap berjalan sampai Anda menekan \"Akhiri sesi\" atau menghentikan runtime.")
            _events.emit(RunEvent.KeepAliveStarted(request.notebookId))
            var tick = 0
            var failures = 0
            while (true) {
                delay(45_000)
                tick++
                try {
                    client.heartbeat()
                    failures = 0
                    val stamp = timeNow()
                    update {
                        copy(
                            active = true,
                            phase = RunPhase.KEEP_ALIVE,
                            message = "Keep-alive aktif · heartbeat #$tick · $stamp",
                        )
                    }
                    if (tick % 10 == 0) appendLog("Keep-alive heartbeat #$tick ok.")
                } catch (e: Exception) {
                    failures++
                    appendLog("Heartbeat gagal ($failures/3): ${e.message}")
                    if (failures >= 3) {
                        throw IllegalStateException(
                            "Keep-alive terputus setelah 3 heartbeat gagal. Runtime mungkin dihentikan.",
                            e,
                        )
                    }
                }
            }
        }

        runCatching { client.deleteKernel() }
        appendLog("Kernel dihentikan. Runtime Colab tetap tersimpan untuk run berikutnya.")
        update {
            copy(
                active = false,
                phase = RunPhase.COMPLETED,
                keepAlive = false,
                activeCellId = null,
                message = "Selesai",
                progress = 1f,
            )
        }
        _events.emit(RunEvent.Finished(request.notebookId, success = true, message = "Colab selesai."))
    }

    private suspend fun cleanupJupyter() {
        val client = jupyter
        jupyter = null
        if (client == null) return
        withContext(NonCancellable) {
            withTimeoutOrNull(15_000) { runCatching { client.interruptKernel() } }
            withTimeoutOrNull(15_000) { runCatching { client.deleteKernel() } }
            client.close()
        }
    }

    private fun update(transform: RunState.() -> RunState) {
        _state.update { it.transform() }
        val s = _state.value
        if (s.active) {
            RunService.update(
                context,
                "${s.notebookName} · ${s.backend?.label ?: ""}",
                s.message,
            )
        }
    }

    private fun appendLog(message: String) {
        val line = "${timeNow()}  $message"
        _state.update { it.copy(logs = (it.logs + line).takeLast(400), message = message) }
        update { this }
    }

    private fun humanMessage(error: Throwable): String = when (error) {
        is ColabAuthException -> error.message ?: "Autentikasi Colab gagal."
        is ApiException -> error.message
        is UnknownHostException -> "Tidak ada koneksi internet atau DNS gagal."
        is SSLException -> "Koneksi TLS gagal: ${error.message}"
        is IllegalStateException, is IllegalArgumentException -> error.message ?: "Terjadi kesalahan."
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun timeNow(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    fun isActiveFor(notebookId: String?): Boolean =
        _state.value.active && (notebookId == null || _state.value.notebookId == notebookId)

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun logsFor(notebookId: String?): List<String> {
        val s = _state.value
        return if (notebookId == null || s.notebookId == notebookId) s.logs else emptyList()
    }
}
