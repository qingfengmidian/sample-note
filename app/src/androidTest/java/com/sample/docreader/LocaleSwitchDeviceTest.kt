package com.sample.docreader

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sample.docreader.core.model.AppLanguage
import com.sample.docreader.core.settings.LocaleSwitcher
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TC-UI-18（M16，F4.9）应用内语言切换设备端回归（需 connectedDebugAndroidTest 执行）。
 *
 * ① 包装路径（全 API 级）：持久覆盖 → 启动 MainActivity，活动资源取对应语言——
 * **判别力与系统语言无关**（OpenCode 复核建议 4）：先测无覆盖时的基础 locale，再取「与之不同」的
 * 语言作覆盖目标，避免英文系统/CI 模拟器下假绿；
 * ② 框架路径（API 33+）：`applyToFramework(EN)` 后系统按应用语言 = en。
 * 每例前后均清理（@Before/@After 同构，OpenCode 复核建议 5），仅清除本应用覆盖、不改系统语言设置。
 */
@RunWith(AndroidJUnit4::class)
class LocaleSwitchDeviceTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private val repository: SettingsRepository get() = SettingsRepository(appContext)

    @Before
    fun clearBefore() {
        runBlocking { repository.saveAppLanguage(AppLanguage.SYSTEM) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleSwitcher.applyToFramework(appContext, AppLanguage.SYSTEM)
        }
    }

    @After
    fun restoreFollowSystem() {
        runBlocking { repository.saveAppLanguage(AppLanguage.SYSTEM) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleSwitcher.applyToFramework(appContext, AppLanguage.SYSTEM)
        }
    }

    @Test
    fun tcUi18_launchActivity_appliesPersistedLanguage() {
        // 与基础 locale 相反的语言作目标：英文系统断中文、中文系统断英文，任何环境下都有判别力
        val baselineLanguage = launchAndReadBaselineLanguage()
        val target = if (baselineLanguage == "zh") AppLanguage.EN else AppLanguage.ZH
        val expectedName = if (target == AppLanguage.ZH) "简记" else "Sample Note"

        runBlocking { repository.saveAppLanguage(target) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(expectedName, activity.getString(R.string.app_name))
                assertEquals(
                    LocaleSwitcher.tagOf(target),
                    activity.resources.configuration.locales[0].language
                )
            }
        }
    }

    @Test
    fun tcUi18_frameworkPath_syncsSystemStateOnSdk33() {
        // 33 以下无框架路径（包装路径已由上一例覆盖）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                LocaleSwitcher.applyToFramework(activity, AppLanguage.EN)
                val locales = activity.getSystemService(LocaleManager::class.java)?.applicationLocales
                assertEquals("en", locales?.get(0)?.language)
            }
        }
    }

    /** 无覆盖（跟随系统）启动一次，读出基础 locale 语言码（目标语言选择的依据） */
    private fun launchAndReadBaselineLanguage(): String {
        runBlocking { repository.saveAppLanguage(AppLanguage.SYSTEM) }
        var language = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { language = it.resources.configuration.locales[0].language }
        }
        return language
    }
}

/**
 * TC-UI-18（M17 增补，决策 D-2026-09-19-02）设置页真实导航：语言行 → 选项对话框 → 选「English」
 * → 确认框「取消」→ 持久值不变（判别力与系统语言无关，红测试先行）。
 * 字符串断言经 `getString` 读取（设备 locale 不可控）；用例结束恢复「跟随系统」。
 */
@RunWith(AndroidJUnit4::class)
class LocaleSwitchPickerDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private val repository: SettingsRepository get() = SettingsRepository(appContext)

    @Before
    fun clearBefore() {
        runBlocking { repository.saveAppLanguage(AppLanguage.SYSTEM) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleSwitcher.applyToFramework(appContext, AppLanguage.SYSTEM)
        }
    }

    @After
    fun restoreFollowSystem() {
        runBlocking { repository.saveAppLanguage(AppLanguage.SYSTEM) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleSwitcher.applyToFramework(appContext, AppLanguage.SYSTEM)
        }
    }

    @Test
    fun tcUi18_settingsScreen_languagePickerCancel_keepsPersistedValue() {
        // 首页 → 设置页（首页设置入口为 TextButton + 文本 home_settings_cd，无 contentDescription）
        composeRule.onNodeWithText(appContext.getString(R.string.home_settings_cd))
            .performClick()
        composeRule.waitForIdle()
        // 语言行 → 选项对话框 → 选「English」→ 确认框
        composeRule.onNodeWithText(appContext.getString(R.string.settings_language)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(appContext.getString(R.string.settings_language_en)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(appContext.getString(R.string.settings_language_restart_message))
            .assertExists()
        // 「取消」→ 持久值不变（确认框与选项框共用 common_cancel：此时仅确认框在屏）
        composeRule.onNodeWithText(appContext.getString(R.string.common_cancel)).performClick()
        composeRule.waitForIdle()
        Thread.sleep(500)
        org.junit.Assert.assertEquals(
            "取消后不应持久化语言变更",
            AppLanguage.SYSTEM,
            runBlocking { repository.appLanguage.first() }
        )
    }
}
