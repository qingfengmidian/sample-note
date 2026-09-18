package com.sample.docreader.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.WrapMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-01：长文档滚动至中部 → 切换 WRAP/NO_WRAP → 首可见段落索引不变。
 * M10（F4.4）：切换入口迁至阅读设置面板；pendingParagraph 记录与滚回逻辑在 ReaderContent 内部，
 * 切 wrap 路径必须经 ReaderContent（唤起 ⋯ → 阅读设置 → 面板内换行单选）才能断言位置保持。
 * 回调 onSetWrapMode 由测试传入，ReaderContent 内部 pendingParagraph 机制照旧生效。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh")
class ReaderWrapPositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun wrapToggle_keepsFirstVisibleParagraph() {
        val paragraphs = (1..400).map { "第 $it 段：" + "滚动的长文档内容。".repeat(10) }
        val document = Document("asset:///fixtures/long.txt", "long.txt", DocFormat.TXT,
            listOf(Chapter(null, paragraphs)))
        val listState = LazyListState()
        var settings by mutableStateOf(ReadingSettings())

        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(loading = false, document = document, settings = settings),
                onBack = {},
                onSetWrapMode = { mode -> settings = settings.copy(wrapMode = mode) },
                onFontSizeStep = { _ -> },
                listState = listState
            )
        }
        composeRule.waitForIdle()

        // 滚动到中部
        runBlocking { listState.scrollToItem(200) }
        composeRule.waitForIdle()
        val before = listState.firstVisibleItemIndex
        assertEquals(200, before)

        // 唤起阅读设置面板（原底部工具条已移除，M10 F4.4 操作入口迁至面板）
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("阅读设置").performClick()
        composeRule.waitForIdle()

        // WRAP → NO_WRAP：面板内单选触发 ReaderContent 内部 pendingParagraph 记录与滚回
        composeRule.onNodeWithText("不换行").performClick()
        composeRule.waitForIdle()
        assertEquals(WrapMode.NO_WRAP, settings.wrapMode)
        assertEquals(before, listState.firstVisibleItemIndex)

        // NO_WRAP → WRAP
        composeRule.onNodeWithText("自动换行").performClick()
        composeRule.waitForIdle()
        assertEquals(WrapMode.WRAP, settings.wrapMode)
        assertEquals(before, listState.firstVisibleItemIndex)
    }
}
