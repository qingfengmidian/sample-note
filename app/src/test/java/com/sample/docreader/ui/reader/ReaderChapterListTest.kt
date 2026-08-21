package com.sample.docreader.ui.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * epub 章节列表（TC-F2-16）：章节列表入口、点击章节回调 0 起索引、
 * 当前章节高亮（selected 语义）、空白标题兜底"第 N 章"。
 * 注：Robolectric 下 AlertDialog 窗口内容不进语义树，列表主体 ChapterListContent 直接驱动。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderChapterListTest {

    @get:Rule
    val composeRule = createComposeRule()

    // 第三章标题为空串 → 兜底"第 3 章"
    private val chapters = listOf(
        Chapter("第一章", listOf("第一章正文")),
        Chapter("第二章", listOf("第二章正文")),
        Chapter("", listOf("第三章正文"))
    )

    /** TC-F2-16：epub 底部工具条有章节列表入口（工具条横向滚动，先滚入视口再断言可见并可点） */
    @Test
    fun epub_toolbar_hasChapterListEntry() {
        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(
                    loading = false,
                    document = Document("asset:///fixtures/sample.epub", "sample.epub", DocFormat.EPUB, chapters)
                ),
                onBack = {},
                onToggleWrap = {},
                onFontSizeStep = { _ -> },
                onCycleLineHeight = {},
                onCycleFont = {},
                onCycleTheme = {}
            )
        }
        composeRule.waitForIdle()
        val entry = composeRule.onNodeWithContentDescription("章节列表")
        entry.assertExists()
        entry.performScrollTo().assertIsDisplayed().performClick()
        // 点击后弹层为 AlertDialog，Robolectric 下不进语义树，不弹崩溃即视为可点；
        // 列表主体行为由下方 ChapterListContent 直接驱动的两个用例覆盖
        composeRule.waitForIdle()
    }

    /** TC-F2-16：点第 2 章 → onSelect 回调索引 1（0 起） */
    @Test
    fun chapterList_clickSecondChapter_callbacksIndex1() {
        var selectedIndex: Int? = null
        composeRule.setContent {
            ChapterListContent(chapters = chapters, currentIndex = 0, onSelect = { selectedIndex = it })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第二章").performClick()
        composeRule.waitForIdle()
        assertEquals(1, selectedIndex)
    }

    /** TC-F2-16：当前章节高亮（selected），空白标题兜底"第 N 章" */
    @Test
    fun chapterList_currentSelectedAndBlankTitleFallback() {
        composeRule.setContent {
            ChapterListContent(chapters = chapters, currentIndex = 1, onSelect = {})
        }
        composeRule.waitForIdle()

        // 空标题兜底为"第 3 章"
        composeRule.onNodeWithText("第 3 章").assertIsDisplayed()
        // 当前章节（第二章）selected，其余未选
        composeRule.onNodeWithText("第二章").assertIsSelected()
        composeRule.onNodeWithText("第一章").assertIsNotSelected()
        composeRule.onNodeWithText("第 3 章").assertIsNotSelected()
    }
}
