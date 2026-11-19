package com.adekarya.ipynbrunner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Bullets(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Table(val text: String) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val ORDERED = Regex("^\\s*\\d+[.)]\\s+(.*)$")
private val RULE = Regex("^\\s*([-*_])\\1{2,}\\s*$")
private val INLINE = Regex(
    "\\*\\*.+?\\*\\*|__.+?__|\\*[^*\\n]+?\\*|_[^_\\n]+?_|`[^`\\n]+?`|\\[[^\\]]+\\]\\([^)\\s]+\\)",
)

private fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split("\n")
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> index++

            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code.appendLine(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks += MdBlock.Code(language, code.toString().trimEnd())
            }

            HEADING.matches(trimmed) -> {
                val match = HEADING.find(trimmed)!!
                val level = match.groupValues[1].length
                blocks += MdBlock.Heading(level, match.groupValues[2].trim())
                index++
            }

            RULE.matches(trimmed) -> {
                blocks += MdBlock.Rule
                index++
            }

            trimmed.startsWith(">") -> {
                val quote = StringBuilder()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    val content = lines[index].trim().removePrefix(">").trimStart()
                    quote.appendLine(content)
                    index++
                }
                blocks += MdBlock.Quote(quote.toString().trimEnd())
            }

            BULLET.matches(line) || ORDERED.matches(line) -> {
                val ordered = ORDERED.matches(line)
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val current = lines[index]
                    val match = if (ordered) ORDERED.find(current) else BULLET.find(current)
                    if (match != null) {
                        items += match.groupValues[1].trim()
                        index++
                    } else if (current.trim().isEmpty()) {
                        break
                    } else if (current.startsWith("  ") || current.startsWith("\t")) {
                        items[items.lastIndex] = items.last() + " " + current.trim()
                        index++
                    } else {
                        break
                    }
                }
                blocks += MdBlock.Bullets(items, ordered)
            }

            trimmed.startsWith("|") -> {
                val table = StringBuilder()
                while (index < lines.size && lines[index].trim().startsWith("|")) {
                    table.appendLine(lines[index].trim())
                    index++
                }
                blocks += MdBlock.Table(table.toString().trimEnd())
            }

            else -> {
                val paragraph = StringBuilder()
                while (index < lines.size) {
                    val current = lines[index]
                    val currentTrimmed = current.trim()
                    if (currentTrimmed.isEmpty() ||
                        currentTrimmed.startsWith("```") ||
                        currentTrimmed.startsWith(">") ||
                        currentTrimmed.startsWith("|") ||
                        HEADING.matches(currentTrimmed) ||
                        RULE.matches(currentTrimmed) ||
                        BULLET.matches(current) ||
                        ORDERED.matches(current)
                    ) {
                        break
                    }
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(currentTrimmed)
                    index++
                }
                if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(paragraph.toString())
            }
        }
    }
    return blocks
}

@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val blocks = remember(source) { parseMarkdown(source) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inlineMarkdown(block.text, linkColor, codeBackground),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    },
                    modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
                )

                is MdBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text, linkColor, codeBackground),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is MdBlock.Code -> Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = codeBackground,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (block.language.isNotBlank()) {
                            Text(
                                block.language,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SelectionContainer {
                            Text(
                                block.code,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                ),
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = inlineMarkdown(block.text, linkColor, codeBackground),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { itemIndex, item ->
                        Row {
                            Text(
                                text = if (block.ordered) "${itemIndex + 1}." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(22.dp),
                            )
                            Text(
                                text = inlineMarkdown(item, linkColor, codeBackground),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MdBlock.Table -> Surface(
                    shape = MaterialTheme.shapes.small,
                    color = codeBackground.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(
                            block.text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                            ),
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

private fun inlineMarkdown(text: String, linkColor: Color, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > last) append(text.substring(last, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.substring(2, token.length - 2))
                    }

                token.startsWith("__") && token.endsWith("__") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.substring(2, token.length - 2))
                    }

                token.startsWith("`") && token.endsWith("`") ->
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground,
                            fontSize = 13.sp,
                        ),
                    ) {
                        append(token.substring(1, token.length - 1))
                    }

                token.startsWith("[") && token.contains("](") -> {
                    val split = token.indexOf("](")
                    val label = token.substring(1, split)
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        append(label)
                    }
                }

                token.startsWith("*") && token.endsWith("*") ->
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }

                token.startsWith("_") && token.endsWith("_") ->
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }

                else -> append(token)
            }
            last = match.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }
