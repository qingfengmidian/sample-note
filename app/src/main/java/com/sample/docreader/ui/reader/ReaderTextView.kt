package com.sample.docreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode

/** 正文容器 testTag，供 UI 测试断言主题背景色 */
const val READER_CONTENT_TAG = "reader_content"

/** 主题/字体语义键：供 UI 测试断言排版样式与 ReadingTheme/ReaderFont 枚举一致（TC-UI-03） */
val ReaderBackgroundColorKey = SemanticsPropertyKey<androidx.compose.ui.graphics.Color>("ReaderBackgroundColor")
val ReaderForegroundColorKey = SemanticsPropertyKey<androidx.compose.ui.graphics.Color>("ReaderForegroundColor")
val ReaderFontFamilyKey = SemanticsPropertyKey<FontFamily>("ReaderFontFamily")

/** 主题解析：null = 跟随系统深浅色（浅色→DEFAULT，深色→NIGHT） */
@Composable
fun resolveReadingTheme(theme: ReadingTheme?): ReadingTheme =
    theme ?: if (isSystemInDarkTheme()) ReadingTheme.NIGHT else ReadingTheme.DEFAULT

/** 字体映射：CUSTOM 的字体文件导入在 M4 实现，本里程碑无文件时回退 Monospace */
fun fontFamilyOf(font: ReaderFont): FontFamily = when (font) {
    ReaderFont.CODE, ReaderFont.CUSTOM -> FontFamily.Monospace
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.SANS -> FontFamily.SansSerif
}

/** 行距公式：lineHeight = fontSizeSp * lineHeightPercent / 100（设计§3.3） */
internal fun lineHeightSp(settings: ReadingSettings) =
    (settings.fontSizeSp * settings.lineHeightPercent / 100).sp

/**
 * 阅读排版组件（设计§3.3）：
 * - WRAP：段落 Text 充满宽度自然折行；NO_WRAP：每段放入可水平滚动的 Row，整行绘制；
 *   两种模式段落间都分行；
 * - 背景色应用容器、前景色应用正文 Text；整体包 SelectionContainer 支持长按选择复制（F2.12）。
 */
@Composable
fun ReaderTextView(
    paragraphs: List<String>,
    settings: ReadingSettings,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val theme = resolveReadingTheme(settings.theme)
    val textStyle = TextStyle(
        color = theme.foreground,
        fontSize = settings.fontSizeSp.sp,
        lineHeight = lineHeightSp(settings),
        fontFamily = fontFamilyOf(settings.font)
    )
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .background(theme.background)
                .testTag(READER_CONTENT_TAG)
                .semantics {
                    // 主题/字体语义：供 UI 测试断言与 ReadingTheme/ReaderFont 枚举一致（TC-UI-03）
                    this[ReaderBackgroundColorKey] = theme.background
                    this[ReaderForegroundColorKey] = theme.foreground
                    this[ReaderFontFamilyKey] = textStyle.fontFamily ?: FontFamily.Default
                }
        ) {
            itemsIndexed(paragraphs, key = { index, _ -> index }) { _, paragraph ->
                val itemModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                when (settings.wrapMode) {
                    WrapMode.WRAP ->
                        Text(paragraph, style = textStyle, modifier = itemModifier)
                    WrapMode.NO_WRAP ->
                        Row(itemModifier.horizontalScroll(rememberScrollState())) {
                            Text(
                                paragraph,
                                style = textStyle,
                                softWrap = false,
                                maxLines = Int.MAX_VALUE
                            )
                        }
                }
            }
        }
    }
}
