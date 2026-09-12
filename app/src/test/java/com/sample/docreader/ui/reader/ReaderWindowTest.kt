package com.sample.docreader.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.TocEntry
import com.sample.docreader.core.parse.EpubParser
import com.sample.docreader.core.parse.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * M12c（F4.7/F4.8/F2.16）UI 层用例（TC-UI-10/11/12 的 Robolectric 底座，参照
 * ReaderWrapPositionTest 的「外部注入 LazyListState + scrollToItem 直驱」手法）：
 * 1. 物化追加（TC-UI-10）：滚近已物化尾部 → materializedChapterCount 递增；末章后不再增长；
 * 2. 进度条（TC-UI-11）：比例 = index/(totalItems-1)；拖动触发目标章物化；
 * 3. 恢复：restoreItem 一次性消费并滚动到全局 item；
 * 4. F2.16 面板：anchor_toc.epub 条目 = NCX 原样，锚点条目换算目标正确；no_nav.epub 无入口；
 * 5. R3 顶栏：未编入目录的章节标题留空（不回落书名/「第 N 章」）。
 * 回归类用例见 ReaderMarkdownPreviewTest/ReaderTocTest/ReaderChapterListTest/ReaderWrapPositionTest 等。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderWindowTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 合成多章文档：每章 paragraphsPerChapter 段，便于精确控制 PREFETCH_MARGIN 阈值 */
    private fun multiChapter(chapterCount: Int, paragraphsPerChapter: Int): Document =
        Document(
            "asset:///fixtures/window.epub", "window.epub", DocFormat.EPUB,
            (0 until chapterCount).map { c ->
                Chapter("第 ${c + 1} 章", List(paragraphsPerChapter) { p -> "章 $c 段 $p" })
            }
        )

    /** 1. TC-UI-10：滚近物化尾部追加下一章，末章停止 */
    @Test
    fun appendNextChapter_onNearTail_thenStops() {
        val doc = multiChapter(chapterCount = 3, paragraphsPerChapter = 100)
        val listState = LazyListState()
        var state by mutableStateOf(
            ReaderUiState(loading = false, document = doc, materializedChapterCount = 1, totalItems = 300)
        )
        composeRule.setContent {
            ReaderContent(
                state = state,
                onBack = {},
                onFontSizeStep = { _ -> },
                listState = listState,
                onAppendNextChapter = {
                    state = state.copy(
                        materializedChapterCount = (state.materializedChapterCount + 1).coerceAtMost(3)
                    )
                }
            )
        }
        composeRule.waitForIdle()
        assertEquals("初始只物化第 1 章", 1, state.materializedChapterCount)

        // 第 1 章尾部附近（item 60 ≥ 100-50）→ 追加第 2 章
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(60) } }
        composeRule.waitForIdle()
        assertEquals("滚近尾部应追加第 2 章", 2, state.materializedChapterCount)

        // 第 2 章尾部（items=200，阈值 150）→ 追加第 3 章
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(195) } }
        composeRule.waitForIdle()
        assertEquals("滚近尾部应追加第 3 章", 3, state.materializedChapterCount)

        // 末章尾部：不再增长（materialized == 章数）
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(299) } }
        composeRule.waitForIdle()
        assertEquals("末章后不再追加", 3, state.materializedChapterCount)
    }

    /** 2. TC-UI-11：进度条比例与拖动物化 */
    @Ignore("F4.8 进度条真机反馈观感不佳已暂时隐藏（2026-09-12），组件保留；恢复渲染时同步移除 @Ignore")
    @Test
    fun progressBar_fractionAndSeekMaterializesTargetChapter() {
        // 纯函数：比例 = index/(totalItems-1)，target = round(fraction*(total-1))
        assertEquals(0.5f, progressFraction(150, 301), 1e-4f)
        assertEquals(0f, progressFraction(0, 300), 1e-4f)
        assertEquals(1f, progressFraction(299, 300), 1e-4f)
        assertEquals(0f, progressFraction(5, 1), 1e-4f) // totalItems <= 1 不除零
        assertEquals(0, progressTargetItem(0f, 300))
        assertEquals(299, progressTargetItem(1f, 300))
        assertEquals(150, progressTargetItem(0.5f, 301))
        assertEquals(0, progressTargetItem(0.5f, 0)) // 空书不越界

        val doc = multiChapter(chapterCount = 3, paragraphsPerChapter = 100)
        val listState = LazyListState()
        var state by mutableStateOf(
            ReaderUiState(loading = false, document = doc, materializedChapterCount = 1, totalItems = 300)
        )
        val ensured = mutableListOf<Int>()
        composeRule.setContent {
            ReaderContent(
                state = state,
                onBack = {},
                onFontSizeStep = { _ -> },
                listState = listState,
                onEnsureMaterialized = { chapter ->
                    ensured += chapter
                    state = state.copy(
                        materializedChapterCount = maxOf(
                            state.materializedChapterCount,
                            (chapter + 1).coerceAtMost(3)
                        )
                    )
                },
                onAppendNextChapter = {}
            )
        }
        composeRule.waitForIdle()

        // 物化窗口=1（仅 100 item）时 item 50 可达；比例 = firstVisible/(totalItems-1)
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(50) } }
        composeRule.waitForIdle()
        val node = composeRule.onNodeWithTag(READER_PROGRESS_TAG).fetchSemanticsNode()
        assertEquals(
            "比例应为 index/(totalItems-1)",
            progressFraction(listState.firstVisibleItemIndex, 300),
            node.config.getOrNull(ReaderProgressFractionKey)!!,
            1e-4f
        )
        composeRule.onNodeWithContentDescription("阅读进度，第 ${listState.firstVisibleItemIndex + 1}/300 段")
            .assertExists()

        // 拖动到末尾 → 目标 item 落在第 3 章 → 触发物化
        composeRule.onNodeWithTag(READER_PROGRESS_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertTrue("拖动末尾应物化目标章，实际 ensured=$ensured", ensured.contains(2))
        assertEquals("物化窗口应扩到第 3 章", 3, state.materializedChapterCount)
    }

    /** 3. 恢复：restoreItem 一次性消费并滚动到该全局 item */
    @Test
    fun restoreItem_scrollsToGlobalItem_andConsumes() {
        val doc = multiChapter(chapterCount = 3, paragraphsPerChapter = 100)
        val listState = LazyListState()
        val target = ChapterItemMap(doc.chapters).globalItem(chapter = 2, paragraph = 5) // 205
        var consumed = false
        var state by mutableStateOf(
            ReaderUiState(
                loading = false, document = doc, chapterIndex = 2,
                materializedChapterCount = 3, totalItems = 300,
                restoreParagraph = 5, restoreItem = target
            )
        )
        composeRule.setContent {
            ReaderContent(
                state = state,
                onBack = {},
                onFontSizeStep = { _ -> },
                listState = listState,
                onRestoreItemConsumed = {
                    consumed = true
                    state = state.copy(restoreItem = null)
                },
                onRestoreParagraphConsumed = { state = state.copy(restoreParagraph = null) }
            )
        }
        composeRule.waitForIdle()
        assertTrue("restoreItem 应被一次性消费", consumed)
        assertEquals("应滚动到恢复全局 item", 205, listState.firstVisibleItemIndex)
    }

    /** 4a. F2.16：anchor_toc.epub 目录 = NCX 原样，锚点条目换算全局 item 正确 */
    @Test
    fun epubNativeToc_panelRendersNcxEntries_andAnchorTarget() {
        val doc = runBlocking { EpubParser().parseBytes(Fixtures.bytes("anchor_toc.epub"), "anchor_toc.epub") }
        assertEquals(
            listOf("封面页", "第一节", "第二节", "未命中锚点", "中间锚点"),
            doc.toc.map { it.title }
        )
        assertEquals("层级 = navPoint 嵌套", listOf(1, 1, 2, 1, 1), doc.toc.map { it.level })

        var selected: TocEntry? = null
        composeRule.setContent {
            ChapterSheetContent(
                toc = doc.toc,
                selectedIndex = -1,
                onSelect = { selected = it },
                onPrev = {},
                onNext = {},
                prevEnabled = false,
                nextEnabled = true
            )
        }
        composeRule.waitForIdle()
        doc.toc.forEach { composeRule.onNodeWithText(it.title).assertExists() }
        // 无「第 N 章」生成条目
        composeRule.onNodeWithText("第 4 章").assertDoesNotExist()

        composeRule.onNodeWithText("中间锚点").performClick()
        composeRule.waitForIdle()
        assertEquals(4, selected!!.chapterIndex)
        assertEquals(1, selected!!.anchorParagraph)
        // 锚点条目换算的全局 item：p4 起始偏移 9 + 锚点段 1 = 10
        assertEquals(10, ChapterItemMap(doc.chapters).globalItem(selected!!.chapterIndex, selected!!.anchorParagraph!!))
    }

    /** 4b. F2.16：无目录 epub（no_nav）顶栏无「章节列表」入口 */
    @Test
    fun noNavEpub_hidesChapterEntry() {
        val doc = runBlocking { EpubParser().parseBytes(Fixtures.bytes("no_nav.epub"), "no_nav.epub") }
        assertTrue("no_nav 应无目录", doc.toc.isEmpty())
        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(loading = false, document = doc),
                onBack = {},
                onFontSizeStep = { _ -> }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("章节列表").assertDoesNotExist()
    }

    /** 4c. epub 目录当前高亮：取最后一个不晚于当前阅读位置的条目；章首之前无高亮（-1） */
    @Test
    fun epubTocSelectedIndex_picksLastAtOrBeforePosition() {
        val toc = listOf(
            TocEntry(1, "A", 0, chapterIndex = 0, anchorParagraph = 0),
            TocEntry(1, "B", 0, chapterIndex = 1, anchorParagraph = 1),
            TocEntry(2, "B2", 0, chapterIndex = 1, anchorParagraph = 3),
            TocEntry(1, "C", 0, chapterIndex = 2, anchorParagraph = 0)
        )
        assertEquals(0, epubTocSelectedIndex(toc, 0, 0))
        assertEquals(0, epubTocSelectedIndex(toc, 0, 5))
        assertEquals(1, epubTocSelectedIndex(toc, 1, 1))
        assertEquals(2, epubTocSelectedIndex(toc, 1, 3))
        assertEquals(3, epubTocSelectedIndex(toc, 2, 0))
        // 第一个条目之前无高亮（-1）
        val laterToc = listOf(TocEntry(1, "B", 0, chapterIndex = 1, anchorParagraph = 0))
        assertEquals(-1, epubTocSelectedIndex(laterToc, 0, 0))
    }

    /** 5. R3：未编入目录的章节顶栏标题留空（不回落书名，也不出现「第 N 章」） */
    @Test
    fun topbarTitle_emptyForChapterWithoutTocEntry() {
        val doc = runBlocking { EpubParser().parseBytes(Fixtures.bytes("anchor_toc.epub"), "anchor_toc.epub") }
        var state by mutableStateOf(
            ReaderUiState(
                loading = false, document = doc, chapterIndex = 3,
                materializedChapterCount = 4, totalItems = 11
            )
        )
        composeRule.setContent {
            ReaderContent(
                state = state,
                onBack = {},
                onFontSizeStep = { _ -> }
            )
        }
        composeRule.waitForIdle()
        // p3 未编入 NCX（title=null）→ 顶栏空
        composeRule.onNodeWithText("anchor_toc.epub").assertDoesNotExist()
        composeRule.onNodeWithText("第 4 章").assertDoesNotExist()

        // 编入目录的章显示目录标题（顶栏与正文同名时取其一即可，断言至少存在）
        composeRule.runOnIdle { state = state.copy(chapterIndex = 1) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第一节").assertExists()
        assertFalse(doc.chapters[3].title?.isNotBlank() == true)
    }

    /** 4d. 目录面板条目点击触发锚点跳转（直接驱动 onSelect 的换算，见 ReaderWindowDeviceTest 真窗口验收） */
    @Test
    fun epubTocEntry_clickInvokesOnSelect() {
        val doc = runBlocking { EpubParser().parseBytes(Fixtures.bytes("anchor_toc.epub"), "anchor_toc.epub") }
        val picked = mutableListOf<TocEntry>()
        composeRule.setContent {
            ChapterSheetContent(
                toc = doc.toc,
                selectedIndex = 0,
                onSelect = { picked += it },
                onPrev = {},
                onNext = {},
                prevEnabled = true,
                nextEnabled = true
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第二节").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, picked.size)
        assertEquals("第二节", picked[0].title)
        assertEquals(3, picked[0].anchorParagraph)
    }
}
