package com.sample.docreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.render.MarkdownBlock
import com.sample.docreader.core.render.MdInline

/**
 * md 预览渲染（设计§3.3）：把 [MarkdownBlock] 映射为 Compose 组件。
 * 样式读取同一 ReadingSettings（字号/行距/字体/主题），与阅读界面保持一致（F3.3）。
 */
@Composable
fun MarkdownPreview(
    blocks: List<MarkdownBlock>,
    settings: ReadingSettings,
    listState: LazyListState,
    theme: ReadingTheme,
    modifier: Modifier = Modifier
) {
    val base = androidx.compose.ui.text.TextStyle(
        color = theme.foreground,
        fontSize = settings.fontSizeSp.sp,
        lineHeight = lineHeightSp(settings),
        fontFamily = fontFamilyFor(settings.font, settings.customFontPath)
    )
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .background(theme.background)
        ) {
            itemsIndexed(blocks, key = { index, _ -> index }) { _, block ->
                when (block) {
                    is MarkdownBlock.Heading -> {
                        val size = when (block.level) {
                            1 -> settings.fontSizeSp + 6
                            2 -> settings.fontSizeSp + 4
                            3 -> settings.fontSizeSp + 2
                            else -> settings.fontSizeSp
                        }
                        InlineText(
                            block.inlines,
                            base.copy(fontSize = size.sp, fontWeight = FontWeight.Bold),
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    is MarkdownBlock.Paragraph -> InlineText(
                        block.inlines, base, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    is MarkdownBlock.ListItem -> Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (block.ordered) "${block.number}. " else "• ",
                            style = base,
                            modifier = Modifier.width(24.dp)
                        )
                        InlineText(block.inlines, base, Modifier.weight(1f))
                    }
                    is MarkdownBlock.Quote -> InlineText(
                        block.inlines,
                        base.copy(fontStyle = FontStyle.Italic),
                        Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                    is MarkdownBlock.CodeBlock -> Text(
                        block.code,
                        style = base.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .background(theme.foreground.copy(alpha = 0.06f))
                            .padding(8.dp)
                    )
                    // F2.18 M20a 占位：表格正式渲染（MdTable）归 M20b，本阶段按原文行输出避免非穷尽崩溃
                    is MarkdownBlock.Table -> Text(
                        (listOf(block.header) + block.rows).joinToString("\n") { row ->
                            row.joinToString(" | ") { cell ->
                                cell.joinToString("") { inline ->
                                    when (inline) {
                                        is MdInline.Text -> inline.text
                                        is MdInline.Bold -> inline.text
                                        is MdInline.Italic -> inline.text
                                        is MdInline.Code -> inline.text
                                    }
                                }
                            }
                        },
                        style = base,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/** 行内片段渲染：加粗/斜体/行内代码以 SpanStyle 应用在 AnnotatedString 上 */
@Composable
private fun InlineText(inlines: List<MdInline>, style: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    val text = buildAnnotatedString {
        inlines.forEach { inline ->
            when (inline) {
                is MdInline.Text -> append(inline.text)
                is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline.text) }
                is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline.text) }
                is MdInline.Code -> withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                    append(inline.text)
                }
            }
        }
    }
    Text(text, style = style, modifier = modifier)
}
