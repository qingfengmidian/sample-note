package com.sample.docreader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TC-UI-15（M14，F4.6）双语资源完整性（Robolectric）：
 * ① 默认 locale app_name == "Sample Note"；② zh 限定符 app_name == "简记"；
 * ③ 反射遍历 R.string 全部键 + 直接解析两份 strings.xml 断言键集对等（资源回退链会
 * 掩盖 values-zh 缺键，getString 检不出，必须解析 XML）；
 * ④ 关键文案双语抽查（强制键契约见 `docs/collab/briefs/m14-i18n.md` T1）。
 */
@RunWith(RobolectricTestRunner::class)
class LocalizationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 键 → (zh 值, 默认英文值)。zh 值逐字沿用 M14 前现有中文文案。 */
    private val contract = linkedMapOf(
        "home_open_file" to ("打开文件" to "Open file"),
        "home_new_file" to ("新建文件" to "New file"),
        "home_settings_cd" to ("设置" to "Settings"),
        "home_fixtures_cd" to ("测试样本" to "Test fixtures"),
        "reader_menu_cd" to ("菜单" to "Menu"),
        "reader_menu_settings" to ("阅读设置" to "Reading settings"),
        "reader_menu_preview" to ("预览" to "Preview"),
        "reader_menu_edit" to ("编辑" to "Edit"),
        "reader_toc_cd" to ("目录" to "Table of contents"),
        "common_back_cd" to ("返回" to "Back"),
        "reader_error_unsupported_format" to ("不支持的格式" to "Unsupported format"),
        "reader_error_provider_not_running" to
            ("文件提供方（如云盘应用）未运行，请启动对应应用后重试" to
                "The file provider (e.g., cloud storage app) is not running. Open the corresponding app and try again."),
    )

    @Test
    fun appName_defaultLocaleIsSampleNote() {
        assertEquals("Sample Note", context.getString(R.string.app_name))
    }

    @Test
    @Config(qualifiers = "zh")
    fun appName_zhLocaleIsJianJi() {
        assertEquals("简记", context.getString(R.string.app_name))
    }

    @Test
    fun stringKeySets_parityBetweenDefaultAndZh() {
        val defaultKeys = parseStringKeys(File(moduleDir(), "src/main/res/values/strings.xml"))
        val zhFile = File(moduleDir(), "src/main/res/values-zh/strings.xml")
        assertTrue("values-zh/strings.xml 必须存在（F4.6）", zhFile.isFile)
        val zhKeys = parseStringKeys(zhFile)
        assertEquals("默认资源与 values-zh 键集必须完全对等", defaultKeys, zhKeys)

        // 反射遍历 R.string 全部键：两份资源都必须能解析出非空值。
        val reflected = R.string::class.java.fields.map { it.getInt(null) }
        assertTrue("R.string 键集合不应为空", reflected.isNotEmpty())
        val zhContext = zhConfigContext()
        reflected.forEach { id ->
            assertNotNull(context.resources.getString(id))
            assertTrue(context.resources.getString(id).isNotBlank())
            assertTrue(zhContext.resources.getString(id).isNotBlank())
        }
    }

    @Test
    @Config(qualifiers = "zh")
    fun keyUiStrings_zhValuesMatchLegacyText() {
        contract.forEach { (key, pair) ->
            val id = context.resources.getIdentifier(key, "string", context.packageName)
            assertTrue("缺少字符串键 $key", id != 0)
            assertEquals("zh 值应逐字沿用既有中文文案（$key）", pair.first, context.getString(id))
        }
    }

    @Test
    fun keyUiStrings_defaultValuesAreEnglish() {
        contract.forEach { (key, pair) ->
            val id = context.resources.getIdentifier(key, "string", context.packageName)
            assertTrue("缺少字符串键 $key", id != 0)
            assertEquals("默认（英文）值不符合契约（$key）", pair.second, context.getString(id))
        }
    }

    // ---- helpers ----

    /** 单测工作目录为模块目录；向上回退以兼容从仓库根启动的测试进程。 */
    private fun moduleDir(): File {
        var dir = File(System.getProperty("user.dir")!!).canonicalFile
        while (true) {
            if (File(dir, "src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile ?: error("找不到 src/main/res/values/strings.xml（user.dir 异常）")
        }
    }

    private fun parseStringKeys(xml: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("name") }.toSortedSet()
    }

    private fun zhConfigContext(): Context {
        val conf = android.content.res.Configuration(context.resources.configuration)
        conf.setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        return context.createConfigurationContext(conf)
    }
}
