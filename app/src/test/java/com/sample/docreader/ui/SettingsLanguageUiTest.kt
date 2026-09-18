package com.sample.docreader.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.R
import com.sample.docreader.core.model.AppLanguage
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-17（M16，F4.9）设置页语言行（Robolectric，红测试先行）。
 *
 * 语义契约（沿用 SettingsGlobalTest 的循环行约定）：语言行 = `CycleSettingRow`，
 * 切换按钮 contentDescription 与行文本同名「语言」；行值显示当前档位标签
 * （跟随系统 / 中文 / English）；点击沿档位前进并即时持久化。
 * 注：zh 下「跟随系统」与阅读主题档位标签同字面，故断言取「语言」语义键与「中文」行值，避免多节点歧义。
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

    /** 语言行存在（含「通用」小节与切换按钮语义键） */
    @Test
    fun tcUi17_settingsScreen_showsLanguageRow() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.settings_general_section)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.settings_language)).assertExists()
    }

    /** 点击语言行：档位前进（跟随系统 → 中文）并即时持久化，行值随动 */
    @Test
    fun tcUi17_languageRowClick_persistsNextLanguage() {
        composeRule.setContent { SettingsScreen(onBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(context.getString(R.string.settings_language)).performClick()

        val deadline = System.currentTimeMillis() + 5_000
        var persisted = AppLanguage.SYSTEM
        while (System.currentTimeMillis() < deadline) {
            persisted = runBlocking { repository.appLanguage.first() }
            if (persisted != AppLanguage.SYSTEM) break
            Thread.sleep(50)
        }
        assertEquals("语言行点击后应沿档位持久化（跟随系统 → 中文）", AppLanguage.ZH, persisted)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.settings_language_zh))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
