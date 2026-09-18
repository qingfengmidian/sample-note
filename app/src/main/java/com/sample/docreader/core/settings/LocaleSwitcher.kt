package com.sample.docreader.core.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.sample.docreader.core.model.AppLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * 应用内语言切换（F4.9/M16，设计 REV15 §3.7、决策 D-2026-09-18-01）：
 * 档位 ↔ BCP-47 标签映射、base context 包装（全 API 级）与 API 33+ 框架路径。
 *
 * 持久值为**单一真值源**（`SettingsRepository.appLanguage`，DataStore 键 `app_language`；缺失/未知 = 跟随系统）：
 * - 全 API 级：`wrapBaseContext` 在 `MainActivity.attachBaseContext` 按持久值包装 Configuration——
 *   「手动选择优先于系统语言」在此路径保证，不依赖框架行为；
 * - API 33+：`applyToFramework` 额外同步框架态（`LocaleManager`），并由系统完成 Activity 重建；
 * - API 26~32：由设置页在写入完成后自行 `Activity.recreate()`（见 `needsManualRecreate`）。
 */
object LocaleSwitcher {

    private const val TAG_ZH = "zh"
    private const val TAG_EN = "en"

    /** 语言档位 → BCP-47 标签（跟随系统 = null，即不覆盖） */
    fun tagOf(language: AppLanguage): String? = when (language) {
        AppLanguage.SYSTEM -> null
        AppLanguage.ZH -> TAG_ZH
        AppLanguage.EN -> TAG_EN
    }

    /** BCP-47 标签 → 语言档位（null/未知 = 跟随系统；未覆盖语言回退默认英文由资源回退链保证） */
    fun languageOf(tag: String?): AppLanguage = when (tag) {
        TAG_ZH -> AppLanguage.ZH
        TAG_EN -> AppLanguage.EN
        else -> AppLanguage.SYSTEM
    }

    /** 设置页循环档位：跟随系统 → 中文 → English → 跟随系统 */
    fun next(current: AppLanguage): AppLanguage = when (current) {
        AppLanguage.SYSTEM -> AppLanguage.ZH
        AppLanguage.ZH -> AppLanguage.EN
        AppLanguage.EN -> AppLanguage.SYSTEM
    }

    /**
     * 按持久值包装 base context（跟随系统时原样返回）；仅由 `MainActivity.attachBaseContext` 调用。
     * 同步读一次 DataStore（Activity 构造期，量小）；不做进程内缓存，避免外部写入后脏读。
     */
    fun wrapBaseContext(base: Context): Context =
        wrap(base, tagOf(runBlocking { SettingsRepository(base).appLanguage.first() }))

    /** 包装语义（纯函数，供测试直驱）：[tag] 为 null 时原样返回，否则按 locale 生成 Configuration 上下文 */
    internal fun wrap(base: Context, tag: String?): Context {
        if (tag == null) return base
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        }
        return base.createConfigurationContext(configuration)
    }

    /**
     * API 33+ 同步框架态（跟随系统 = 空 LocaleList）；低版本为显式空操作。
     * 框架服务缺失（含单测环境）或调用被拒时静默回退——可见行为已由 [wrapBaseContext] 独立保证。
     */
    fun applyToFramework(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            manager.setApplicationLocales(
                tagOf(language)?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
            )
        }
    }

    /** 切换后是否需由 UI 自行 recreate（API < 33）；33+ 由框架完成重建，避免重复 */
    val needsManualRecreate: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
}
