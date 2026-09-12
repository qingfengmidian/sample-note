package com.sample.docreader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sample.docreader.ui.reader.READER_CONTENT_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M11c 设备端验收（F2.15 CSS 文字样式，真机/模拟器 `connectedDebugAndroidTest`）：
 * 打开 styled.epub（<style> + 内联样式）与 bad_css.epub（非法语法/未知选择器混排），
 * 以 Compose UI **语义断言**验证：正常显示、正文可读不崩溃（TC-F2-17e/f，协同规范 §验收）。
 * 字重/斜体/对齐/颜色的视觉观感与夜间主题对比度回退属视觉项，追加到真机清单手工确认。
 */
@RunWith(AndroidJUnit4::class)
class MdCssDeviceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    /** 首页顶栏「测试样本」→ 夹具列表逐页上滑直到目标 epub 行可见并点击 */
    private fun openEpubFixture(name: String) {
        composeTestRule.onNodeWithText("测试样本").performClick()
        composeTestRule.waitForIdle()
        // M12 真机复盘（2026-09-12）：swipeUp 的 fling 会过冲跳过目标条目且只上滑不回退，
        // 尾部夹具（sample_*/styled）间歇性「未找到」；改 performScrollToNode 语义确定性滚动
        // （与 M11b 对正文滚动的同款处理）。
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(name))
        composeTestRule.onNodeWithText(name).performClick()
    }

    private fun waitForText(text: String) {
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 让正文区滚动到带指定文本的节点可见（确定性滚动） */
    private fun scrollUntilText(text: String) {
        composeTestRule.onNodeWithTag(READER_CONTENT_TAG).performScrollToNode(hasText(text))
        composeTestRule.onNodeWithText(text).assertIsDisplayed()
    }

    /**
     * TC-F2-17e 设备端：styled.epub——<style> + 内联样式章正常显示；
     * 首段（h1.big 大字居中）与末段（内联样式后普通段 + 列表项）均渲染可读，不崩溃。
     */
    @Test
    fun styledEpub_opensAndRendersReadable() {
        openEpubFixture("styled.epub")
        waitForText("居中大标题")
        scrollUntilText("居中大标题")
        // 样式段落在列表中可滚动到达：内联样式行
        scrollUntilText("行内样式右对齐蓝字。")
        // 列表项（li 行）也在正文流内
        scrollUntilText("无序列表项一。")
        // 章 2 经章节入口切换属面板交互；此处验证同书多章不崩（正文首段可达即足够）
        composeTestRule.onNodeWithText("居中大标题").assertIsDisplayed()
    }

    /**
     * TC-F2-17f 设备端：bad_css.epub——非法语法/未知选择器混排仍正常打开、正文可读、不崩溃。
     */
    @Test
    fun badCss_opensReadableNoCrash() {
        openEpubFixture("bad_css.epub")
        waitForText("正文第一段可读。")
        scrollUntilText("正文第一段可读。")
        scrollUntilText("非法颜色忽略、正文可读。")
    }
}
