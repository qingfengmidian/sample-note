package com.sample.docreader

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sample.docreader.core.model.AppLanguage
import com.sample.docreader.core.settings.LocaleSwitcher
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TC-UI-18（M16，F4.9）应用内语言切换设备端回归（红测试先行，需 connectedDebugAndroidTest 执行）。
 *
 * ① 包装路径（全 API 级）：持久 en → 启动 MainActivity，活动资源取英文（对系统语言 = 中文的机器可判）；
 * ② 框架路径（API 33+）：`applyToFramework(EN)` 后系统按应用语言 = en。
 * 用例结束恢复「跟随系统」（仅清除本应用的覆盖，不改系统语言设置）。
 */
@RunWith(AndroidJUnit4::class)
class LocaleSwitchDeviceTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private val repository: SettingsRepository get() = SettingsRepository(appContext)

    @After
    fun restoreFollowSystem() {
        runBlocking { repository.saveAppLanguage(AppLanguage.SYSTEM) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleSwitcher.applyToFramework(appContext, AppLanguage.SYSTEM)
        }
    }

    @Test
    fun tcUi18_launchActivity_appliesPersistedLanguage() {
        runBlocking { repository.saveAppLanguage(AppLanguage.EN) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("Sample Note", activity.getString(R.string.app_name))
                assertEquals("en", activity.resources.configuration.locales[0].language)
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
}
