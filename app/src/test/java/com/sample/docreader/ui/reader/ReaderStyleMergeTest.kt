package com.sample.docreader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.sample.docreader.core.model.ParagraphStyleHints
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * M11c（F2.15）样式合并纯函数（《软件设计文档》§3.3 样式合并规则）：
 * 全局设置为绝对基准，CSS 仅相对偏移——fontSizeEm/lineHeightEm clamp 0.5~3.0 后乘全局值；
 * bold/italic/textAlign 直接采用；CSS 颜色与当前主题背景对比度 < 4.5:1 回退主题前景色；
 * 段间距 0/非 0 两档由解析层产出、渲染层 item 间隔处理（不在 TextStyle 内）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderStyleMergeTest {

    private val base: TextStyle = TextStyle(
        color = ReadingTheme.DEFAULT.foreground,
        fontSize = ReadingSettings().fontSizeSp.sp,
        lineHeight = (ReadingSettings().fontSizeSp * ReadingSettings().lineHeightPercent / 100).sp
    )

    /** clamp 上下界：0.5~3.0 生效于最终倍率（0.1→0.5、10→3.0、2 保持） */
    @Test
    fun clampStyleEm_bounds() {
        assertEquals(0.5f, clampStyleEm(0.1f), 1e-5f)
        assertEquals(0.5f, clampStyleEm(0.5f), 1e-5f)
        assertEquals(2f, clampStyleEm(2f), 1e-5f)
        assertEquals(3f, clampStyleEm(3f), 1e-5f)
        assertEquals(3f, clampStyleEm(10f), 1e-5f)
    }

    /** 字号倍率 clamp 后乘全局字号；未指定行距时行距随字号等比缩放（保持全局行距比例） */
    @Test
    fun fontSize_hintClampedAndMultiplied_lineHeightFollows() {
        // 16sp × 10 → clamp 3 → 48sp
        val huge = applyParagraphHints(
            base, ParagraphStyleHints(fontSizeEm = 10f), ReadingTheme.DEFAULT
        )
        assertEquals(48f, huge.fontSize.value, 1e-4f)
        assertEquals(72f, huge.lineHeight.value, 1e-4f) // 16*1.5=24 → 24*3

        // 16sp × 0.1 → clamp 0.5 → 8sp
        val tiny = applyParagraphHints(
            base, ParagraphStyleHints(fontSizeEm = 0.1f), ReadingTheme.DEFAULT
        )
        assertEquals(8f, tiny.fontSize.value, 1e-4f)

        // 正常范围内倍率直接乘
        val normal = applyParagraphHints(
            base, ParagraphStyleHints(fontSizeEm = 1.5f), ReadingTheme.DEFAULT
        )
        assertEquals(24f, normal.fontSize.value, 1e-4f)
    }

    /** 行距独立 hint：clamp 后乘全局行距；仅行距变化不改字号 */
    @Test
    fun lineHeight_hintClampedAndMultiplied() {
        val doubled = applyParagraphHints(
            base, ParagraphStyleHints(lineHeightEm = 2f), ReadingTheme.DEFAULT
        )
        assertEquals(16f, doubled.fontSize.value, 1e-4f)
        assertEquals(48f, doubled.lineHeight.value, 1e-4f) // 24*2
    }

    /** bold / italic / textAlign 直接采用（null 不动默认） */
    @Test
    fun boldItalicAlign_appliedDirectly() {
        val ts = applyParagraphHints(
            base,
            ParagraphStyleHints(bold = true, italic = true, textAlign = 2),
            ReadingTheme.DEFAULT
        )
        assertEquals(FontWeight.Bold, ts.fontWeight)
        assertEquals(FontStyle.Italic, ts.fontStyle)
        assertEquals(TextAlign.Right, ts.textAlign)

        // 显式 false 亦直接采用（覆盖祖先继承语义，落到段落即按此渲染）
        val off = applyParagraphHints(
            base, ParagraphStyleHints(bold = false, italic = false, textAlign = 1), ReadingTheme.DEFAULT
        )
        assertEquals(FontWeight.Normal, off.fontWeight)
        assertEquals(FontStyle.Normal, off.fontStyle)
        assertEquals(TextAlign.Center, off.textAlign)

        // hints == null：样式原样（零改动路径）
        assertEquals(base, applyParagraphHints(base, null, ReadingTheme.DEFAULT))
    }

    /** 颜色对比度：与四套 ReadingTheme 背景核对 4.5:1 边界——不足回退主题前景色 */
    @Test
    fun cssColor_contrastFallbackAgainstFourThemes() {
        ReadingTheme.entries.forEach { theme ->
            val bgArgb = theme.background.toArgb()
            // 主题前景色与自身背景必然 ≥ 4.5（无障碍硬要求）→ 保留 CSS 色
            assertTrue(
                "${theme} 主题前景应通过对比度",
                keepCssColor(theme.foreground.toArgb(), bgArgb)
            )
            // 背景色即前景 → 对比度 1:1 → 回退
            assertFalse(
                "${theme} 背景同色应回退",
                keepCssColor(bgArgb, bgArgb)
            )
        }

        // 合并层：低对比 CSS 色回退为主题前景；高对比 CSS 色直接采用
        val bgSelf = applyParagraphHints(
            base,
            ParagraphStyleHints(colorArgb = ReadingTheme.DEFAULT.background.toArgb()),
            ReadingTheme.DEFAULT
        )
        assertEquals(base.color, bgSelf.color)

        val high = applyParagraphHints(
            base,
            ParagraphStyleHints(colorArgb = 0xFF000000.toInt()),
            ReadingTheme.DEFAULT
        )
        assertEquals(Color(0xFF000000), high.color)

        // 夜间主题：黑字贴深底 → 回退**该主题前景色**（阅读器 Text 基准色即解析后的主题前景）
        val nightBase = base.copy(color = ReadingTheme.NIGHT.foreground)
        val nightBg = applyParagraphHints(
            nightBase,
            ParagraphStyleHints(colorArgb = 0xFF000000.toInt()),
            ReadingTheme.NIGHT
        )
        assertEquals(ReadingTheme.NIGHT.foreground, nightBg.color)
    }

    /** WCAG 相对亮度公式（评审附录沿用）：0.5/0.0 边界参考 */
    @Test
    fun wcagContrast_referenceValues() {
        // 黑 vs 白 = 21:1
        assertEquals(21.0, wcagContrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.05)
        // 同色 = 1:1
        assertEquals(1.0, wcagContrast(0xFF123456.toInt(), 0xFF123456.toInt()), 1e-6)
    }

    // ---------------- F2.17（M15）默认标题层级（TC-UT-18） ----------------

    /** 默认层级倍率表：H1~H6 逐级递减；越界（0/7/负数）→ 1f */
    @Test
    fun headingFontScale_table() {
        assertEquals(1.6f, headingFontScale(1), 1e-5f)
        assertEquals(1.45f, headingFontScale(2), 1e-5f)
        assertEquals(1.3f, headingFontScale(3), 1e-5f)
        assertEquals(1.2f, headingFontScale(4), 1e-5f)
        assertEquals(1.1f, headingFontScale(5), 1e-5f)
        assertEquals(1.05f, headingFontScale(6), 1e-5f)
        assertEquals(1f, headingFontScale(0), 1e-5f)
        assertEquals(1f, headingFontScale(7), 1e-5f)
        assertEquals(1f, headingFontScale(-1), 1e-5f)
    }

    /** 书未声明字号的标题段：字号 = 全局 × 默认倍率、行距等比跟随、默认加粗（F2.17） */
    @Test
    fun heading_defaultScaleAndBold() {
        val h1 = applyParagraphHints(base, null, ReadingTheme.DEFAULT, headingLevel = 1)
        assertEquals(16f * 1.6f, h1.fontSize.value, 1e-4f)
        assertEquals(24f * 1.6f, h1.lineHeight.value, 1e-4f) // 行距随字号等比，保持全局比例
        assertEquals(FontWeight.Bold, h1.fontWeight)

        val h6 = applyParagraphHints(base, null, ReadingTheme.DEFAULT, headingLevel = 6)
        assertEquals(16f * 1.05f, h6.fontSize.value, 1e-4f)
        assertEquals(FontWeight.Bold, h6.fontWeight)
    }

    /** 书 CSS 已声明字号 → **覆盖**默认倍率（不相乘）；未声明字号的标题仍走默认倍率（F2.17） */
    @Test
    fun heading_bookCssOverridesDefaultScale() {
        val h2 = applyParagraphHints(
            base, ParagraphStyleHints(fontSizeEm = 1.1f), ReadingTheme.DEFAULT, headingLevel = 2
        )
        assertEquals("1.1 而非 1.45×1.1（覆盖非叠加）", 16f * 1.1f, h2.fontSize.value, 1e-4f)
        val h3 = applyParagraphHints(
            base, ParagraphStyleHints(fontSizeEm = 2f), ReadingTheme.DEFAULT, headingLevel = 3
        )
        assertEquals("2.0 而非 1.3×2", 32f, h3.fontSize.value, 1e-4f)
        val h4 = applyParagraphHints(base, null, ReadingTheme.DEFAULT, headingLevel = 4)
        assertEquals("未声明 → 默认 1.2", 16f * 1.2f, h4.fontSize.value, 1e-4f)
    }

    /** bold 三态（F2.17）：null → 默认加粗；true → 加粗；false → 非粗（遵循书） */
    @Test
    fun heading_boldThreeStates() {
        assertEquals(
            FontWeight.Bold,
            applyParagraphHints(base, null, ReadingTheme.DEFAULT, headingLevel = 2).fontWeight
        )
        assertEquals(
            FontWeight.Bold,
            applyParagraphHints(base, ParagraphStyleHints(bold = true), ReadingTheme.DEFAULT, headingLevel = 2).fontWeight
        )
        assertEquals(
            FontWeight.Normal,
            applyParagraphHints(base, ParagraphStyleHints(bold = false), ReadingTheme.DEFAULT, headingLevel = 2).fontWeight
        )
    }

    /** 非标题段行为与 M15 前完全一致（headingLevel = null 零变化） */
    @Test
    fun nonHeading_unchanged() {
        val plain = applyParagraphHints(base, null, ReadingTheme.DEFAULT, headingLevel = null)
        assertEquals(base.fontSize.value, plain.fontSize.value, 1e-5f)
        assertEquals(base.lineHeight.value, plain.lineHeight.value, 1e-5f)
        assertNull("非标题段不默认加粗", plain.fontWeight)

        val styled = applyParagraphHints(
            base, ParagraphStyleHints(fontSizeEm = 1.5f), ReadingTheme.DEFAULT, headingLevel = null
        )
        assertEquals(24f, styled.fontSize.value, 1e-4f)
        assertNull("非标题段不因 CSS 字号而默认加粗", styled.fontWeight)
    }
}
