package com.sample.docreader.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TC-UT-20（F2.18）：GFM 管道表识别——成表/不成表边界（缺分隔行、非法分隔行）、
 * 对齐冒号四种形态、首尾竖线省略、`\|` 转义、单元格行内标记、行数不齐补空/截断（R1）、
 * 围栏内不识别、无表体退化、吞行截止（空行/普通段落/列表行）、startParagraphIndex 锚定表头行。
 * 性能兜底：大段落流多表解析计时（单测仅兜底回归，最终验收以真机为准）。
 */
class MarkdownTableTest {

    private fun table(block: MarkdownBlock): MarkdownBlock.Table =
        block as MarkdownBlock.Table

    /** 单元格文本便捷断言：把 MdInline 序列摊平为纯文本 */
    private fun plain(inlines: List<MdInline>): String = buildString {
        for (s in inlines) when (s) {
            is MdInline.Text -> append(s.text)
            is MdInline.Bold -> append(s.text)
            is MdInline.Italic -> append(s.text)
            is MdInline.Code -> append(s.text)
        }
    }

    @Test
    fun parse_standardTable_alignsAndBody() {
        val blocks = MarkdownParser.parse(listOf(
            "表前段落。",              // 0
            "| 名称 | 数量 | 单价 |",   // 1 表头
            "|:-----|:----:|-----:|",  // 2 分隔行
            "| 苹果 | 10 | 5 |",       // 3
            "| **香蕉** | `20` | 3 |", // 4
            "",                        // 5
            "表后段落。"               // 6
        ))
        assertEquals(3, blocks.size)
        val t = table(blocks[1])
        assertEquals(1, t.startParagraphIndex) // 锚定表头行而非分隔行
        assertEquals(listOf(MdAlign.LEFT, MdAlign.CENTER, MdAlign.RIGHT), t.aligns)
        assertEquals(3, t.header.size)
        assertEquals("名称", plain(t.header[0]))
        assertEquals(2, t.rows.size)
        assertEquals(listOf("苹果", "10", "5"), t.rows[0].map(::plain))
        // 行内标记在单元格内生效
        assertEquals(listOf(MdInline.Bold("香蕉"), MdInline.Text(" ")), t.rows[1][0])
        assertEquals(listOf(MdInline.Code("20")), t.rows[1][1])
        assertEquals(MarkdownBlock.Paragraph(listOf(MdInline.Text("表后段落。")), 6), blocks[2])
    }

    @Test
    fun parse_noColonDefaultsLeftAndOuterPipesOptional() {
        // 无冒号默认左对齐；省略首尾竖线
        val t = table(MarkdownParser.parse(listOf(
            "左列 | 右列",
            "---|---",
            "甲 | 乙"
        )).single())
        assertEquals(listOf(MdAlign.LEFT, MdAlign.LEFT), t.aligns)
        assertEquals(listOf("左列", "右列"), t.header.map(::plain))
        assertEquals(listOf("甲", "乙"), t.rows.single().map(::plain))
    }

    @Test
    fun parse_centerAlignRightAlign() {
        val t = table(MarkdownParser.parse(listOf(
            "| a | b | c | d |",
            "|:-|:-:|-:|---|",
            "| 1 | 2 | 3 | 4 |"
        )).single())
        assertEquals(listOf(MdAlign.LEFT, MdAlign.CENTER, MdAlign.RIGHT, MdAlign.LEFT), t.aligns)
    }

    @Test
    fun parse_escapedPipeAndInlineInCell() {
        val t = table(MarkdownParser.parse(listOf(
            "| 转义 | 说明 |",
            "|---|---|",
            "| a\\|b | 含 **加粗** 与 `代码` |"
        )).single())
        // `\|` 不当分隔符且转义符剥除
        assertEquals("a|b", plain(t.rows.single()[0]))
        assertEquals(
            listOf(MdInline.Text("含 "), MdInline.Bold("加粗"), MdInline.Text(" 与 "), MdInline.Code("代码")),
            t.rows.single()[1]
        )
    }

    @Test
    fun parse_raggedRows_padShortTruncateLong() {
        // R1 拍板：短行补空单元格、长行截断
        val blocks = MarkdownParser.parse(listOf(
            "| 短行表 | 二列 | 三列 |",
            "|---|---|---|",
            "| 只有一格 |",
            "",                 // 空行截止
            "| 长行表 | 二列 |",
            "|---|---|",
            "| 一 | 二 | 多余 |",
            "表后段落。"         // 普通段落截止
        ))
        val short = table(blocks[0])
        assertEquals(3, short.header.size)
        assertEquals(3, short.rows.single().size) // 补两格空单元格
        assertEquals("", plain(short.rows.single()[1]))
        assertEquals("", plain(short.rows.single()[2]))

        val long = table(blocks[1])
        assertEquals(2, long.rows.single().size) // 截断到表头列数
        assertEquals(listOf("一", "二"), long.rows.single().map(::plain))
        assertEquals(MarkdownBlock.Paragraph(listOf(MdInline.Text("表后段落。")), 7), blocks[2])
    }

    @Test
    fun parse_missingDelimiter_notATable() {
        // F2.18：缺分隔行的竖线文本按普通段落原文输出
        val blocks = MarkdownParser.parse(listOf("这不是 | 表格（缺分隔行）"))
        assertEquals(1, blocks.size)
        val p = blocks.single() as MarkdownBlock.Paragraph
        assertEquals(listOf(MdInline.Text("这不是 | 表格（缺分隔行）")), p.inlines)
    }

    @Test
    fun parse_invalidDelimiterRow_notATable() {
        // 表头下一行不是合法分隔行（含非 -/|/:/空白字符）→ 两行均按原文段落输出
        val blocks = MarkdownParser.parse(listOf("| a | b |", "|---| 杂 |", "| 1 | 2 |"))
        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it is MarkdownBlock.Paragraph })
    }

    @Test
    fun parse_tableInsideFence_notRecognized() {
        val blocks = MarkdownParser.parse(listOf(
            "```text",              // 0
            "| 围栏 | 表头 |",       // 1 围栏内
            "|---|---|",           // 2 围栏内
            "| 围栏 | 表体 |",       // 3 围栏内
            "```",                 // 4
            "",                     // 5
            "| 真表 | 表头 |",       // 6
            "|---|---|",           // 7
            "| 真表 | 表体 |"        // 8
        ))
        assertEquals(2, blocks.size)
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("| 围栏 | 表头 |\n|---|---|\n| 围栏 | 表体 |", code.code)
        assertTrue(blocks[1] is MarkdownBlock.Table) // 围栏外的表正常识别
    }

    @Test
    fun parse_headerOnlyTable_degradesToHeaderOnly() {
        // GFM 合法：表头 + 分隔行后无表体 → 仅表头的表格（rows 为空），分隔行本身不产出块
        val blocks = MarkdownParser.parse(listOf(
            "前文。",            // 0
            "| 仅表头 | 无表体 |", // 1
            "|---|---|",        // 2
            "",                  // 3
            "后文。"             // 4
        ))
        assertEquals(3, blocks.size)
        val t = table(blocks[1])
        assertEquals(1, t.startParagraphIndex)
        assertTrue(t.rows.isEmpty())
        assertEquals(listOf("仅表头", "无表体"), t.header.map(::plain))
    }

    @Test
    fun parse_bodyConsumption_stopsAtBlankParagraphAndList() {
        val blocks = MarkdownParser.parse(listOf(
            "| a | b |",     // 0 表头
            "|---|---|",    // 1 分隔行
            "| 1 | 2 |",    // 2 表体
            "",              // 3 空行截止
            "| x | y |",    // 4 空行后的管道行 → 新段落（GFM：表已结束）
            "普通段落",       // 5
            "- 列表项 | 带竖线", // 6 列表行不得被吞进表
            "| c | d |",    // 7
            "|---|---|",    // 8
            "| 3 | 4 |",    // 9
            "- 列表中断"      // 10 块起始语法截止吞行
        ))
        val t = table(blocks[0])
        assertEquals(1, t.rows.size)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph) // 空行后的管道行独立成段
        assertTrue(blocks[2] is MarkdownBlock.Paragraph)
        assertEquals(
            MarkdownBlock.ListItem(listOf(MdInline.Text("列表项 | 带竖线")), ordered = false, startParagraphIndex = 6),
            blocks[3]
        )
        val t2 = table(blocks[4])
        assertEquals(1, t2.rows.size) // 列表行截止了第二张表的吞行
        assertEquals(MarkdownBlock.ListItem(listOf(MdInline.Text("列表中断")), ordered = false, startParagraphIndex = 10), blocks[5])
    }

    @Test
    fun parse_tableCellEmpty_allowed() {
        val t = table(MarkdownParser.parse(listOf(
            "| | b |",
            "|---|---|",
            "| 1 | |"
        )).single())
        assertEquals("", plain(t.header[0]))
        assertEquals("", plain(t.rows.single()[1]))
    }

    /** TC-UT-20（性能基准）：大段落流含多表解析耗时下限断言（写法参照 TC-UT-07 性能基准） */
    @Test
    fun parse_largeInputWithTables_performanceBaseline() {
        val lines = (1..1000).flatMap {
            listOf("| 列一 | 列二 | 列三 |", "|---|---|---|", "| 数据 $it | **粗** | `码` |", "")
        }
        MarkdownParser.parse(lines.take(200)) // 预热
        val start = System.nanoTime()
        val blocks = MarkdownParser.parse(lines)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(1000, blocks.size)
        assertTrue("4000 行含表解析耗时 ${elapsedMs}ms，应 ≤ 2000ms", elapsedMs <= 2000)
    }
}
