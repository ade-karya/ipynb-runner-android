package com.adekarya.ipynbrunner.data.colab

import com.adekarya.ipynbrunner.data.model.NotebookJson
import com.adekarya.ipynbrunner.data.model.int
import com.adekarya.ipynbrunner.data.model.outputType
import com.adekarya.ipynbrunner.data.model.sourceText
import com.adekarya.ipynbrunner.data.model.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/**
 * The small subset of the Jupyter messaging protocol needed to execute a
 * notebook cell over a kernel WebSocket.
 */
object JupyterProtocol {
    const val PROTOCOL_VERSION = "5.3"

    fun executeRequest(sessionId: String, msgId: String, code: String): String =
        envelope(
            sessionId = sessionId,
            msgId = msgId,
            msgType = "execute_request",
            content = buildJsonObject {
                put("code", code)
                put("silent", false)
                put("store_history", true)
                put("user_expressions", buildJsonObject {})
                put("allow_stdin", false)
                put("stop_on_error", true)
            },
        )

    fun kernelInfoRequest(sessionId: String, msgId: String): String =
        envelope(
            sessionId = sessionId,
            msgId = msgId,
            msgType = "kernel_info_request",
            content = buildJsonObject {},
        )

    private fun envelope(
        sessionId: String,
        msgId: String,
        msgType: String,
        content: JsonObject,
    ): String = buildJsonObject {
        put(
            "header",
            buildJsonObject {
                put("msg_id", msgId)
                put("username", "notebook-runner")
                put("session", sessionId)
                put("msg_type", msgType)
                put("version", PROTOCOL_VERSION)
                put("date", Instant.now().toString())
            },
        )
        put("parent_header", buildJsonObject {})
        put("metadata", buildJsonObject {})
        put("content", content)
        put("buffers", JsonArray(emptyList()))
        put("channel", "shell")
    }.toString()

    fun parseMessage(text: String): JsonObject? =
        runCatching { NotebookJson.format.parseToJsonElement(text) as? JsonObject }.getOrNull()

    fun msgType(message: JsonObject): String =
        message["header"]?.let { header ->
            (header as? JsonObject)?.string("msg_type")
        }.orEmpty()

    fun parentMsgId(message: JsonObject): String? =
        message["parent_header"]?.let { parent ->
            (parent as? JsonObject)?.string("msg_id")
        }
}

data class JupyterUpdate(
    val outputs: List<JsonObject>,
    val executionCount: Int?,
)

data class JupyterExecutionResult(
    val outputs: List<JsonObject>,
    val executionCount: Int?,
    val status: String,
)

/**
 * Converts iopub messages into nbformat-compatible output objects and merges
 * consecutive stdout/stderr chunks the way Jupyter's own notebook frontend does.
 */
class JupyterOutputCollector {
    private val outputs = mutableListOf<JsonObject>()
    private var pendingClear = false
    var executionCount: Int? = null
        private set

    fun onMessage(message: JsonObject) {
        val type = JupyterProtocol.msgType(message)
        val content = message["content"] as? JsonObject ?: return
        when (type) {
            "execute_input" -> {
                executionCount = content.int("execution_count")
            }

            "stream" -> {
                clearIfPending()
                val name = content.string("name") ?: "stdout"
                val text = content["text"]?.sourceText() ?: return
                val last = outputs.lastOrNull()
                if (last?.outputType() == "stream" && last.string("name") == name) {
                    val oldText = last["text"]?.sourceText().orEmpty()
                    val map = LinkedHashMap(last)
                    map["text"] = JsonPrimitive(oldText + text)
                    outputs[outputs.lastIndex] = JsonObject(map)
                } else {
                    outputs += buildJsonObject {
                        put("output_type", "stream")
                        put("name", name)
                        put("text", text)
                    }
                }
            }

            "execute_result" -> {
                clearIfPending()
                executionCount = content.int("execution_count") ?: executionCount
                val data = content["data"] as? JsonObject ?: return
                outputs += buildJsonObject {
                    put("output_type", "execute_result")
                    put("execution_count", content.int("execution_count") ?: executionCount ?: 0)
                    put("data", data)
                    put("metadata", content["metadata"] as? JsonObject ?: buildJsonObject {})
                }
            }

            "display_data" -> {
                clearIfPending()
                val data = content["data"] as? JsonObject ?: return
                outputs += buildJsonObject {
                    put("output_type", "display_data")
                    put("data", data)
                    put("metadata", content["metadata"] as? JsonObject ?: buildJsonObject {})
                    (content["transient"] as? JsonObject)?.let { put("transient", it) }
                }
            }

            "update_display_data" -> {
                clearIfPending()
                val data = content["data"] as? JsonObject ?: return
                val displayId = (content["transient"] as? JsonObject)?.string("display_id")
                val index = if (displayId == null) -1 else outputs.indexOfLast { output ->
                    ((output["transient"] as? JsonObject)?.string("display_id")) == displayId
                }
                if (index >= 0) {
                    val map = LinkedHashMap(outputs[index])
                    map["data"] = data
                    (content["metadata"] as? JsonObject)?.let { map["metadata"] = it }
                    outputs[index] = JsonObject(map)
                } else {
                    outputs += buildJsonObject {
                        put("output_type", "display_data")
                        put("data", data)
                        put("metadata", content["metadata"] as? JsonObject ?: buildJsonObject {})
                        (content["transient"] as? JsonObject)?.let { put("transient", it) }
                    }
                }
            }

            "error" -> {
                clearIfPending()
                outputs += buildJsonObject {
                    put("output_type", "error")
                    put("ename", content.string("ename").orEmpty())
                    put("evalue", content.string("evalue").orEmpty())
                    put(
                        "traceback",
                        content["traceback"] as? JsonArray
                            ?: JsonArray(content.string("traceback").orEmpty().lines().map(::JsonPrimitive)),
                    )
                }
            }

            "clear_output" -> {
                val wait = content["wait"]?.toString() == "true"
                if (wait) pendingClear = true else {
                    outputs.clear()
                    pendingClear = false
                }
            }

            "execute_reply" -> {
                val status = content.string("status")
                if (status == "error" && outputs.none { it.outputType() == "error" }) {
                    outputs += buildJsonObject {
                        put("output_type", "error")
                        put("ename", content.string("ename").orEmpty())
                        put("evalue", content.string("evalue").orEmpty())
                        put(
                            "traceback",
                            content["traceback"] as? JsonArray
                                ?: JsonArray(content.string("traceback").orEmpty().lines().map(::JsonPrimitive)),
                        )
                    }
                }
            }
        }
    }

    fun snapshot(): List<JsonObject> = outputs.toList()

    private fun clearIfPending() {
        if (pendingClear) {
            outputs.clear()
            pendingClear = false
        }
    }
}
