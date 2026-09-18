package com.sample.docreader.ui.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * md 目录入口与目录面板（F2.13，TC-F2-20a/20b，M11a）：
 * - 顶栏「目录」图标：md 且 toc 非空时出现；toc 为空 / 非 md 隐藏（TC-F2-20b）；
 * - 面板主体 TocSheetContent / TocListContent 直驱：目录项 selected 语义、点击回调携带对应目录项。
 * 注：Robolectric 下 ModalBottomSheet 内容不进语义树，面板主体抽出为无状态 composable 直驱（M10 立的模式）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh")
class ReaderTocTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val toc = listOf(
        TocEntry(1, "标题一", 0),
        TocEntry(2, "标题二", 3),
        TocEntry(3, "标题三", 5)
    )

    private val mdDocument = Document(
        "asset:///fixtures/sample_toc.md", "sample_toc.md", DocFormat.MD,
        listOf(
            Chapter(
                null,
                // 段落索引须与 toc 锚点一致：H1@0、H2@3、H3@5
                listOf("# 标题一", "", "", "## 标题二", "", "### 标题三", "正文段落", "结尾")
            )
        ),
        toc = toc
    )

    private fun setReaderContent(document: Document) {
        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(loading = false, document = document),
                onBack = {},
                onFontSizeStep = { _ -> }
            )
        }
        composeRule.waitForIdle()
    }

    /** TC-F2-20a：md 且 toc 非空时顶栏有「目录」入口，点击打开面板（弹层不进语义树，不崩溃即可） */
    @Test
    fun md_topbar_showsTocEntry_whenTocNotEmpty() {
        setReaderContent(mdDocument)
        val entry = composeRule.onNodeWithContentDescription("目录")
        entry.assertExists().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
    }

    /** TC-F2-20b：toc 为空的 md（无标题文档）不显示「目录」图标 */
    @Test
    fun md_topbar_hidesTocEntry_whenTocEmpty() {
        val noTitle = Document(
            "asset:///fixtures/sample_utf8.txt", "no-title.md", DocFormat.MD,
            listOf(Chapter(null, listOf("仅正文，无标题", "第二段"))),
            toc = emptyList()
        )
        setReaderContent(noTitle)
        composeRule.onNodeWithContentDescription("目录").assertDoesNotExist()
    }

    /** 非 md（epub/xml/txt）不显示「目录」图标（epub 另有「章节列表」图标，互不干扰） */
    @Test
    fun nonMd_hasNoTocEntry() {
        val txt = Document(
            "asset:///fixtures/sample_utf8.txt", "sample.txt", DocFormat.TXT,
            listOf(Chapter(null, listOf("行一", "行二")))
        )
        setReaderContent(txt)
        composeRule.onNodeWithContentDescription("目录").assertDoesNotExist()
    }

    /** TC-F2-20a：目录面板标题存在，目录项点击回调携带对应 TocEntry（含层级缩进数据） */
    @Test
    fun tocSheet_clickEntry_carriesEntry() {
        var selected: TocEntry? = null
        composeRule.setContent {
            TocSheetContent(
                toc = toc,
                selectedIndex = 0,
                accent = ReadingTheme.DEFAULT.foreground, // M13：本类不断言颜色，取中性值
                onSelect = { selected = it }
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("目录").assertIsDisplayed()
        composeRule.onNodeWithText("标题三").performClick()
        composeRule.waitForIdle()
        assertEquals(TocEntry(3, "标题三", 5), selected)
    }

    /** TC-F2-20a：目录项高亮规则——当前标题 selected 语义、其余未选（S11：-1 时无高亮） */
    @Test
    fun tocList_selectedSemantics() {
        composeRule.setContent {
            TocListContent(
                toc = toc,
                selectedIndex = 1,
                onSelect = {},
                accent = ReadingTheme.DEFAULT.foreground // M13：本类不断言颜色，取中性值
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("标题二").assertIsSelected()
        composeRule.onNodeWithText("标题一").assertIsNotSelected()
        composeRule.onNodeWithText("标题三").assertIsNotSelected()
    }

    /** TC-F2-20a/S11：selectedIndex = -1（首可见段在第一个标题之前）→ 无任何高亮项 */
    @Test
    fun tocList_noHighlightWhenSelectedIndexMinusOne() {
        composeRule.setContent {
            TocListContent(
                toc = toc,
                selectedIndex = -1,
                onSelect = {},
                accent = ReadingTheme.DEFAULT.foreground // M13：本类不断言颜色，取中性值
            )
        }
        composeRule.waitForIdle()
        listOf("标题一", "标题二", "标题三").forEach {
            composeRule.onNodeWithText(it).assertIsNotSelected()
        }
    }
}
