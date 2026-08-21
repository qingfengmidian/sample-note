package com.sample.docreader.core.render

/**
 * 简易 Markdown 渲染节点模型与解析（《软件设计文档》§3.3）：
 * 覆盖标题/无序与有序列表/引用/代码块/行内加粗斜体行内代码；
 * 未识别语法按原文输出。接口独立，后续可替换为 commonmark 实现。
 */
sealed class MarkdownBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MarkdownBlock()
    data class Paragraph(val inlines: List<MdInline>) : MarkdownBlock()
    data class ListItem(val inlines: List<MdInline>, val ordered: Boolean, val number: Int? = null) : MarkdownBlock()
    data class Quote(val inlines: List<MdInline>) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
}

/** 行内片段：粗体/斜体/行内代码/普通文本 */
sealed class MdInline {
    data class Text(val text: String) : MdInline()
    data class Bold(val text: String) : MdInline()
    data class Italic(val text: String) : MdInline()
    data class Code(val text: String) : MdInline()
}

object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val UNORDERED = Regex("^[-*]\\s+(.*)$")
    private val ORDERED = Regex("^(\\d+)\\.\\s+(.*)$")

    /** 逐行解析为块；空行跳过，``` 围栏合并为 CodeBlock */
    fun parse(lines: List<String>): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    val language = trimmed.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    blocks += MarkdownBlock.CodeBlock(language, code.toString())
                    i++ // 跳过闭合围栏
                }
                line.isBlank() -> i++
                else -> {
                    blocks += parseBlock(line)
                    i++
                }
            }
        }
        return blocks
    }

    private fun parseBlock(line: String): MarkdownBlock {
        HEADING.matchEntire(line)?.let { m ->
            return MarkdownBlock.Heading(m.groupValues[1].length, parseInline(m.groupValues[2].trim()))
        }
        if (line.startsWith(">")) {
            return MarkdownBlock.Quote(parseInline(line.removePrefix(">").trimStart()))
        }
        UNORDERED.matchEntire(line)?.let { m ->
            return MarkdownBlock.ListItem(parseInline(m.groupValues[1].trim()), ordered = false)
        }
        ORDERED.matchEntire(line)?.let { m ->
            return MarkdownBlock.ListItem(parseInline(m.groupValues[2].trim()), ordered = true,
                number = m.groupValues[1].toInt())
        }
        return MarkdownBlock.Paragraph(parseInline(line.trim()))
    }

    /** 行内解析：`**加粗**` → Bold，`*斜体*` → Italic，`` `code` `` → Code；其余为 Text */
    internal fun parseInline(text: String): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<MdInline>()
        var last = 0
        INLINE.findAll(text).forEach { m ->
            if (m.range.first > last) result += MdInline.Text(text.substring(last, m.range.first))
            val token = m.value
            result += when {
                token.startsWith("**") -> MdInline.Bold(token.removeSurrounding("**"))
                token.startsWith("*") -> MdInline.Italic(token.removeSurrounding("*"))
                else -> MdInline.Code(token.removeSurrounding("`"))
            }
            last = m.range.last + 1
        }
        if (last < text.length) result += MdInline.Text(text.substring(last))
        return result.ifEmpty { listOf(MdInline.Text(text)) }
    }

    private val INLINE = Regex("""(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`)""")
}
