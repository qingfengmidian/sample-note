package com.sample.docreader.core.render

import com.sample.docreader.core.model.TocEntry

/**
 * 简易 Markdown 渲染节点模型与解析（《软件设计文档》§3.3）：
 * 覆盖标题/无序与有序列表/引用/代码块/行内加粗斜体行内代码；
 * 未识别语法按原文输出。接口独立，后续可替换为 commonmark 实现。
 *
 * F2.13（S4）：每个块携带 [startParagraphIndex]——块起始行在源码段落流中的行号
 * （预览 item 是块、源码 item 是段落，两者索引不同义；目录跳转/源码↔预览切换经
 * 此字段做 block↔段落映射）。CodeBlock 锚定其围栏起始行。
 */
sealed class MarkdownBlock {
    abstract val startParagraphIndex: Int
    data class Heading(
        val level: Int,
        val inlines: List<MdInline>,
        override val startParagraphIndex: Int
    ) : MarkdownBlock()

    data class Paragraph(
        val inlines: List<MdInline>,
        override val startParagraphIndex: Int
    ) : MarkdownBlock()

    data class ListItem(
        val inlines: List<MdInline>,
        val ordered: Boolean,
        val number: Int? = null,
        override val startParagraphIndex: Int
    ) : MarkdownBlock()

    data class Quote(
        val inlines: List<MdInline>,
        override val startParagraphIndex: Int
    ) : MarkdownBlock()

    data class CodeBlock(
        val language: String,
        val code: String,
        override val startParagraphIndex: Int
    ) : MarkdownBlock()

    /**
     * GFM 管道表（F2.18）：表头 + 分隔行必需（缺分隔行的竖线文本不识别为表格）；
     * 行数不齐容错 = 短行补空单元格、长行截断（R1 拍板）。
     * startParagraphIndex = 表头行行号（源码段落流坐标，U4 块↔段落映射经此换算，映射函数零改动）。
     */
    data class Table(
        val header: List<List<MdInline>>,     // 表头各单元格（行内标记已解析）
        val aligns: List<MdAlign>,            // 每列对齐，长度 = 列数
        val rows: List<List<List<MdInline>>>, // 表体各行；每个单元格是 MdInline 列表
        override val startParagraphIndex: Int
    ) : MarkdownBlock()
}

/** 表格列对齐（F2.18）：GFM 分隔行冒号位置决定；无冒号默认左对齐 */
enum class MdAlign { LEFT, CENTER, RIGHT }

/** 行内片段：粗体/斜体/行内代码/普通文本 */
sealed class MdInline {
    data class Text(val text: String) : MdInline()
    data class Bold(val text: String) : MdInline()
    data class Italic(val text: String) : MdInline()
    data class Code(val text: String) : MdInline()
}

object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val CLOSED_ATX = Regex("^(#{1,6})\\s+.+\\s+#+\\s*$") // ATX 封闭形式（`# 标题 #`），不支持
    private val UNORDERED = Regex("^[-*]\\s+(.*)$")
    private val ORDERED = Regex("^(\\d+)\\.\\s+(.*)$")

    /** 逐行解析为块；空行跳过，``` 围栏合并为 CodeBlock。 */
    fun parse(paragraphs: List<String>): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0
        while (i < paragraphs.size) {
            val line = paragraphs[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    val language = trimmed.removePrefix("```").trim()
                    val code = StringBuilder()
                    val start = i // CodeBlock 锚定围栏起始行（S4）
                    i++
                    while (i < paragraphs.size && !paragraphs[i].trimStart().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(paragraphs[i])
                        i++
                    }
                    blocks += MarkdownBlock.CodeBlock(language, code.toString(), start)
                    i++ // 跳过闭合围栏
                }
                line.isBlank() -> i++
                else -> {
                    blocks += parseBlock(line, i)
                    i++
                }
            }
        }
        return blocks
    }

    private fun parseBlock(line: String, paragraphIndex: Int): MarkdownBlock {
        atxHeading(line)?.let { (level, content) ->
            return MarkdownBlock.Heading(level, parseInline(content), paragraphIndex)
        }
        if (line.startsWith(">")) {
            return MarkdownBlock.Quote(parseInline(line.removePrefix(">").trimStart()), paragraphIndex)
        }
        UNORDERED.matchEntire(line)?.let { m ->
            return MarkdownBlock.ListItem(parseInline(m.groupValues[1].trim()), ordered = false,
                startParagraphIndex = paragraphIndex)
        }
        ORDERED.matchEntire(line)?.let { m ->
            return MarkdownBlock.ListItem(parseInline(m.groupValues[2].trim()), ordered = true,
                number = m.groupValues[1].toInt(), startParagraphIndex = paragraphIndex)
        }
        return MarkdownBlock.Paragraph(parseInline(line.trim()), paragraphIndex)
    }

    /**
     * ATX 标题识别（与目录提取共用同一标题语法，防两版逻辑漂移，评审 S10）：
     * 仅支持开放形式 `#{1,6}\s+`；**不支持 ATX 封闭（`# 标题 #`）与 Setext（`===`/`---`）**——
     * 封闭形式返回 null（按普通段落处理），Setext 的 `===`/`---` 行天然不满足 ATX 语法。
     * 返回 (level, 去 # 前缀后的原文内容)。
     */
    private fun atxHeading(line: String): Pair<Int, String>? {
        if (CLOSED_ATX.matches(line.trimEnd())) return null
        HEADING.matchEntire(line)?.let { m ->
            return m.groupValues[1].length to m.groupValues[2].trim()
        }
        return null
    }

    /**
     * F2.13（S10）：目录提取——单次线性扫描源码段落流，跳过 ``` 围栏代码块；
     * 代码块内行（含 `#` 注释）一律不产出目录项。标题取纯文本（去除 # 前缀与
     * 行内加粗/斜体/行内代码标记符号）。无标题文档返回空表。
     *
     * 围栏识别与 [parse] 完全同源：**仅识别行首围栏**（trimStart 后以三个反引号开头的行
     * 进入/离开代码块），列表/引用内的嵌套围栏不识别，其内容按普通段落处理。
     *
     * 循环内检查 [Thread.currentThread().isInterrupted]（AGENTS §5 解析器取消约定）：
     * 阻塞式循环不主动让出协程，靠线程中断尽早止损；本函数非挂起（供 UI remember 与
     * PlainTextParser 共用），故用中断位而非 ensureActive。
     */
    fun scanHeadings(paragraphs: List<String>): List<TocEntry> {
        val toc = mutableListOf<TocEntry>()
        var inFence = false
        var paragraphIndex = 0
        while (paragraphIndex < paragraphs.size) {
            if (Thread.currentThread().isInterrupted) return toc // 响应取消：立即中止扫描（withContext 出口会转 CancellationException）
            val line = paragraphs[paragraphIndex]
            val trimmed = line.trimStart()
            when {
                inFence -> {
                    if (trimmed.startsWith("```")) inFence = false // 围栏闭合
                }
                trimmed.startsWith("```") -> inFence = true // 进入围栏
                !line.isBlank() -> atxHeading(line)?.let { (level, content) ->
                    toc += TocEntry(level, plainTitle(content), paragraphIndex)
                }
            }
            paragraphIndex++
        }
        return toc
    }

    /**
     * 行内标记剥离：把 parseInline 的结果段拼接为纯文本标题（加粗/斜体/行内代码取内部文字）。
     * F2.13 REV8：链接 `[文字](url)` 先替换为其文字（链接文字内的行内标记随后续一并剥离），
     * 多余空白归一为单空格；**预览视图 [parseInline] 语义不变**（对链接仍按原文输出）。
     */
    private fun plainTitle(content: String): String {
        val noLinks = LINK.replace(content) { it.groupValues[1] }
        val text = buildString {
            for (segment in parseInline(noLinks)) {
                when (segment) {
                    is MdInline.Text -> append(segment.text)
                    is MdInline.Bold -> append(segment.text)
                    is MdInline.Italic -> append(segment.text)
                    is MdInline.Code -> append(segment.text)
                }
            }
        }
        return text.replace(WHITESPACE, " ").trim()
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
    private val LINK = Regex("""\[([^\]]*)\]\([^)]*\)""") // F2.13 REV8：目录标题剥链接（仅目录路径，parseInline 不动）
    private val WHITESPACE = Regex("\\s+")
}
