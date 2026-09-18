package com.sample.docreader.core.settings

import android.content.Context
import com.sample.docreader.core.model.AppLanguage

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

    /** 语言档位 → BCP-47 标签（跟随系统 = null，即不覆盖） */
    fun tagOf(language: AppLanguage): String? = null // 红态（M16）：待接线

    /** BCP-47 标签 → 语言档位（null/未知 = 跟随系统；未覆盖语言回退默认英文由资源回退链保证） */
    fun languageOf(tag: String?): AppLanguage = AppLanguage.SYSTEM // 红态（M16）：待接线

    /** 设置页循环档位：跟随系统 → 中文 → English → 跟随系统 */
    fun next(current: AppLanguage): AppLanguage = AppLanguage.SYSTEM // 红态（M16）：待接线

    /** 按持久值包装 base context（跟随系统时原样返回）；仅由 MainActivity.attachBaseContext 调用 */
    fun wrapBaseContext(base: Context): Context = base // 红态（M16）：待接线

    /** API 33+ 同步框架态（跟随系统 = 空 LocaleList）；低版本与框架不可用时为空操作 */
    fun applyToFramework(context: Context, language: AppLanguage) {
        // 红态（M16）：待接线
    }

    /** 切换后是否需由 UI 自行 recreate（API < 33）；33+ 由框架完成重建，避免重复 */
    val needsManualRecreate: Boolean
        get() = false // 红态（M16）：待接线
}
