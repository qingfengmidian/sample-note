package com.sample.docreader.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 自动保存开关（F3.6/TC-F3-06，M7c 由 OpenCode 编写）：
 * 直驱无状态 AutoSaveRow——默认关、点击回调翻转；持久化回环见下方 SettingsScreenAutoSaveTest
 * 及 SettingsRepositoryTest.roundtrip_allFields（autoSave=true 往返）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsAutoSaveTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 默认关闭渲染为 Off；点击回调 true */
    @Test
    fun autoSaveRow_defaultOff_clickTogglesOn() {
        var toggled: Boolean? = null
        composeRule.setContent {
            AutoSaveRow(checked = false, onCheckedChange = { toggled = it })
        }
        composeRule.waitForIdle()
        val switch = composeRule.onNodeWithContentDescription("自动保存开关")
        switch.assertExists().assertIsOff()
        switch.performClick()
        composeRule.waitForIdle()
        assertEquals(true, toggled)
    }

    /** 开启状态渲染为 On；点击回调 false */
    @Test
    fun autoSaveRow_on_clickTogglesOff() {
        var toggled: Boolean? = null
        composeRule.setContent {
            AutoSaveRow(checked = true, onCheckedChange = { toggled = it })
        }
        composeRule.waitForIdle()
        val switch = composeRule.onNodeWithContentDescription("自动保存开关")
        switch.assertExists().assertIsOn()
        switch.performClick()
        composeRule.waitForIdle()
        assertEquals(false, toggled)
    }
}

/**
 * TC-F3-06（UI 侧）：设置页出现"自动保存"开关且默认关闭；
 * 切换后经 SettingsRepository.save 持久化（DataStore 回读为 true）。
 * 单独成类：Robolectric 沙盒按类隔离，避免 settings DataStore 单例跨用例串文件。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenAutoSaveTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsScreen_autoSaveSwitch_defaultOff_persistsOnToggle() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()
        val switch = composeRule.onNodeWithContentDescription("自动保存开关")
        switch.assertExists().assertIsOff()

        switch.performClick()
        composeRule.waitForIdle()

        // DataStore 写入异步落盘，轮询回读直至可见
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext<android.content.Context>())
        val deadline = System.currentTimeMillis() + 5_000
        var persisted = false
        while (System.currentTimeMillis() < deadline) {
            persisted = runBlocking { repo.settings.first().autoSave }
            if (persisted) break
            Thread.sleep(50)
        }
        assertTrue("切换后 autoSave=true 应持久化到 DataStore", persisted)
    }
}
