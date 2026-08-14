package com.sample.docreader.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.testutil.ShadowNoOpMagnifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-02（自动化部分）：长按正文出现选区（起止选择手柄可见且有序）。
 * 复制菜单与剪贴板内容校验依赖平台浮动 ActionMode（非 Compose 语义树），
 * 在 Robolectric 下不可达，由真机手工用例 TC-F2-22/22a 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowNoOpMagnifier::class])
class ReaderSelectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longPressSelect_showsSelectionHandles() {
        val paragraph = "第一段 uniqueAlpha bravo charlie 结尾。"
        val document = Document("asset:///fixtures/sample_utf8.txt", "sample_utf8.txt", DocFormat.TXT,
            listOf(Chapter(null, listOf(paragraph, "第二段内容。"))))
        var settings by mutableStateOf(ReadingSettings())

        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(loading = false, document = document, settings = settings),
                onBack = {},
                onToggleWrap = {},
                onFontSizeStep = { _ -> },
                onCycleLineHeight = {},
                onCycleFont = {},
                onCycleTheme = {}
            )
        }
        composeRule.waitForIdle()

        // 长按选中词语（Robolectric 下放大镜由 ShadowNoOpMagnifier 置空，避免 Surface NPE）
        composeRule.onNodeWithText(paragraph).performTouchInput { longClick() }
        composeRule.waitForIdle()

        // 选区生效：出现起止选择手柄（SelectionHandleInfo popup），可见且起点在终点左侧。
        // SelectionHandleInfoKey/SelectionHandleInfo 为 Compose internal，按语义键名匹配 + 字符串解析字段。
        val handles = composeRule.onAllNodes(
            SemanticsMatcher("SelectionHandleInfo is defined") { node ->
                node.config.any { (key, _) -> key.name == "SelectionHandleInfo" }
            },
            useUnmergedTree = true
        ).fetchSemanticsNodes().mapNotNull { node ->
            val value = node.config.firstOrNull { (key, _) -> key.name == "SelectionHandleInfo" }?.value
                ?: return@mapNotNull null
            Regex("handle=(\\w+), position=Offset\\(([\\d.]+), [\\d.]+\\), anchor=\\w+, visible=(\\w+)")
                .find(value.toString())?.destructured?.let { (handle, x, visible) ->
                    Triple(handle, x.toFloat(), visible.toBoolean())
                }
        }
        assertEquals("应出现起止两个选择手柄", 2, handles.size)
        val start = handles.first { it.first == "SelectionStart" }
        val end = handles.first { it.first == "SelectionEnd" }
        assertTrue("选择手柄应可见", start.third && end.third)
        assertTrue("起点应在终点左侧", start.second < end.second)
    }
}
