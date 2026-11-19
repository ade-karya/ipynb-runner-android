package com.adekarya.ipynbrunner

import com.adekarya.ipynbrunner.data.model.CellType
import com.adekarya.ipynbrunner.data.model.KeepAliveCell
import com.adekarya.ipynbrunner.data.model.NotebookCell
import com.adekarya.ipynbrunner.data.model.NotebookDocument
import com.adekarya.ipynbrunner.data.model.outputText
import com.adekarya.ipynbrunner.data.model.outputType
import com.adekarya.ipynbrunner.data.kaggle.KaggleClient
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookTest {
    private val sample = """
        {
          "nbformat": 4,
          "nbformat_minor": 5,
          "metadata": {"kernelspec": {"name": "python3", "display_name": "Python 3", "language": "python"}},
          "cells": [
            {
              "cell_type": "markdown",
              "metadata": {},
              "source": ["# Halo\n", "dunia"]
            },
            {
              "cell_type": "code",
              "metadata": {},
              "execution_count": 2,
              "source": "print('x')",
              "outputs": [
                {"output_type": "stream", "name": "stdout", "text": ["x\n"]}
              ],
              "custom_field": {"keep": true}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parse preserves cells, outputs and unknown fields`() {
        val document = NotebookDocument.parse("uji.ipynb", sample)

        assertEquals(2, document.cells.size)
        assertEquals(CellType.MARKDOWN, document.cells[0].cellType)
        assertEquals("# Halo\ndunia", document.cells[0].source)
        assertEquals(CellType.CODE, document.cells[1].cellType)
        assertEquals(2, document.cells[1].executionCount)
        assertEquals(1, document.cells[1].outputs.size)
        assertEquals("x\n", document.cells[1].outputs[0].outputText())

        val roundTrip = document.toPrettyJson()
        assertTrue(roundTrip.contains("custom_field"))
        val reparsed = NotebookDocument.parse("uji.ipynb", roundTrip)
        assertEquals(document.cells.map { it.source }, reparsed.cells.map { it.source })
        assertEquals(1, reparsed.cells[1].outputs.size)
    }

    @Test
    fun `withRemoteOutputs matches cells by source`() {
        val local = NotebookDocument.parse("uji.ipynb", sample)
        val remote = listOf(
            NotebookCell(
                cellType = CellType.CODE,
                source = "print('x')",
                outputs = listOf(
                    kotlinx.serialization.json.buildJsonObject {
                        put("output_type", "stream")
                        put("name", "stdout")
                        put("text", "x\n")
                    },
                ),
                executionCount = 7,
            ),
        )

        val merged = local.withRemoteOutputs(remote)

        assertEquals(7, merged.cells[1].executionCount)
        assertEquals("x\n", merged.cells[1].outputs[0].outputText())
        assertEquals(CellType.MARKDOWN, merged.cells[0].cellType)
    }

    @Test
    fun `withRemoteOutputs falls back to code order when source changes`() {
        val local = NotebookDocument.empty("test.ipynb")
        val remote = local.cells
            .filter { it.cellType == CellType.CODE }
            .mapIndexed { index, cell ->
                cell.copy(
                    source = "berubah $index",
                    outputs = listOf(
                        kotlinx.serialization.json.buildJsonObject {
                            put("output_type", "stream")
                            put("name", "stdout")
                            put("text", "hasil $index")
                        },
                    ),
                    executionCount = index + 1,
                )
            }

        val merged = local.withRemoteOutputs(remote)
        val code = merged.cells.filter { it.cellType == CellType.CODE }
        assertEquals("hasil 0", code[0].outputs[0].outputText())
        assertEquals(1, code[0].executionCount)
    }

    @Test
    fun `keep-alive cell is inserted once at the top`() {
        val document = NotebookDocument.empty("test.ipynb")
        val withCell = document.insertKeepAliveCell()

        assertEquals(document.cells.size + 1, withCell.cells.size)
        assertTrue(withCell.cells.first().source.contains(KeepAliveCell.MARKER))
        assertTrue(KeepAliveCell.source.contains("daemon=True"))

        val second = withCell.insertKeepAliveCell()
        assertEquals(withCell.cells.size, second.cells.size)
    }

    @Test
    fun `clear outputs keeps markdown unaffected`() {
        val document = NotebookDocument.parse("uji.ipynb", sample)
        val cleared = document.clearOutputs()

        assertEquals(0, cleared.cells[1].outputs.size)
        assertEquals(null, cleared.cells[1].executionCount)
        assertEquals(CellType.MARKDOWN, cleared.cells[0].cellType)
        assertNotNull(cleared.cells[0].source)
    }

    @Test
    fun `parse rejects invalid notebooks`() {
        val error = runCatching { NotebookDocument.parse("rusak.ipynb", "{\"cells\": 3}") }
        assertTrue(error.isFailure)
        assertFalse(error.exceptionOrNull()!!.message.isNullOrBlank())
    }

    @Test
    fun `slugify produces kaggle compatible slugs`() {
        assertEquals("my-notebook-v2", KaggleClient.slugify("My Notebook (v2)!"))
        assertEquals("hello-world", KaggleClient.slugify("  Hello,   World  "))
        assertEquals("", KaggleClient.slugify("!!!"))
        assertTrue(KaggleClient.slugify("a".repeat(120)).length <= 50)
    }

    @Test
    fun `stream output detection works for array text`() {
        val output = kotlinx.serialization.json.buildJsonObject {
            put("output_type", "stream")
            put("name", "stderr")
            put("text", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("satu "))
                add(kotlinx.serialization.json.JsonPrimitive("dua"))
            })
        }
        assertEquals("stream", output.outputType())
        assertEquals("satu dua", output.outputText())
    }
}
