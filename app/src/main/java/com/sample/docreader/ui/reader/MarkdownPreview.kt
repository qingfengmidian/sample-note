package com.sample.docreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sample.docreader.R
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.render.MarkdownBlock
import com.sample.docreader.core.render.MdAlign
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
                    is MarkdownBlock.Table -> MdTable(
                        block.header,
                        block.aligns,
                        block.rows,
                        base,
                        theme,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ---------------- F2.18（M20b）md 表格渲染 ----------------

/** 表格单元格语义键：供 UI 测试断言加粗/对齐与 MdAlign 一致（TC-UI-22） */
val MdTableCellBoldKey = SemanticsPropertyKey<Boolean>("MdTableCellBold")
val MdTableCellAlignKey = SemanticsPropertyKey<TextAlign>("MdTableCellAlign")
val MdTableHorizontalScrollKey = SemanticsPropertyKey<Boolean>("MdTableHorizontalScroll")

/** 列最小宽度（R2 拍板：默认 96dp，实现可调常量） */
private val MD_TABLE_MIN_COLUMN_WIDTH = 96.dp
/** 表头行底部分隔线透明度（主题前景低透明度，深/浅主题均可见） */
private const val MD_TABLE_HEADER_DIVIDER_ALPHA = 0.28f
/** 表体行间细分隔线透明度（比表头更浅一档） */
private const val MD_TABLE_BODY_DIVIDER_ALPHA = 0.14f

/**
 * F2.18（M20b）：md 预览表格渲染。
 * - 结构：Column 包多个 Row（表头 + 表体），单元格文本走既有 [InlineText]（加粗/斜体/行内代码生效）。
 * - 样式：字号/字体/颜色全部沿用传入的 [base]（与预览其他块同一 TextStyle 基准），不硬编码颜色。
 * - 表头加粗；表头行底部较深分隔线、表体行间更浅分隔线（均取主题前景低透明度）。
 * - 对齐：按 aligns 逐列设 textAlign。
 * - 列宽（R2）：等权重 + 最小列宽；各行共享同一 colWidth（禁止逐行独立测量）；总宽超屏时容器横向滚动，
 *   横向滚动不抢占 LazyColumn 垂直滑动。
 * - 长按复制：复用外层 SelectionContainer（MarkdownPreview 已包裹），单元格不新造交互。
 * - 无障碍：容器 semantics 描述行列数（stringResource 中英双语）；表头单元格加粗可辨，不做复杂 table 语义角色。
 */
@Composable
private fun MdTable(
    header: List<List<MdInline>>,
    aligns: List<MdAlign>,
    rows: List<List<List<MdInline>>>,
    base: androidx.compose.ui.text.TextStyle,
    theme: ReadingTheme,
    modifier: Modifier = Modifier
) {
    val columnCount = header.size
    val semanticsText = stringResource(R.string.reader_table_semantics, columnCount, rows.size + 1)
    val scrollState = rememberScrollState()
    BoxWithConstraints(modifier = modifier) {
        val available = maxWidth
        val colWidth = maxOf(MD_TABLE_MIN_COLUMN_WIDTH, available / columnCount.coerceAtLeast(1))
        val needsScroll = colWidth * columnCount > available + 0.5.dp
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .semantics {
                    contentDescription = semanticsText
                    this[MdTableHorizontalScrollKey] = needsScroll
                }
        ) {
            Column(modifier = Modifier.width(colWidth * columnCount)) {
                TableRow(header, aligns, colWidth, base, isHeader = true)
                DividerLine(theme, MD_TABLE_HEADER_DIVIDER_ALPHA)
                rows.forEachIndexed { index, row ->
                    TableRow(row, aligns, colWidth, base, isHeader = false)
                    if (index != rows.lastIndex) DividerLine(theme, MD_TABLE_BODY_DIVIDER_ALPHA)
                }
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<List<MdInline>>,
    aligns: List<MdAlign>,
    columnWidth: Dp,
    base: androidx.compose.ui.text.TextStyle,
    isHeader: Boolean
) {
    Row {
        cells.forEachIndexed { index, inlines ->
            TableCell(inlines, aligns.getOrNull(index), columnWidth, base, isHeader)
        }
    }
}

@Composable
private fun TableCell(
    inlines: List<MdInline>,
    align: MdAlign?,
    columnWidth: Dp,
    base: androidx.compose.ui.text.TextStyle,
    isHeader: Boolean
) {
    val textAlign = when (align) {
        MdAlign.CENTER -> TextAlign.Center
        MdAlign.RIGHT -> TextAlign.Right
        else -> TextAlign.Left
    }
    val style = base.copy(textAlign = textAlign).let {
        if (isHeader) it.copy(fontWeight = FontWeight.Bold) else it
    }
    InlineText(
        inlines,
        style,
        Modifier
            .width(columnWidth)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics {
                this[MdTableCellBoldKey] = isHeader
                this[MdTableCellAlignKey] = textAlign
            }
    )
}

@Composable
private fun DividerLine(theme: ReadingTheme, alpha: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.foreground.copy(alpha = alpha))
    )
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
