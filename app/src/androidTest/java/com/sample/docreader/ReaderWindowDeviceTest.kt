package com.sample.docreader

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sample.docreader.ui.reader.READER_PROGRESS_TAG
import org.junit.Assert.assertNotEquals
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M12c 设备端验收（TC-UI-12，F2.16/F4.7/F4.8，真机/模拟器 `connectedDebugAndroidTest`）：
 * 真实导航打开 epub → 目录面板条目与 NCX 一致 + 点击锚点条目定位准确；全书进度条存在且拖动后位置变化。
 * 面板/进度条类交互以设备端语义断言为验收手段（协同规范 §验收；Robolectric 弹层内容不进语义树）。
 */
@RunWith(AndroidJUnit4::class)
class ReaderWindowDeviceTest {

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

    /** 读取进度条 contentDescription（语义断言，避免像素截图） */
    private fun progressDescription(): String {
        val node = composeTestRule.onNodeWithTag(READER_PROGRESS_TAG).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: ""
    }

    /**
     * TC-F2-25/26 设备端：anchor_toc.epub 目录面板 = NCX 原样（5 条，含层级/锚点），
     * 无「第 N 章」生成条目；点击带锚点的「第一节」条目后定位到锚点段落。
     */
    @Test
    fun anchorToc_panelMatchesNcx_andAnchorJump() {
        openEpubFixture("anchor_toc.epub")
        waitForText("封面页段落。")

        composeTestRule.onNodeWithContentDescription("章节列表").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        // 条目断言限定在面板列表内（hasAnyAncestor(hasScrollAction())）——顶栏当前章标题可能
        // 与首个条目同名（「封面页」章即封面页条目），全树断言会命中 2 个节点（真机复盘 2026-09-12）。
        listOf("封面页", "第一节", "第二节", "未命中锚点", "中间锚点").forEach {
            composeTestRule.onNode(hasText(it) and hasAnyAncestor(hasScrollAction())).assertIsDisplayed()
        }
        composeTestRule.onNodeWithText("第 4 章").assertDoesNotExist()

        // 点击锚点条目「第一节」（p1#sec1 → 段落 1）→ 面板关闭并定位到锚点段落
        composeTestRule.onNode(hasText("第一节") and hasAnyAncestor(hasScrollAction())).performClick()
        composeTestRule.waitForIdle()
        waitForText("第一节锚点段。")
    }

    /**
     * TC-F4-09/10 设备端：many_chapters.epub（>100 章）全书进度条存在；
     * 拖动到末尾后语义描述（第 x/y 段）发生变化（位置变化），未崩溃。
     */
    @Ignore("F4.8 进度条真机反馈观感不佳已暂时隐藏（2026-09-12），组件保留；恢复渲染时同步移除 @Ignore")
    @Test
    fun progressBar_exists_andSeekChangesPosition() {
        openEpubFixture("many_chapters.epub")
        waitForText("第 1 章内容。")

        composeTestRule.onNodeWithTag(READER_PROGRESS_TAG).assertIsDisplayed()
        val before = progressDescription()
        composeTestRule.onNodeWithTag(READER_PROGRESS_TAG).performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        val after = progressDescription()
        assertNotEquals("拖动进度条后位置语义应变化（before=$before after=$after）", before, after)
    }
}
