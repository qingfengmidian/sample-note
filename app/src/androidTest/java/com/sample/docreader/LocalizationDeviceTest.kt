package com.sample.docreader

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * TC-UI-16（M14，F4.6）双语资源设备端回归：真窗口资源配置下，
 * 默认 locale 关键文案为英文，`createConfigurationContext(zh)` 切换后为中文。
 * 与 Robolectric `LocalizationTest`（TC-UI-15）同契约，见 `docs/collab/briefs/m14-i18n.md` T1。
 */
@RunWith(AndroidJUnit4::class)
class LocalizationDeviceTest {

    @Test
    fun keyStrings_defaultEnglish_zhSimplifiedChinese() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val zhConf = Configuration(ctx.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val zh = ctx.createConfigurationContext(zhConf)

        assertEquals("Sample Note", ctx.getString(R.string.app_name))
        assertEquals("简记", zh.getString(R.string.app_name))
        assertEquals("Reading settings", ctx.getString(R.string.reader_menu_settings))
        assertEquals("阅读设置", zh.getString(R.string.reader_menu_settings))
        assertEquals("Menu", ctx.getString(R.string.reader_menu_cd))
        assertEquals("菜单", zh.getString(R.string.reader_menu_cd))
        assertEquals("Back", ctx.getString(R.string.common_back_cd))
        assertEquals("返回", zh.getString(R.string.common_back_cd))
        assertEquals(
            "The file provider (e.g., cloud storage app) is not running. Open the corresponding app and try again.",
            ctx.getString(R.string.reader_error_provider_not_running)
        )
        assertEquals(
            "文件提供方（如云盘应用）未运行，请启动对应应用后重试",
            zh.getString(R.string.reader_error_provider_not_running)
        )
    }
}
