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
 * epub 章节列表（TC-F2-16/TC-UI-07，M10 F4.5）：章节列表入口（顶栏图标）、点击章节回调 0 起索引、
 * 当前章节高亮（selected 语义）、空白标题兜底"第 N 章"；章节面板底部上一章/下一章翻章。
 * 注：Robolectric 下弹层（AlertDialog/ModalBottomSheet）窗口内容不进语义树，列表主体 ChapterListContent
 * 与面板主体 ChapterSheetContent 直接驱动。
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

    /** TC-F2-16（M10 F4.5）：epub 顶栏有章节列表入口图标（原底部工具条按钮已移除，入口上移顶栏） */
    @Test
    fun epub_topbar_hasChapterListEntry() {
        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(
                    loading = false,
                    document = Document("asset:///fixtures/sample.epub", "sample.epub", DocFormat.EPUB, chapters)
                ),
                onBack = {},
                onFontSizeStep = { _ -> }
            )
        }
        composeRule.waitForIdle()
        val entry = composeRule.onNodeWithContentDescription("章节列表")
        // 顶栏常驻图标无需滚动（原底部工具条可横向滚动，M10 已移除）
        entry.assertExists().assertIsDisplayed().performClick()
        // 点击后弹层为 ModalBottomSheet，Robolectric 下不进语义树，不弹崩溃即视为可点；
        // 列表/面板主体行为由下方直接驱动的用例覆盖（TC-UI-07）
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

    /**
     * TC-UI-07（M10 F4.5）：章节面板主体 ChapterSheetContent 直驱——
     * 上一章/下一章按钮存在且点击触发回调；当前章节 selected 语义保留（ChapterListContent 既有用例不回退）。
     */
    @Test
    fun chapterSheet_hasPrevNext_andCurrentSelected() {
        var prevCalls = 0
        var nextCalls = 0
        val selects = mutableListOf<Int>()
        composeRule.setContent {
            ChapterSheetContent(
                chapters = chapters,
                currentIndex = 1,
                onSelect = { selects += it },
                onPrev = { prevCalls++ },
                onNext = { nextCalls++ }
            )
        }
        composeRule.waitForIdle()

        // 当前章节（第二章）selected 语义保留，非当前章未选
        composeRule.onNodeWithText("第二章").assertIsSelected()
        composeRule.onNodeWithText("第一章").assertIsNotSelected()

        // 上一章/下一章按钮存在；currentIndex=1 时首末章均在界内，两按钮可点且触发各自回调
        composeRule.onNodeWithContentDescription("上一章").assertExists().performClick()
        composeRule.onNodeWithContentDescription("下一章").assertExists().performClick()
        composeRule.waitForIdle()
        assertEquals(1, prevCalls)
        assertEquals(1, nextCalls)

        // 面板内点章节仍触发 onSelect（面板内翻章后可继续选章节，F4.5）
        composeRule.onNodeWithText("第 3 章").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(2), selects)
    }
}
