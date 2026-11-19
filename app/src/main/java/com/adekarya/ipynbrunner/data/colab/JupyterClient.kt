package com.adekarya.ipynbrunner.data.colab

import com.adekarya.ipynbrunner.data.model.NotebookJson
import com.adekarya.ipynbrunner.data.model.string
import com.adekarya.ipynbrunner.data.network.ApiException
import com.adekarya.ipynbrunner.data.network.DefaultHttpClient
import com.adekarya.ipynbrunner.data.network.await
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

class JupyterClient(
    baseUrl: String,
    private val proxyToken: String,
    private val http: OkHttpClient = DefaultHttpClient,
) {
    private val base = baseUrl.trim().trimEnd('/')
    private val sessionId: String = UUID.randomUUID().toString()
    private var webSocket: WebSocket? = null
    private var incoming: Channel<JsonObject>? = null
    private var kernelId: String? = null

    suspend fun createKernel(name: String = "python3"): String {
        val request = restBuilder("/api/kernels")
            .post("""{"name":"$name"}""".toRequestBody(JSON))
            .build()
        val response = http.await(request)
        val body = response.use { it.body?.string().orEmpty() }
        val obj = runCatching { NotebookJson.format.parseToJsonElement(body).jsonObject }.getOrNull()
        val id = obj?.string("id")
            ?: throw ApiException(500, "Kernel Jupyter tidak mengembalikan id.", body)
        kernelId = id
        return id
    }

    suspend fun connect(kernelId: String) {
        closeSocket()
        val channel = Channel<JsonObject>(capacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        incoming = channel
        val opened = CompletableDeferred<Unit>()
        val url = webSocketUrl(kernelId)
        val request = Request.Builder()
            .url(url)
            .header("X-Colab-Runtime-Proxy-Token", proxyToken)
            .header("X-Colab-Client-Agent", "notebook-runner-android")
            .build()
        val socket = http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!opened.isCompleted) opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JupyterProtocol.parseMessage(text) ?: return
                    channel.trySend(message)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened.isCompleted) opened.completeExceptionally(t)
                    channel.close(IOException("Koneksi kernel Jupyter terputus: ${t.message}", t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!opened.isCompleted) {
                        opened.completeExceptionally(IOException("Koneksi kernel ditutup ($code): $reason"))
                    }
                    channel.close(IOException("Koneksi kernel ditutup."))
                }
            },
        )
        webSocket = socket
        try {
            withTimeout(30_000) { opened.await() }
        } catch (e: Exception) {
            socket.cancel()
            throw ApiException(0, "Gagal menyambung ke kernel Colab: ${e.message}", cause = e)
        }
    }

    suspend fun execute(
        code: String,
        onUpdate: (JupyterUpdate) -> Unit,
    ): JupyterExecutionResult {
        val socket = webSocket ?: throw IllegalStateException("Kernel belum tersambung.")
        val channel = incoming ?: throw IllegalStateException("Channel kernel tidak tersedia.")
        val msgId = UUID.randomUUID().toString()
        val message = JupyterProtocol.executeRequest(sessionId, msgId, code)
        if (!socket.send(message)) {
            throw IOException("Tidak bisa mengirim kode ke kernel (koneksi tertutup).")
        }
        val collector = JupyterOutputCollector()
        var idle = false
        var replyStatus = "ok"
        while (!idle) {
            val messageObject = withTimeout(EXECUTION_TIMEOUT_MS) { channel.receive() }
            val parent = JupyterProtocol.parentMsgId(messageObject)
            // Ignore keep-alive/background messages that don't belong to this request.
            if (parent != msgId) continue
            when (JupyterProtocol.msgType(messageObject)) {
                "status" -> {
                    val state = (messageObject["content"] as? JsonObject)?.string("execution_state")
                    if (state == "idle") idle = true
                }
                "execute_reply" -> {
                    val content = messageObject["content"] as? JsonObject
                    replyStatus = content?.string("status") ?: "ok"
                    collector.onMessage(messageObject)
                }
                else -> collector.onMessage(messageObject)
            }
            onUpdate(JupyterUpdate(collector.snapshot(), collector.executionCount))
        }
        return JupyterExecutionResult(
            outputs = collector.snapshot(),
            executionCount = collector.executionCount,
            status = replyStatus,
        )
    }

    /**
     * Sends a kernel_info_request and waits briefly for its reply. This is a
     * harmless activity signal used while a Colab runtime is kept alive.
     */
    suspend fun heartbeat() {
        val socket = webSocket ?: throw IOException("WebSocket kernel sudah ditutup.")
        val channel = incoming ?: throw IOException("Channel kernel sudah ditutup.")
        val msgId = UUID.randomUUID().toString()
        if (!socket.send(JupyterProtocol.kernelInfoRequest(sessionId, msgId))) {
            throw IOException("Tidak bisa mengirim heartbeat ke kernel.")
        }
        withTimeout(30_000) {
            while (true) {
                val message = channel.receive()
                if (JupyterProtocol.parentMsgId(message) != msgId) continue
                val type = JupyterProtocol.msgType(message)
                if (type == "kernel_info_reply") return@withTimeout
            }
        }
    }

    suspend fun interruptKernel() {
        val id = kernelId ?: return
        runCatching {
            val request = restBuilder("/api/kernels/$id/interrupt")
                .post("".toRequestBody(JSON))
                .build()
            http.await(request).close()
        }
    }

    suspend fun deleteKernel() {
        val id = kernelId ?: return
        kernelId = null
        runCatching {
            val request = restBuilder("/api/kernels/$id").delete().build()
            http.await(request).close()
        }
    }

    fun close() {
        closeSocket()
        kernelId = null
    }

    private fun closeSocket() {
        runCatching { webSocket?.cancel() }
        webSocket = null
        incoming?.close()
        incoming = null
    }

    private fun webSocketUrl(kernelId: String): String {
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            base.startsWith("wss://") || base.startsWith("ws://") -> base
            else -> "wss://$base"
        }
        val token = URLEncoder.encode(proxyToken, "UTF-8")
        return "$wsBase/api/kernels/$kernelId/channels" +
            "?session_id=${URLEncoder.encode(sessionId, "UTF-8")}" +
            "&colab-runtime-proxy-token=$token"
    }

    private fun restBuilder(path: String): Request.Builder = Request.Builder()
        .url("$base$path")
        .header("X-Colab-Runtime-Proxy-Token", proxyToken)
        .header("X-Colab-Client-Agent", "notebook-runner-android")
        .header("Accept", "application/json")

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val EXECUTION_TIMEOUT_MS = 24L * 60L * 60L * 1000L
    }
}
