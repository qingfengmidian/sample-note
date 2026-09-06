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
