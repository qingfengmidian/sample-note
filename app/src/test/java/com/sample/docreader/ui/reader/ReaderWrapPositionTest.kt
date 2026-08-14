package com.sample.docreader.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.robolectric.annotation.GraphicsMode

/** TC-UI-01：长文档滚动至中部 → 切换 WRAP/NO_WRAP → 首可见段落索引不变 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
                onToggleWrap = {
                    settings = settings.copy(
                        wrapMode = if (settings.wrapMode == WrapMode.WRAP) WrapMode.NO_WRAP else WrapMode.WRAP
                    )
                },
                onFontSizeStep = { _ -> },
                onCycleLineHeight = {},
                onCycleFont = {},
                onCycleTheme = {},
                listState = listState
            )
        }
        composeRule.waitForIdle()

        // 滚动到中部
        runBlocking { listState.scrollToItem(200) }
        composeRule.waitForIdle()
        val before = listState.firstVisibleItemIndex
        assertEquals(200, before)

        // WRAP → NO_WRAP
        composeRule.onNodeWithContentDescription("切换换行模式").performClick()
        composeRule.waitForIdle()
        assertEquals(WrapMode.NO_WRAP, settings.wrapMode)
        assertEquals(before, listState.firstVisibleItemIndex)

        // NO_WRAP → WRAP
        composeRule.onNodeWithContentDescription("切换换行模式").performClick()
        composeRule.waitForIdle()
        assertEquals(WrapMode.WRAP, settings.wrapMode)
        assertEquals(before, listState.firstVisibleItemIndex)
    }
}
