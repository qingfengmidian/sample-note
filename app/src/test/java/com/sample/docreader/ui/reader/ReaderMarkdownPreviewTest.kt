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
import com.sample.docreader.core.render.MarkdownParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** md 源码/预览切换（TC-F2-19、TC-F2-20）：菜单入口、渲染内容、序号与进度上报隔离 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh")
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
                onFontSizeStep = { _ -> },
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

    /**
     * TC-F2-20（U4）：源码↔预览切换的位置映射——源码 item=段落、预览 item=块，索引不同义；
     * 来回切换后应经块↔段落映射定位到语义一致位置，而非"同序号不同内容"。
     * 前置长文：Robolectric 视口较矮，目标行前后都要有足够内容（前可滚到、后可定位到顶），
     * 故在目标段（代码块内 `# code`）前后各铺一批长填充段，保证滚动空间充足。
     * 结构：A 填充(0..24) + "# 目标标题" + 围栏代码块(`# code`≈29) + "正文尾"(33) + B 填充(34..58)。
     * 验证：源码滚到段落 29（代码块内）→ 预览落到所属 CodeBlock（块 26，≠29，映射非同序号）；
     * 预览滚到"正文尾"块（块 27）→ 回源码落到其起始段落 33（≠27）。
     */
    @Test
    fun md_previewToggle_mapsToSemanticPosition() {
        val filler = (0 until 25).map { "填充段落 $it：" + "这是一段足够长的文字用于撑高列表项，使目标行超出 Robolectric 视口。".repeat(3) }
        val paragraphs = buildList {
            addAll(filler)
            addAll(listOf("", "# 目标标题", "", "```text", "# code", "x", "```", "", "正文尾"))
            addAll(filler)
        }
        val doc = Document(
            "asset:///fixtures/map.md", "map.md", DocFormat.MD,
            listOf(Chapter("map.md", paragraphs))
        )
        val blocks = MarkdownParser.parse(paragraphs)
        val sourcePara = paragraphs.indexOf("# code")
        val tailPara = paragraphs.indexOf("正文尾")
        val listState = setContent(doc)

        // 源码滚到代码块内段落（# code 注释行，位于视口外但距底足够远）
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(sourcePara) } }
        composeRule.waitForIdle()
        assertEquals("源码应滚到段落 $sourcePara，实际首可见 ${listState.firstVisibleItemIndex}",
            sourcePara, listState.firstVisibleItemIndex)

        // 源码 → 预览：该段落属 CodeBlock → 预览应落在所属块；块索引 ≠ 段落索引（语义映射）
        val expectedBlock = MdViewMapping.blockIndexForParagraph(blocks, sourcePara)
        assertTrue("映射应跨坐标（块 ≠ 段落），实际块 $expectedBlock", expectedBlock != sourcePara)
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("预览").performClick()
        composeRule.waitForIdle()
        assertEquals("预览应落在所属 CodeBlock", expectedBlock, listState.firstVisibleItemIndex)

        // 预览滚到"正文尾"块，再切回源码：应落到该块起始段落（≠块序号）
        val tailBlock = MdViewMapping.blockIndexForParagraph(blocks, tailPara)
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(tailBlock) } }
        composeRule.waitForIdle()
        assertEquals(tailBlock, listState.firstVisibleItemIndex)
        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("源码").performClick()
        composeRule.waitForIdle()
        val expectedParagraph = MdViewMapping.paragraphIndexForBlock(blocks, tailBlock)
        assertTrue("预览→源码应落到块起始段落（≠块序号），实际段落 $expectedParagraph",
            expectedParagraph != tailBlock)
        assertEquals(expectedParagraph, listState.firstVisibleItemIndex)
    }
}
