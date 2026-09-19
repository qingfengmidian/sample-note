package com.sample.docreader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
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
 * TC-UI-20（M18，F4.10，设计 REV18 §3.10）设置页「问 AI」小节（Robolectric，红测试先行）。
 *
 * 契约：小节含三行——启用开关（默认开、可关并持久化，开关 cd = 行文本）、目标应用（未选显示
 * 「未选择」，无候选时对话框显示空态文案）、提问前缀（输入框 cd = 行文本，输入即时持久化，
 * 清空 = 空串 = 原文直发语义，与「未设置」= 资源默认前缀区分）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh")
class SettingsAskAiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository by lazy { SettingsRepository(context) }

    @Before
    fun reset() {
        runBlocking {
            repository.saveAskAiEnabled(true)
            repository.saveAskAiTargetPackage(null)
            repository.saveAskAiPrefix(null)
        }
    }

    @After
    fun restore() {
        runBlocking {
            repository.saveAskAiEnabled(true)
            repository.saveAskAiTargetPackage(null)
            repository.saveAskAiPrefix(null)
        }
    }

    /** ① 小节标题与三行存在（标题 + 开关 + 目标应用 + 提问前缀） */
    @Test
    fun tcUi20_sectionAndRowsExist() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("问 AI").performScrollTo().assertExists()
        composeRule.onNodeWithText("启用「问 AI」").performScrollTo().assertExists()
        composeRule.onNodeWithText("目标应用").performScrollTo().assertExists()
        composeRule.onNodeWithText("提问前缀").performScrollTo().assertExists()
    }

    /** ② 开关默认开；点按关闭并即时持久化 */
    @Test
    fun tcUi20_toggle_disablesAndPersists() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("启用「问 AI」").performScrollTo().performClick()
        composeRule.waitForIdle()

        val deadline = System.currentTimeMillis() + 5_000
        var enabled = true
        while (System.currentTimeMillis() < deadline) {
            enabled = runBlocking { repository.askAiEnabled.first() }
            if (!enabled) break
            Thread.sleep(50)
        }
        assertEquals("关闭开关应即时持久化", false, enabled)
    }

    /** ③ 目标应用未选时显示「未选择」；点击唤出选项对话框——无候选时显示空态文案 */
    @Test
    fun tcUi20_targetRow_noneAndEmptyStateDialog() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("未选择").performScrollTo().assertExists()
        composeRule.onNodeWithText("目标应用").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Robolectric 环境下无第三方 ACTION_SEND 接收方 → 空态文案
        composeRule.onNodeWithText("未发现可接收文本的应用").assertExists()
    }

    /** ④ 前缀输入即时持久化；清空后回读为空串（原文直发语义，与「未设置」= 资源默认区分） */
    @Test
    fun tcUi20_prefixInput_persistsAndClearsToEmpty() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("提问前缀").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("提问前缀").performTextInput("总结：")
        composeRule.waitForIdle()

        val deadline = System.currentTimeMillis() + 5_000
        var prefix: String? = null
        while (System.currentTimeMillis() < deadline) {
            prefix = runBlocking { repository.askAiPrefix.first() }
            if (prefix?.contains("总结：") == true) break
            Thread.sleep(50)
        }
        assertTrue("输入后应即时持久化", prefix?.contains("总结：") == true)

        composeRule.onNodeWithContentDescription("提问前缀").performTextClearance()
        composeRule.waitForIdle()
        val deadline2 = System.currentTimeMillis() + 5_000
        var cleared: String? = null
        while (System.currentTimeMillis() < deadline2) {
            cleared = runBlocking { repository.askAiPrefix.first() }
            if (cleared == "") break
            Thread.sleep(50)
        }
        assertEquals("清空后应持久化为空串（原文直发）", "", cleared)
    }
}
