package com.sample.docreader.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
 * TC-F4-03 全局设置页（M8a 先行失败测试，TDD 红 → OpenCode 实现转绿）。
 * F4.3：设置页包含默认字号/默认行距/默认换行模式/默认字体/默认阅读主题，修改即时持久化，
 * 新打开的文件应用新默认值（"新打开应用新默认值"由 SettingsRepository 单一数据源保证，
 * 阅读页本就订阅同一份 settings flow）。
 *
 * 被测 UI 契约（语义描述约定，实现方按此命名）：
 * - 五个设置行文本：默认字号 / 默认行距 / 默认换行模式 / 默认字体 / 默认阅读主题；
 * - 字号调节按钮 contentDescription："减小默认字号" / "增大默认字号"；
 * - 行距/换行/字体/主题各为一个循环切换按钮，contentDescription 与行文本同名。
 * 档位契约（与阅读页 ReaderViewModel 一致）：字号 12/14/16/18/20/22/24 步进、行距 120→150→180→200 循环、
 * 字体 代码→衬线→无衬线、主题 跟随系统→默认白→护眼绿→柔光米黄→夜间黑。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsGlobalTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repo by lazy {
        SettingsRepository(ApplicationProvider.getApplicationContext<android.content.Context>())
    }

    /** TC-F4-03：设置页出现全部五个全局默认项 + 既有自动保存开关 */
    @Test
    fun settingsScreen_showsAllGlobalDefaults() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        listOf("默认字号", "默认行距", "默认换行模式", "默认字体", "默认阅读主题").forEach {
            composeRule.onNodeWithText(it, substring = true).assertExists()
        }
        composeRule.onNodeWithContentDescription("自动保存开关").assertExists()
    }

    /** TC-F4-03：字号调节（增大默认字号）即时持久化到 DataStore，档位与阅读页一致（16 → 18，ReaderViewModel.FONT_SIZE_STEPS） */
    @Test
    fun settingsScreen_fontSizeIncrease_persists() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("增大默认字号").assertExists().performClick()
        composeRule.waitForIdle()

        // DataStore 写入异步落盘，轮询回读直至可见（同 SettingsScreenAutoSaveTest）
        val deadline = System.currentTimeMillis() + 5_000
        var persisted = -1
        while (System.currentTimeMillis() < deadline) {
            persisted = runBlocking { repo.settings.first().fontSizeSp }
            if (persisted == 18) break
            Thread.sleep(50)
        }
        assertEquals("默认字号应沿档位 16 → 18 持久化", 18, persisted)
    }
}
