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
        assertEquals(MarkdownBlock.ListItem(listOf(MdInline.Text("有序项")), ordered = true, number = 1), blocks[5])
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

    /** TC-UT-07（性能基准，写法参照 TC-UT-04c）：数千行混合语法解析耗时下限断言；单测仅兜底回归，最终验收以真机为准 */
    @Test
    fun parse_largeInput_performanceBaseline() {
        val lines = (1..3000).flatMap {
            listOf("# 标题 $it", "普通段落 **加粗** 与 *斜体* $it", "$it. 有序项", "> 引用 $it",
                "```text", "code $it", "```", "")
        }
        MarkdownParser.parse(lines.take(200)) // 预热：正则编译等一次性开销不计入
        val start = System.nanoTime()
        val blocks = MarkdownParser.parse(lines)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(blocks.isNotEmpty())
        assertTrue("24000 行解析耗时 ${elapsedMs}ms，应 ≤ 2000ms", elapsedMs <= 2000)
    }

    /** TC-UT-07：有序列表保留真实序号（无序列表 number 为 null，预览渲染据此输出序号） */
    @Test
    fun parse_orderedList_keepsNumbers() {
        val blocks = MarkdownParser.parse(listOf("2. 第二项", "3. 第三项", "- 无序项"))
        assertEquals(MarkdownBlock.ListItem(listOf(MdInline.Text("第二项")), ordered = true, number = 2), blocks[0])
        assertEquals(MarkdownBlock.ListItem(listOf(MdInline.Text("第三项")), ordered = true, number = 3), blocks[1])
        assertEquals(MarkdownBlock.ListItem(listOf(MdInline.Text("无序项")), ordered = false), blocks[2])
    }

    /** TC-UT-07：未闭合 ``` 围栏 → 产出单个 CodeBlock（含到文件末尾的内容），不崩溃 */
    @Test
    fun parse_unclosedFence_singleCodeBlock() {
        val blocks = MarkdownParser.parse(listOf("前文", "```kotlin", "val x = 1", "val y = 2"))
        assertEquals(2, blocks.size)
        val code = blocks[1] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nval y = 2", code.code)
    }

    /** TC-UT-07：未闭合/空行内标记回退原文（`**bold`、`****`） */
    @Test
    fun parseInline_unclosedOrEmptyMarkers_keptAsPlainText() {
        assertEquals(listOf(MdInline.Text("**bold")), MarkdownParser.parseInline("**bold"))
        assertEquals(listOf(MdInline.Text("****")), MarkdownParser.parseInline("****"))
        val paragraph = MarkdownParser.parse(listOf("前文 **bold 后文")).single() as MarkdownBlock.Paragraph
        assertEquals(listOf(MdInline.Text("前文 **bold 后文")), paragraph.inlines)
    }

    /** TC-UT-07：标题边界——`#标题` 无空格、`#######` 超 6 级、前导缩进 `  # x` 均降级为 Paragraph */
    @Test
    fun parse_headingEdgeCases_degradeToParagraph() {
        val noSpace = MarkdownParser.parse(listOf("#标题")).single() as MarkdownBlock.Paragraph
        assertEquals(listOf(MdInline.Text("#标题")), noSpace.inlines)

        val tooDeep = MarkdownParser.parse(listOf("####### 七级")).single() as MarkdownBlock.Paragraph
        assertEquals(listOf(MdInline.Text("####### 七级")), tooDeep.inlines)

        val indented = MarkdownParser.parse(listOf("  # x")).single() as MarkdownBlock.Paragraph
        assertEquals(listOf(MdInline.Text("# x")), indented.inlines)
    }
}
