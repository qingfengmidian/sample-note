package com.sample.docreader.ui.reader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-06（F4.4，M10）：阅读设置面板主体 ReaderSettingsSheetContent 直驱（Robolectric 下
 * ModalBottomSheet 弹层内容不进语义树，面板主体抽出为无状态 composable 供直驱，设计 §4）。
 * 断言：排版/字体/主题三组存在；字号 A-/A+、换行单选、行距单选、字体单选（含 CUSTOM，
 * 需先置 customFontPath）、主题单选、编码单选（isTxt=true）各触发对应回调；
 * 非 txt 时编码组不显示；customFontPath 为空时 CUSTOM 字体项不出现。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSettingsSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setSheet(
        settings: ReadingSettings = ReadingSettings(),
        isTxt: Boolean = true,
        onSelectWrapMode: (WrapMode) -> Unit = {},
        onFontSizeStep: (Int) -> Unit = {},
        onSelectLineHeight: (Int) -> Unit = {},
        onSelectFont: (ReaderFont) -> Unit = {},
        onSelectTheme: (ReadingTheme?) -> Unit = {},
        onSelectEncoding: (String?) -> Unit = {},
        onImportFont: () -> Unit = {}
    ) {
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = settings,
                isTxt = isTxt,
                encodingOverride = null,
                accent = ReadingTheme.DEFAULT.foreground, // M13：本类不断言颜色，取中性值
                onSelectWrapMode = onSelectWrapMode,
                onFontSizeStep = onFontSizeStep,
                onSelectLineHeight = onSelectLineHeight,
                onSelectFont = onSelectFont,
                onSelectTheme = onSelectTheme,
                onSelectEncoding = onSelectEncoding,
                onImportFont = onImportFont
            )
        }
        composeRule.waitForIdle()
    }

    /** TC-UI-06：三组存在；各控件点击触发一次回调（sheet 内容可纵向滚动，先滚入视口再点） */
    @Test
    fun tcUi06_groupsExist_andEachControlTriggersCallback() {
        var wrap: WrapMode? = null
        var sizeStep: Int? = null
        var lineHeight: Int? = null
        var font: ReaderFont? = null
        var theme: ReadingTheme? = null
        var encoding: String? = null
        var importCalls = 0
        setSheet(
            // 置 customFontPath：CUSTOM 字体项才会出现（G2）
            settings = ReadingSettings(customFontPath = "fake/custom.ttf"),
            onSelectWrapMode = { wrap = it },
            onFontSizeStep = { sizeStep = it },
            onSelectLineHeight = { lineHeight = it },
            onSelectFont = { font = it },
            onSelectTheme = { theme = it },
            onSelectEncoding = { encoding = it },
            onImportFont = { importCalls++ }
        )

        // 三组标题存在（面板内容可纵向滚动，后两组可能在视口外——断言存在即可，不做布局断言）
        listOf("排版", "字体", "主题").forEach {
            composeRule.onNodeWithText(it).assertExists()
        }

        // 字号 A-/A+（contentDescription 定位按钮）
        composeRule.onNodeWithContentDescription("减小字号").performScrollTo().performClick()
        assertEquals(-1, sizeStep)
        composeRule.onNodeWithContentDescription("增大字号").performScrollTo().performClick()
        assertEquals(1, sizeStep)

        // 换行单选：默认 WRAP → 点"不换行"触发 NO_WRAP
        composeRule.onNodeWithText("不换行").performScrollTo().performClick()
        assertEquals(WrapMode.NO_WRAP, wrap)

        // 行距单选（120/150/180/200%）：点"行距 180%"
        composeRule.onNodeWithText("行距 180%").performScrollTo().performClick()
        assertEquals(180, lineHeight)

        // 编码单选（isTxt=true）：点 GBK
        composeRule.onNodeWithText("GBK").performScrollTo().performClick()
        assertEquals("GBK", encoding)

        // 字体单选：衬线 → CUSTOM（customFontPath 已置，CUSTOM 项可见）
        composeRule.onNodeWithText("衬线").performScrollTo().performClick()
        assertEquals(ReaderFont.SERIF, font)
        composeRule.onNodeWithText("自定义").performScrollTo().performClick()
        assertEquals(ReaderFont.CUSTOM, font)

        // 主题单选：点"柔光米黄"
        composeRule.onNodeWithText("柔光米黄").performScrollTo().performClick()
        assertEquals(ReadingTheme.SOFT_LIGHT, theme)

        // 导入字体入口存在（点触发回调）
        composeRule.onNodeWithContentDescription("导入字体").performScrollTo().performClick()
        assertEquals(1, importCalls)
    }

    /** TC-UI-06：非 txt 时编码组不显示；customFontPath 为空时 CUSTOM 字体项不出现（G2 负例） */
    @Test
    fun tcUi06_nonTxt_hidesEncodingAndCustomFont() {
        setSheet(settings = ReadingSettings(), isTxt = false)

        // 编码组不显示（含各编码项）
        composeRule.onNodeWithText("编码").assertDoesNotExist()
        composeRule.onNodeWithText("自动探测").assertDoesNotExist()

        // CUSTOM 项不显示（customFontPath 为空），三态字体项仍在
        composeRule.onNodeWithText("自定义").assertDoesNotExist()
        composeRule.onNodeWithText("代码").assertExists()

        // 三组标题仍在
        listOf("排版", "字体", "主题").forEach {
            composeRule.onNodeWithText(it).assertExists()
        }
    }

    /** TC-UI-06 补充：编码组仅 txt 显示时含自动探测默认项，且默认选中当前 override（null = 自动探测） */
    @Test
    fun tcUi06_txt_showsAutoDetectEncodingOption() {
        var encoding: String? = "unset"
        setSheet(isTxt = true, onSelectEncoding = { encoding = it })

        // isTxt=true 时编码组可见，含"自动探测"项
        composeRule.onNodeWithText("编码").assertExists()
        composeRule.onNodeWithText("自动探测").performScrollTo().performClick()
        assertEquals("自动探测项应回调 null", null, encoding)
        assertNull(encoding)
    }
}
