package com.sample.docreader.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** md 源码/预览切换（TC-F2-19、TC-F2-20）：菜单入口、渲染内容、序号与进度上报隔离 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderMarkdownPreviewTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val mdDocument = Document(
        "asset:///fixtures/sample.md", "sample.md", DocFormat.MD,
        listOf(
            Chapter(
                "sample.md",
                listOf("# 标题一", "", "2. 第二项", "3. 第三项", "- 无序项", "", "正文段落")
            )
        )
    )

    private fun setContent(
        document: Document,
        onFirstVisibleParagraphChanged: (Int) -> Unit = {}
    ): LazyListState {
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState()
            ReaderContent(
                state = ReaderUiState(loading = false, document = document),
                onBack = {},
                onToggleWrap = {},
                onFontSizeStep = { _ -> },
                onCycleLineHeight = {},
                onCycleFont = {},
                onCycleTheme = {},
                listState = listState,
                onFirstVisibleParagraphChanged = onFirstVisibleParagraphChanged
            )
        }
        composeRule.waitForIdle()
        return listState
    }

    /** TC-F2-19：md 菜单含"预览"入口，切换后标题渲染（去掉 # 前缀）且有序列表输出真实序号 */
    @Test
    fun md_previewMenu_rendersBlocksWithRealNumbers() {
        setContent(mdDocument)

        // 初始为源码视图：原文段落原样显示
        composeRule.onNodeWithText("# 标题一").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("预览").performClick()
        composeRule.waitForIdle()

        // 预览视图：标题去掉 "#"，有序列表渲染真实序号（2./3. 而非硬编码 1.）
        composeRule.onNodeWithText("标题一").assertIsDisplayed()
        composeRule.onNodeWithText("2. ").assertIsDisplayed()
        composeRule.onNodeWithText("3. ").assertIsDisplayed()
    }

    /** TC-F2-20：预览 ↔ 源码来回切换，内容一致 */
    @Test
    fun md_previewToggle_backToSource() {
        setContent(mdDocument)

        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("预览").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("标题一").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("源码").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("# 标题一").assertIsDisplayed()
        composeRule.onNodeWithText("2. 第二项").assertIsDisplayed()
    }

    /** TC-F2-19：xml 无预览入口 */
    @Test
    fun xml_menu_hasNoPreviewEntry() {
        val xmlDocument = Document(
            "asset:///fixtures/sample.xml", "sample.xml", DocFormat.XML,
            listOf(Chapter("sample.xml", listOf("<contacts>", "  <contact>", "</contacts>")))
        )
        setContent(xmlDocument)

        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("预览").assertDoesNotExist()
    }

    /** TC-F2-18/20（M6 评审修复）：预览模式下滚动不上报段落进度（块索引≠段落索引，防止污染进度） */
    @Test
    fun md_previewMode_doesNotReportParagraphProgress() {
        val reported = mutableListOf<Int>()
        val listState = setContent(mdDocument, onFirstVisibleParagraphChanged = { reported += it })

        // 源码模式初始上报（首可见段落 0），清空后进入预览
        reported.clear()

        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("预览").performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            runBlocking { listState.scrollToItem(1) }
        }
        composeRule.waitForIdle()

        assertTrue("预览模式不应上报段落进度，实际上报：$reported", reported.isEmpty())
    }
}
