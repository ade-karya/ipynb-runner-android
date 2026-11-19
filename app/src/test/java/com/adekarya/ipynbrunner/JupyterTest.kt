package com.adekarya.ipynbrunner

import com.adekarya.ipynbrunner.data.colab.JupyterOutputCollector
import com.adekarya.ipynbrunner.data.colab.JupyterProtocol
import com.adekarya.ipynbrunner.data.model.errorText
import com.adekarya.ipynbrunner.data.model.outputText
import com.adekarya.ipynbrunner.data.model.outputType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JupyterTest {
    private fun message(type: String, content: JsonObject): JsonObject = buildJsonObject {
        put(
            "header",
            buildJsonObject {
                put("msg_type", type)
            },
        )
        put(
            "parent_header",
            buildJsonObject {
                put("msg_id", "parent-1")
            },
        )
        put("content", content)
    }

    @Test
    fun `collector merges stdout chunks and captures result`() {
        val collector = JupyterOutputCollector()
        collector.onMessage(
            message(
                "execute_input",
                buildJsonObject { put("execution_count", 3) },
            ),
        )
        collector.onMessage(
            message(
                "stream",
                buildJsonObject {
                    put("name", "stdout")
                    put("text", "Halo ")
                },
            ),
        )
        collector.onMessage(
            message(
                "stream",
                buildJsonObject {
                    put("name", "stdout")
                    put("text", "dunia\n")
                },
            ),
        )
        collector.onMessage(
            message(
                "execute_result",
                buildJsonObject {
                    put("execution_count", 3)
                    put("data", buildJsonObject { put("text/plain", "42") })
                    put("metadata", buildJsonObject {})
                },
            ),
        )

        assertEquals(3, collector.executionCount)
        val outputs = collector.snapshot()
        assertEquals(2, outputs.size)
        assertEquals("stream", outputs[0].outputType())
        assertEquals("Halo dunia\n", outputs[0].outputText())
        assertEquals("execute_result", outputs[1].outputType())
        assertEquals("42", outputs[1].outputText())
    }

    @Test
    fun `clear_output wait clears previous stream before next output`() {
        val collector = JupyterOutputCollector()
        collector.onMessage(
            message(
                "stream",
                buildJsonObject {
                    put("name", "stdout")
                    put("text", "lama")
                },
            ),
        )
        collector.onMessage(
            message(
                "clear_output",
                buildJsonObject { put("wait", true) },
            ),
        )
        collector.onMessage(
            message(
                "stream",
                buildJsonObject {
                    put("name", "stdout")
                    put("text", "baru")
                },
            ),
        )

        val outputs = collector.snapshot()
        assertEquals(1, outputs.size)
        assertEquals("baru", outputs[0].outputText())
    }

    @Test
    fun `error output keeps traceback`() {
        val collector = JupyterOutputCollector()
        collector.onMessage(
            message(
                "error",
                buildJsonObject {
                    put("ename", "ValueError")
                    put("evalue", "nilai salah")
                    put("traceback", kotlinx.serialization.json.JsonArray(listOf(
                        kotlinx.serialization.json.JsonPrimitive("Traceback line 1"),
                        kotlinx.serialization.json.JsonPrimitive("ValueError: nilai salah"),
                    )))
                },
            ),
        )

        val output = collector.snapshot().single()
        assertEquals("error", output.outputType())
        assertTrue(output.errorText().orEmpty().contains("ValueError"))
    }

    @Test
    fun `execute request contains jupyter envelope`() {
        val raw = JupyterProtocol.executeRequest("session-1", "msg-1", "print(1)")
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        val header = parsed["header"]!!.jsonObject
        assertEquals("execute_request", header["msg_type"]!!.jsonPrimitive.content)
        assertEquals("msg-1", header["msg_id"]!!.jsonPrimitive.content)
        assertEquals("session-1", header["session"]!!.jsonPrimitive.content)
        assertEquals("print(1)", parsed["content"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(false, parsed["content"]!!.jsonObject["allow_stdin"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `kernel info request is parsed as heartbeat`() {
        val raw = JupyterProtocol.kernelInfoRequest("s", "m")
        val parsed = JupyterProtocol.parseMessage(raw)!!
        assertEquals("kernel_info_request", JupyterProtocol.msgType(parsed))
        assertEquals(null, JupyterProtocol.parentMsgId(parsed))
    }
}
