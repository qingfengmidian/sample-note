package com.sample.docreader.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun newRepository(scope: TestScope): SettingsRepository {
        val store = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { tmpFolder.newFile("settings_${System.nanoTime()}.preferences_pb") }
        )
        return SettingsRepository(store)
    }

    @Test
    fun defaults_matchSpec() = runTest {
        val repo = newRepository(this)
        val s = repo.settings.first()
        assertEquals(16, s.fontSizeSp)                 // 默认字号 16sp
        assertEquals(150, s.lineHeightPercent)         // 默认行距 1.5 倍
        assertEquals(WrapMode.WRAP, s.wrapMode)        // 默认自动换行
        assertEquals(ReaderFont.CODE, s.font)          // 默认代码风格字体
        assertNull(s.theme)                            // 默认跟随系统
        assertEquals(false, s.autoSave)                // 自动保存默认关闭
    }

    @Test
    fun roundtrip_allFields() = runTest {
        val repo = newRepository(this)
        val custom = ReadingSettings(
            fontSizeSp = 20,
            lineHeightPercent = 180,
            wrapMode = WrapMode.NO_WRAP,
            font = ReaderFont.SERIF,
            theme = ReadingTheme.EYE_CARE,
            autoSave = true
        )
        repo.save(custom)
        assertEquals(custom, repo.settings.first())
    }

    @Test
    fun lineHeightPercent_storedAsInt_noFloatDrift() = runTest {
        val repo = newRepository(this)
        repo.save(ReadingSettings(lineHeightPercent = 150))
        // Int 百分值读写无精度漂移（REV2 4.2）
        assertEquals(150, repo.settings.first().lineHeightPercent)
    }

    @Test
    fun theme_nullMeansFollowSystem_persists() = runTest {
        val repo = newRepository(this)
        repo.save(ReadingSettings(theme = ReadingTheme.NIGHT))
        assertEquals(ReadingTheme.NIGHT, repo.settings.first().theme)
        // 再存 null（跟随系统）应清除而非保留旧值
        repo.save(ReadingSettings(theme = null))
        assertNull(repo.settings.first().theme)
    }

    @Test
    fun customFontPath_roundtrip() = runTest {
        val repo = newRepository(this)
        repo.save(ReadingSettings(font = ReaderFont.CUSTOM, customFontPath = "/data/files/fonts/x.ttf"))
        assertEquals("/data/files/fonts/x.ttf", repo.settings.first().customFontPath)
        // 清除自定义字体路径（切回内置字体）后键应被移除
        repo.save(ReadingSettings(font = ReaderFont.CODE, customFontPath = null))
        assertNull(repo.settings.first().customFontPath)
    }
}
