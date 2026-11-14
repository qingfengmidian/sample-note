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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
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

/** F2.18（M21）列宽语义键：表格容器节点携带每列实际渲染宽度（dp），供 UI 测试断言内容自适应分配（TC-UI-23） */
val MdTableColumnWidthsKey = SemanticsPropertyKey<List<Dp>>("MdTableColumnWidths")

/** 列最小宽度（R2 拍板：默认 96dp，实现可调常量） */
private val MD_TABLE_MIN_COLUMN_WIDTH = 96.dp
/** F2.18（M21）：单列宽度上限 = 可用宽的 50%（评审点拍板，防止超长单元格吞掉整行） */
private const val MD_TABLE_MAX_COLUMN_FRACTION = 0.5f
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
 * - 列宽（M21 修订）：按内容占比自适应——测量每列代表宽度（表头与表体均值取大），经
 *   [allocateTableColumnWidths] 分配（单列上限 50% 可用宽 / 最小列宽保底 / 余量按占比再分配撑满）；
 *   各行共享同一列宽列表（禁止逐行独立测量）；总宽超屏时容器横向滚动，横向滚动不抢占 LazyColumn 垂直滑动。
 * - 长按复制：复用外层 SelectionContainer（MarkdownPreview 已包裹），单元格不新造交互。
 * - 无障碍：容器 semantics 描述行列数（stringResource 中英双语）并携带列宽语义键；表头单元格加粗可辨，
 *   不做复杂 table 语义角色。
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
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val available = maxWidth
        // F2.18（M21）：测量按 Table 块内容缓存（remember 键），同一表格不重复测量（大表性能兜底见 TC-UI-23）
        val targets = remember(header, rows, base, textMeasurer, density) {
            measureTableColumnTargets(header, rows, base, textMeasurer, density)
        }
        val columnWidths = allocateTableColumnWidths(targets, available)
        val totalWidth = columnWidths.fold(0.dp) { acc, w -> acc + w }
        val needsScroll = totalWidth > available + 0.5.dp
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .semantics {
                    contentDescription = semanticsText
                    this[MdTableHorizontalScrollKey] = needsScroll
                    this[MdTableColumnWidthsKey] = columnWidths
                }
        ) {
            Column(modifier = Modifier.width(totalWidth)) {
                TableRow(header, aligns, columnWidths, base, isHeader = true)
                DividerLine(theme, MD_TABLE_HEADER_DIVIDER_ALPHA)
                rows.forEachIndexed { index, row ->
                    TableRow(row, aligns, columnWidths, base, isHeader = false)
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
    columnWidths: List<Dp>,
    base: androidx.compose.ui.text.TextStyle,
    isHeader: Boolean
) {
    Row {
        cells.forEachIndexed { index, inlines ->
            TableCell(inlines, aligns.getOrNull(index), columnWidths.getOrNull(index) ?: MD_TABLE_MIN_COLUMN_WIDTH, base, isHeader)
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
    Text(buildInlineAnnotatedString(inlines), style = style, modifier = modifier)
}

/**
 * F2.18（M21）：把行内片段构造为 [AnnotatedString]，测量与渲染共用同一构造逻辑（防止两版文本漂移）。
 * 加粗/斜体/行内代码以 SpanStyle 应用；表格单元格测量经此拿到与渲染一致的文本。
 */
private fun buildInlineAnnotatedString(inlines: List<MdInline>): AnnotatedString {
    return buildAnnotatedString {
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
}

// ---------------- F2.18（M21）列宽测量与分配 ----------------

/** 单元格文本单行不换行的最大固有宽度（即 maxIntrinsicWidth），换算为 dp（与渲染所用密度一致） */
private fun maxIntrinsicWidthDp(
    inlines: List<MdInline>,
    style: androidx.compose.ui.text.TextStyle,
    textMeasurer: TextMeasurer,
    density: Density
): Dp {
    val annotated = buildInlineAnnotatedString(inlines)
    val layout = textMeasurer.measure(
        text = annotated,
        style = style,
        overflow = TextOverflow.Clip,
        softWrap = false,
        maxLines = 1,
        density = density
    )
    return with(density) { layout.size.width.toDp() }
}

/**
 * F2.18（M21）：计算每列代表宽度 target_j = max（表头单元格宽度，表体该列所有单元格宽度的平均值）。
 * 平均避免单个超长单元格主导；表头参与保证短列表头不被压没。表头按加粗样式测量（与渲染一致）。
 */
private fun measureTableColumnTargets(
    header: List<List<MdInline>>,
    rows: List<List<List<MdInline>>>,
    base: androidx.compose.ui.text.TextStyle,
    textMeasurer: TextMeasurer,
    density: Density
): List<Dp> {
    val headerStyle = base.copy(fontWeight = FontWeight.Bold)
    return header.indices.map { col ->
        val headerWidth = maxIntrinsicWidthDp(header[col], headerStyle, textMeasurer, density).value
        val bodyCells = rows.map { row -> row.getOrNull(col) ?: emptyList() }
        val bodyAvg = if (bodyCells.isEmpty()) 0f else
            bodyCells.map { maxIntrinsicWidthDp(it, base, textMeasurer, density).value }.average().toFloat()
        maxOf(headerWidth, bodyAvg).dp
    }
}

/**
 * F2.18（M21）：列宽分配纯函数（可单测，TC-UI-23）。
 * - cap_j = min(target_j, CAP)，CAP = 可用宽 × [MD_TABLE_MAX_COLUMN_FRACTION]（单列上限 50%）。
 * - sum ≤ W：余量按占比再分配撑满屏宽，结果钳制在 [MIN, CAP]（含 MIN 保底 / CAP 上限）。
 * - sum > W：按 W/sum 缩放并 MIN 保底；若仍超屏（列数极多），回退为 cap_j 并保留横向滚动。
 * - 全 0（空单元格表格）特判为等分 W/n（避免除零）。
 */
internal fun allocateTableColumnWidths(
    targets: List<Dp>,
    available: Dp,
    minWidth: Dp = MD_TABLE_MIN_COLUMN_WIDTH
): List<Dp> {
    val n = targets.size
    if (n == 0) return emptyList()
    val cap = maxOf(available * MD_TABLE_MAX_COLUMN_FRACTION, minWidth)
    val caps = targets.map { minOf(it, cap) }
    val sum = caps.fold(0.dp) { acc, w -> acc + w }
    if (sum.value <= 0f) return List(n) { available / n }
    return if (sum <= available) {
        val surplus = available - sum
        caps.map { c ->
            val filled = c + surplus * (c.value / sum.value)
            filled.coerceIn(minWidth, cap)
        }
    } else {
        val ratio = available.value / sum.value
        val scaled = caps.map { c -> maxOf(minWidth, c * ratio) }
        val scaledSum = scaled.fold(0.dp) { acc, w -> acc + w }
        if (scaledSum <= available + 0.5.dp) scaled else caps
    }
}
