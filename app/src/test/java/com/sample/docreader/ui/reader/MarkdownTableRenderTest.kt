package com.sample.docreader.ui.reader

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextAlign
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.render.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-22（F2.18，M20b）：md 预览表格渲染——表格节点存在且语义含行列数；
 * 表头单元格加粗、列对齐 textAlign、围栏内假表格渲染为原文（非表格节点）、
 * 缺分隔行对照段渲染为普通段落、超宽表存在横向滚动容器而窄表不存在。
 * 样式断言走自定义语义键（见 MarkdownPreview 的 MdTableCell* 键），与 TC-UI-03 同法。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh")
class MarkdownTableRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val settings = ReadingSettings()

    private fun setPreview(paragraphs: List<String>) {
        val blocks = MarkdownParser.parse(paragraphs)
        composeRule.setContent {
            MarkdownPreview(blocks, settings, rememberLazyListState(), ReadingTheme.DEFAULT)
        }
        composeRule.waitForIdle()
    }

    private fun cellBold(text: String): Boolean? =
        composeRule.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(MdTableCellBoldKey)

    private fun cellAlign(text: String): TextAlign? =
        composeRule.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(MdTableCellAlignKey)

    private fun tableScrollFlag(description: String): Boolean? =
        composeRule.onNodeWithContentDescription(description).fetchSemanticsNode()
            .config.getOrNull(MdTableHorizontalScrollKey)

    @Test
    fun table_rendersHeaderBoldAndAlignment() {
        setPreview(
            listOf(
                "| 名称 | 数量 | 单价 |",
                "|:-----|:----:|-----:|",
                "| 苹果 | 10 | 5 |",
                "| **香蕉** | `20` | 3 |"
            )
        )

        // 表格节点存在且语义含行列数（表头 + 2 表体 = 3 行）
        composeRule.onNodeWithContentDescription("表格，3 列 3 行").assertExists()

        // 表头加粗、表体普通
        assertEquals(true, cellBold("名称"))
        assertEquals(false, cellBold("苹果"))

        // 列对齐：左/中/右
        assertEquals(TextAlign.Left, cellAlign("名称"))
        assertEquals(TextAlign.Center, cellAlign("数量"))
        assertEquals(TextAlign.Right, cellAlign("单价"))
    }

    @Test
    fun fenceFakeTable_rendersAsPlainTextNotTable() {
        setPreview(
            listOf(
                "```",
                "| 假 | 表 |",
                "|---|---|",
                "| 假 | 体 |",
                "```",
                "",
                "| 真 | 表 |",
                "|---|---|",
                "| 真 | 体 |"
            )
        )

        // 围栏内假表格渲染为代码块原文（含管道符），非表格节点
        composeRule.onNodeWithText("| 假 | 表 |", substring = true).assertExists()
        // 真表渲染为表格：语义含行列数，表头单元格单独成格（不再以原文管道行出现）
        composeRule.onNodeWithContentDescription("表格，2 列 2 行").assertExists()
        composeRule.onNodeWithText("| 真 | 表 |").assertDoesNotExist()
    }

    @Test
    fun missingDelimiter_rendersAsPlainParagraph() {
        setPreview(listOf("这不是 | 表格（缺分隔行）"))

        composeRule.onNodeWithText("这不是 | 表格（缺分隔行）").assertExists()
        composeRule.onNodeWithContentDescription("表格，", substring = true).assertDoesNotExist()
    }

    @Test
    fun wideTable_hasHorizontalScroll() {
        // 超宽表（8 列）：最小列宽 96dp × 8 超屏 → 横向滚动
        setPreview(
            listOf(
                "| 列1 | 列2 | 列3 | 列4 | 列5 | 列6 | 列7 | 列8 |",
                "|---|---|---|---|---|---|---|---|",
                "| 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |"
            )
        )
        assertEquals(true, tableScrollFlag("表格，8 列 2 行"))
    }

    @Test
    fun narrowTable_hasNoHorizontalScroll() {
        // 窄表（2 列）：总宽 ≤ 屏宽 → 无横向滚动
        setPreview(listOf("| a | b |", "|---|---|", "| 1 | 2 |"))
        assertEquals(false, tableScrollFlag("表格，2 列 2 行"))
    }
}
