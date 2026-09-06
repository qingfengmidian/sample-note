package com.sample.docreader.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sample.docreader.core.model.ParagraphImage
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 正文容器 testTag，供 UI 测试断言主题背景色 */
const val READER_CONTENT_TAG = "reader_content"

/** 主题/字体语义键：供 UI 测试断言排版样式与 ReadingTheme/ReaderFont 枚举一致（TC-UI-03） */
val ReaderBackgroundColorKey = SemanticsPropertyKey<Color>("ReaderBackgroundColor")
val ReaderForegroundColorKey = SemanticsPropertyKey<Color>("ReaderForegroundColor")
val ReaderFontFamilyKey = SemanticsPropertyKey<FontFamily>("ReaderFontFamily")

/** 主题解析：null = 跟随系统深浅色（浅色→DEFAULT，深色→NIGHT） */
@Composable
fun resolveReadingTheme(theme: ReadingTheme?): ReadingTheme =
    theme ?: if (isSystemInDarkTheme()) ReadingTheme.NIGHT else ReadingTheme.DEFAULT

/** 字体映射：CUSTOM 无字体文件时回退 Monospace（TC-UI-03 断言仍以此为准） */
fun fontFamilyOf(font: ReaderFont): FontFamily = when (font) {
    ReaderFont.CODE, ReaderFont.CUSTOM -> FontFamily.Monospace
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.SANS -> FontFamily.SansSerif
}

/**
 * CUSTOM 字体解析：customFontPath 有效时加载用户经 SAF 导入的 ttf/otf；
 * 加载失败或缺省回退 Monospace。非 CUSTOM 字体直接走 [fontFamilyOf]。
 */
@Composable
fun fontFamilyFor(font: ReaderFont, customFontPath: String?): FontFamily {
    val custom = remember(font, customFontPath) {
        if (font != ReaderFont.CUSTOM) return@remember null
        val path = customFontPath
        if (path.isNullOrBlank()) null
        else runCatching { FontFamily(Font(File(path))) }.getOrNull()
    }
    return custom ?: fontFamilyOf(font)
}

/** 行距公式：lineHeight = fontSizeSp * lineHeightPercent / 100（设计§3.3） */
internal fun lineHeightSp(settings: ReadingSettings) =
    (settings.fontSizeSp * settings.lineHeightPercent / 100).sp

/**
 * 阅读排版组件（设计§3.3 + §3.3 epub 富文本 F2.14）：
 * - WRAP：段落 Text 充满宽度自然折行；NO_WRAP：每段放入可水平滚动的 Row，整行绘制；
 *   两种模式段落间都分行；
 * - F2.14 图片：渲染在所属段落 item 内（**不新增 LazyColumn 项**，item 索引 == 段落索引不变量，评审 S8）——
 *   封面图（isCover）在首段文字上方，其余图在所属段落文字下方；svg/解码失败/文件缺失给占位提示（S9 语义）；
 * - 背景色应用容器、前景色应用正文 Text；整体包 SelectionContainer 支持长按选择复制（F2.12）。
 */
@Composable
fun ReaderTextView(
    paragraphs: List<String>,
    settings: ReadingSettings,
    listState: LazyListState,
    images: List<ParagraphImage> = emptyList(),
    imageRoot: String? = null,
    modifier: Modifier = Modifier
) {
    val theme = resolveReadingTheme(settings.theme)
    val textStyle = TextStyle(
        color = theme.foreground,
        fontSize = settings.fontSizeSp.sp,
        lineHeight = lineHeightSp(settings),
        fontFamily = fontFamilyFor(settings.font, settings.customFontPath)
    )
    // 段落 → 图片分组（仅所属段落的图在本 item 内渲染，评审 S8）
    val imagesByParagraph = remember(images) { images.groupBy { it.paragraphIndex } }
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
            itemsIndexed(paragraphs, key = { index, _ -> index }) { itemIndex, paragraph ->
                val itemImages = imagesByParagraph[itemIndex].orEmpty()
                val covers = itemImages.filter { it.isCover }      // 封面：该段文字上方
                val below = itemImages.filter { !it.isCover }      // 正文图：该段文字下方
                Column(modifier = Modifier.fillMaxWidth()) {
                    covers.forEach {
                        ChapterImage(it, imageRoot, theme.foreground, imageVerticalPad = 4.dp)
                    }
                    val itemModifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                    when (settings.wrapMode) {
                        WrapMode.WRAP -> Text(paragraph, style = textStyle, modifier = itemModifier)
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
                    below.forEach {
                        ChapterImage(it, imageRoot, theme.foreground, imageVerticalPad = 4.dp)
                    }
                }
            }
        }
    }
}

/** 解码失败的图片解码结果载体（bitmap + 目标宽=2×屏宽采样） */
private class DecodedImage(val bitmap: Bitmap)

/**
 * 段落内单张图片渲染（F2.14/设计 §3.3 图片管线）：
 * - 解码在 [Dispatchers.IO]（produceState 随 item 可见才进入组合 = LazyColumn 天然懒加载）；
 * - `BitmapFactory` 先 inJustDecodeBounds 再 inSampleSize，目标宽 = 2×屏宽 px 防大图 OOM；
 * - 等比宽高按图片原始尺寸 + Modifier.aspectRatio 保持；
 * - svg / 解码失败 / 文件缺失 / 缓存不可用（imageRoot 空）统一占位提示：主题前景图标 + 文案，
 *   alt 非空时显示 alt；占位与图片均带 contentDescription（评审 S9）。
 */
@Composable
internal fun ChapterImage(
    image: ParagraphImage,
    imageRoot: String?,
    foreground: Color,
    imageVerticalPad: Dp
) {
    val alt = image.alt?.takeIf { it.isNotBlank() }
    val placeholderDesc = alt ?: "图片无法显示"
    val resolvedFile = resolveImageFile(image, imageRoot)
    // svg 一律不直接渲染；缓存不可用/文件缺失 → 占位（不触发解码）
    if (image.src.endsWith(".svg", ignoreCase = true) || resolvedFile == null) {
        ImagePlaceholder(alt = alt, foreground = foreground, description = placeholderDesc)
        return
    }
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
    // 目标解码上限 = 2×屏宽 / 2×屏高（宽高双维度约束，极窄超高图同样限总量，L1）
    val targetWidthPx = screenWidthPx * 2
    val targetHeightPx = screenHeightPx * 2
    val path = resolvedFile
    val decodeKey = Triple(path, targetWidthPx, targetHeightPx)
    val decoded by produceState<DecodedImage?>(initialValue = null, decodeKey) {
        value = withContext(Dispatchers.IO) {
            decodeSampled(path, targetWidthPx, targetHeightPx)?.let { DecodedImage(it) }
        }
    }
    val bitmap = decoded?.bitmap
    if (bitmap == null) {
        ImagePlaceholder(alt = alt, foreground = foreground, description = placeholderDesc)
        return
    }
    // 无 alt 的图给通用语义（封面给"封面"），见需求 F2.14/S9
    val description = alt ?: if (image.isCover) "封面" else "图片"
    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = description,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = imageVerticalPad)
            .aspectRatio(ratio)
    )
}

/**
 * 图片 src → 缓存目录内文件绝对路径；目录不可用或文件不存在返回 null（渲染占位）。
 * L3（评审）：文件名含空格等字符时 XHTML src 常以百分号编码（如 %20），直接查找失败后
 * 尝试 URLDecoder 解码（UTF-8）后再找一次。
 */
private fun resolveImageFile(image: ParagraphImage, imageRoot: String?): String? {
    val root = imageRoot ?: return null
    if (image.src.isBlank()) return null
    fun find(name: String): String? {
        val f = File(root, name)
        return if (f.isFile) f.absolutePath else null
    }
    find(image.src)?.let { return it }
    return runCatching { find(java.net.URLDecoder.decode(image.src, "UTF-8")) }.getOrNull()
}

/**
 * 采样解码（防大图 OOM，设计 §3.3/§8 + L1 评审）：
 * 先 decodeBounds 得原始宽高，再取最大 2 的幂 inSampleSize 使解码宽/高均落在上限
 * （目标 = 2×屏宽/屏高）之下——宽高双维度采样，极窄超高图也限总量；
 * decodeFile 解码失败返回 null；**OutOfMemoryError 是 Error，非 null 分支能接住**，单独捕获亦返回 null 走占位。
 */
internal fun decodeSampled(filePath: String, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(filePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null // 文件缺失/截断损坏
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidthPx ||
        bounds.outHeight / (sample * 2) >= targetHeightPx
    ) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return try {
        BitmapFactory.decodeFile(filePath, opts)
    } catch (e: OutOfMemoryError) {
        null // OOM：回退占位，不崩溃
    }
}

/** 占位提示：主题前景图标 + 「图片无法显示」（alt 非空时显示 alt），带 contentDescription（S9）。
 *  M2（评审）：容器用 clearAndSetSemantics 收敛——清除子 Text/图标的自身语义，
 *  避免 TalkBack 对 contentDescription 与子文本双重播报；视觉文案仍正常显示。 */
@Composable
internal fun ImagePlaceholder(
    alt: String?,
    foreground: Color,
    description: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .heightIn(min = 64.dp)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(alt ?: "图片无法显示", color = foreground)
        }
    }
}
