package com.sample.docreader

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M11a 设备端验收（TC-UI-08/09，F2.13 md 目录 + U4 视图切换，真机/模拟器 `connectedDebugAndroidTest`）：
 * 与 Robolectric 单测（ReaderTocTest/MdViewMappingTest）互补——本套用例走真实窗口与导航，断言目录
 * 面板的展示/关闭、顶栏图标显隐、预览视图下目录可用。
 *
 * 语义断言取整串精确匹配（onNodeWithText 默认 substring=false），源码视图正文与面板标题同屏时
 * 以"是否带 # 前缀"区分；"代码注释不进目录/层级/selected"等纯解析与列表语义由单测覆盖（防滚动/弹层
 * 遮挡导致的设备端抖动，见 docs/collab/reviews/m11a-acceptance.md 遗留说明）。
 */
@RunWith(AndroidJUnit4::class)
class MdTocDeviceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    /** 首页顶栏 → 「测试样本」夹具入口；夹具列表为 LazyColumn，逐页上滑直到目标行可见。 */
    private fun openFixture(name: String) {
        composeTestRule.onNodeWithText("测试样本").performClick()
        composeTestRule.waitForIdle()
        repeat(10) {
            if (composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()) {
                composeTestRule.onNodeWithText(name).performClick()
                return
            }
            composeTestRule.onRoot().performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()
        }
        throw AssertionError("夹具列表中未找到 $name")
    }

    private fun waitTextGone(text: String) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    /** TC-F2-20a 设备端回归：目录面板展示（H1~H2B 完整）、点条目后面板关闭、正文仍在。 */
    @Test
    fun tocPanel_opens_showsHeadings_selectCloses() {
        openFixture("sample_toc.md")
        // 等解析完成：源码视图首段可见（带 # 前缀）
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("# 标题一（H1）").fetchSemanticsNodes().isNotEmpty()
        }
        // 顶栏目录图标存在且可点
        composeTestRule.onNodeWithContentDescription("目录").assertIsDisplayed().performClick()
        // 面板打开：无 # 前缀的标题行（仅面板持有该精确串）
        composeTestRule.onNodeWithText("标题一（H1）").assertIsDisplayed()
        composeTestRule.onNodeWithText("标题五（H5）").assertIsDisplayed()
        composeTestRule.onNodeWithText("结尾标题（H2B）").assertIsDisplayed()
        // 点击「标题五（H5）」→ 面板关闭、回到源码正文
        composeTestRule.onNodeWithText("标题五（H5）").performClick()
        waitTextGone("标题五（H5）")
        composeTestRule.onNodeWithText("# 标题一（H1）").assertIsDisplayed()
    }

    /** TC-F2-20b 设备端回归：txt（无目录能力）顶栏无目录图标。 */
    @Test
    fun txtFile_hasNoTocIcon() {
        openFixture("empty.txt")
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("文件为空").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithContentDescription("目录").assertCountEquals(0)
    }

    /** U4/F2.13：预览视图下目录图标仍在、面板可开可跳。 */
    @Test
    fun previewMode_tocStillWorks() {
        openFixture("sample_toc.md")
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithText("# 标题一（H1）").fetchSemanticsNodes().isNotEmpty()
        }
        // ⋮ 菜单 → 预览
        composeTestRule.onNodeWithContentDescription("菜单").performClick()
        composeTestRule.onNodeWithText("预览").performClick()
        // 预览渲染完成（标题无 # 前缀）
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("标题一（H1）").fetchSemanticsNodes().isNotEmpty()
        }
        // 预览下目录图标可用 → 面板打开 → 点击 H2 → 面板关闭。预览正文与面板行同名
        // （均无 # 前缀），用「文本 + click 动作」组合匹配器只命中面板行（正文块无 click 语义）。
        composeTestRule.onNodeWithContentDescription("目录").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("目录").assertIsDisplayed()
        val tocRowH2 = composeTestRule.onNode(hasText("标题二（H2）") and hasClickAction())
        tocRowH2.assertIsDisplayed().performClick()
        waitTextGone("目录")
        composeTestRule.onNodeWithText("标题一（H1）").assertIsDisplayed()
    }
}
