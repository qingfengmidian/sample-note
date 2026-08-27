package com.sample.docreader.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * TC-F4-03 补充（M8a，OpenCode 编写）：默认行距循环切换即时持久化。
 * 档位与阅读页 ReaderViewModel.cycleLineHeight 一致：默认 150 → 点击"默认行距"循环按钮 → 180。
 * 单独成类：Robolectric 沙盒按类隔离，避免 settings DataStore 跨用例串文件。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLineHeightCycleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsScreen_lineHeightCycle_persists() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        // 行距行位于首屏下方，先滚入可视区再点击（设置页 Column 为 verticalScroll）
        composeRule.onNodeWithContentDescription("默认行距")
            .assertExists()
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        // DataStore 写入异步落盘，轮询回读直至可见（同 SettingsScreenAutoSaveTest）
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext<android.content.Context>())
        val deadline = System.currentTimeMillis() + 5_000
        var persisted = -1
        while (System.currentTimeMillis() < deadline) {
            persisted = runBlocking { repo.settings.first().lineHeightPercent }
            if (persisted == 180) break
            Thread.sleep(50)
        }
        assertEquals("默认行距应沿档位 150 → 180 持久化", 180, persisted)
    }
}

/**
 * TC-F4-03 补充（M8a，OpenCode 编写）：默认阅读主题循环切换即时持久化。
 * 档位与阅读页 ReaderViewModel.cycleTheme 一致：默认 null（跟随系统）→ 点击"默认阅读主题"循环按钮 → DEFAULT。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsThemeCycleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsScreen_themeCycle_persists() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        // 主题行为五个默认项末行，先滚入可视区再点击
        composeRule.onNodeWithContentDescription("默认阅读主题")
            .assertExists()
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        // DataStore 写入异步落盘，轮询回读直至可见
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext<android.content.Context>())
        val deadline = System.currentTimeMillis() + 5_000
        var persisted: ReadingTheme? = null
        while (System.currentTimeMillis() < deadline) {
            persisted = runBlocking { repo.settings.first().theme }
            if (persisted == ReadingTheme.DEFAULT) break
            Thread.sleep(50)
        }
        assertEquals("默认主题应沿循环 null → DEFAULT 持久化", ReadingTheme.DEFAULT, persisted)
    }
}
