package com.adekarya.ipynbrunner.data.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

val DefaultHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

class ApiException(
    val statusCode: Int,
    override val message: String,
    val body: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        },
    )
}

suspend fun OkHttpClient.await(request: Request): Response {
    val response = newCall(request).awaitResponse()
    if (!response.isSuccessful) {
        val body = runCatching { response.body?.string() }.getOrNull()
        val code = response.code
        response.close()
        throw ApiException(code, friendlyHttpMessage(code, body), body)
    }
    return response
}

suspend fun OkHttpClient.jsonRequest(request: Request): JsonObject {
    val response = await(request)
    val body = response.use { it.body?.string().orEmpty() }
    return try {
        Json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw ApiException(response.code, "Respons server bukan JSON yang valid.", body, e)
    }
}

fun jsonBody(json: JsonObject)= json.toString().toRequestBody(JSON_MEDIA_TYPE)

fun textBody(text: String) = text.toRequestBody(JSON_MEDIA_TYPE)

fun friendlyHttpMessage(code: Int, body: String?): String {
    val serverMessage = body?.let { raw ->
        runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val error = obj["error"]
            when (error) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                else -> error?.jsonPrimitive?.contentOrNull
            } ?: obj["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }
    val base = when (code) {
        400 -> "Permintaan ditolak (400)."
        401 -> "Autentikasi gagal (401). Kaggle: token KGAT_ (Bearer) atau username + key legacy. Colab: login ulang."
        403 -> "Akses ditolak (403). Periksa izin, scope OAuth, dan quota project."
        404 -> "Resource tidak ditemukan (404). Periksa username, slug, atau nama runtime."
        409 -> "Konflik data (409)."
        429 -> "Terlalu banyak permintaan / kuota habis (429). Coba lagi nanti."
        in 500..599 -> "Server sedang bermasalah ($code). Coba lagi nanti."
        else -> "Permintaan gagal ($code)."
    }
    return if (serverMessage.isNullOrBlank()) base else "$base\n$serverMessage"
}
