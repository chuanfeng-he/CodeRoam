package com.local.codexremote.ui

sealed interface MessageMarkdownBlock {
    data class Paragraph(val spans: List<MessageMarkdownSpan>) : MessageMarkdownBlock
    data class Code(val language: String?, val code: String) : MessageMarkdownBlock
}

sealed interface MessageMarkdownSpan {
    data class Text(val text: String) : MessageMarkdownSpan
    data class Bold(val text: String) : MessageMarkdownSpan
    data class Code(val text: String) : MessageMarkdownSpan
    data class Link(val label: String, val destination: String) : MessageMarkdownSpan
}

fun parseMessageMarkdown(text: String): List<MessageMarkdownBlock> {
    val lines = text.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MessageMarkdownBlock>()
    val paragraph = mutableListOf<String>()
    var codeLanguage: String? = null
    val codeLines = mutableListOf<String>()

    fun flushParagraph() {
        val paragraphText = paragraph.joinToString("\n").trim()
        if (paragraphText.isNotEmpty()) {
            blocks += MessageMarkdownBlock.Paragraph(parseInlineMarkdown(paragraphText))
        }
        paragraph.clear()
    }

    lines.forEach { line ->
        val trimmed = line.trim()
        if (codeLanguage != null) {
            if (trimmed.startsWith("```")) {
                blocks += MessageMarkdownBlock.Code(
                    language = codeLanguage?.takeIf { it.isNotBlank() },
                    code = codeLines.joinToString("\n").trimEnd()
                )
                codeLanguage = null
                codeLines.clear()
            } else {
                codeLines += line
            }
            return@forEach
        }

        if (trimmed.startsWith("```")) {
            flushParagraph()
            codeLanguage = trimmed.removePrefix("```").trim()
            return@forEach
        }

        if (trimmed.isBlank()) {
            flushParagraph()
        } else {
            paragraph += line
        }
    }

    if (codeLanguage != null) {
        blocks += MessageMarkdownBlock.Code(
            language = codeLanguage?.takeIf { it.isNotBlank() },
            code = codeLines.joinToString("\n").trimEnd()
        )
    }
    flushParagraph()
    return blocks
}

private fun parseInlineMarkdown(text: String): List<MessageMarkdownSpan> {
    val spans = mutableListOf<MessageMarkdownSpan>()
    val plain = StringBuilder()
    var index = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            spans += MessageMarkdownSpan.Text(plain.toString())
            plain.clear()
        }
    }

    while (index < text.length) {
        when {
            text[index] == '[' -> {
                val labelEnd = text.indexOf(']', startIndex = index + 1)
                val destinationStart = labelEnd + 1
                if (
                    labelEnd > index + 1 &&
                    destinationStart < text.length &&
                    text[destinationStart] == '('
                ) {
                    val destinationEnd = text.indexOf(')', startIndex = destinationStart + 1)
                    if (destinationEnd > destinationStart + 1) {
                        flushPlain()
                        spans += MessageMarkdownSpan.Link(
                            label = text.substring(index + 1, labelEnd),
                            destination = text.substring(destinationStart + 1, destinationEnd)
                        )
                        index = destinationEnd + 1
                    } else {
                        plain.append(text[index])
                        index += 1
                    }
                } else {
                    plain.append(text[index])
                    index += 1
                }
            }
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end > index + 2) {
                    flushPlain()
                    spans += MessageMarkdownSpan.Bold(text.substring(index + 2, end))
                    index = end + 2
                } else {
                    plain.append(text[index])
                    index += 1
                }
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end > index + 1) {
                    flushPlain()
                    spans += MessageMarkdownSpan.Code(text.substring(index + 1, end))
                    index = end + 1
                } else {
                    plain.append(text[index])
                    index += 1
                }
            }
            else -> {
                plain.append(text[index])
                index += 1
            }
        }
    }
    flushPlain()
    return spans
}
