package com.sample.docreader.ui.reader

import com.sample.docreader.core.model.TocEntry
import com.sample.docreader.core.render.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 源码段落 ↔ 预览块 ↔ 目录三坐标映射纯函数（S4/U4）。
 * 前提：预览 item 是块、源码 item 是段落，索引不同义，靠 startParagraphIndex 换算。
 */
class MdViewMappingTest {

    // 段落流（含空行占位、围栏代码块）：
    //  0 "# 甲"     1 ""      2 "正文一"    3 "```text"
    //  4 "# 代码注释" 5 "code"  6 "```"     7 ""
    //  8 "## 乙"    9 ""       10 "正文二"
    private val paragraphs = listOf(
        "# 甲", "", "正文一", "```text", "# 代码注释", "code", "```", "", "## 乙", "", "正文二"
    )
    private val blocks = MarkdownParser.parse(paragraphs)

    init {
        // 结构先验：4 块 5 段结构 = Heading0, Paragraph2, CodeBlock3, Heading8, Paragraph10
        assertEquals(5, blocks.size)
    }

    /** S4：段落 → 所属块（含落在围栏代码块内的行归其 CodeBlock） */
    @Test
    fun blockIndexForParagraph_mapsToContainingBlock() {
        val m = MdViewMapping
        assertEquals(0, m.blockIndexForParagraph(blocks, 0))
        assertEquals(0, m.blockIndexForParagraph(blocks, 1)) // 标题后空行仍属标题块
        assertEquals(1, m.blockIndexForParagraph(blocks, 2))
        assertEquals(2, m.blockIndexForParagraph(blocks, 4)) // 代码块内注释行 → CodeBlock
        assertEquals(2, m.blockIndexForParagraph(blocks, 6)) // 围栏闭合行 → CodeBlock
        assertEquals(3, m.blockIndexForParagraph(blocks, 8))
        assertEquals(4, m.blockIndexForParagraph(blocks, 10))
        assertEquals(0, m.blockIndexForParagraph(emptyList(), 5)) // 空块表兜底
    }

    /** S4：块 → 起始段落（反向经 startParagraphIndex 换算，预览跳转进度上报的基础） */
    @Test
    fun paragraphIndexForBlock_mapsToBlockStart() {
        val m = MdViewMapping
        assertEquals(0, m.paragraphIndexForBlock(blocks, 0))
        assertEquals(2, m.paragraphIndexForBlock(blocks, 1))
        assertEquals(3, m.paragraphIndexForBlock(blocks, 2))
        assertEquals(8, m.paragraphIndexForBlock(blocks, 3))
        assertEquals(10, m.paragraphIndexForBlock(blocks, 4))
        assertEquals(0, m.paragraphIndexForBlock(blocks, 99)) // 越界兜底
        assertEquals(0, m.paragraphIndexForBlock(emptyList(), 0))
    }

    /**
     * TC-UT-21（F2.18）：含 Table 块的块表映射——表体中间段落归其 Table 块
     * （U4 源码↔预览切换与目录跳转经 startParagraphIndex 换算，映射函数零改动）。
     * 块表手工构造（纯函数输入），与解析层实现解耦。
     */
    @Test
    fun blockIndexForParagraph_tableBlock_mapsHeaderAndBodyLines() {
        val table = com.sample.docreader.core.render.MarkdownBlock.Table(
            header = listOf(
                listOf(com.sample.docreader.core.render.MdInline.Text("表头")),
                listOf(com.sample.docreader.core.render.MdInline.Text("二列"))
            ),
            aligns = listOf(com.sample.docreader.core.render.MdAlign.LEFT, com.sample.docreader.core.render.MdAlign.LEFT),
            rows = listOf(
                listOf(
                    listOf(com.sample.docreader.core.render.MdInline.Text("表体")),
                    listOf(com.sample.docreader.core.render.MdInline.Text("数据"))
                )
            ),
            startParagraphIndex = 1
        )
        // 段落流：0 表前段、1 表头行、2 分隔行、3 表体行、4 表后段
        val tableBlocks = listOf(
            com.sample.docreader.core.render.MarkdownBlock.Paragraph(
                listOf(com.sample.docreader.core.render.MdInline.Text("表前段落。")), 0),
            table,
            com.sample.docreader.core.render.MarkdownBlock.Paragraph(
                listOf(com.sample.docreader.core.render.MdInline.Text("表后段落。")), 4)
        )
        val m = MdViewMapping
        assertEquals(0, m.blockIndexForParagraph(tableBlocks, 0)) // 表前段
        assertEquals(1, m.blockIndexForParagraph(tableBlocks, 1)) // 表头行 → Table
        assertEquals(1, m.blockIndexForParagraph(tableBlocks, 2)) // 分隔行 → Table
        assertEquals(1, m.blockIndexForParagraph(tableBlocks, 3)) // 表体行 → Table
        assertEquals(2, m.blockIndexForParagraph(tableBlocks, 4)) // 表后段
        assertEquals(1, m.paragraphIndexForBlock(tableBlocks, 1)) // Table → 表头行（S3 方案① 跳转落库语义）
    }

    /** TC-UT-21（F2.18）集成：parse 产出的含表块表与映射函数协同（依赖 MarkdownParser 表格识别，红态期失败） */
    @Test
    fun blockIndexForParagraph_parsedTable_integration() {
        val paragraphs = listOf(
            "表前段落。", "| a | b |", "|---|---|", "| 1 | 2 |", "表后段落。"
        )
        val parsedBlocks = MarkdownParser.parse(paragraphs)
        assertEquals(3, parsedBlocks.size)
        val m = MdViewMapping
        assertEquals(1, m.blockIndexForParagraph(parsedBlocks, 2)) // 分隔行归 Table
        assertEquals(1, m.blockIndexForParagraph(parsedBlocks, 3)) // 表体行归 Table
        assertEquals(4, m.paragraphIndexForBlock(parsedBlocks, 2)) // 表后块起始段
    }

    /** S11：可见段落 → 目录项；首可见段在首个标题之前返回 -1（无高亮） */
    @Test
    fun tocIndexAtParagraph_findsCurrentHeading() {
        val toc = listOf(TocEntry(1, "甲", 0), TocEntry(2, "乙", 8))
        val m = MdViewMapping
        assertEquals(0, m.tocIndexAtParagraph(0, toc))
        assertEquals(0, m.tocIndexAtParagraph(7, toc)) // 仍在"甲"节内
        assertEquals(1, m.tocIndexAtParagraph(8, toc))
        assertEquals(1, m.tocIndexAtParagraph(10, toc))
        assertEquals(-1, m.tocIndexAtParagraph(0, emptyList()))

        // 首个标题不在第 0 段时，其前的可见段落无高亮
        val lateToc = listOf(TocEntry(1, "晚", 2))
        assertEquals(-1, m.tocIndexAtParagraph(1, lateToc))
        assertEquals(0, m.tocIndexAtParagraph(2, lateToc))
    }
}
