package com.sample.docreader

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sample.docreader.ui.reader.READER_PROGRESS_TAG
import org.junit.Assert.assertNotEquals
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
        repeat(12) {
            if (composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()) {
                composeTestRule.onNodeWithText(name).performClick()
                return
            }
            composeTestRule.onRoot().performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()
        }
        throw AssertionError("夹具列表中未找到 $name")
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
        listOf("封面页", "第一节", "第二节", "未命中锚点", "中间锚点").forEach {
            composeTestRule.onNodeWithText(it).assertIsDisplayed()
        }
        composeTestRule.onNodeWithText("第 4 章").assertDoesNotExist()

        // 点击锚点条目「第一节」（p1#sec1 → 段落 1）→ 面板关闭并定位到锚点段落
        composeTestRule.onNodeWithText("第一节").performClick()
        composeTestRule.waitForIdle()
        waitForText("第一节锚点段。")
    }

    /**
     * TC-F4-09/10 设备端：many_chapters.epub（>100 章）全书进度条存在；
     * 拖动到末尾后语义描述（第 x/y 段）发生变化（位置变化），未崩溃。
     */
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
