package com.sample.docreader.ui.reader

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.render.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-23（F2.18 REV16，M21）：md 表格列宽按内容占比自适应——
 * 长短列区分、单列上限 50% 可用宽、近似等宽退化、窄表撑满屏宽、超宽多列保留横向滚动、测量性能兜底。
 * 列宽经语义键 [MdTableColumnWidthsKey] 断言（与 TC-UI-22 语义键同法）。
 *
 * 测试环境宽度约定：Robolectric 默认屏宽 320dp，预览容器左右 padding 各 16dp → 可用宽 288dp，
 * 单列上限 CAP = 144dp，最小列宽 MIN = 96dp。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh")
class MarkdownTableColumnWidthTest {

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

    private fun columnWidths(description: String): List<Dp> {
        val widths = composeRule.onNodeWithContentDescription(description)
            .fetchSemanticsNode().config.getOrNull(MdTableColumnWidthsKey)
        assertNotNull("表格节点应携带列宽语义（MdTableColumnWidthsKey）", widths)
        return widths!!
    }

    @Test
    fun unevenContent_longColumnWider() {
        val longText = "作为户外爱好者，我希望一键让闪光灯按 SOS 节奏闪烁，以便在无信号山区夜间向远处求救。"
        setPreview(listOf(
            "| 编号 | 用户故事 |",
            "|---|---|",
            "| US-1 | $longText |",
            "| US-2 | $longText |"
        ))
        val widths = columnWidths("表格，2 列 3 行")
        assertEquals(2, widths.size)
        assertTrue("长文列应明显宽于短列：$widths", widths[1] > widths[0] + 20.dp)
    }

    @Test
    fun veryLongCell_cappedAtHalfAvailable() {
        val huge = "很长".repeat(200) // 远超屏宽
        setPreview(listOf(
            "| 列一 | 列二 |",
            "|---|---|",
            "| 短 | $huge |"
        ))
        val widths = columnWidths("表格，2 列 2 行")
        assertTrue("超长列应 ≤ 可用宽 50%（144dp）+1dp 容差：${widths[1]}", widths[1] <= 145.dp)
        assertTrue("另一列仍 ≥ 最小列宽 96dp：${widths[0]}", widths[0] >= 96.dp)
    }

    @Test
    fun similarContent_columnsNearlyEqual() {
        setPreview(listOf(
            "| 名称 | 别名 |",
            "|---|---|",
            "| 苹果 | 香蕉 |",
            "| 橘子 | 葡萄 |"
        ))
        val widths = columnWidths("表格，2 列 3 行")
        assertTrue("内容相近的两列宽度差应 ≤ 2dp：$widths", kotlin.math.abs(widths[0].value - widths[1].value) <= 2f)
    }

    @Test
    fun narrowTable_fillsAvailableWidthWithoutScroll() {
        setPreview(listOf("| a | b |", "|---|---|", "| 1 | 2 |"))
        val widths = columnWidths("表格，2 列 2 行")
        val sum = widths[0] + widths[1]
        assertEquals("窄表列宽之和应撑满可用宽 288dp（±1dp）", 288f, sum.value, 1f)
        val scroll = composeRule.onNodeWithContentDescription("表格，2 列 2 行")
            .fetchSemanticsNode().config.getOrNull(MdTableHorizontalScrollKey)
        assertEquals(false, scroll)
    }

    @Test
    fun wideManyColumnTable_keepsHorizontalScroll() {
        setPreview(listOf(
            "| 列1 | 列2 | 列3 | 列4 | 列5 | 列6 | 列7 | 列8 |",
            "|---|---|---|---|---|---|---|---|",
            "| 内容一 | 内容二 | 内容三 | 内容四 | 内容五 | 内容六 | 内容七 | 内容八 |"
        ))
        val scroll = composeRule.onNodeWithContentDescription("表格，8 列 2 行")
            .fetchSemanticsNode().config.getOrNull(MdTableHorizontalScrollKey)
        assertEquals(true, scroll)
    }

    /** TC-UI-23（性能兜底）：大表（80 行 × 6 列）测量 + 组合耗时下限断言（单测仅兜底回归，最终验收以真机为准） */
    @Test
    fun largeTable_measureAndCompose_performanceBaseline() {
        val rows = (1..80).joinToString("\n") { "| 数据$it | 内容${it}号 | 第三列值$it | 第四列$it | 第五$it | 第六列$it |" }
        val md = "| 表头一 | 表头二 | 表头三 | 表头四 | 表头五 | 表头六 |\n|---|---|---|---|---|---|\n$rows"
        val start = System.nanoTime()
        setPreview(md.lines()) // 单次组合即含测量路径（setContent 仅可调用一次；测量经 remember(block) 缓存）
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("80×6 表格测量+组合耗时 ${elapsedMs}ms，应 ≤ 3000ms", elapsedMs <= 3000)
    }
}
