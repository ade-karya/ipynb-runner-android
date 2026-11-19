package com.adekarya.ipynbrunner.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.adekarya.ipynbrunner.data.model.errorText
import com.adekarya.ipynbrunner.data.model.outputHtml
import com.adekarya.ipynbrunner.data.model.outputImage
import com.adekarya.ipynbrunner.data.model.outputJsonText
import com.adekarya.ipynbrunner.data.model.outputSvg
import com.adekarya.ipynbrunner.data.model.outputText
import com.adekarya.ipynbrunner.data.model.outputType
import com.adekarya.ipynbrunner.data.model.string
import androidx.compose.foundation.isSystemInDarkTheme
import com.adekarya.ipynbrunner.ui.theme.MonoStyle
import kotlinx.serialization.json.JsonObject

@Composable
fun CellOutputs(
    outputs: List<JsonObject>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        outputs.forEachIndexed { index, output ->
            OutputItem(output, index)
        }
    }
}

@Composable
private fun OutputItem(output: JsonObject, index: Int) {
    val type = output.outputType()
    when {
        type == "stream" -> StreamOutput(output)
        type == "error" -> ErrorOutput(output)
        else -> RichOutput(output, index)
    }
}

@Composable
private fun StreamOutput(output: JsonObject) {
    val isError = output.string("name") == "stderr"
    val text = output.outputText().orEmpty()
    if (text.isBlank()) return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = truncateOutput(text),
                style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun ErrorOutput(output: JsonObject) {
    val text = output.errorText() ?: "Eksekusi gagal."
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = truncateOutput(text),
                style = MonoStyle.copy(fontSize = 11.sp, lineHeight = 17.sp),
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun RichOutput(output: JsonObject, index: Int) {
    val image = output.outputImage()
    if (image != null) {
        val bitmap = remember(image.base64, index) {
            runCatching { decodeSampledBitmap(image.base64) }.getOrNull()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Output gambar $index",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Fit,
            )
            return
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                Icons.Outlined.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "  Gambar tidak bisa didekode",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val html = output.outputHtml()
    if (!html.isNullOrBlank()) {
        HtmlOutput(html)
        return
    }

    val svg = output.outputSvg()
    if (!svg.isNullOrBlank()) {
        HtmlOutput(svg, isSvg = true)
        return
    }

    val text = output.outputText()
    if (!text.isNullOrBlank()) {
        PlainOutput(text)
        return
    }

    val json = output.outputJsonText()
    if (!json.isNullOrBlank()) {
        PlainOutput(json)
        return
    }

    val mime = output.string("output_type").orEmpty().ifBlank { "output" }
    Text(
        "($mime tanpa data teks)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontStyle = FontStyle.Italic,
    )
}

@Composable
private fun PlainOutput(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = truncateOutput(text),
                style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlOutput(html: String, isSvg: Boolean = false) {
    val dark = isSystemInDarkTheme()
    val textColor = if (dark) "#EDE9FE" else "#1C1B22"
    val linkColor = if (dark) "#B8A2FF" else "#6442C9"
    val document = remember(html, dark, isSvg) {
        val body = if (isSvg) html else html
        """
        <!doctype html>
        <html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html,body { margin:0; padding:0; background:transparent; color:$textColor;
            font-family: -apple-system, Roboto, sans-serif; font-size:13px; overflow-x:auto; }
          img,svg { max-width:100%; height:auto; }
          table { border-collapse:collapse; width:100%; font-size:12px; }
          th,td { border:1px solid rgba(128,128,128,.35); padding:4px 6px; text-align:left; }
          th { background:rgba(128,128,128,.12); }
          pre { white-space:pre-wrap; word-break:break-word; font-size:12px; }
          a { color:$linkColor; }
        </style></head>
        <body>$body</body></html>
        """.trimIndent()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(190.dp),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.loadsImagesAutomatically = true
                    isVerticalScrollBarEnabled = true
                    loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                }
            },
            update = { view ->
                if (view.tag != document) {
                    view.tag = document
                    view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                }
            },
            onRelease = { view -> runCatching { view.destroy() } },
        )
    }
}

private fun decodeSampledBitmap(base64: String): androidx.compose.ui.graphics.ImageBitmap? {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
            sample *= 2
        }
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

private fun truncateOutput(text: String): String =
    if (text.length <= 60_000) text else text.take(60_000) + "\n\n… output dipotong (${text.length} karakter)."
