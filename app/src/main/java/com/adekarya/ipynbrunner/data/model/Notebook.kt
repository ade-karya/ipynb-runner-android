package com.adekarya.ipynbrunner.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

object NotebookJson {
    val format: Json = Json {
        prettyPrint = true
        prettyPrintIndent = " "
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = true
    }
}

class NotebookParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class CellType(val wire: String) {
    CODE("code"),
    MARKDOWN("markdown"),
    RAW("raw"),
    UNKNOWN("raw");

    companion object {
        fun fromWire(value: String?): CellType = when (value?.lowercase()) {
            "code" -> CODE
            "markdown" -> MARKDOWN
            "raw" -> RAW
            else -> UNKNOWN
        }
    }
}

/**
 * One cell of an .ipynb file. [raw] keeps every unknown field (attachments,
 * metadata, widget state, ...) so that saving the notebook never destroys data.
 */
data class NotebookCell(
    val id: String = UUID.randomUUID().toString(),
    val cellType: CellType,
    val source: String,
    val outputs: List<JsonObject> = emptyList(),
    val executionCount: Int? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun toJson(): JsonObject {
        val map = LinkedHashMap<String, JsonElement>(raw)
        map["id"] = raw["id"] ?: JsonPrimitive(id)
        map["cell_type"] = JsonPrimitive(cellType.wire)
        map["source"] = JsonPrimitive(source)
        map["metadata"] = raw["metadata"] ?: JsonObject(emptyMap())
        if (cellType == CellType.CODE) {
            map["outputs"] = JsonArray(outputs)
            map["execution_count"] = executionCount?.let(::JsonPrimitive) ?: JsonNull
        } else {
            // Markdown/raw cells have no execution results. Keep any custom
            // metadata the file already contained, but do not invent outputs.
            map.remove("outputs")
            map.remove("execution_count")
        }
        return JsonObject(map)
    }

    companion object {
        fun fromJson(obj: JsonObject): NotebookCell {
            val type = CellType.fromWire(obj["cell_type"]?.stringOrNull())
            val source = obj["source"]?.sourceText() ?: ""
            val outputs = (obj["outputs"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val count = obj["execution_count"]?.let {
                (it as? JsonPrimitive)?.intOrNull
            }
            return NotebookCell(
                cellType = type,
                source = source,
                outputs = outputs,
                executionCount = count,
                raw = obj,
            )
        }

        fun newCode(source: String): NotebookCell = NotebookCell(
            cellType = CellType.CODE,
            source = source,
            raw = buildJsonObject {
                put("cell_type", "code")
                put("metadata", buildJsonObject {})
                put("source", source)
                put("execution_count", JsonNull)
                put("outputs", buildJsonArray {})
            },
        )

        fun newMarkdown(source: String): NotebookCell = NotebookCell(
            cellType = CellType.MARKDOWN,
            source = source,
            raw = buildJsonObject {
                put("cell_type", "markdown")
                put("metadata", buildJsonObject {})
                put("source", source)
            },
        )
    }
}

data class NotebookDocument(
    val fileName: String,
    val json: JsonObject,
    val cells: List<NotebookCell>,
) {
    val nbformat: Int
        get() = (json["nbformat"] as? JsonPrimitive)?.intOrNull ?: 4

    fun toJson(): JsonObject {
        val map = LinkedHashMap<String, JsonElement>(json)
        map["cells"] = JsonArray(cells.map { it.toJson() })
        if (map["nbformat"] == null) map["nbformat"] = JsonPrimitive(4)
        if (map["nbformat_minor"] == null) map["nbformat_minor"] = JsonPrimitive(5)
        if (map["metadata"] == null) map["metadata"] = defaultMetadata()
        return JsonObject(map)
    }

    fun toPrettyJson(): String = NotebookJson.format.encodeToString(JsonObject.serializer(), toJson())

    fun withCells(newCells: List<NotebookCell>): NotebookDocument = copy(cells = newCells)

    fun updateCell(id: String, transform: (NotebookCell) -> NotebookCell): NotebookDocument =
        copy(cells = cells.map { if (it.id == id) transform(it) else it })

    fun addCell(cell: NotebookCell, afterCellId: String? = null): NotebookDocument {
        if (afterCellId == null) return copy(cells = cells + cell)
        val index = cells.indexOfFirst { it.id == afterCellId }
        if (index < 0) return copy(cells = cells + cell)
        val mutable = cells.toMutableList()
        mutable.add(index + 1, cell)
        return copy(cells = mutable)
    }

    fun deleteCell(id: String): NotebookDocument =
        copy(cells = cells.filterNot { it.id == id })

    fun moveCell(id: String, delta: Int): NotebookDocument {
        val index = cells.indexOfFirst { it.id == id }
        if (index < 0) return this
        val target = (index + delta).coerceIn(0, cells.lastIndex)
        if (target == index) return this
        val mutable = cells.toMutableList()
        val cell = mutable.removeAt(index)
        mutable.add(target, cell)
        return copy(cells = mutable)
    }

    fun clearOutputs(): NotebookDocument = copy(
        cells = cells.map { cell ->
            if (cell.cellType == CellType.CODE) {
                cell.copy(outputs = emptyList(), executionCount = null)
            } else cell
        },
    )

    fun insertKeepAliveCell(): NotebookDocument {
        if (cells.any { it.cellType == CellType.CODE && it.source.contains(KeepAliveCell.MARKER) }) {
            return this
        }
        return copy(cells = listOf(KeepAliveCell.create()) + cells)
    }

    /**
     * Merges results returned by a remote runtime (Kaggle) into this document.
     * Sources are matched first; duplicate or changed cells fall back to the
     * order of code cells. This keeps local edits and metadata intact.
     */
    fun withRemoteOutputs(remoteCells: List<NotebookCell>): NotebookDocument {
        val remoteCode = remoteCells.filter { it.cellType == CellType.CODE }.toMutableList()
        if (remoteCode.isEmpty()) return this
        var fallbackCursor = 0
        val merged = cells.map { local ->
            if (local.cellType != CellType.CODE) return@map local
            val normalized = normalizeSource(local.source)
            val exactIndex = remoteCode.indexOfFirst { normalizeSource(it.source) == normalized }
            val match = when {
                exactIndex >= 0 -> remoteCode.removeAt(exactIndex)
                fallbackCursor < remoteCode.size -> remoteCode[fallbackCursor++]
                else -> null
            }
            if (match == null) local else local.copy(
                outputs = match.outputs,
                executionCount = match.executionCount,
            )
        }
        return copy(cells = merged)
    }

    fun codeCellCount(): Int = cells.count { it.cellType == CellType.CODE }

    companion object {
        private fun normalizeSource(source: String): String =
            source.replace("\r\n", "\n").trimEnd()

        fun parse(fileName: String, text: String): NotebookDocument {
            val root = try {
                NotebookJson.format.parseToJsonElement(text) as? JsonObject
                    ?: throw NotebookParseException("Isi file bukan objek JSON notebook.")
            } catch (e: NotebookParseException) {
                throw e
            } catch (e: Exception) {
                throw NotebookParseException("File .ipynb tidak valid: ${e.message}", e)
            }
            val cellsArray = root["cells"] as? JsonArray
                ?: throw NotebookParseException("Notebook tidak memiliki array \"cells\".")
            val cells = cellsArray.mapIndexed { index, element ->
                val obj = element as? JsonObject
                    ?: throw NotebookParseException("Cell #${index + 1} bukan objek JSON.")
                NotebookCell.fromJson(obj)
            }
            return NotebookDocument(fileName = fileName, json = root, cells = cells)
        }

        fun empty(fileName: String = "notebook-baru.ipynb"): NotebookDocument {
            val title = fileName.removeSuffix(".ipynb")
            val welcome = NotebookCell.newMarkdown(
                """
                # $title

                Notebook baru siap dijalankan di **Kaggle** atau **Colab**.

                - Ketuk **Jalankan** untuk memilih backend.
                - Ketuk cell untuk mengedit.
                - Impor file `.ipynb` yang sudah ada dari halaman utama.
                """.trimIndent(),
            )
            val code = NotebookCell.newCode(
                """
                print("Hello from Notebook Runner!")
                """.trimIndent(),
            )
            val keepAlive = NotebookCell.newMarkdown(
                """
                > **Tip:** aktifkan opsi *Cell keep-alive* saat menjalankan di Colab
                > agar runtime mendapat loop heartbeat. Cell itu dijalankan otomatis
                > sebelum eksekusi, tanpa mengubah notebook asli.
                """.trimIndent(),
            )
            return NotebookDocument(
                fileName = fileName,
                json = buildJsonObject {
                    put("nbformat", 4)
                    put("nbformat_minor", 5)
                    put("metadata", defaultMetadata())
                    put("cells", buildJsonArray {})
                },
                cells = listOf(welcome, code, keepAlive),
            )
        }

        private fun defaultMetadata(): JsonObject = buildJsonObject {
            put(
                "kernelspec",
                buildJsonObject {
                    put("display_name", "Python 3")
                    put("language", "python")
                    put("name", "python3")
                },
            )
            put(
                "language_info",
                buildJsonObject {
                    put("name", "python")
                    put("version", "3.11")
                    put("mimetype", "text/x-python")
                    put("file_extension", ".py")
                    put(
                        "codemirror_mode",
                        buildJsonObject {
                            put("name", "ipython")
                            put("version", 3)
                        },
                    )
                    put("pygments_lexer", "ipython3")
                    put("nbconvert_exporter", "python")
                },
            )
        }
    }
}

object KeepAliveCell {
    const val MARKER = "NOTEBOOK_RUNNER_KEEPALIVE"

    val source: String = """
        # === $MARKER ===
        # Loop di background agar kernel/runtime Colab terus menunjukkan aktivitas.
        # Cell ini tidak memblokir cell berikutnya. Hentikan dengan interrupt
        # atau tombol "Akhiri sesi" di aplikasi.
        import threading as _nr_threading
        import time as _nr_time
        import os as _nr_os

        def _notebook_runner_keepalive():
            _tick = 0
            while True:
                _nr_time.sleep(45)
                _tick += 1
                try:
                    with open("/content/.notebook_runner_keepalive", "w") as _f:
                        _f.write(str(_nr_time.time()))
                except Exception:
                    pass
                print(f"[keep-alive] tick {_tick} {_nr_time.strftime('%H:%M:%S')}", flush=True)

        _nr_thread = _nr_threading.Thread(
            target=_notebook_runner_keepalive,
            name="notebook-runner-keepalive",
            daemon=True,
        )
        _nr_thread.start()
        print("Keep-alive aktif: loop 45 detik berjalan di background.")
    """.trimIndent()

    fun create(): NotebookCell = NotebookCell.newCode(source)
}

fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Jupyter stores text as a string or as an array of strings. */
fun JsonElement.sourceText(): String = when (this) {
    is JsonArray -> joinToString("") { it.sourceText() }
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> ""
}

fun JsonObject.string(key: String): String? = this[key]?.stringOrNull()

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

fun JsonObject.nestedObject(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.nestedArray(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.outputType(): String = string("output_type").orEmpty()

fun JsonObject.outputText(): String? {
    if (outputType() == "stream") return this["text"]?.sourceText()
    val data = this["data"] as? JsonObject ?: return null
    return data["text/plain"]?.sourceText()
}

fun JsonObject.outputHtml(): String? {
    val data = this["data"] as? JsonObject ?: return null
    return data["text/html"]?.sourceText()
}

fun JsonObject.outputJsonText(): String? {
    val data = this["data"] as? JsonObject ?: return null
    return data["application/json"]?.let { element ->
        runCatching { NotebookJson.format.encodeToString(JsonElement.serializer(), element) }.getOrNull()
    }
}

fun JsonObject.outputImage(): OutputImage? {
    val data = this["data"] as? JsonObject ?: return null
    val candidates = listOf("image/png", "image/jpeg", "image/webp", "image/gif")
    for (mime in candidates) {
        val value = data[mime]?.sourceText() ?: continue
        if (value.isNotBlank()) return OutputImage(mime, value)
    }
    return null
}

fun JsonObject.outputSvg(): String? {
    val data = this["data"] as? JsonObject ?: return null
    return data["image/svg+xml"]?.sourceText()
}

fun JsonObject.errorText(): String? {
    if (outputType() != "error") return null
    val traceback = this["traceback"]?.sourceText()
    val name = string("ename").orEmpty()
    val value = string("evalue").orEmpty()
    return when {
        !traceback.isNullOrBlank() -> traceback
        name.isNotBlank() -> "$name: $value"
        else -> "Eksekusi gagal."
    }.replace(Regex("\\u001b\\[[0-9;]*m"), "")
}

data class OutputImage(val mime: String, val base64: String)

/** A lightweight result used when a remote notebook is merged into the UI. */
data class RemoteCellResult(
    val source: String,
    val outputs: List<JsonObject>,
    val executionCount: Int?,
)
