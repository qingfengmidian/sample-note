package com.sample.docreader.ui.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * epub 章节/目录面板（F2.16，M12c；取代 M10 的 spine 逐章列表）：
 * - 数据源 = `Document.toc`（文件自带目录原样，**无「第 N 章」生成条目**），条目层级缩进 / selected 语义
 *   复用 [TocListContent]（TC-UI-07 的交互契约保留，断言对象改为目录条目）；
 * - 顶栏「章节列表」入口仅在 toc 非空时出现（无目录 epub 隐藏，F2.16/TC-F2-27）；
 * - 面板底部保留上一章/下一章翻章（按 spine，与目录有无无关，F4.5/F4.7）。
 * 注：Robolectric 下弹层（ModalBottomSheet）窗口内容不进语义树，面板主体 [ChapterSheetContent] 直接驱动。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderChapterListTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val toc = listOf(
        TocEntry(1, "第一章", 0, chapterIndex = 0, anchorParagraph = 0),
        TocEntry(1, "第二章", 0, chapterIndex = 1, anchorParagraph = 0),
        TocEntry(2, "第二章之一", 0, chapterIndex = 1, anchorParagraph = 2),
        TocEntry(1, "第三章", 0, chapterIndex = 2, anchorParagraph = 0)
    )

    private val chapters = listOf(
        Chapter("第一章", listOf("第一章正文")),
        Chapter("第二章", listOf("第二章正文")),
        Chapter(null, listOf("第三章正文"))
    )

    private fun epubDocument(toc: List<TocEntry> = emptyList()) = Document(
        "asset:///fixtures/anchor_toc.epub", "anchor_toc.epub", DocFormat.EPUB, chapters, toc = toc
    )

    /** F2.16/TC-F2-25：toc 非空时 epub 顶栏有「章节列表」入口，可点击打开面板（弹层不进语义树，不崩溃即可） */
    @Test
    fun epub_topbar_showsChapterEntry_whenTocNotEmpty() {
        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(loading = false, document = epubDocument(toc)),
                onBack = {},
                onFontSizeStep = { _ -> }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("章节列表")
            .assertExists().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
    }

    /** F2.16/TC-F2-27：无目录 epub（toc 空）隐藏「章节列表」入口，不提供生成式兜底 */
    @Test
    fun epub_topbar_hidesChapterEntry_whenTocEmpty() {
        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(loading = false, document = epubDocument(emptyList())),
                onBack = {},
                onFontSizeStep = { _ -> }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("章节列表").assertDoesNotExist()
    }

    /** F2.16：面板条目 = 文件自带目录原样（标题/层级），点击回调携带对应 TocEntry（含章坐标/锚点） */
    @Test
    fun chapterSheet_clickEntry_carriesEntry() {
        var selected: TocEntry? = null
        composeRule.setContent {
            ChapterSheetContent(
                toc = toc,
                selectedIndex = 0,
                onSelect = { selected = it },
                onPrev = {},
                onNext = {},
                prevEnabled = false,
                nextEnabled = true
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第三章").performClick()
        composeRule.waitForIdle()
        assertEquals(TocEntry(1, "第三章", 0, chapterIndex = 2, anchorParagraph = 0), selected)
    }

    /** F2.16：当前目录条目高亮（selected 语义），其余未选 */
    @Test
    fun chapterSheet_selectedSemantics() {
        composeRule.setContent {
            ChapterSheetContent(
                toc = toc,
                selectedIndex = 2,
                onSelect = {},
                onPrev = {},
                onNext = {},
                prevEnabled = true,
                nextEnabled = true
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第二章之一").assertIsSelected()
        composeRule.onNodeWithText("第一章").assertIsNotSelected()
    }

    /**
     * TC-UI-07（F4.5/F4.7）：面板底部上一章/下一章翻章按钮存在、点击触发回调；
     * 边界章置灰由 prevEnabled/nextEnabled 表达（首章禁上一章、末章禁下一章）。
     */
    @Test
    fun chapterSheet_hasPrevNext_andEnabledFlags() {
        var prevCalls = 0
        var nextCalls = 0
        composeRule.setContent {
            ChapterSheetContent(
                toc = toc,
                selectedIndex = 0,
                onSelect = {},
                onPrev = { prevCalls++ },
                onNext = { nextCalls++ },
                prevEnabled = false,
                nextEnabled = true
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("上一章").assertExists().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("下一章").assertExists().assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(0, prevCalls)
        assertEquals(1, nextCalls)
    }
}
