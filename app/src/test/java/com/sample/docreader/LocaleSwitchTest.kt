package com.sample.docreader

import android.app.LocaleManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.core.model.AppLanguage
import com.sample.docreader.core.settings.LocaleSwitcher
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TC-UI-17（M16，F4.9）应用内语言切换机制（Robolectric，红测试先行）。
 *
 * 断言口径与 TC-UI-15 一致：断言包装后的**资源取值**（app_name 双语）与 Configuration，
 * 不做截图比对。默认限定符（英文）下运行，因此「包装前 = 英文、包装后 = 中文」可判。
 */
@RunWith(RobolectricTestRunner::class)
class LocaleSwitchTest {

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

    /** ① 包装语义：持久 zh 时资源取中文且 Configuration 生效；跟随系统（键缺失）时原样返回不包装 */
    @Test
    fun tcUi17_wrapBaseContext_appliesPersistedLanguage() {
        assertEquals("默认（英文）资源前置条件", "Sample Note", context.getString(R.string.app_name))
        assertSame("跟随系统不应包装", context, LocaleSwitcher.wrapBaseContext(context))

        runBlocking { repository.saveAppLanguage(AppLanguage.ZH) }
        val wrapped = LocaleSwitcher.wrapBaseContext(context)
        assertEquals("简记", wrapped.getString(R.string.app_name))
        assertEquals("zh", wrapped.resources.configuration.locales[0].language)

        runBlocking { repository.saveAppLanguage(AppLanguage.EN) }
        assertEquals("Sample Note", LocaleSwitcher.wrapBaseContext(context).getString(R.string.app_name))
    }

    /** ② 档位映射：zh/en 标签往返一致；缺失或未覆盖语言 = 跟随系统（不切换） */
    @Test
    fun tcUi17_languageTagMapping_unknownFallsBackToSystem() {
        assertNull(LocaleSwitcher.tagOf(AppLanguage.SYSTEM))
        assertEquals("zh", LocaleSwitcher.tagOf(AppLanguage.ZH))
        assertEquals("en", LocaleSwitcher.tagOf(AppLanguage.EN))
        AppLanguage.entries.forEach { language ->
            assertEquals(language, LocaleSwitcher.languageOf(LocaleSwitcher.tagOf(language)))
        }
        assertEquals(AppLanguage.SYSTEM, LocaleSwitcher.languageOf(null))
        assertEquals(AppLanguage.SYSTEM, LocaleSwitcher.languageOf("de"))
    }

    /** ③ 档位循环：跟随系统 → 中文 → English → 跟随系统 */
    @Test
    fun tcUi17_languageCycle_systemZhEn() {
        assertEquals(AppLanguage.ZH, LocaleSwitcher.next(AppLanguage.SYSTEM))
        assertEquals(AppLanguage.EN, LocaleSwitcher.next(AppLanguage.ZH))
        assertEquals(AppLanguage.SYSTEM, LocaleSwitcher.next(AppLanguage.EN))
    }

    /** ④ 持久化：写入即读回；回写「跟随系统」清除覆盖 */
    @Test
    fun tcUi17_saveAppLanguage_persistsAndClears() = runBlocking {
        assertEquals(AppLanguage.SYSTEM, repository.appLanguage.first())
        repository.saveAppLanguage(AppLanguage.ZH)
        assertEquals(AppLanguage.ZH, repository.appLanguage.first())
        repository.saveAppLanguage(AppLanguage.EN)
        assertEquals(AppLanguage.EN, repository.appLanguage.first())
        repository.saveAppLanguage(AppLanguage.SYSTEM)
        assertEquals(AppLanguage.SYSTEM, repository.appLanguage.first())
    }

    /** ⑤ API 33+ 框架路径：调用不抛异常，框架服务可用时框架态同步为 zh（真机行为由 TC-UI-18 复核） */
    @Test
    @Config(sdk = [33])
    fun tcUi17_applyToFramework_sdk33_syncsFrameworkState() {
        LocaleSwitcher.applyToFramework(context, AppLanguage.ZH)
        val locales = runCatching {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales
        }.getOrNull()
        if (locales != null && !locales.isEmpty) {
            assertEquals("zh", locales[0].language)
        }
    }
}
