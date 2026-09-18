package com.sample.docreader.ui.reader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.parse.EpubParser
import com.sample.docreader.core.parse.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F2.14 渲染回退路径（Robolectric，imageRoot=null 即缓存不可用/整字节解析）：
 * epub 图片一律走占位（svg/缺失/解码不可行统一占位，语义带 alt 或默认文案，评审 S9），
 * 不崩溃、正文文本仍渲染。真实位图解码（BitmapFactory）路径由设备端 MdImageDeviceTest 验收。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh")
class ReaderImageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setDoc(document: Document) {
        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(loading = false, document = document),
                onBack = {},
                onFontSizeStep = { _ -> }
            )
        }
        composeRule.waitForIdle()
    }

    /** broken_image.epub：缺失条目图占位带 alt（"失联配图"），截断位图占位带默认语义；正文仍在 */
    @Test
    fun brokenImages_renderPlaceholders_textIntact() {
        val doc = runBlocking {
            EpubParser().parseBytes(Fixtures.bytes("broken_image.epub"), "broken_image.epub")
        }
        setDoc(doc)
        composeRule.onNodeWithContentDescription("失联配图").assertExists()
        composeRule.onNodeWithContentDescription("图片无法显示").assertExists()
        composeRule.onNodeWithText("正文仍在的首段。").assertExists()
        assertTrue(doc.format == DocFormat.EPUB)
    }

    /** 无图 epub（混合编码单章）：正文正常渲染、无任何图片占位节点 */
    @Test
    fun imageFreeEpub_noPlaceholderNodes() {
        val doc = runBlocking {
            EpubParser().parseBytes(Fixtures.bytes("mixed_encoding.epub"), "mixed_encoding.epub")
        }
        setDoc(doc)
        composeRule.onNodeWithText("这是 GBK 编码的章节内容。").assertExists()
        assertTrue(
            "无图书不应出现图片占位",
            composeRule.onAllNodesWithContentDescription("图片无法显示").fetchSemanticsNodes().isEmpty()
        )
    }

    /** 封面（无缓存解码路径）：isCover 占位渲染于正文之上，正文段落不被吞 */
    @Test
    fun coverFallback_placeholderAboveText() {
        val doc = runBlocking {
            EpubParser().parseBytes(Fixtures.bytes("sample_cover.epub"), "sample_cover.epub")
        }
        setDoc(doc)
        composeRule.onNodeWithContentDescription("图片无法显示").assertExists()
        composeRule.onNodeWithText("封面之后的第一段文字。").assertExists()
    }
}
