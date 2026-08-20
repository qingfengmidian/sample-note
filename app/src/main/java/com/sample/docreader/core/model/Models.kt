package com.sample.docreader.core.model

import androidx.compose.ui.graphics.Color

/** 支持的文档格式 */
enum class DocFormat { EPUB, XML, MD, TXT }

/** 统一的文档内存模型：所有格式解析后都归一化为此结构 */
data class Document(
    val uri: String,             // 内容 URI（SAF）或 asset:/// 桩
    val name: String,
    val format: DocFormat,
    val chapters: List<Chapter>  // txt/md/xml 为单章；epub 为多章
)

data class Chapter(
    val title: String?,          // 无标题可为 null
    val paragraphs: List<String>,// 段落级纯文本，渲染/编辑的基础单位
    val level: Int = 1           // 章节层级（1 起）：epub 目录嵌套用于章节列表缩进，单章格式恒为 1
)

enum class WrapMode { WRAP, NO_WRAP }

/**
 * 字体方案；均不打包字体文件。
 * CODE 为默认"代码风格"：英文/数字走系统等宽字体（编辑器风格），
 * 中文由系统字体回退机制自动落到默认中文字体（等线风格）。
 * 等线为微软商业字体，不可随应用分发；需要真等线的用户走 CUSTOM 导入自备 ttf。
 */
enum class ReaderFont { CODE, SERIF, SANS, CUSTOM }

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
