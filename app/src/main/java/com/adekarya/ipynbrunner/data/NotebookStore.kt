package com.adekarya.ipynbrunner.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.adekarya.ipynbrunner.data.model.NotebookDocument
import com.adekarya.ipynbrunner.data.model.NotebookJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

data class RecentNotebook(
    val id: String,
    val displayName: String,
    val path: String,
    val lastOpened: Long,
    val cellCount: Int,
    val sizeBytes: Long,
)

class NotebookStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val notebookDir: File
        get() = File(context.filesDir, "notebooks").apply { if (!exists()) mkdirs() }

    suspend fun recents(): List<RecentNotebook> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_RECENTS, null)
        if (raw.isNullOrBlank()) return@withContext emptyList()
        runCatching {
            val array = NotebookJson.format.parseToJsonElement(raw) as JsonArray
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                val path = obj["path"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                val file = File(path)
                if (!file.isFile) return@mapNotNull null
                RecentNotebook(
                    id = id,
                    displayName = obj["name"]?.jsonPrimitive?.contentOrNullSafe() ?: file.name,
                    path = path,
                    lastOpened = obj["lastOpened"]?.jsonPrimitive?.longOrNull ?: 0L,
                    cellCount = obj["cellCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.longOrNull ?: file.length(),
                )
            }.sortedByDescending { it.lastOpened }
        }.getOrDefault(emptyList())
    }

    suspend fun importFromUri(uri: Uri): RecentNotebook = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: "notebook-${System.currentTimeMillis()}.ipynb"

        val safeName = sanitizeName(if (name.endsWith(".ipynb", true)) name else "$name.ipynb")
        val bytes = resolver.openInputStream(uri)?.use { readLimited(it, MAX_IMPORT_BYTES) }
            ?: throw IllegalStateException("Tidak bisa membuka file yang dipilih.")
        val text = bytes.toString(Charsets.UTF_8)
        // Validate before placing the file in the private library.
        val document = NotebookDocument.parse(safeName, text)

        val id = UUID.randomUUID().toString()
        val target = File(notebookDir, "$id.ipynb")
        target.writeText(text, Charsets.UTF_8)
        val recent = recent(id, safeName, target, document.cells.size)
        upsertRecent(recent)
        recent
    }

    suspend fun createNew(name: String? = null): RecentNotebook = withContext(Dispatchers.IO) {
        val display = sanitizeName(
            (name?.takeIf { it.isNotBlank() } ?: "notebook-baru-${System.currentTimeMillis()}.ipynb")
                .let { if (it.endsWith(".ipynb", true)) it else "$it.ipynb" },
        )
        val id = UUID.randomUUID().toString()
        val target = File(notebookDir, "$id.ipynb")
        val document = NotebookDocument.empty(display)
        target.writeText(document.toPrettyJson(), Charsets.UTF_8)
        val recent = recent(id, display, target, document.cells.size)
        upsertRecent(recent)
        recent
    }

    suspend fun load(recent: RecentNotebook): NotebookDocument = withContext(Dispatchers.IO) {
        val file = File(recent.path)
        if (!file.isFile) throw IllegalStateException("File notebook tidak ditemukan.")
        val text = file.readText(Charsets.UTF_8)
        val document = NotebookDocument.parse(recent.displayName, text)
        touch(recent, document.cells.size)
        document
    }

    suspend fun load(id: String): Pair<RecentNotebook, NotebookDocument>? = withContext(Dispatchers.IO) {
        val item = recents().firstOrNull { it.id == id } ?: return@withContext null
        item to load(item)
    }

    suspend fun save(document: NotebookDocument, recent: RecentNotebook): RecentNotebook =
        withContext(Dispatchers.IO) {
            val file = File(recent.path)
            file.writeText(document.toPrettyJson(), Charsets.UTF_8)
            val updated = recent.copy(
                cellCount = document.cells.size,
                sizeBytes = file.length(),
                lastOpened = System.currentTimeMillis(),
            )
            upsertRecent(updated)
            updated
        }

    suspend fun export(document: NotebookDocument, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(document.toPrettyJson().toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Tidak bisa menulis ke lokasi yang dipilih.")
    }

    suspend fun delete(recent: RecentNotebook) = withContext(Dispatchers.IO) {
        runCatching { File(recent.path).delete() }
        val remaining = recents().filterNot { it.id == recent.id }
        writeRecents(remaining)
    }

    suspend fun touch(recent: RecentNotebook, cellCount: Int) = withContext(Dispatchers.IO) {
        val file = File(recent.path)
        upsertRecent(
            recent.copy(
                lastOpened = System.currentTimeMillis(),
                cellCount = cellCount,
                sizeBytes = file.length(),
            ),
        )
    }

    private fun recent(id: String, name: String, file: File, cells: Int): RecentNotebook =
        RecentNotebook(
            id = id,
            displayName = name,
            path = file.absolutePath,
            lastOpened = System.currentTimeMillis(),
            cellCount = cells,
            sizeBytes = file.length(),
        )

    private fun upsertRecent(recent: RecentNotebook) {
        val all = recentsBlocking().filterNot { it.id == recent.id } + recent
        writeRecents(all.sortedByDescending { it.lastOpened }.take(MAX_RECENTS))
    }

    private fun recentsBlocking(): List<RecentNotebook> {
        // Used from IO-only call sites; avoids re-entering withContext.
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return runCatching {
            (NotebookJson.format.parseToJsonElement(raw) as JsonArray).mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                val path = obj["path"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                val file = File(path)
                if (!file.isFile) return@mapNotNull null
                RecentNotebook(
                    id = id,
                    displayName = obj["name"]?.jsonPrimitive?.contentOrNullSafe() ?: file.name,
                    path = path,
                    lastOpened = obj["lastOpened"]?.jsonPrimitive?.longOrNull ?: 0L,
                    cellCount = obj["cellCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.longOrNull ?: file.length(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeRecents(items: List<RecentNotebook>) {
        val array = buildJsonArray {
            items.forEach { item ->
                add(
                    buildJsonObject {
                        put("id", item.id)
                        put("name", item.displayName)
                        put("path", item.path)
                        put("lastOpened", item.lastOpened)
                        put("cellCount", item.cellCount)
                        put("sizeBytes", item.sizeBytes)
                    },
                )
            }
        }
        prefs.edit().putString(KEY_RECENTS, NotebookJson.format.encodeToString(JsonArray.serializer(), array)).apply()
    }

    private fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
        return cleaned.ifBlank { "notebook.ipynb" }.take(120)
    }

    private fun readLimited(stream: java.io.InputStream, limitBytes: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > limitBytes) {
                throw IllegalArgumentException("Notebook terlalu besar (maksimal ${limitBytes / 1024 / 1024} MB).")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    private companion object {
        const val KEY_RECENTS = "recents"
        const val MAX_RECENTS = 30
        const val MAX_IMPORT_BYTES = 25 * 1024 * 1024
    }
}
