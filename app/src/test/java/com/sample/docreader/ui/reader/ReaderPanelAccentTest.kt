package com.sample.docreader.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.TocEntry
import com.sample.docreader.ui.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-13（M13，F4.4/F4.5）：面板强调色（选中勾 / 当前目录项）随阅读主题，不再取 colorScheme。
 *
 * 复现 M13 缺陷场景：**系统深色**（`@Config(qualifiers = "night")` → [AppTheme] 走 darkColorScheme，
 * 其 primary 为浅紫）而阅读主题为亮色主题——旧实现取 `colorScheme.primary`，对主题背景对比度仅 1.3~1.6:1。
 * 断言走语义键 [ReaderPanelAccentColorKey]（对色值非截图，同 TC-UI-03 的
 * ReaderBackground/ForegroundColorKey 机制）；键由实现侧记录「实际绘制所用」的强调色。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "night zh")
class ReaderPanelAccentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val toc = listOf(
        TocEntry(1, "标题一", 0),
        TocEntry(2, "标题二", 5),
        TocEntry(3, "标题三", 9)
    )

    /** M13 契约：强调色 = 阅读主题前景色，且对主题背景 WCAG 对比度 ≥4.5:1 */
    private fun assertFollowsTheme(accent: Color?, theme: ReadingTheme, label: String) {
        assertEquals("$label：强调色应为阅读主题前景色（M13/TC-UI-13）", theme.foreground, accent)
        val contrast = wcagContrast(accent!!.toArgb(), theme.background.toArgb())
        assertTrue("$label：对主题背景对比度应 ≥4.5:1，实测 $contrast", contrast >= 4.5)
    }

    /** 阅读设置面板：所有选中项的「✓」强调色随阅读主题（四主题轮转） */
    @Test
    fun tcUi13_settingsSheetSelectedMark_followsReadingTheme() {
        var theme by mutableStateOf(ReadingTheme.DEFAULT)
        composeRule.setContent {
            AppTheme {
                ReaderSettingsSheetContent(
                    settings = ReadingSettings(theme = theme),
                    isTxt = false,
                    encodingOverride = null,
                    accent = theme.foreground,
                    onSelectWrapMode = {},
                    onFontSizeStep = {},
                    onSelectLineHeight = {},
                    onSelectFont = {},
                    onSelectTheme = {},
                    onSelectEncoding = {},
                    onImportFont = {}
                )
            }
        }
        ReadingTheme.entries.forEach { t ->
            composeRule.runOnIdle { theme = t }
            composeRule.waitForIdle()
            val marks = composeRule.onAllNodesWithText("✓").fetchSemanticsNodes()
            assertTrue("${t.name}：面板应有选中项（✓）", marks.isNotEmpty())
            marks.forEach { node ->
                assertFollowsTheme(node.config.getOrNull(ReaderPanelAccentColorKey), t, t.name)
            }
        }
    }

    /** 目录/章节面板：当前项高亮色随阅读主题（四主题轮转），selected 语义保留 */
    @Test
    fun tcUi13_tocCurrentItem_followsReadingTheme() {
        var theme by mutableStateOf(ReadingTheme.DEFAULT)
        composeRule.setContent {
            AppTheme {
                TocListContent(toc = toc, selectedIndex = 1, onSelect = {}, accent = theme.foreground)
            }
        }
        ReadingTheme.entries.forEach { t ->
            composeRule.runOnIdle { theme = t }
            composeRule.waitForIdle()
            val node = composeRule.onNodeWithText("标题二")
            node.assertIsSelected()
            assertFollowsTheme(node.fetchSemanticsNode().config.getOrNull(ReaderPanelAccentColorKey), t, t.name)
        }
    }
}
