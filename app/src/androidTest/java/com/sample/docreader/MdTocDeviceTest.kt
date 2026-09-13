package com.sample.docreader

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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
        // M12 真机复盘：swipe fling 过冲会跳过尾部夹具，改 performScrollToNode 确定性滚动。
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(name))
        composeTestRule.onNodeWithText(name).performClick()
    }

    /** 等待文本节点「可见」（存在且 displayed）——面板/下拉滑入动画期间节点已入树但 bounds 未稳定，
     * 直接 assertIsDisplayed 会撞上「component is not displayed」（真机复盘 2026-09-12，MdToc 两例同根因）。 */
    private fun waitTextDisplayed(text: String, timeoutMillis: Long = 5_000) {
        composeTestRule.waitUntil(timeoutMillis) {
            runCatching { composeTestRule.onNodeWithText(text).assertIsDisplayed() }.isSuccess
        }
    }

    private fun waitTextGone(text: String) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * 打开夹具并滑回顶部锚点（2026-09-13 真机复盘，位置无关化）：
     * 进度按 uri 落库恢复（F2.8/S3 方案①），前序用例/手工走查可把同一夹具留在任意深处，
     * 首段节点根本不在语义树（LazyColumn 离屏销毁），任何「首段存在/显示」前置断言都会炸。
     * 有界循环下滑：解析未完成时列表为空、下滑空转，解析完成后从恢复位置自然滑到锚点出现。
     */
    private fun openFixtureAtTop(name: String, anchorText: String) {
        openFixture(name)
        repeat(25) {
            if (composeTestRule.onAllNodesWithText(anchorText).fetchSemanticsNodes().isNotEmpty()) {
                return
            }
            runCatching {
                composeTestRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
            }
            composeTestRule.waitForIdle()
        }
    }

    /** TC-F2-20a 设备端回归：目录面板展示（H1~H2B 完整）、点条目后面板关闭并跳到目标段。 */
    @Test
    fun tocPanel_opens_showsHeadings_selectCloses() {
        openFixtureAtTop("sample_toc.md", "# 标题一（H1）")
        // 顶栏目录图标存在且可点
        composeTestRule.onNodeWithContentDescription("目录").assertIsDisplayed().performClick()
        // 面板打开：无 # 前缀的标题行（仅面板持有该精确串）；等待滑入动画结束（bounds 稳定）
        waitTextDisplayed("标题一（H1）")
        waitTextDisplayed("标题五（H5）")
        waitTextDisplayed("结尾标题（H2B）")
        // 点击「标题五（H5）」→ 面板关闭、正文跳到目标段。
        // 真机复盘（2026-09-13）：原断言「H1 仍显示」依赖视口几何（跳到 H5 后 H1 在真机上已滚出），
        // 改为断言跳转目标本身（源码段保留全部 5 个 # 前缀，精确串）——既验证 S3 跳转落地，又与屏幕尺寸/字号解耦。
        composeTestRule.onNodeWithText("标题五（H5）").performClick()
        waitTextGone("标题五（H5）")
        waitTextDisplayed("##### 标题五（H5）")
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
        // 位置无关化：前序用例（tocPanel）跳转后位置已落库，openFixtureAtTop 内滑回顶部再切预览
        openFixtureAtTop("sample_toc.md", "# 标题一（H1）")
        // ⋮ 菜单 → 预览
        composeTestRule.onNodeWithContentDescription("菜单").performClick()
        waitTextDisplayed("预览") // 下拉菜单滑入动画结束再点，否则点击落空（真机复盘 2026-09-12）
        composeTestRule.onNodeWithText("预览").performClick()
        // 预览渲染完成（标题无 # 前缀）
        waitTextDisplayed("标题一（H1）", timeoutMillis = 10_000)
        // 预览下目录图标可用 → 面板打开 → 点击 H2 → 面板关闭。预览正文与面板行同名
        // （均无 # 前缀），用「文本 + click 动作」组合匹配器只命中面板行（正文块无 click 语义）。
        composeTestRule.onNodeWithContentDescription("目录").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("目录").assertIsDisplayed()
        val tocRowH2 = composeTestRule.onNode(hasText("标题二（H2）") and hasClickAction())
        tocRowH2.assertIsDisplayed().performClick()
        waitTextGone("目录")
        // 断言跳转目标（面板已关，正文唯一命中；不断言 H1——同 tocPanel 的几何依赖问题）
        waitTextDisplayed("标题二（H2）")
    }
}
