package com.sample.docreader.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 全局阅读/显示设置仓库（DataStore Preferences）。
 * 全局共享一套设置，不按文件独立存储（需求 F3.3）。
 *
 * V1.0 迁移策略：键缺失回退默认值；Room 侧用 fallbackToDestructiveMigration（见 M4）。
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.settingsDataStore)

    private object Keys {
        val FONT_SIZE_SP = intPreferencesKey("font_size_sp")
        val LINE_HEIGHT_PERCENT = intPreferencesKey("line_height_percent")
        val WRAP_MODE = stringPreferencesKey("wrap_mode")
        val FONT = stringPreferencesKey("font")
        val THEME = stringPreferencesKey("theme") // 缺省/空 = 跟随系统
        val AUTO_SAVE = booleanPreferencesKey("auto_save")
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
            autoSave = p[Keys.AUTO_SAVE] ?: false
        )
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
        }
    }
}
