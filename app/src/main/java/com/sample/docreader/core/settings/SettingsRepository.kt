package com.sample.docreader.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.sample.docreader.core.model.AppLanguage
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore 实例按 `applicationContext` 缓存（M17 由单例委托改造）：同一进程内每应用一份，
 * 生产语义不变；Robolectric 每个测试方法会重建 Application，故各方法获得独立实例与独立文件，
 * 避免 settings 数据跨用例串文件（TC-UI-19/17 需要「默认值」类用例独立）。
 */
private val settingsDataStores = java.util.WeakHashMap<Context, DataStore<Preferences>>()

private fun Context.settingsDataStore(): DataStore<Preferences> =
    synchronized(settingsDataStores) {
        settingsDataStores.getOrPut(applicationContext) {
            PreferenceDataStoreFactory.create(
                produceFile = { applicationContext.preferencesDataStoreFile("settings") }
            )
        }
    }

/**
 * 全局阅读/显示设置仓库（DataStore Preferences）。
 * 全局共享一套设置，不按文件独立存储（需求 F3.3）。
 *
 * V1.0 迁移策略：键缺失回退默认值；Room 侧用 fallbackToDestructiveMigration（见 M4）。
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.settingsDataStore())

    private object Keys {
        val FONT_SIZE_SP = intPreferencesKey("font_size_sp")
        val LINE_HEIGHT_PERCENT = intPreferencesKey("line_height_percent")
        val WRAP_MODE = stringPreferencesKey("wrap_mode")
        val FONT = stringPreferencesKey("font")
        val THEME = stringPreferencesKey("theme") // 缺省/空 = 跟随系统
        val AUTO_SAVE = booleanPreferencesKey("auto_save")
        val CUSTOM_FONT_PATH = stringPreferencesKey("custom_font_path")
        // F4.9/M16：应用内语言覆盖（BCP-47 标签 zh/en）；键缺失或值未知 = 跟随系统
        val APP_LANGUAGE = stringPreferencesKey("app_language")
    }

    val settings: Flow<ReadingSettings> = dataStore.data.map { p ->
        ReadingSettings(
            fontSizeSp = p[Keys.FONT_SIZE_SP] ?: 16,
            lineHeightPercent = p[Keys.LINE_HEIGHT_PERCENT] ?: 150,
            wrapMode = p[Keys.WRAP_MODE]?.let { runCatching { WrapMode.valueOf(it) }.getOrNull() }
                ?: WrapMode.WRAP,
            font = p[Keys.FONT]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() }
                ?: ReaderFont.CODE,
            theme = p[Keys.THEME]?.let { runCatching { ReadingTheme.valueOf(it) }.getOrNull() },
            autoSave = p[Keys.AUTO_SAVE] ?: false,
            customFontPath = p[Keys.CUSTOM_FONT_PATH]
        )
    }

    /** 应用内语言覆盖（F4.9/M16，UI 真值源）：经 LocaleSwitcher 归一化，缺失/未知标签 = 跟随系统 */
    val appLanguage: Flow<AppLanguage> = dataStore.data.map { p ->
        LocaleSwitcher.languageOf(p[Keys.APP_LANGUAGE])
    }

    suspend fun save(settings: ReadingSettings) {
        dataStore.edit { p ->
            p[Keys.FONT_SIZE_SP] = settings.fontSizeSp
            p[Keys.LINE_HEIGHT_PERCENT] = settings.lineHeightPercent
            p[Keys.WRAP_MODE] = settings.wrapMode.name
            p[Keys.FONT] = settings.font.name
            val themeKey = Keys.THEME
            if (settings.theme == null) p.remove(themeKey) else p[themeKey] = settings.theme.name
            p[Keys.AUTO_SAVE] = settings.autoSave
            if (settings.customFontPath == null) p.remove(Keys.CUSTOM_FONT_PATH)
            else p[Keys.CUSTOM_FONT_PATH] = settings.customFontPath
        }
    }

    /**
     * 写入应用内语言覆盖（F4.9/M16）；SYSTEM 时移除键（回到跟随系统，不再被旧值覆盖）。
     * 与 [save] 键级隔离：保存阅读设置不会动该键。
     */
    suspend fun saveAppLanguage(language: AppLanguage) {
        dataStore.edit { p ->
            val tag = LocaleSwitcher.tagOf(language)
            if (tag == null) p.remove(Keys.APP_LANGUAGE) else p[Keys.APP_LANGUAGE] = tag
        }
    }
}
