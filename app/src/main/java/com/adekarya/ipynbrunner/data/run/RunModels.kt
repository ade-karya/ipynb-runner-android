package com.adekarya.ipynbrunner.data.run

import com.adekarya.ipynbrunner.data.model.Backend
import com.adekarya.ipynbrunner.data.model.ColabRunOptions
import com.adekarya.ipynbrunner.data.model.KaggleRunOptions
import com.adekarya.ipynbrunner.data.model.NotebookCell
import com.adekarya.ipynbrunner.data.model.NotebookDocument
import kotlinx.serialization.json.JsonObject

data class RunRequest(
    val notebookId: String,
    val notebook: NotebookDocument,
    val backend: Backend,
    val kaggleOptions: KaggleRunOptions,
    val colabOptions: ColabRunOptions,
)

enum class RunPhase {
    IDLE,
    STARTING,
    UPLOADING,
    QUEUED,
    RUNNING,
    KEEP_ALIVE,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class RunState(
    val active: Boolean = false,
    val notebookId: String? = null,
    val notebookName: String = "",
    val backend: Backend? = null,
    val phase: RunPhase = RunPhase.IDLE,
    val message: String = "",
    val logs: List<String> = emptyList(),
    val activeCellId: String? = null,
    val keepAlive: Boolean = false,
    val startedAt: Long = 0L,
    val progress: Float? = null,
) {
    val isRunningNotebook: Boolean
        get() = active && phase != RunPhase.KEEP_ALIVE
}

sealed interface RunEvent {
    val notebookId: String

    data class CellStarted(override val notebookId: String, val cellId: String) : RunEvent

    data class CellOutputs(
        override val notebookId: String,
        val cellId: String,
        val outputs: List<JsonObject>,
        val executionCount: Int?,
    ) : RunEvent

    data class NotebookSynced(
        override val notebookId: String,
        val remoteCells: List<NotebookCell>,
    ) : RunEvent

    data class KeepAliveStarted(override val notebookId: String) : RunEvent

    data class Finished(
        override val notebookId: String,
        val success: Boolean,
        val message: String,
    ) : RunEvent
}
