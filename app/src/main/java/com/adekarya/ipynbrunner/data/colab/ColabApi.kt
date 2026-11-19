package com.adekarya.ipynbrunner.data.colab

import com.adekarya.ipynbrunner.data.SettingsStore
import com.adekarya.ipynbrunner.data.model.ColabRuntimeInfo
import com.adekarya.ipynbrunner.data.model.ColabRuntimeSpec
import com.adekarya.ipynbrunner.data.model.ColabSpecKey
import com.adekarya.ipynbrunner.data.model.int
import com.adekarya.ipynbrunner.data.model.nestedArray
import com.adekarya.ipynbrunner.data.model.nestedObject
import com.adekarya.ipynbrunner.data.model.string
import com.adekarya.ipynbrunner.data.model.stringOrNull
import com.adekarya.ipynbrunner.data.network.ApiException
import com.adekarya.ipynbrunner.data.network.DefaultHttpClient
import com.adekarya.ipynbrunner.data.network.jsonBody
import com.adekarya.ipynbrunner.data.network.jsonRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

/**
 * Client for the official Google Colab API (colaboratory.googleapis.com).
 * All calls require an OAuth access token carrying the
 * https://www.googleapis.com/auth/colaboratory scope, plus a Google Cloud
 * quota project header.
 */
class ColabApi(
    private val settings: SettingsStore,
    private val oauth: ColabOAuth,
    private val http: OkHttpClient = DefaultHttpClient,
) {
    suspend fun listRuntimeSpecs(): List<ColabRuntimeSpec> = withContext(Dispatchers.IO) {
        val response = call("GET", "$BASE/v1beta/runtimespecs")
        response.nestedArray("runtimeSpecs").orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val key = obj.nestedObject("key") ?: return@mapNotNull null
            ColabRuntimeSpec(
                key = parseSpec(key) ?: return@mapNotNull null,
                eligible = obj["eligible"]?.stringOrNull()?.toBoolean() == true,
            )
        }
    }

    suspend fun listRuntimes(): List<ColabRuntimeInfo> = withContext(Dispatchers.IO) {
        val response = call("GET", "$BASE/v1beta/runtimes")
        response.nestedArray("runtimes").orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.let(::parseRuntime)
        }
    }

    suspend fun getRuntime(name: String): ColabRuntimeInfo = withContext(Dispatchers.IO) {
        val response = call("GET", "$BASE/v1beta/${name.trim('/')}")
        parseRuntime(response) ?: throw ApiException(500, "Respons runtime Colab tidak lengkap.")
    }

    suspend fun ensureRuntime(
        requestedSpec: ColabSpecKey,
        onProgress: (String) -> Unit = {},
    ): ColabRuntimeInfo = withContext(Dispatchers.IO) {
        val saved = settings.load().colabRuntimeName
        if (!saved.isNullOrBlank()) {
            try {
                val existing = getRuntime(saved)
                if (existing.connectionUrl.isNotBlank() && existing.proxyToken.isNotBlank()) {
                    onProgress("Menggunakan runtime tersimpan: ${existing.name.removePrefix("runtimes/")}")
                    return@withContext existing
                }
            } catch (e: ApiException) {
                if (e.statusCode == 404) {
                    settings.setColabRuntime(null, null)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                throw e
            }
        }

        val runtimeId = "nr-" + UUID.randomUUID().toString().replace("-", "").take(24)
        onProgress("Membuat runtime Colab baru...")
        val body = buildJsonObject {
            put(
                "runtimeSpec",
                buildJsonObject {
                    put("variant", requestedSpec.variant)
                    put("accelerator", requestedSpec.accelerator)
                    put("shape", requestedSpec.shape)
                },
            )
        }
        val url = "$BASE/v1beta/runtimes".toHttpUrl().newBuilder()
            .addQueryParameter("runtimeId", runtimeId)
            .addQueryParameter("requestId", UUID.randomUUID().toString())
            .build()
        val operation = call("POST", url.toString(), body)
        val runtime = waitForOperation(operation, runtimeId, requestedSpec, onProgress)
        settings.setColabRuntime(runtime.name, runtime.spec?.label)
        runtime
    }

    suspend fun deleteRuntime(name: String) = withContext(Dispatchers.IO) {
        val safeName = name.trim('/')
        call(
            "DELETE",
            "$BASE/v1beta/$safeName?allowMissing=true",
            body = null,
            expectJson = false,
        )
        if (settings.load().colabRuntimeName == safeName) {
            settings.setColabRuntime(null, null)
        }
    }

    private suspend fun waitForOperation(
        first: JsonObject,
        runtimeId: String,
        requestedSpec: ColabSpecKey,
        onProgress: (String) -> Unit,
    ): ColabRuntimeInfo {
        var operation = first
        var attempts = 0
        while (true) {
            val done = operation["done"]?.stringOrNull()?.toBoolean() == true
            if (done) {
                val error = operation.nestedObject("error")
                if (error != null) {
                    val code = error.int("code") ?: 0
                    val message = error.string("message").orEmpty()
                    if (code == 6) { // ALREADY_EXISTS
                        return getRuntime("runtimes/$runtimeId")
                    }
                    val reason = operationErrorDetails(error)
                    throw ApiException(
                        if (code in 3..9) 400 else 500,
                        "Gagal membuat runtime Colab: ${message.ifBlank { reason.ifBlank { "kode $code" } }}",
                    )
                }
                val response = operation.nestedObject("response")
                if (response != null) {
                    parseRuntime(response)?.let { return it }
                    val name = response.string("name")
                    if (!name.isNullOrBlank()) return getRuntime(name)
                }
                return getRuntime("runtimes/$runtimeId")
            }

            attempts++
            if (attempts > MAX_OPERATION_POLLS) {
                throw ApiException(0, "Runtime Colab belum siap setelah beberapa menit. Coba lagi.")
            }
            onProgress("Menunggu runtime Colab siap... (${attempts * 5} dtk)")
            delay(5_000)
            val name = operation.string("name")
                ?: throw ApiException(500, "Operation Colab tidak memiliki nama.")
            operation = runCatching {
                // :wait blocks up to 30 seconds server-side and returns the latest state.
                call("GET", "$BASE/v1/$name:wait?timeout=30s", expectJson = true)
            }.getOrElse { e ->
                if (e is ApiException && (e.statusCode == 400 || e.statusCode == 404)) {
                    call("GET", "$BASE/v1/$name")
                } else {
                    throw e
                }
            }
        }
    }

    private fun operationErrorDetails(error: JsonObject): String {
        val details = error.nestedArray("details").orEmpty()
        for (element in details) {
            val obj = element as? JsonObject ?: continue
            val reason = obj.string("reason")
            val domain = obj.string("domain")
            if (!reason.isNullOrBlank()) return "$reason${if (domain.isNullOrBlank()) "" else " ($domain)"}"
        }
        return ""
    }

    private fun parseRuntime(obj: JsonObject): ColabRuntimeInfo? {
        val name = obj.string("name") ?: return null
        val connection = obj.nestedObject("connectionInfo")
        val spec = obj.nestedObject("runtimeSpec")?.let(::parseSpec)
        return ColabRuntimeInfo(
            name = name,
            spec = spec,
            connectionUrl = connection?.string("url").orEmpty(),
            proxyToken = connection?.string("token").orEmpty(),
            tokenExpireTime = connection?.string("expireTime"),
            endpoint = connection?.string("endpoint"),
        )
    }

    private fun parseSpec(obj: JsonObject): ColabSpecKey? {
        val variant = obj.string("variant") ?: return null
        val accelerator = obj.string("accelerator") ?: "NONE"
        val shape = obj.string("shape") ?: "SHAPE_STANDARD"
        return ColabSpecKey(variant, accelerator, shape)
    }

    private suspend fun call(
        method: String,
        url: String,
        body: JsonObject? = null,
        expectJson: Boolean = true,
    ): JsonObject {
        val access = oauth.accessToken()
        val quotaProject = oauth.quotaProject()
        if (quotaProject.isBlank()) {
            throw IllegalStateException(
                "Quota project Google Cloud belum diisi. Isi di Pengaturan → Colab (project ID yang mengaktifkan Colab API).",
            )
        }
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $access")
            .header("x-goog-user-project", quotaProject)
            .header("X-Colab-Client-Agent", "notebook-runner-android")
            .header("Accept", "application/json")
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post(jsonBody(body ?: buildJsonObject {}))
            else -> throw IllegalArgumentException("Metode HTTP tidak didukung: $method")
        }
        return if (expectJson) {
            try {
                http.jsonRequest(builder.build())
            } catch (e: ApiException) {
                if (e.statusCode == 403) {
                    throw ApiException(
                        e.statusCode,
                        "Colab API menolak akses (403). Pastikan Colab API sudah diaktifkan di project " +
                            "\"$quotaProject\", OAuth consent memuat scope colaboratory, dan akun Anda menjadi test user.\n${e.message}",
                        e.body,
                        e,
                    )
                }
                throw e
            }
        } else {
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(response.code, "Permintaan Colab gagal (${response.code}).")
                }
                JsonObject(emptyMap())
            }
        }
    }

    private fun String?.toBoolean(): Boolean =
        this?.equals("true", ignoreCase = true) == true

    private companion object {
        const val BASE = "https://colaboratory.googleapis.com"
        const val MAX_OPERATION_POLLS = 120
    }
}
