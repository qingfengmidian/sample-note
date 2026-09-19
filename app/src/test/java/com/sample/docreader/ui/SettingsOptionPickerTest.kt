package com.sample.docreader.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-19（M17，F4.3，决策 D-2026-09-19-01）设置项选项列表交互（Robolectric，红测试先行）。
 *
 * 契约（替代原 TC-F4-03 循环用例 SettingsGlobalCycleTest）：点击设置行唤出单选对话框——
 * 对话框列出**全部**可选值、当前值以选中态标记（选项行 `Modifier.selectable`，
 * 合并语义树上 `assertIsSelected` 直接可用）、点选即持久化并关闭对话框。
 * 行距/主题各一例覆盖 `OptionPickerRow` 通用组件；换行模式/字体行同组件对称实现。
 * 单独成类：Robolectric 沙盒按类隔离，避免 settings DataStore 跨用例串文件。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh")
class SettingsLineHeightPickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** ① 点「默认行距」行 → 对话框列出 120/150/180/200% 全部四档，当前值 150% 选中 */
    @Test
    fun tcUi19_lineHeightRow_opensPickerWithAllOptions() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("默认行距").performScrollTo().performClick()
        composeRule.waitForIdle()

        listOf("120%", "150%", "180%", "200%").forEach { option ->
            composeRule.onNodeWithText(option).assertExists()
        }
        // 当前值 150% 选中（与选中语义联合断言：背景行值同字面，单匹配断言会被双节点卡死）
        composeRule.onNode(
            hasText("150%") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        ).assertExists()
    }

    /** ② 点选 180% → 即时持久化且对话框关闭 */
    @Test
    fun tcUi19_lineHeightSelectOption_persistsAndCloses() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("默认行距").performScrollTo().performClick()
        composeRule.onNodeWithText("180%").performClick()
        composeRule.waitForIdle()

        // 对话框已关闭（选项不再存在）
        composeRule.onNodeWithText("120%").assertDoesNotExist()

        // DataStore 写入异步落盘，轮询回读直至可见
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext<android.content.Context>())
        val deadline = System.currentTimeMillis() + 5_000
        var persisted = -1
        while (System.currentTimeMillis() < deadline) {
            persisted = runBlocking { repo.settings.first().lineHeightPercent }
            if (persisted == 180) break
            Thread.sleep(50)
        }
        assertEquals("点选 180% 应即时持久化", 180, persisted)
    }
}

/**
 * TC-UI-19（M17）默认阅读主题选项列表（与行距例同组件；当前值判定需区分背景行值同字面项，
 * 故取「选中态节点存在」而非单节点断言）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh")
class SettingsThemePickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** ③ 点「默认阅读主题」行 → 对话框列出五档，当前值「跟随系统」为选中态 */
    @Test
    fun tcUi19_themeRow_opensPickerWithAllOptions() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("默认阅读主题").performScrollTo().performClick()
        composeRule.waitForIdle()

        listOf("跟随系统", "默认白", "护眼绿", "柔光米黄", "夜间黑").forEach { option ->
            composeRule.onNodeWithText(option).assertExists()
        }
        // 「跟随系统」同时是背景行值与对话框选项：断言其中存在选中态节点（= 对话框当前值）
        val hasSelected = composeRule.onAllNodesWithText("跟随系统")
            .fetchSemanticsNodes()
            .any { it.config[SemanticsProperties.Selected] == true }
        assertTrue("当前值「跟随系统」应以选中态标记", hasSelected)
    }

    /** ④ 点选「夜间黑」→ 即时持久化且对话框关闭 */
    @Test
    fun tcUi19_themeSelectOption_persistsAndCloses() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("默认阅读主题").performScrollTo().performClick()
        composeRule.onNodeWithText("夜间黑").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("默认白").assertDoesNotExist()

        val repo = SettingsRepository(ApplicationProvider.getApplicationContext<android.content.Context>())
        val deadline = System.currentTimeMillis() + 5_000
        var persisted: ReadingTheme? = null
        while (System.currentTimeMillis() < deadline) {
            persisted = runBlocking { repo.settings.first().theme }
            if (persisted == ReadingTheme.NIGHT) break
            Thread.sleep(50)
        }
        assertEquals("点选「夜间黑」应即时持久化", ReadingTheme.NIGHT, persisted)
    }
}
