package com.sample.docreader.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC-UT-07：标题/列表/加粗斜体/代码块/引用混合样本 → 各语法元素映射为对应节点，未识别语法按原文输出。 */
class MarkdownParserTest {

    private val sample = listOf(
        "# 标题一",
        "",
        "## 标题二",
        "",
        "普通段落，含 **加粗** 与 *斜体*。",
        "",
        "- 列表项一",
        "- 列表项二",
        "",
        "1. 有序项",
        "",
        "> 引用段落。",
        "",
        "```kotlin",
        "fun main() { println(\"code\") }",
        "```",
        "",
        "未识别语法按原文输出 ~~删除线~~。"
    )

    @Test
    fun parse_mixedBlocks() {
        val blocks = MarkdownParser.parse(sample)

        assertEquals(9, blocks.size)

        assertEquals(MarkdownBlock.Heading(1, listOf(MdInline.Text("标题一"))), blocks[0])
        assertEquals(MarkdownBlock.Heading(2, listOf(MdInline.Text("标题二"))), blocks[1])

        val paragraph = blocks[2] as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MdInline.Text("普通段落，含 "),
                MdInline.Bold("加粗"),
                MdInline.Text(" 与 "),
                MdInline.Italic("斜体"),
                MdInline.Text("。")
            ),
            paragraph.inlines
        )

        assertEquals(MarkdownBlock.ListItem(listOf(MdInline.Text("列表项一")), ordered = false), blocks[3])
        assertEquals(MarkdownBlock.ListItem(listOf(MdInline.Text("列表项二")), ordered = false), blocks[4])
        assertEquals(MarkdownBlock.ListItem(listOf(MdInline.Text("有序项")), ordered = true), blocks[5])
        assertEquals(MarkdownBlock.Quote(listOf(MdInline.Text("引用段落。"))), blocks[6])

        val code = blocks[7] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("fun main() { println(\"code\") }", code.code)
    }

    @Test
    fun parse_unknownSyntax_keptAsPlainText() {
        val blocks = MarkdownParser.parse(listOf("未识别语法按原文输出 ~~删除线~~。"))
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals(listOf(MdInline.Text("未识别语法按原文输出 ~~删除线~~。")), paragraph.inlines)
    }

    @Test
    fun parseInline_codeAndBoldItalic() {
        val inlines = MarkdownParser.parseInline("`x` 和 **b** 与 *i*")
        assertEquals(
            listOf(
                MdInline.Code("x"),
                MdInline.Text(" 和 "),
                MdInline.Bold("b"),
                MdInline.Text(" 与 "),
                MdInline.Italic("i")
            ),
            inlines
        )
    }

    @Test
    fun parse_emptyAndBlankLines_skipped() {
        assertTrue(MarkdownParser.parse(listOf("", "   ", "\t")).isEmpty())
    }
}
