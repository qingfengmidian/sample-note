package com.sample.docreader.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.core.model.AppLanguage
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-17（M16，F4.9；M17 REV12 改写）设置页语言切换交互（Robolectric，红测试先行）。
 *
 * M17 契约（决策 D-2026-09-19-02）：
 * ① 点「语言」行 → 唤出语言选项对话框（跟随系统/中文/English 三项，当前值选中）；
 * ② 选「English」→ 弹确认框「切换语言需要重启应用，是否立即重启？」（立即重启/取消）；
 * ③ 「取消」→ 不做任何持久化；④ 「立即重启」→ 先落库再重建，持久化为 EN。
 * 断言用 zh 字面量（与既有 Robolectric zh 口径一致）；红态为「点击唤不出对话框」必然失败。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh")
class SettingsLanguageUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository by lazy { SettingsRepository(context) }

    @Before
    fun resetToFollowSystem() {
        runBlocking { repository.saveAppLanguage(AppLanguage.SYSTEM) }
    }

    @After
    fun restoreFollowSystem() {
        runBlocking { repository.saveAppLanguage(AppLanguage.SYSTEM) }
    }

    /** ① 语言行存在（「通用」小节 + 行标题） */
    @Test
    fun tcUi17_settingsScreen_showsLanguageRow() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("通用").assertExists()
        composeRule.onNodeWithText("语言").assertExists()
    }

    /** ② 点语言行 → 选项对话框列出三项语言，当前值「跟随系统」选中 */
    @Test
    fun tcUi17_languageRow_opensOptionDialog() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("语言").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("跟随系统").assertExists()
        composeRule.onNodeWithText("中文").assertExists()
        composeRule.onNodeWithText("English").assertExists()
        val hasSelected = composeRule.onAllNodesWithText("跟随系统")
            .fetchSemanticsNodes()
            .any { it.config[SemanticsProperties.Selected] == true }
        assertTrue("当前值「跟随系统」应以选中态标记", hasSelected)
    }

    /** ③ 选「English」→ 确认框出现；语言选项对话框已关闭 */
    @Test
    fun tcUi17_selectEnglish_showsRestartConfirmDialog() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("语言").performClick()
        composeRule.onNodeWithText("English").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("切换语言需要重启应用，是否立即重启？").assertExists()
        composeRule.onNodeWithText("立即重启").assertExists()
        composeRule.onNodeWithText("取消").assertExists()
        // 选项对话框已关闭：「跟随系统」仅剩主界面行值一处
        assertEquals(
            "语言选项对话框应已关闭",
            1,
            composeRule.onAllNodesWithText("跟随系统").fetchSemanticsNodes().size
        )
    }

    /** ④ 确认框「取消」→ 持久值保持「跟随系统」不落库 */
    @Test
    fun tcUi17_confirmCancel_keepsPersistedValue() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("语言").performClick()
        composeRule.onNodeWithText("English").performClick()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.waitForIdle()

        // 给潜在的错误异步写入留出落盘窗口后断言「未变」
        Thread.sleep(500)
        assertEquals(
            "取消后不应持久化语言变更",
            AppLanguage.SYSTEM,
            runBlocking { repository.appLanguage.first() }
        )
    }

    /** ⑤ 确认框「立即重启」→ 持久化为 EN（先落库再重建；compose 测试上下文非 Activity 不会真重建） */
    @Test
    fun tcUi17_confirmRestart_persistsEnglish() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("语言").performClick()
        composeRule.onNodeWithText("English").performClick()
        composeRule.onNodeWithText("立即重启").performClick()
        composeRule.waitForIdle()

        val deadline = System.currentTimeMillis() + 5_000
        var persisted = AppLanguage.SYSTEM
        while (System.currentTimeMillis() < deadline) {
            persisted = runBlocking { repository.appLanguage.first() }
            if (persisted == AppLanguage.EN) break
            Thread.sleep(50)
        }
        assertEquals("「立即重启」后应持久化为 English", AppLanguage.EN, persisted)
    }
}
