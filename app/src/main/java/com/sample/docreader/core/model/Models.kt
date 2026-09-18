package com.sample.docreader.core.model

import androidx.compose.ui.graphics.Color

/** 支持的文档格式 */
enum class DocFormat { EPUB, XML, MD, TXT }

/** 统一的文档内存模型：所有格式解析后都归一化为此结构 */
data class Document(
    val uri: String,             // 内容 URI（SAF）或 asset:/// 桩
    val name: String,
    val format: DocFormat,
    val chapters: List<Chapter>, // txt/md/xml 为单章；epub 为多章
    val toc: List<TocEntry> = emptyList(), // 文档目录锚点（F2.13，md 由标题提取；epub 可留空，章节跳转走 chapters）
    val imageRoot: String? = null // F2.14：epub 解压缓存目录绝对路径（缓存成功时非空，渲染层据 src 拼文件路径）；
                                  // txt/md/xml 恒 null；缓存不可用回退字节解析时也 null（图片走占位）
)

data class Chapter(
    val title: String?,          // 无标题可为 null
    val paragraphs: List<String>,// 段落级纯文本，渲染/编辑的基础单位
    val level: Int = 1,          // 章节层级（1 起）：epub 目录嵌套用于章节列表缩进，单章格式恒为 1
    val images: List<ParagraphImage> = emptyList(), // F2.14 段落内图片（仅 epub 非空）：挂所属段落 item 内渲染，
                                                    // 不新增 LazyColumn 项——段落索引稳定（评审 S8）
    val styles: Map<Int, ParagraphStyleHints> = emptyMap(), // F2.15 CSS 样式提示（仅 epub 非空；默认空表零影响）：
                                                           // 段落索引 → 相对样式，LazyColumn item 索引 == 段落索引恒成立
    val headingLevels: List<Int?> = emptyList() // F2.17 标题层级（仅 epub 非空）：与 paragraphs 等长、
                                                // 1~6 = h1~h6，null = 非标题。与 styles **解耦**——
                                                // 标题段无任何 CSS 命中（styles 无该索引）时渲染层仍能取到层级走默认标题样式
)

/**
 * 段落内图片（F2.14）：src 为 epub zip 内路径（按 manifest href 形式规范化，如 "OEBPS/images/x.png"），
 * 渲染层以 Document.imageRoot + src 定位缓存文件；svg 一律不直接渲染（渲染层给占位提示，评审 S6/S9）。
 */
data class ParagraphImage(
    val paragraphIndex: Int,     // 所属段落索引：图片渲染在该段落 item 内（文字下方；封面在首段文字上方）
    val src: String,
    val alt: String? = null,     // 无障碍语义描述来源（img @alt）；占位提示优先显示 alt
    val isCover: Boolean = false // 封面图（OPF cover 声明）：挂 paragraphIndex=0，按屏宽置顶渲染
)

/**
 * CSS 子集相对样式提示（F2.15，设计 §3.1/§3.3）：全部为"相对全局设置的偏移/开关"，
 * 渲染层负责与全局设置合并（全局字号/行距/字体/主题为**绝对基准**）。
 * null = 未指定（走全局/默认）；bold/italic 用 Boolean? 三态以支持"显式非粗/非斜"覆盖继承语义。
 */
data class ParagraphStyleHints(
    val fontSizeEm: Float? = null,       // 相对字号倍率（如 1.2f）；渲染时 clamp 0.5~3.0 后乘全局字号
    val lineHeightEm: Float? = null,     // 相对行距倍率；同上 clamp 后乘全局行距
    val bold: Boolean? = null,           // null 未指定；true 加粗 / false 非粗
    val italic: Boolean? = null,         // null 未指定；true 斜体 / false 非斜
    val textAlign: Int? = null,          // 0 左 1 中 2 右（text-align 子集）；null 未指定
    val colorArgb: Int? = null,          // CSS 颜色（#AARRGGBB，不透明 alpha=FF）；对比度 <4.5:1 由渲染层回退主题前景色
    val paragraphSpacing: Boolean = false // margin/padding 垂直分量 0/非 0 两档段距（true = 非 0，item 间加段距）
)

/**
 * 目录条目（F2.13 md / F2.16 epub）：
 * - md：把标题提取为目录项，锚定单章段落流中的段落索引。段落索引是渲染/进度/toc 的统一坐标
 *   （源码 LazyColumn item 索引 == 段落索引），预览视图跳转经 MarkdownBlock↔段落索引映射换算
 *   （见 MarkdownParser.startParagraphIndex）。
 * - epub（F2.16，M12）：条目 = 文件自带目录（nav/NCX）原样；[chapterIndex] 为目标章（spine 序），
 *   [anchorParagraph] 为锚点段落（href 的 #fragment 经章节锚点表换算；无 fragment/未命中/章首为 0），
 *   [paragraphIndex] 恒 0（md 单章坐标语义保留）。
 */
data class TocEntry(
    val level: Int,            // 1~6（对应 ATX H1~H6）
    val title: String,         // 标题纯文本（去除 # 前缀与行内标记符号，评审 S10；F2.13 REV8：链接保留文字）
    val paragraphIndex: Int,   // md：标题所在段落索引（源码视图滚动目标；预览经块↔段落映射）
    val chapterIndex: Int = 0, // epub（F2.16）：目标章索引（spine 序）；md 恒 0
    val anchorParagraph: Int? = null // epub（F2.16）：锚点段落；md 为 null（用 paragraphIndex 语义）
)

enum class WrapMode { WRAP, NO_WRAP }

/**
 * 字体方案；均不打包字体文件。
 * CODE 为默认"代码风格"：英文/数字走系统等宽字体（编辑器风格），
 * 中文由系统字体回退机制自动落到默认中文字体（等线风格）。
 * 等线为微软商业字体，不可随应用分发；需要真等线的用户走 CUSTOM 导入自备 ttf。
 */
enum class ReaderFont { CODE, SERIF, SANS, CUSTOM }

/**
 * 应用内语言档位（F4.9/M16）：SYSTEM = 跟随系统（默认；DataStore 键缺失或值未知即此档）。
 * 标签读写与生效机制见 LocaleSwitcher；UI 侧经 settings_language_* 资源映射，本枚举不引用资源。
 */
enum class AppLanguage { SYSTEM, ZH, EN }

/** 阅读主题：前景/背景成对搭配；颜色使用 Compose Color */
enum class ReadingTheme(val background: Color, val foreground: Color) {
    DEFAULT(Color(0xFFFAFAFA), Color(0xFF1B1B1F)),    // 默认白（微淡灰白，降低 OLED 亮度冲击）
    EYE_CARE(Color(0xFFC7EDCC), Color(0xFF2B3A2E)),   // 护眼绿（豆沙绿）
    SOFT_LIGHT(Color(0xFFF5F0E1), Color(0xFF3D3A30)), // 柔光米黄（暖黄低亮度）
    NIGHT(Color(0xFF121212), Color(0xFFB8B8B8))       // 夜间黑
}

data class ReadingSettings(
    val fontSizeSp: Int = 16,           // 适中默认值
    val lineHeightPercent: Int = 150,   // 行距 Int 百分值（150 = 1.5 倍）；档位 120/150/180/200
    val wrapMode: WrapMode = WrapMode.WRAP,
    val font: ReaderFont = ReaderFont.CODE, // 默认代码风格，与需求 F2.4 一致
    val theme: ReadingTheme? = null,    // null = 跟随系统深浅色（浅色→DEFAULT，深色→NIGHT）
    val autoSave: Boolean = false,      // md/txt 自动保存开关（F3.4），默认关闭
    val customFontPath: String? = null  // CUSTOM 字体经 SAF 导入后复制到 files/fonts/ 的绝对路径（M4）
)

/** 解析层受控异常，UI 按类型给出差异化提示（需求"稳定性"条目） */
sealed class ParseException(message: String) : Exception(message) {
    class UnsupportedFormat : ParseException("不支持的格式")
    class MissingOpf : ParseException("epub 缺少 OPF 描述文件，无法解析")
    class MalformedContent(cause: Throwable) :
        ParseException("内容解析失败：${cause.message ?: cause::class.simpleName}")
    class TooLarge : ParseException("文件过大，超出可编辑上限")
    class NoPermission : ParseException("文件访问权限不足或已被移动/删除")
}
