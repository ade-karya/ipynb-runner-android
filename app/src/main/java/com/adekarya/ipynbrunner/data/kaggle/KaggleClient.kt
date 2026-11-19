package com.adekarya.ipynbrunner.data.kaggle

import com.adekarya.ipynbrunner.data.model.CellType
import com.adekarya.ipynbrunner.data.model.KaggleRunOptions
import com.adekarya.ipynbrunner.data.model.NotebookCell
import com.adekarya.ipynbrunner.data.model.NotebookDocument
import com.adekarya.ipynbrunner.data.model.int
import com.adekarya.ipynbrunner.data.model.nestedArray
import com.adekarya.ipynbrunner.data.model.nestedObject
import com.adekarya.ipynbrunner.data.model.string
import com.adekarya.ipynbrunner.data.network.ApiException
import com.adekarya.ipynbrunner.data.network.DefaultHttpClient
import com.adekarya.ipynbrunner.data.network.await
import com.adekarya.ipynbrunner.data.network.jsonBody
import com.adekarya.ipynbrunner.data.network.jsonRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class KagglePushResult(
    val ref: String,
    val url: String,
    val versionNumber: Int?,
)

data class KaggleStatus(
    val status: String,
    val failureMessage: String?,
) {
    val normalized: String get() = status.lowercase()
    val isFinished: Boolean
        get() = normalized == "complete" || normalized == "error" ||
            normalized == "cancel_acknowledged"
}

data class KaggleOutputFile(
    val fileName: String,
    val url: String,
)

data class KaggleOutputListing(
    val files: List<KaggleOutputFile>,
    val log: String?,
)

class KaggleClient(
    private val username: String,
    private val apiKey: String,
    private val http: OkHttpClient = DefaultHttpClient,
) {
    private val ownerDefault = username.trim().removePrefix("@")
    private val normalizedKey = normalizeApiKey(apiKey)
    private val usesBearer = normalizedKey.startsWith(TOKEN_PREFIX, ignoreCase = true)

    /**
     * Kaggle now issues personal API tokens starting with "KGAT_" that must be
     * sent as a Bearer token. Legacy kaggle.json credentials (username + key)
     * still use HTTP Basic auth, so both formats are supported here.
     */
    suspend fun checkCredentials(): String {
        if (ownerDefault.isBlank()) {
            throw IllegalArgumentException("Username Kaggle belum diisi.")
        }
        val url = "https://www.kaggle.com/api/v1/kernels/list".toHttpUrl().newBuilder()
            .addQueryParameter("group", "profile")
            .addQueryParameter("pageSize", "1")
            .build()
        val response = execute(baseRequest(url.toString()).get())
        val count = response.nestedArray("kernels")?.size ?: 0
        val mode = if (usesBearer) "token KGAT_" else "username + key legacy"
        return "Kredensial valid ($mode). $count kernel pribadi terbaca."
    }

    suspend fun push(
        notebook: NotebookDocument,
        options: KaggleRunOptions,
    ): KagglePushResult {
        val (owner, slug) = parseSlug(options.slug, ownerDefault)
        val title = options.title.trim()
        if (title.length < 5) {
            throw IllegalArgumentException("Judul kernel Kaggle minimal 5 karakter.")
        }
        if (slug.isBlank()) {
            throw IllegalArgumentException("Slug kernel Kaggle tidak boleh kosong.")
        }
        val source = notebook.copy(
            cells = notebook.cells.map { cell ->
                if (cell.cellType == CellType.CODE) {
                    cell.copy(outputs = emptyList(), executionCount = null)
                } else {
                    cell
                }
            },
        ).toJson()

        val body = buildJsonObject {
            put("slug", "$owner/$slug")
            put("newTitle", title)
            put("text", source.toString())
            put("language", "python")
            put("kernelType", "notebook")
            put("isPrivate", options.isPrivate)
            put("enableGpu", options.useGpu)
            put("enableInternet", options.enableInternet)
            put("datasetDataSources", buildJsonArray {})
            put("kernelDataSources", buildJsonArray {})
            put("competitionDataSources", buildJsonArray {})
            put("modelDataSources", buildJsonArray {})
            put("categoryIds", buildJsonArray {})
            put("sessionTimeoutSeconds", (options.timeoutMinutes.coerceIn(1, 720)) * 60)
        }

        val request = baseRequest("https://www.kaggle.com/api/v1/kernels/push")
            .post(jsonBody(body))
        val response = execute(request)
        val error = response.string("error")
        if (!error.isNullOrBlank()) {
            throw ApiException(400, "Kaggle menolak notebook: $error", response.toString())
        }
        return KagglePushResult(
            ref = response.string("ref").orEmpty(),
            url = response.string("url").orEmpty(),
            versionNumber = response.int("versionNumber")?.takeIf { it > 0 },
        )
    }

    suspend fun status(
        slugInput: String,
        versionNumber: Int? = null,
    ): KaggleStatus {
        val (owner, slug) = parseSlug(slugInput, ownerDefault)
        val url = "https://www.kaggle.com/api/v1/kernels/status".toHttpUrl().newBuilder()
            .addQueryParameter("userName", owner)
            .addQueryParameter("kernelSlug", slug)
            .apply { if (versionNumber != null) addQueryParameter("versionLabel", versionNumber.toString()) }
            .build()
        val response = execute(baseRequest(url.toString()).get())
        return KaggleStatus(
            status = response.string("status").orEmpty().ifBlank { "queued" },
            failureMessage = response.string("failureMessage")?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun output(
        slugInput: String,
        versionNumber: Int? = null,
    ): KaggleOutputListing {
        val (owner, slug) = parseSlug(slugInput, ownerDefault)
        val files = mutableListOf<KaggleOutputFile>()
        var log: String? = null
        var pageToken: String? = null
        var guard = 0
        do {
            val builder = "https://www.kaggle.com/api/v1/kernels/output".toHttpUrl().newBuilder()
                .addQueryParameter("userName", owner)
                .addQueryParameter("kernelSlug", slug)
                .addQueryParameter("pageSize", "100")
            if (versionNumber != null) builder.addQueryParameter("versionLabel", versionNumber.toString())
            if (pageToken != null) builder.addQueryParameter("pageToken", pageToken)
            val response = execute(baseRequest(builder.build().toString()).get())
            if (log.isNullOrBlank()) log = response.string("log")
            response.nestedArray("files")?.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val name = obj.string("fileName") ?: return@forEach
                val url = obj.string("url") ?: return@forEach
                if (url.isNotBlank()) files += KaggleOutputFile(name, url)
            }
            pageToken = response.string("nextPageToken")?.takeIf { it.isNotBlank() }
            guard++
        } while (!pageToken.isNullOrBlank() && guard < 20)
        return KaggleOutputListing(files = files, log = log)
    }

    suspend fun pullNotebook(
        slugInput: String,
        versionNumber: Int? = null,
    ): String? {
        val (owner, slug) = parseSlug(slugInput, ownerDefault)
        val builder = "https://www.kaggle.com/api/v1/kernels/pull".toHttpUrl().newBuilder()
            .addQueryParameter("userName", owner)
            .addQueryParameter("kernelSlug", slug)
        if (versionNumber != null) builder.addQueryParameter("versionLabel", versionNumber.toString())
        val response = execute(baseRequest(builder.build().toString()).get())
        return response.nestedObject("blob")?.string("source")
    }

    suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        val withoutAuth = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val response = runCatching { http.newCall(withoutAuth).execute() }.getOrElse { error ->
            throw ApiException(0, "Gagal mengunduh output Kaggle: ${error.message}", cause = error)
        }
        response.use {
            if (it.isSuccessful) {
                it.body?.string() ?: ""
            } else if (it.code == 401 || it.code == 403) {
                val withAuth = withoutAuth.newBuilder().addHeader("Authorization", authHeader()).build()
                http.newCall(withAuth).execute().use { authed ->
                    if (!authed.isSuccessful) {
                        throw ApiException(authed.code, "Gagal mengunduh output Kaggle (${authed.code}).")
                    }
                    authed.body?.string() ?: ""
                }
            } else {
                throw ApiException(it.code, "Gagal mengunduh output Kaggle (${it.code}).")
            }
        }
    }

    private suspend fun execute(builder: Request.Builder): JsonObject = try {
        http.jsonRequest(builder.build())
    } catch (e: ApiException) {
        if (e.statusCode == 401) {
            val hint = if (usesBearer) {
                "Token KGAT_ dikirim sebagai Bearer. Pastikan token masih aktif dan dibuat ulang dari " +
                    "kaggle.com → Settings → API jika perlu."
            } else {
                "Kredensial legacy dikirim sebagai Basic. Jika Anda memakai token baru yang diawali KGAT_, " +
                    "tempel token itu di kolom API key (username tetap diisi)."
            }
            throw ApiException(
                e.statusCode,
                "Kaggle menolak autentikasi (401). $hint\n${e.message}",
                e.body,
                e,
            )
        }
        throw e
    }

    private fun baseRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", authHeader())
        .header("Accept", "application/json")
        .header("User-Agent", USER_AGENT)

    private fun authHeader(): String = buildAuthorizationHeader(ownerDefault, normalizedKey)

    private fun parseSlug(input: String, defaultOwner: String): Pair<String, String> {
        var cleaned = input.trim()
        cleaned = cleaned.substringBefore("?").trimEnd('/')
        if (cleaned.contains("kaggle.com/code/")) {
            cleaned = cleaned.substringAfter("kaggle.com/code/")
        }
        val parts = cleaned.split("/").filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> parts[0] to parts[1]
            parts.size == 1 -> defaultOwner to parts[0]
            else -> defaultOwner to ""
        }
    }

    companion object {
        private const val USER_AGENT = "NotebookRunnerAndroid/1.0.1 (Kaggle API)"
        private const val TOKEN_PREFIX = "KGAT_"

        /**
         * Accepts a legacy API key, a new KGAT_ token (optionally pasted with a
         * "Bearer " prefix), or the full kaggle.json contents. This makes
         * copy-pasting from Kaggle's settings page forgiving.
         */
        fun normalizeApiKey(raw: String): String {
            var value = raw.trim()
            if (value.startsWith("{") && value.contains("\"key\"")) {
                runCatching {
                    Json.parseToJsonElement(value).jsonObject["key"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.let { extracted -> value = extracted.trim() }
            }
            value = value.removeSurrounding("\"").trim()
            if (value.startsWith("Bearer ", ignoreCase = true)) {
                value = value.substring(7).trim()
            }
            return value
        }

        fun buildAuthorizationHeader(username: String, apiKey: String): String {
            val key = normalizeApiKey(apiKey)
            return if (key.startsWith(TOKEN_PREFIX, ignoreCase = true)) {
                "Bearer $key"
            } else {
                Credentials.basic(username.trim().removePrefix("@"), key)
            }
        }

        fun slugify(value: String): String {
            return value.lowercase()
                .replace(Regex("[^a-z0-9\\s-]"), "")
                .replace(Regex("\\s+"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(50)
        }

        suspend fun awaitCompletion(
            client: KaggleClient,
            slug: String,
            versionNumber: Int?,
            timeoutMinutes: Int,
            onStatus: (KaggleStatus, Int) -> Unit,
        ): KaggleStatus {
            val deadline = System.currentTimeMillis() + timeoutMinutes.coerceAtLeast(1) * 60_000L
            var tick = 0
            while (System.currentTimeMillis() < deadline) {
                val status = client.status(slug, versionNumber)
                onStatus(status, tick)
                if (status.isFinished) return status
                tick++
                delay(5_000)
            }
            throw ApiException(0, "Batas waktu menunggu Kaggle (${timeoutMinutes} menit) habis.")
        }
    }
}
