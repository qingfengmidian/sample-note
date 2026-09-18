package com.sample.docreader

import android.content.Context
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.ui.reader.ReaderPanelAccentColorKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TC-UI-14（M13，F4.4/F4.5）设备端：真实窗口下阅读设置面板的选中勾强调色随阅读主题
 * （旧缺陷：取 `colorScheme.primary`，只随系统深浅色，明暗错配时对比度不足）。
 * Robolectric 侧断色值与对比度见 `ReaderPanelAccentTest`（TC-UI-13）；本例验证
 * 「ReaderScreen → 面板」的真实接线（面板在真实窗口下才进语义树）。
 */
@RunWith(AndroidJUnit4::class)
class ReaderAccentDeviceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val uiContext: Context get() = ApplicationProvider.getApplicationContext()

    /** 首页顶栏 → 「测试样本」夹具入口；夹具列表为 LazyColumn，确定性滚动到目标行。 */
    private fun openFixture(name: String) {
        composeTestRule.onNodeWithText(uiContext.getString(R.string.home_fixtures_cd)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(name))
        composeTestRule.onNodeWithText(name).performClick()
    }

    /** 等待文本节点「可见」：面板/下拉滑入动画期间节点已入树但 bounds 未稳定（M12 真机复盘手法）。 */
    private fun waitTextDisplayed(text: String, timeoutMillis: Long = 5_000) {
        composeTestRule.waitUntil(timeoutMillis) {
            runCatching { composeTestRule.onNodeWithText(text).assertIsDisplayed() }.isSuccess
        }
    }

    /** TC-UI-14：⋮ 菜单 → 阅读设置 → 切「柔光米黄」→ 面板内所有 ✓ 的强调色 = 柔光米黄前景色 */
    @Test
    fun readingSettingsSheet_selectedMarkAccent_followsReadingTheme() {
        openFixture("sample_toc.md")
        // ⋮ 菜单 → 阅读设置（等下拉/面板滑入动画结束再点，防点击落空）
        composeTestRule.onNodeWithContentDescription(uiContext.getString(R.string.reader_menu_cd)).performClick()
        waitTextDisplayed(uiContext.getString(R.string.reader_menu_settings))
        composeTestRule.onNodeWithText(uiContext.getString(R.string.reader_menu_settings)).performClick()
        waitTextDisplayed(uiContext.getString(R.string.reader_layout))
        // 切到柔光米黄（面板配色与强调色应即时跟随）
        composeTestRule.onNodeWithText(uiContext.getString(R.string.reader_theme_soft)).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val theme = ReadingTheme.SOFT_LIGHT
        val marks = composeTestRule.onAllNodesWithText("✓").fetchSemanticsNodes()
        assertTrue("阅读设置面板内应有选中项（✓）", marks.isNotEmpty())
        marks.forEach { node ->
            val accent = node.config.getOrNull(ReaderPanelAccentColorKey)
            assertEquals(
                "选中勾强调色应为阅读主题前景色（M13/TC-UI-14）",
                theme.foreground,
                accent
            )
        }
    }
}
