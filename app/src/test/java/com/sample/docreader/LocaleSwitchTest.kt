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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-17（M16，F4.9）应用内语言切换机制（Robolectric）。
 *
 * 断言口径与 TC-UI-15 一致：断言包装后的**资源取值**（app_name 双语）与 Configuration，不做截图比对。
 * 默认限定符（英文）下运行，因此「包装前 = 英文、包装后 = 中文」可判；⑤⑥ 分别固化 33+ 框架路径与
 * <33（及框架不可用）的自重建回退路径（审查必修项 1 返工 + 建议 1）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocaleSwitchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository by lazy { SettingsRepository(context) }

    @Before
    fun resetToFollowSystem() {
        runBlocking { repository.saveAppLanguage(AppLanguage.SYSTEM) }
        LocaleSwitcher.applyToFramework(context, AppLanguage.SYSTEM)
    }

    @After
    fun restoreFollowSystem() {
        runBlocking { repository.saveAppLanguage(AppLanguage.SYSTEM) }
        LocaleSwitcher.applyToFramework(context, AppLanguage.SYSTEM)
    }

    /** ① 包装语义：持久 zh 时资源取中文且 Configuration 生效；跟随系统（键缺失）时原样返回不包装 */
    @Test
    fun tcUi17_wrapBaseContext_appliesPersistedLanguage() {
        assertEquals("默认（英文）资源前置条件", "Sample Note", context.getString(R.string.app_name))
        assertSame("跟随系统不应包装", context, LocaleSwitcher.wrapBaseContext(context))

        // 纯包装直驱（不经 DataStore）
        assertSame("tag=null 原样返回", context, LocaleSwitcher.wrap(context, null))
        assertEquals("简记", LocaleSwitcher.wrap(context, "zh").getString(R.string.app_name))

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

    /**
     * ⑤ API 33+ 框架路径（**无条件断言**，审查必修项 1）：Robolectric 提供 LocaleManager 服务
     * （ShadowLocaleManager，见审查记录探针 A），故本例在红态（空实现）必然失败、绿态通过；
     * 真机行为另由设备端 TC-UI-18 复核。
     */
    @Test
    @Config(sdk = [33])
    fun tcUi17_applyToFramework_sdk33_syncsFrameworkState() {
        val manager = context.getSystemService(LocaleManager::class.java)
        assertNotNull("API 33+ 应提供 LocaleManager 服务（否则本例退化为假绿）", manager)
        assertTrue("切换前框架态应为空（跟随系统）", manager!!.applicationLocales.isEmpty)

        assertTrue("33+ 应交由框架处理并触发重建", LocaleSwitcher.applyToFramework(context, AppLanguage.ZH))
        assertEquals("zh", manager.applicationLocales?.get(0)?.language)

        assertTrue(LocaleSwitcher.applyToFramework(context, AppLanguage.SYSTEM))
        assertTrue("回写「跟随系统」后框架态应为空", manager.applicationLocales.isEmpty)
    }

    /**
     * ⑥ API 26~32（及框架不可用）回退路径：`applyToFramework` 返回 false，由 UI 自行 `recreate()`
     * 使包装生效——`attachBaseContext` 在新实例按持久值重新包装（审查建议 1）。
     */
    @Test
    @Config(sdk = [32], qualifiers = "zh")
    fun tcUi17_sdk32_noFramework_recreateAppliesWrappedLanguage() {
        assertFalse("低版本不应交由框架处理（UI 须自行重建）", LocaleSwitcher.applyToFramework(context, AppLanguage.ZH))
        assertEquals("基础 locale 为中文（限定符）", "简记", context.getString(R.string.app_name))

        runBlocking { repository.saveAppLanguage(AppLanguage.EN) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals("装箱后的 Activity 取英文", "Sample Note", controller.get().getString(R.string.app_name))
        assertEquals("en", controller.get().resources.configuration.locales[0].language)

        // 回写「跟随系统」→ 重建后回到基础中文（包装路径对跟随系统原样返回）
        runBlocking { repository.saveAppLanguage(AppLanguage.SYSTEM) }
        controller.recreate()
        assertEquals("简记", controller.get().getString(R.string.app_name))
        assertEquals("zh", controller.get().resources.configuration.locales[0].language)
    }
}
