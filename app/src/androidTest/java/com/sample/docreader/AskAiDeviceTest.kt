package com.sample.docreader

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sample.docreader.core.askai.AskAi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TC-UI-21（M18，F4.10，设计 REV18 §3.10）问 AI 入口与候选枚举设备端回归
 * （真机/模拟器 `connectedDebugAndroidTest`）：
 * ① 真实导航打开夹具 → 阅读器 ⋮ 菜单含「问 AI」项（语义断言，**不实际发送**，避免真机弹第三方应用）；
 * ② `AskAi.candidates(context)` 在真实 PackageManager 下包名唯一、不含本应用。
 */
@RunWith(AndroidJUnit4::class)
class AskAiDeviceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val uiContext: Context get() = ApplicationProvider.getApplicationContext()

    /** 首页顶栏 → 「测试样本」夹具入口 → 打开指定夹具（同 MdTocDeviceTest 的确定性滚动做法） */
    private fun openFixture(name: String) {
        composeTestRule.onNodeWithText(uiContext.getString(R.string.home_fixtures_cd)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(name))
        composeTestRule.onNodeWithText(name).performClick()
        composeTestRule.waitForIdle()
    }

    /** ① 阅读器 ⋮ 菜单存在「问 AI」项 */
    @Test
    fun tcUi21_readerMenu_hasAskAiEntry() {
        openFixture("sample.md")

        composeTestRule.onNodeWithContentDescription(uiContext.getString(R.string.reader_menu_cd))
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeTestRule.onNodeWithText(uiContext.getString(R.string.reader_menu_ask_ai))
                    .assertIsDisplayed()
            }.isSuccess
        }
        composeTestRule.onNodeWithText(uiContext.getString(R.string.reader_menu_ask_ai))
            .assertIsDisplayed()
    }

    /** ② 真实 PackageManager 候选枚举：包名唯一且不含本应用 */
    @Test
    fun tcUi21_candidates_uniqueAndExcludeSelf() {
        val candidates = AskAi.candidates(uiContext)
        val packages = candidates.map { it.packageName }
        assertTrue("候选包名应唯一", packages.size == packages.distinct().size)
        assertFalse("不应把本应用列为候选", packages.contains(uiContext.packageName))
    }
}
