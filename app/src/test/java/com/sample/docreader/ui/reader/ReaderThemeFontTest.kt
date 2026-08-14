package com.sample.docreader.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontFamily
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-03：主题/字体切换即时生效，色值与 ReadingTheme 枚举一致。
 * 断言走自定义语义键（见 ReaderTextView），不用像素截图（Robolectric 下 captureToImage 不可靠）。
 * NATIVE 图形模式：LEGACY 下矢量图标绘制会 NPE。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderThemeFontTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val paragraph = "theme font rendering check abcdefg 中文混排。"
    private val document = Document("asset:///fixtures/sample_utf8.txt", "sample_utf8.txt",
        DocFormat.TXT, listOf(Chapter(null, listOf(paragraph))))

    private fun contentColors(): Triple<Any?, Any?, Any?> {
        val config = composeRule.onNodeWithTag(READER_CONTENT_TAG).fetchSemanticsNode().config
        return Triple(
            config.getOrNull(ReaderBackgroundColorKey),
            config.getOrNull(ReaderForegroundColorKey),
            config.getOrNull(ReaderFontFamilyKey)
        )
    }

    @Test
    fun themeSwitch_appliesColorsImmediately() {
        var settings by mutableStateOf(ReadingSettings(theme = ReadingTheme.EYE_CARE))
        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(loading = false, document = document, settings = settings),
                onBack = {},
                onToggleWrap = {},
                onFontSizeStep = { _ -> },
                onCycleLineHeight = {},
                onCycleFont = {},
                onCycleTheme = { settings = settings.copy(theme = ReadingTheme.SOFT_LIGHT) }
            )
        }
        composeRule.waitForIdle()

        // 护眼绿：背景/前景与枚举色值一致
        contentColors().let { (bg, fg, _) ->
            assertEquals(ReadingTheme.EYE_CARE.background, bg)
            assertEquals(ReadingTheme.EYE_CARE.foreground, fg)
        }

        // 切换柔光米黄 → 重组后即时生效（按钮在工具条右侧，先滚动到可见再点击）
        composeRule.onNodeWithContentDescription("切换主题").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(ReadingTheme.SOFT_LIGHT, settings.theme)
        contentColors().let { (bg, fg, _) ->
            assertEquals(ReadingTheme.SOFT_LIGHT.background, bg)
            assertEquals(ReadingTheme.SOFT_LIGHT.foreground, fg)
        }
    }

    @Test
    fun fontSwitch_changesRenderingImmediately() {
        // 字体映射规则（CUSTOM 无字体文件时回退 Monospace，M4 才支持导入）
        assertEquals(FontFamily.Monospace, fontFamilyOf(ReaderFont.CODE))
        assertEquals(FontFamily.Serif, fontFamilyOf(ReaderFont.SERIF))
        assertEquals(FontFamily.SansSerif, fontFamilyOf(ReaderFont.SANS))
        assertEquals(FontFamily.Monospace, fontFamilyOf(ReaderFont.CUSTOM))

        var settings by mutableStateOf(ReadingSettings(font = ReaderFont.CODE))
        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(loading = false, document = document, settings = settings),
                onBack = {},
                onToggleWrap = {},
                onFontSizeStep = { _ -> },
                onCycleLineHeight = {},
                onCycleFont = { settings = settings.copy(font = ReaderFont.SERIF) },
                onCycleTheme = {}
            )
        }
        composeRule.waitForIdle()
        assertEquals(FontFamily.Monospace, contentColors().third)

        composeRule.onNodeWithContentDescription("切换字体").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(ReaderFont.SERIF, settings.font)
        assertEquals(FontFamily.Serif, contentColors().third)
    }
}
