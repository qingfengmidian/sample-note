package com.sample.docreader.core.parse

import android.content.ContentResolver
import android.net.Uri
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.ParagraphImage
import com.sample.docreader.core.model.ParagraphStyleHints
import com.sample.docreader.core.model.ParseException
import com.sample.docreader.core.model.TocEntry as ModelTocEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

// ---------------- F2.15 CSS 子集（《软件设计文档》§3.2-6） ----------------

/** 行内元素集：不在段落边界触发 flush，也不作为段样式归属（整段样式取最近的块级祖先） */
private val inlineTags = setOf(
    "a", "abbr", "b", "bdi", "bdo", "big", "cite", "code", "del", "dfn", "em",
    "font", "i", "ins", "kbd", "label", "mark", "q", "ruby", "rp", "rt", "s",
    "samp", "small", "span", "strike", "strong", "sub", "sup", "time", "tt", "u", "var", "wbr"
)

/** 空/自闭合元素（不压样式帧、结束标签不 flush 不 pop）：img 在抽取处单独处理；br/hr 为段落边界在 START 处单独 flush */
private val voidTags = setOf(
    "meta", "link", "base", "input", "source", "area", "track", "param", "col", "br", "hr"
)

/** 支持的选择器：元素 / element.class / .class / #id（后代/组合/伪类/@ 等一律忽略） */
internal data class CssSelector(
    val tag: String? = null,      // 元素选择器（小写）；null = 无元素限定
    val className: String? = null,
    val idName: String? = null
) {
    /** 近似优先级分值（子集内至少一个 id/class/元素）：id > element.class > class > element */
    fun specificity(): Int = (if (idName != null) 100 else 0) + (if (className != null) 10 else 0) + (if (tag != null) 1 else 0)
}

/** 一条规则的已解析声明（原始值；长度/颜色等在帧构建时结合父级上下文换算）。spacing 为 margin/padding 垂直分量合并档位 */
internal class CssDecls(
    var fontSizeRaw: String? = null,
    var lineHeightRaw: String? = null,
    var weightRaw: String? = null,
    var styleRaw: String? = null,
    var alignRaw: String? = null,
    var colorRaw: String? = null,
    var spacing: Boolean? = null
) {
    /** 层叠覆盖：后定义/更高优先级声明非空即覆盖（CSS 属性级 last-wins） */
    fun overrideWith(o: CssDecls) {
        o.fontSizeRaw?.let { fontSizeRaw = it }
        o.lineHeightRaw?.let { lineHeightRaw = it }
        o.weightRaw?.let { weightRaw = it }
        o.styleRaw?.let { styleRaw = it }
        o.alignRaw?.let { alignRaw = it }
        o.colorRaw?.let { colorRaw = it }
        o.spacing?.let { spacing = it }
    }
}

/** 一条样式规则：selector 为 null 表示该选择器不在子集（调用方已忽略），declarations 为声明 */
internal class CssRule(
    val selector: CssSelector,
    val declarations: CssDecls,
    val order: Int // 定义先后（后定义覆盖先定义）
)

/**
 * F2.15 CSS 子集解析（纯函数，无 Android 依赖）：
 * 选择器仅元素 / element.class / .class / #id；属性仅 font-size/line-height/font-weight/font-style/
 * text-align/color/margin·padding 垂直分量；一切未识别属性/选择器静默忽略；解析失败不抛异常。
 */
internal object CssSubset {

    private val tokenRe = Regex("[A-Za-z0-9_\\-]+")
    private val lengthRe = Regex("^(-?\\d+(\\.\\d+)?|\\.\\d+)([a-z%]*)$")
    private val importantRe = Regex("\\s*!important\\s*$")
    private val verticalProps = setOf(
        "margin-top", "margin-bottom", "padding-top", "padding-bottom", "margin", "padding"
    )

    private val namedColors = mapOf(
        "black" to 0x000000, "silver" to 0xC0C0C0, "gray" to 0x808080, "grey" to 0x808080,
        "white" to 0xFFFFFF, "maroon" to 0x800000, "red" to 0xFF0000, "purple" to 0x800080,
        "fuchsia" to 0xFF00FF, "green" to 0x008000, "lime" to 0x00FF00, "olive" to 0x808000,
        "yellow" to 0xFFFF00, "navy" to 0x000080, "blue" to 0x0000FF, "teal" to 0x008080,
        "aqua" to 0x00FFFF, "orange" to 0xFFA500
    )

    /** 选择器解析：仅支持四种；其余（后代/组合/伪类/属性/@ 等）返回 null 静默忽略 */
    fun parseSelector(raw: String): CssSelector? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.any { it in " \t\n>+~[]*:()@&^$=|,{}!;\"'" }) return null
        return when {
            s.startsWith('#') -> {
                val id = s.substring(1)
                if (id.isNotEmpty() && tokenRe.matches(id)) CssSelector(idName = id) else null
            }
            s.startsWith('.') -> {
                val c = s.substring(1)
                if (c.isNotEmpty() && !c.contains('.') && tokenRe.matches(c)) CssSelector(className = c) else null
            }
            s.contains('.') -> {
                // element.class（仅允许一个点）
                val idx = s.indexOf('.')
                if (s.indexOf('.', idx + 1) != -1) return null
                val tag = s.substring(0, idx)
                val c = s.substring(idx + 1)
                if (c.isEmpty() || c.contains('.')) return null
                if (!tokenRe.matches(c)) return null
                if (tag.isNotEmpty() && !tokenRe.matches(tag)) return null
                CssSelector(tag = tag.lowercase().ifEmpty { null }, className = c)
            }
            else -> if (tokenRe.matches(s)) CssSelector(tag = s.lowercase()) else null
        }
    }

    /**
     * 长度 → 相对 16sp 基准的**绝对倍率**（设计 §3.2-6）：
     * px/pt 与父级无关（24px=18pt=1.5×基准）；em/% 及（行距）无单位为相对父级乘子。
     * 支持单位之外的输入（rem/关键字等）返回 null。
     */
    fun lengthToEm(raw: String, parentEm: Float, allowUnitless: Boolean): Float? {
        val v = raw.trim().lowercase()
        if (v.isEmpty()) return null
        val num: Float
        val unit: String
        when {
            v.endsWith("%") -> {
                num = v.dropLast(1).toFloatOrNull() ?: return null
                return parentEm * num / 100f
            }
            v.endsWith("em") -> {
                num = v.dropLast(2).toFloatOrNull() ?: return null
                return parentEm * num
            }
            v.endsWith("px") -> {
                num = v.dropLast(2).toFloatOrNull() ?: return null
                return num / 16f // 16sp 基准
            }
            v.endsWith("pt") -> {
                num = v.dropLast(2).toFloatOrNull() ?: return null
                return num * 4f / 3f / 16f // 1pt = 4/3 px
            }
            else -> {
                if (!allowUnitless) return null // 字号不允许无单位
                num = v.toFloatOrNull() ?: return null
                return parentEm * num
            }
        }
    }

    /** 字重：≥600 或 bold/bolder → true（粗）；normal/lighter/<600 → false（非粗）；不可解析 → null */
    fun weightToBold(raw: String): Boolean? {
        val v = raw.trim().lowercase()
        if (v == "bold" || v == "bolder") return true
        if (v == "normal" || v == "lighter") return false
        val n = v.toFloatOrNull() ?: return null
        return n >= 600f
    }

    /** font-style：italic/oblique → true；normal → false；其余 null */
    fun fontStyleItalic(raw: String): Boolean? {
        val v = raw.trim().lowercase()
        if (v == "italic" || v == "oblique") return true
        if (v == "normal") return false
        return null
    }

    /** text-align 子集：left/center/right → 0/1/2；其余（含 justify）不支持 → null */
    fun textAlignValue(raw: String): Int? {
        val v = raw.trim().lowercase()
        if (v == "left") return 0
        if (v == "center") return 1
        if (v == "right") return 2
        return null
    }

    /** CSS 颜色 → #AARRGGBB（alpha=FF）Int；仅 #rgb/#rrggbb/rgb()/命名色子集；其余返回 null */
    fun parseColor(raw: String): Int? {
        val v = raw.trim().lowercase()
        if (v.isEmpty()) return null
        if (v.startsWith('#')) {
            val hex = v.substring(1)
            return when (hex.length) {
                3 -> {
                    val expanded = hex.map { "$it$it" }.joinToString("")
                    argbOfRgb(expanded.toLongOrNull(16) ?: return null)
                }
                6 -> argbOfRgb(hex.toLongOrNull(16) ?: return null)
                else -> null
            }
        }
        if (v.startsWith("rgb(") && v.endsWith(")")) {
            val inner = v.removePrefix("rgb(").removeSuffix(")")
            val tokens = if (inner.contains(',')) inner.split(',') else inner.split(Regex("\\s+"))
            if (tokens.size != 3) return null
            val rgb = tokens.map { t ->
                val tok = t.trim()
                if (tok.isEmpty()) return null
                if (tok.endsWith("%")) {
                    val pct = tok.dropLast(1).toFloatOrNull() ?: return null
                    (pct / 100f * 255f).toInt().coerceIn(0, 255)
                } else {
                    val n = tok.toIntOrNull() ?: return null
                    n.coerceIn(0, 255)
                }
            }
            return argbOfRgb(((rgb[0].toLong() shl 16) or (rgb[1].toLong() shl 8) or rgb[2].toLong()))
        }
        return namedColors[v]?.let { argbOfRgb(it.toLong()) }
    }

    private fun argbOfRgb(rgb: Long): Int = (0xFF000000L or rgb).toInt()

    /** 解析声明块文本 → CssDecls；未知属性/非法条目静默忽略（逐条，不影响其余） */
    fun parseDeclarations(text: String): CssDecls {
        val decls = CssDecls()
        // margin/padding 垂直分量：分侧累计后归并为 0/非 0 两档（做不了精确排版，设计 §3.2-6）
        var mTop: Boolean? = null
        var mBottom: Boolean? = null
        var pTop: Boolean? = null
        var pBottom: Boolean? = null
        for (seg in text.split(';')) {
            val colon = seg.indexOf(':')
            if (colon <= 0) continue
            val prop = seg.substring(0, colon).trim().lowercase()
            val value = seg.substring(colon + 1).replace(importantRe, "").trim()
            if (value.isEmpty()) continue
            when (prop) {
                "font-size" -> decls.fontSizeRaw = value
                "line-height" -> decls.lineHeightRaw = value
                "font-weight" -> decls.weightRaw = value
                "font-style" -> decls.styleRaw = value
                "text-align" -> decls.alignRaw = value
                "color" -> decls.colorRaw = value
                "margin-top", "margin-bottom", "padding-top", "padding-bottom" -> {
                    val b = verticalNonZero(value) ?: continue
                    when (prop) {
                        "margin-top" -> mTop = b
                        "margin-bottom" -> mBottom = b
                        "padding-top" -> pTop = b
                        "padding-bottom" -> pBottom = b
                    }
                }
                "margin", "padding" -> {
                    val tokens = value.split(Regex("\\s+"))
                    if (tokens.size !in 1..4) continue
                    // 简写垂直分量取位：1 值四边同；2 值 [上下, 左右] → bottom=top=token0；
                    // 3 值 [上, 左右, 下] 与 4 值 [上, 右, 下, 左] → bottom=token2（审查修复：原 2/3 值取位错误）
                    val top = tokens[0]
                    val bottom = when (tokens.size) {
                        1, 2 -> tokens[0]
                        3, 4 -> tokens[2]
                        else -> continue
                    }
                    val tb = verticalNonZero(top) ?: continue
                    val bb = verticalNonZero(bottom) ?: continue
                    if (prop == "margin") { mTop = tb; mBottom = bb } else { pTop = tb; pBottom = bb }
                }
                else -> {} // 其余属性一律静默忽略
            }
        }
        val vertical = listOfNotNull(mTop, mBottom, pTop, pBottom)
        if (vertical.isNotEmpty()) decls.spacing = vertical.any { it }
        return decls
    }

    /** 单值垂直分量是否非 0：0/0px/0em/auto → false；非 0 数值 → true；无法判读（calc 等）→ null */
    private fun verticalNonZero(token: String): Boolean? {
        val t = token.trim().lowercase()
        if (t in setOf("auto", "inherit", "initial", "unset", "revert")) return false
        val m = lengthRe.find(t) ?: return null
        val num = m.groupValues[1].toDouble()
        return num != 0.0
    }

    /**
     * 解析 `<style>` 文本 → 规则列表。非法语法静默恢复（未闭合花括号丢弃余下；@ 语句/未知选择器忽略）。
     * 解析失败返回空表——该书该章回退纯文本（保底可读，设计 §3.2-3）。
     *
     * [startOrder] 为跨 `<style>` 块的**全局已累计规则数**：order 必须全书递增，否则"前表表内靠后规则"
     * 与"后表规则"同优先级时因 order 各自从 0 重计而无法区分先后（CSS 语义：后定义的样式表应赢）。
     */
    fun parseStyleSheet(css: String, startOrder: Int = 0): List<CssRule> {
        val text = css.replace(Regex("(?s)/\\*.*?\\*/"), "")
        val rules = mutableListOf<CssRule>()
        var order = startOrder
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('{', i)
            if (open < 0) break
            // @charset/@import 等不以 { 结尾的语句段：跳到下一个 ';' 继续
            val semi = text.indexOf(';', i)
            if (semi in 0 until open) {
                i = semi + 1
                continue
            }
            // 匹配闭合 '}'（统计嵌套花括号深度）
            var depth = 0
            var close = -1
            for (j in open until text.length) {
                if (text[j] == '{') depth++
                else if (text[j] == '}') {
                    depth--
                    if (depth == 0) { close = j; break }
                }
            }
            if (close < 0) break // 未闭合：放弃余下（静默）
            val selRaw = text.substring(i, open).trim()
            val declRaw = text.substring(open + 1, close).trim()
            if (declRaw.isNotEmpty()) {
                splitSelectorList(selRaw).forEach { part ->
                    CssSubset.parseSelector(part)?.let { selector ->
                        rules += CssRule(selector, parseDeclarations(declRaw), order++)
                    }
                }
            }
            i = close + 1
        }
        return rules
    }

    /** 顶层逗号切分选择器列表（简单子集内无需嵌套，保守忽略括号/引号内容） */
    private fun splitSelectorList(raw: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var quote = '\u0000'
        val sb = StringBuilder()
        for (ch in raw) {
            if (quote != '\u0000') {
                if (ch == quote) quote = '\u0000'
                sb.append(ch)
            } else when (ch) {
                '\'', '"' -> { quote = ch; sb.append(ch) }
                '(', '[' -> { depth++; sb.append(ch) }
                ')', ']' -> { depth = (depth - 1).coerceAtLeast(0); sb.append(ch) }
                ',' -> if (depth == 0) { parts += sb.toString().trim(); sb.clear() } else sb.append(ch)
                else -> sb.append(ch)
            }
        }
        if (sb.isNotBlank()) parts += sb.toString().trim()
        return parts.filter { it.isNotEmpty() }
    }
}

/** 目录条目：标题、目标 href、嵌套层级（1 起） */
internal data class TocEntry(val title: String, val href: String, val level: Int)

/**
 * 章节抽取结果：段落纯文本 + 段落内图片（F2.14）+ 段落样式提示（F2.15，均挂所属段落索引，不新增 LazyColumn 项）
 * + 章节锚点表（F2.16：元素 id / `<a name>` → 段落索引；降级路径为空表）。
 */
internal data class ExtractedChapter(
    val paragraphs: List<String>,
    val images: List<ParagraphImage>,
    val styles: Map<Int, ParagraphStyleHints> = emptyMap(),
    val anchors: Map<String, Int> = emptyMap()
)

/**
 * EPUB 解析器（《软件设计文档》§3.2/§5.1）：
 * container.xml 定位 OPF → manifest + spine 得章节顺序 → 逐章 XmlPullParser 抽取正文与图片；
 * F2.16（M12）：目录 = 文件自带 nav.xhtml（优先）/ NCX 原样产出到 Document.toc（含章坐标 + 锚点段落），
 * 章节标题取匹配条目、无匹配为 null（取消「第 N 章」兜底）。
 *
 * M11b（F2.14）：解析数据源抽象为 [EpubFiles]——生产 epub 打开先经 EpubCache 解压到私有缓存，
 * 由 [parseFromDir] 从缓存目录读取（正文不整书驻留内存，评审 C1）；整字节路径（单测/缓存回退）
 * 走 [parseBytes]。两种路径共用同一同步核心 [parseCore]，图片提取行为完全一致。
 *
 * C4（一致性评审 §5）：解析/抽取在 [runInterruptible] 内执行，协程取消会中断工作线程；
 * 逐章与 XHTML 抽取段级循环均检查线程中断并抛 [CancellationException]，超大单章也可及时取消。
 */
class EpubParser : DocumentParser {

    override suspend fun parse(uri: Uri, resolver: ContentResolver): Document {
        val (bytes, name) = readAllBytes(uri, resolver)
        return parseBytes(bytes, name, uri.toString())
    }

    /** 直接喂整书字节解析（单测不经 ContentResolver；缓存不可用时生产回退路径也走此入口） */
    internal suspend fun parseBytes(bytes: ByteArray, name: String, uri: String = name): Document =
        runInterruptible(Dispatchers.IO) {
            val entries = try {
                readZip(bytes)
            } catch (e: IOException) {
                throw ParseException.MalformedContent(e) // zip 容器损坏
            }
            parseCore(MapFiles(entries), uri, name, imageRoot = null)
        }

    /** 从 epub 解压缓存目录解析（M11b 生产路径）：容器/OPF/章节均从磁盘读，图片仅供渲染层懒加载 */
    internal suspend fun parseFromDir(dir: File, uri: String, name: String): Document =
        runInterruptible(Dispatchers.IO) {
            parseCore(DirFiles(dir), uri, name, imageRoot = dir.absolutePath)
        }

    /**
     * 同步解析核心（C4/AGENTS §5：阻塞式同步实现靠线程中断响应取消，调用方须经 runInterruptible）。
     * 数据源为 [EpubFiles]，读取惰性化：每次仅读 OPF/导航/当前章节，峰值内存 ≈ 最大单章而非整书。
     */
    private fun parseCore(files: EpubFiles, uri: String, name: String, imageRoot: String?): Document {
        // 1. container.xml 定位 OPF，缺失抛 MissingOpf
        val opfPath = findOpfPath(files) ?: throw ParseException.MissingOpf()
        val opfBytes = files.read(opfPath) ?: throw ParseException.MissingOpf()
        val base = opfPath.substringBeforeLast('/', "")
        // 2. manifest + spine 得章节顺序
        val opf = try {
            parseOpf(opfBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ParseException.MalformedContent(e)
        }
        // 3. 目录：优先 nav.xhtml，回退 NCX
        val toc = parseToc(files, opf, base)
        val titleByPath = LinkedHashMap<String, TocEntry>() // 先出现的条目优先（仅用于章节标题匹配）
        // calibre 等生成器常在 toc href 后带锚点（如 "text/ch1.html#sec1"，Flatland.epub 实测），
        // 剥离 #fragment 后才能与 spine/manifest 的 href 匹配（真机缺陷，2026-08-31）
        for (t in toc) titleByPath.putIfAbsent(resolvePath(base, t.href.substringBefore('#')), t)

        val chapters = ArrayList<Chapter>(opf.spine.size)
        val anchorsByChapter = ArrayList<Map<String, Int>>(opf.spine.size)
        val chapterIndexByPath = HashMap<String, Int>() // F2.16：spine 路径 → 章索引（先出现者优先）
        opf.spine.forEachIndexed { index, idref ->
            checkInterrupted() // 逐章循环内响应取消（C4 与逐章粒度并存）
            val href = opf.manifest[idref]
                ?: throw ParseException.MalformedContent(IllegalStateException("spine 引用未知 id：$idref"))
            val path = resolvePath(base, href)
            chapterIndexByPath.putIfAbsent(path, index)
            val chapterBytes = files.read(path)
                ?: throw ParseException.MalformedContent(IllegalStateException("章节文件缺失：$path"))
            val extracted = try {
                extractChapterFrom(chapterBytes, chapterDir = path.substringBeforeLast('/', ""))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ParseException.MalformedContent(e)
            }
            // M3（评审）：整章纯图（扉页/图版章）无文本段落时合成一条空字符串宿主段落承载图片，
            // 否则 LazyColumn 无 item 可挂图片（空宿主段落 index 0 与 standalone 图 owner 对齐）。
            val paragraphs = if (extracted.paragraphs.isEmpty() && extracted.images.isNotEmpty()) {
                listOf("")
            } else {
                extracted.paragraphs
            }
            anchorsByChapter += extracted.anchors
            val tocEntry = titleByPath[path]
            chapters += Chapter(
                title = tocEntry?.title, // F2.16：取消「第 N 章」兜底，无匹配即 null
                paragraphs = paragraphs,
                level = tocEntry?.level ?: 1,
                images = extracted.images,
                styles = extracted.styles
            )
        }
        // F2.14 封面：OPF meta name="cover" 或 manifest properties="cover-image" → isCover 挂首章段落 0
        if (chapters.isNotEmpty()) {
            resolveCover(files, opf, base)?.let { cover ->
                val head = chapters[0]
                chapters[0] = head.copy(images = listOf(cover) + head.images)
            }
        }
        // F2.16：Document.toc = 文件自带目录原样（标题/层级/条目数），逐条经原值 href 的 #fragment 换算锚点段落。
        // 多条目同指一章全部保留；fragment 命中锚点表 → 该段落，无 fragment/未命中/映射缺失 → 章首 0（仍保证可达）。
        val docToc = toc.map { t ->
            val path = resolvePath(base, t.href.substringBefore('#'))
            val chapterIndex = chapterIndexByPath[path] ?: 0
            val fragment = t.href.substringAfter('#', "")
            val anchorParagraph = if (fragment.isEmpty()) {
                0
            } else {
                anchorsByChapter.getOrNull(chapterIndex)?.get(fragment) ?: 0
            }
            ModelTocEntry(
                level = t.level,
                title = t.title,
                paragraphIndex = 0,
                chapterIndex = chapterIndex,
                anchorParagraph = anchorParagraph
            )
        }
        return Document(uri, name, DocFormat.EPUB, chapters, toc = docToc, imageRoot = imageRoot)
    }

    /** 目录条目（含嵌套层级），便于单测验证嵌套 toc */
    internal fun tocEntries(bytes: ByteArray): List<TocEntry> {
        val files = MapFiles(readZip(bytes))
        val opfPath = findOpfPath(files) ?: throw ParseException.MissingOpf()
        val opf = parseOpf(files.read(opfPath) ?: ByteArray(0))
        return parseToc(files, opf, opfPath.substringBeforeLast('/', ""))
    }

    /**
     * 章节锚点表（F2.16）：元素 id → 段落索引。
     * 段落元素 id → 该段落；容器元素 id → 其内首个段落（容器内无段落时取最近前段落）。
     * `<a name="x">` 同样视为锚点；正文解析降级路径（stripTagsFallback2）无结构信息，返回空表。
     * [bytes] 为整书字节，[chapterPath] 为 zip 内章节路径（如 "OEBPS/Text/p1.xhtml"）；条目缺失返回空表。
     */
    internal fun chapterAnchors(bytes: ByteArray, chapterPath: String): Map<String, Int> {
        val chapterBytes = readZip(bytes)[chapterPath] ?: return emptyMap()
        return extractChapterFrom(chapterBytes, chapterDir = chapterPath.substringBeforeLast('/', "")).anchors
    }

    /**
     * 章节正文/图片抽取统一出口（供 zip 条目与缓存目录两条路径复用）。
     * 图片规则（设计 §3.2-3，评审 S8/S6）：
     *  - 行内 `<img>`（段内已有文字）归当前未 flush 段落；
     *  - 块级独立 `<img>`（段间）挂最近已 flush 段落，渲染紧随该段文字下方；
     *  - 无文字纯图段（`<p><img/></p>`）同样挂最近已 flush 段，图中段落不出现在文本流；
     *  - `<img src=*.svg>` 仅保留 src（渲染层给占位，svg 一律不直接渲染）；
     *  - src 按 manifest href 同款规则解析为 epub zip 内规范化路径（基于所在章节目录，剥 . / .. / #fragment）。
     */
    internal fun extractChapterFrom(
        bytes: ByteArray,
        chapterDir: String = "",
        cancelCheck: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): ExtractedChapter = try {
        extractWithPull2(bytes, null, chapterDir, cancelCheck)
    } catch (e: XmlPullParserException) {
        try {
            extractWithPull2(bytes, "UTF-8", chapterDir, cancelCheck)
        } catch (e2: XmlPullParserException) {
            ExtractedChapter(stripTagsFallback2(bytes), emptyList())
        } catch (io: IOException) {
            ExtractedChapter(stripTagsFallback2(bytes), emptyList())
        }
    } catch (io: IOException) {
        ExtractedChapter(stripTagsFallback2(bytes), emptyList())
    }

    /** 章节正文抽取（既有测试用纯文本契约）；图片提取统一走 [extractChapterFrom] */
    internal fun extractParagraphs(bytes: ByteArray): List<String> =
        extractChapterFrom(bytes).paragraphs

    // ---------------- F2.15 段样式帧（继承 + 层叠 + 内联，随 DOM 树压栈） ----------------

    /** 一个开标签的计算样式：继承父级解析后，叠加自身样式表命中规则与内联 style（内联优先级最高） */
    private class StyleFrame(
        val tag: String,
        val parent: StyleFrame?,
        val fontEm: Float,     // 字号绝对倍率（相对 16sp 基准；em/% 已乘父级）
        val lineEm: Float,     // 行距绝对倍率（同上）
        val bold: Boolean?,    // null=未指定（走全局/继承）
        val italic: Boolean?,
        val align: Int?,       // 0 左 1 中 2 右
        val colorArgb: Int?,
        val spacing: Boolean,  // 自身声明的 margin/padding 垂直分量非 0（不继承）
        val id: String?,       // F2.16：元素 id（锚点映射，块级元素取"其内首个段落"）
        val anchorName: String? // F2.16：`<a name="x">` 旧式锚点
    )

    /** 归并命中元素（tag/id/class）的样式表规则：id>class>元素、同优先级后定义覆盖先定义 */
    private fun mergeRuleDecls(
        tag: String,
        id: String?,
        classAttr: String?,
        rules: List<CssRule>
    ): CssDecls {
        val classes = classAttr?.split(' ', '\t')?.filter { it.isNotEmpty() }.orEmpty()
        val acc = CssDecls()
        rules.filter { r ->
            val s = r.selector
            when {
                s.idName != null -> id == s.idName
                s.className != null -> classes.contains(s.className)
                else -> s.tag != null && s.tag == tag
            }
        }.sortedWith(compareBy<CssRule> { it.selector.specificity() }.thenBy { it.order })
            .forEach { acc.overrideWith(it.declarations) }
        return acc
    }

    /** 由声明 + 父级上下文构建计算样式帧（继承：未声明即取父级值）；id/name 供 F2.16 锚点映射 */
    private fun buildFrame(
        tag: String,
        parent: StyleFrame?,
        decls: CssDecls,
        id: String?,
        anchorName: String?
    ): StyleFrame {
        val parentFont = parent?.fontEm ?: 1f
        val parentLine = parent?.lineEm ?: 1f
        return StyleFrame(
            tag = tag,
            parent = parent,
            fontEm = decls.fontSizeRaw?.let { CssSubset.lengthToEm(it, parentFont, allowUnitless = false) } ?: parentFont,
            lineEm = decls.lineHeightRaw?.let { CssSubset.lengthToEm(it, parentLine, allowUnitless = true) } ?: parentLine,
            bold = decls.weightRaw?.let { CssSubset.weightToBold(it) } ?: parent?.bold,
            italic = decls.styleRaw?.let { CssSubset.fontStyleItalic(it) } ?: parent?.italic,
            align = decls.alignRaw?.let { CssSubset.textAlignValue(it) } ?: parent?.align,
            colorArgb = decls.colorRaw?.let { CssSubset.parseColor(it) } ?: parent?.colorArgb,
            spacing = decls.spacing ?: false,
            id = id,
            anchorName = anchorName
        )
    }

    /** 帧 → 段落样式提示：与全局默认等价的取值省略（避免 styles 表冗余）；全空返回 null */
    private fun hintsOf(frame: StyleFrame?): ParagraphStyleHints? {
        if (frame == null) return null
        val font = if (frame.fontEm != 1f) frame.fontEm else null
        val line = if (frame.lineEm != 1f) frame.lineEm else null
        if (font == null && line == null && frame.bold == null && frame.italic == null &&
            frame.align == null && frame.colorArgb == null && !frame.spacing
        ) return null
        return ParagraphStyleHints(
            fontSizeEm = font,
            lineHeightEm = line,
            bold = frame.bold,
            italic = frame.italic,
            textAlign = frame.align,
            colorArgb = frame.colorArgb,
            paragraphSpacing = frame.spacing
        )
    }

    // ---------------- zip / container / opf ----------------

    /** epub 数据源抽象：解析从 zip 内存表（字节路径）或缓存目录（磁盘路径）读取 */
    private interface EpubFiles {
        fun read(path: String): ByteArray?
    }

    private class MapFiles(private val map: Map<String, ByteArray>) : EpubFiles {
        override fun read(path: String): ByteArray? = map[path]
    }

    private class DirFiles(private val dir: File) : EpubFiles {
        override fun read(path: String): ByteArray? {
            // I2（评审）：读侧 zip-slip 防逃逸——container/OPF/spine/nav 等路径为朴素拼接，
            // 规范化后必须落在缓存目录内，否则拒绝（按缺失处理），防恶意 ../ 读到缓存目录外文件
            if (!pathWithinDir(dir, path)) return null
            val f = File(dir, path)
            return if (f.isFile) f.readBytes() else null
        }

        /** [child]（相对缓存根）解析后的规范路径必须等于根目录或位于其下 */
        private fun pathWithinDir(root: File, child: String): Boolean = runCatching {
            if (child.isBlank()) return false
            val rootPath = root.absoluteFile.canonicalPath
            val target = File(root, child).absoluteFile.canonicalPath
            target == rootPath || target.startsWith("$rootPath${File.separator}")
        }.getOrDefault(false)
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                checkInterrupted()
                val entry = zip.nextEntry ?: break
                map[entry.name] = zip.readBytes()
            }
        }
        return map
    }

    private fun checkInterrupted() {
        // C4/AGENTS §5：同步阻塞解析靠线程中断响应协程取消（runInterruptible 出口会置中断位）
        if (Thread.currentThread().isInterrupted) throw CancellationException("epub 解析被取消")
    }

    private fun resolvePath(base: String, href: String): String =
        if (base.isEmpty()) href else "$base/$href"

    /** 读 META-INF/container.xml 取 rootfile 的 full-path；读不到返回 null */
    private fun findOpfPath(files: EpubFiles): String? {
        val container = files.read("META-INF/container.xml") ?: return null
        return runCatching {
            val parser = newPullParser()
            parser.setInput(container.inputStream(), null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.equals("rootfile", true)) {
                    return parser.getAttributeValue(null, "full-path")
                }
                event = parser.next()
            }
            null
        }.getOrNull()
    }

    private class OpfData(
        val manifest: MutableMap<String, String> = mutableMapOf(), // id -> href
        val spine: MutableList<String> = mutableListOf(),          // idref，即章节顺序
        val mediaByItem: MutableMap<String, String> = mutableMapOf(), // id -> media-type（封面 svg 判定用）
        val propsByItem: MutableMap<String, String> = mutableMapOf(), // id -> properties
        var metaCoverId: String? = null,                              // <meta name="cover" content="{id}">
        var navHref: String? = null,
        var ncxHref: String? = null
    )

    private fun parseOpf(bytes: ByteArray): OpfData {
        val parser = newPullParser()
        parser.setInput(bytes.inputStream(), null)
        val opf = OpfData()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) {
                            opf.manifest[id] = href
                            val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
                            val properties = parser.getAttributeValue(null, "properties").orEmpty()
                            opf.mediaByItem[id] = mediaType
                            opf.propsByItem[id] = properties
                            if (mediaType == "application/x-dtbncx+xml") opf.ncxHref = href
                            if (properties.split(' ').contains("nav")) opf.navHref = href
                        }
                    }
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let { opf.spine += it }
                    // F2.14：epub2 常见封面声明 <meta name="cover" content="{manifest-id}"/>
                    "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                        opf.metaCoverId = parser.getAttributeValue(null, "content")
                    }
                }
            }
            event = parser.next()
        }
        return opf
    }

    /**
     * F2.14 封面解析：meta name="cover"（epub2）或 manifest properties="cover-image"（epub3）。
     * 位图直接作封面；svg 封面仅当其内嵌 `<image>` 引用位图时提取内嵌位图（评审 S6），纯矢量 svg 不提取。
     * 声明缺失/文件缺失/内嵌解析失败 → 无封面（不阻塞阅读）。
     */
    private fun resolveCover(files: EpubFiles, opf: OpfData, base: String): ParagraphImage? {
        val coverId = opf.metaCoverId
            ?: opf.manifest.keys.firstOrNull {
                opf.propsByItem[it].orEmpty().split(' ').contains("cover-image")
            } ?: return null
        val href = opf.manifest[coverId] ?: return null
        val mediaType = opf.mediaByItem[coverId].orEmpty()
        val coverPath = resolvePath(base, href)
        val isSvgCover = mediaType.equals("image/svg+xml", true) || href.endsWith(".svg", true)
        val rasterSrc = if (isSvgCover) {
            val bytes = files.read(coverPath) ?: return null
            innerRasterHref(bytes)?.let { resolveHref(coverPath.substringBeforeLast('/', ""), it) }
                ?.takeIf { !it.endsWith(".svg", true) }
        } else {
            coverPath
        } ?: return null
        return ParagraphImage(paragraphIndex = 0, src = rasterSrc, isCover = true)
    }

    /** 解析 svg 内 `<image href/xlink:href>` 引用的位图路径；取不到返回 null（纯矢量或解析失败） */
    private fun innerRasterHref(bytes: ByteArray): String? = runCatching {
        val parser = newPullParser()
        parser.setInput(bytes.inputStream(), null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.lowercase() == "image") {
                for (i in 0 until parser.attributeCount) {
                    val attr = parser.getAttributeName(i).lowercase()
                    if (attr == "href" || attr.endsWith(":href")) {
                        return parser.getAttributeValue(i)
                    }
                }
            }
            event = parser.next()
        }
        null
    }.getOrNull()

    // ---------------- 目录（nav.xhtml 优先，NCX 回退） ----------------

    private fun parseToc(files: EpubFiles, opf: OpfData, base: String): List<TocEntry> {
        opf.navHref?.let { resolvePath(base, it) }?.let { path ->
            files.read(path)?.let { return parseNavXhtml(it) }
        }
        opf.ncxHref?.let { resolvePath(base, it) }?.let { path ->
            files.read(path)?.let { return parseNcx(it) }
        }
        return emptyList()
    }

    /** epub3 nav.xhtml：`<nav epub:type="toc">` 内 `<a>` 文本，`<ol>` 嵌套深度即层级 */
    private fun parseNavXhtml(bytes: ByteArray): List<TocEntry> {
        val parser = newPullParser()
        parser.setInput(bytes.inputStream(), null)
        val result = mutableListOf<TocEntry>()
        var inNav = false
        var olDepth = 0
        var inAnchor = false
        var href = ""
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "nav" -> inNav = true
                    "ol" -> if (inNav) olDepth++
                    "a" -> if (inNav) {
                        inAnchor = true
                        href = parser.getAttributeValue(null, "href").orEmpty()
                        text.clear()
                    }
                }
                XmlPullParser.TEXT -> if (inAnchor) text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "a" -> if (inAnchor) {
                        result += TocEntry(text.toString().trim(), href, maxOf(olDepth, 1))
                        inAnchor = false
                    }
                    "ol" -> if (inNav) olDepth--
                    "nav" -> inNav = false
                }
            }
            event = parser.next()
        }
        return result
    }

    /** epub2 NCX：navLabel/text 为标题，content src 为目标，navPoint 嵌套深度即层级 */
    private fun parseNcx(bytes: ByteArray): List<TocEntry> {
        // 嵌套 navPoint 用栈保存未完结条目：占位下标保证输出为文档先序，标签归属最内层 navPoint
        data class Pending(val index: Int, val level: Int, var label: String? = null, var src: String? = null)

        val parser = newPullParser()
        parser.setInput(bytes.inputStream(), null)
        val result = mutableListOf<TocEntry>()
        val stack = ArrayDeque<Pending>()
        var inLabel = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "navpoint" -> {
                        stack.addLast(Pending(result.size, stack.size + 1))
                        result += TocEntry("", "", stack.size) // 占位，END_TAG 时回填
                    }
                    "text" -> if (stack.isNotEmpty()) inLabel = true
                    "content" -> stack.lastOrNull()?.let { it.src = parser.getAttributeValue(null, "src") }
                }
                XmlPullParser.TEXT -> if (inLabel) {
                    stack.lastOrNull()?.let { it.label = (it.label ?: "") + parser.text }
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "text" -> inLabel = false
                    "navpoint" -> stack.removeLastOrNull()?.let { p ->
                        result[p.index] = TocEntry(p.label?.trim().orEmpty(), p.src.orEmpty(), p.level)
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    // ---------------- 章节正文与图片抽取 ----------------

    /**
     * 段落级抽取（C4：**段级循环内响应线程中断**）。
     * - 段落边界：遇块级标签（p/h1~h6/li/div/body 等）或 br/hr 结束当前段落（行内元素不打断）；
     *   纯文本直接置于 body/div 下时，随所在块起止自然成段（与既有 fixture 纯文本契约兼容——块间空白 trim 后丢弃）；
     * - F2.15：`<style>` 内容收集解析为 CSS 规则（head/body 内均可）；每个非 void 元素开标签压一个样式帧
     *   （父级继承 + 命中规则 + 内联 style），段落 flush 时以**最近块级祖先帧**产出 [ParagraphStyleHints] 记入 styles[段落索引]；
     *   `script` 仍忽略；内联样式只影响整段近似（不支持段内 run 级富文本，v1 子集限定）；
     * - 编码防御：优先信任 XML 声明，XmlPullParserException 时按 UTF-8 重解析一次（在 [extractChapterFrom] 处理）。
     */
    private fun extractWithPull2(
        bytes: ByteArray,
        encoding: String?,
        chapterDir: String,
        cancelCheck: () -> Boolean
    ): ExtractedChapter {
        val parser = newPullParser()
        parser.setInput(bytes.inputStream(), encoding)
        val paragraphs = mutableListOf<String>()
        val images = mutableListOf<ParagraphImage>()
        val styles = mutableMapOf<Int, ParagraphStyleHints>()
        val anchors = LinkedHashMap<String, Int>() // F2.16：元素 id / <a name> → 段落索引
        val cssRules = mutableListOf<CssRule>() // 本章已收集的 <style> 规则（head 先行 → body 全量命中）
        val frames = ArrayDeque<StyleFrame>()   // 开标签样式帧栈
        val sb = StringBuilder()
        var skipTag: String? = null // script 忽略中的标签名
        var skipDepth = 0
        var headDepth = 0 // <head> 嵌套深度（head 内除 style 外不入正文，防 <title>Cover</title> 泄漏）
        var collectingStyle = false
        val styleBuf = StringBuilder()

        /** 最近的块级（非行内）祖先帧：整段样式归属它 */
        fun ownerFrame(): StyleFrame? {
            var i = frames.size - 1
            while (i >= 0) {
                val f = frames[i]
                if (f.tag !in inlineTags) return f
                i--
            }
            return null
        }

        /** 段落收尾：非空白文本入 paragraphs，并以最近块级祖先帧的样式写 styles[新索引]；同步记锚点 */
        fun flush() {
            val s = sb.toString().trim()
            sb.clear()
            if (s.isEmpty()) return
            val idx = paragraphs.size
            paragraphs += s
            hintsOf(ownerFrame())?.let { styles[idx] = it }
            // F2.16 锚点映射：段级 flush 时记录祖先元素 id / <a name> → 该段落。
            // 遍历全部祖先：容器 id 因此映射到"其内首个段落"（putIfAbsent 保证首个命中不被后续覆盖）。
            for (i in frames.indices.reversed()) {
                val f = frames[i]
                f.anchorName?.let { anchors.putIfAbsent(it, idx) }
                if (f.tag !in inlineTags && f.id != null) anchors.putIfAbsent(f.id, idx)
            }
        }

        /** 为当前元素开标签压样式帧（样式表命中规则 + 内联 style 覆盖） */
        fun pushFrameFor(tag: String) {
            val id = parser.getAttributeValue(null, "id")
            val classAttr = parser.getAttributeValue(null, "class")
            val inlineRaw = parser.getAttributeValue(null, "style")
            // F2.16：<a name="x"> 旧式锚点（仅 a 元素；其他元素的 name 不作为锚点）
            val anchorName = if (tag == "a") parser.getAttributeValue(null, "name")?.takeIf { it.isNotBlank() } else null
            val parent = frames.lastOrNull()
            val decls = mergeRuleDecls(tag, id, classAttr, cssRules)
            if (inlineRaw != null) decls.overrideWith(CssSubset.parseDeclarations(inlineRaw))
            frames.addLast(buildFrame(tag, parent, decls, id, anchorName))
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (cancelCheck()) throw CancellationException("epub 章节抽取被取消") // C4：段级循环取消检查
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    when {
                        tag == "head" -> headDepth++
                        collectingStyle -> {} // style 文本内的 "<" 不应作为标签；保守忽略
                        headDepth > 0 -> if (tag == "style") { // head 内 style 需收集（其余标签忽略）
                            collectingStyle = true
                            styleBuf.clear()
                        }
                        skipDepth > 0 -> if (tag == skipTag) skipDepth++
                        tag == "script" -> {
                            skipTag = tag
                            skipDepth = 1
                        }
                        tag == "style" -> {
                            collectingStyle = true
                            styleBuf.clear()
                        }
                        tag == "img" -> {
                            val src = parser.getAttributeValue(null, "src")?.trim().orEmpty()
                            if (src.isNotEmpty()) {
                                val alt = parser.getAttributeValue(null, "alt")?.takeIf { it.isNotBlank() }
                                val canonical = resolveHref(chapterDir, src)
                                // 归属规则：段内已有文字 → 归当前未 flush 段；否则块级独立/纯图段 → 挂最近已 flush 段
                                val owner = if (sb.isNotBlank()) {
                                    paragraphs.size
                                } else {
                                    (paragraphs.size - 1).coerceAtLeast(0)
                                }
                                images += ParagraphImage(paragraphIndex = owner, src = canonical, alt = alt)
                            }
                        }
                        tag == "br" || tag == "hr" -> flush()
                        tag in voidTags -> {} // meta/link/input 等：无内容无样式帧
                        else -> {
                            if (tag !in inlineTags) flush() // 块级开始 = 段落边界
                            pushFrameFor(tag) // 行内元素同样压帧（继承链连续），但自身不产生段落
                        }
                    }
                }
                XmlPullParser.TEXT -> when {
                    collectingStyle -> styleBuf.append(parser.text)
                    headDepth == 0 && skipDepth == 0 -> sb.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    when {
                        tag == "head" -> headDepth--
                        collectingStyle && tag == "style" -> {
                            collectingStyle = false
                            // order 全书递增：后一张 <style> 的同优先级规则应赢（跨表层叠，审查修复）
                            cssRules += CssSubset.parseStyleSheet(styleBuf.toString(), startOrder = cssRules.size)
                        }
                        collectingStyle -> {} // style 文本内不应含标签结束
                        headDepth > 0 -> {} // head 内其余结束标签忽略（未压帧，勿 pop）
                        skipDepth > 0 -> if (tag == skipTag) skipDepth--
                        tag == "style" -> {} // 非 head 的 style 在 collectingStyle 分支已处理，此处防御
                        tag == "img" || tag in voidTags -> {} // 无帧可 pop（XHTML 显式闭合 img 等）
                        else -> {
                            if (tag !in inlineTags) flush() // 块级结束：收尾本块余下文本（归属本块样式）
                            val frame = frames.removeLastOrNull() // 与 pushFrameFor 对称
                            // F2.16 锚点兜底目标（flush 未命中时）：
                            //  - 行内标签且段落仍在累积（sb 非空，如段中闭合的 <a name>）→ 其**所在段落**（即将 flush 的 paragraphs.size）；
                            //  - 块级标签（此处已先 flush，sb 必空）与 sb 为空的行内标签 → 最近前段落（章首兜底 0）。
                            // 纯图章此值即合成宿主段 0（parseCore 合成空宿主段时锚点照常映射到该段）。
                            if (frame != null) {
                                val target = if (tag in inlineTags && sb.isNotEmpty()) {
                                    paragraphs.size
                                } else {
                                    (paragraphs.size - 1).coerceAtLeast(0)
                                }
                                if (tag !in inlineTags) frame.id?.let { if (it !in anchors) anchors[it] = target }
                                frame.anchorName?.let { if (it !in anchors) anchors[it] = target }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        flush()
        return ExtractedChapter(paragraphs, images, styles, anchors)
    }

    /** 剥标签正则降级：仅在 XmlPullParserException/IOException 时触发；head 一并剥除（同正文抽取契约）。无图信息，图片为空。 */
    private fun stripTagsFallback2(bytes: ByteArray): List<String> {
        var text = bytes.toString(Charsets.UTF_8) // 宽松解码，乱码可接受
        text = Regex("(?is)<head[^>]*>.*?</head>").replace(text, "")
        text = Regex("(?is)<(script|style)[^>]*>.*?</\\1>").replace(text, "")
        text = Regex("(?i)<img[^>]*>").replace(text, "")
        text = Regex("(?i)</?(?:p|li|h[1-6]|br)[^>]*>").replace(text, "\n")
        text = Regex("<[^>]+>").replace(text, "")
        return text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * href 解析为 epub zip 内规范化路径：相对所在章节目录（chapterDir，如 "OEBPS/text"）解析，
     * 剥离 . / .. 片段与 #fragment/?query；绝对路径（/开头）视为 zip 根下路径。
     * 输出形如 manifest href 规范化的 "OEBPS/images/x.png"。
     */
    private fun resolveHref(chapterDir: String, href: String): String {
        var h = href.substringBefore('#').substringBefore('?').trim()
        if (h.isEmpty()) return ""
        val combined = when {
            h.startsWith('/') -> h.drop(1)
            chapterDir.isEmpty() -> h
            else -> "$chapterDir/$h"
        }
        val parts = ArrayDeque<String>()
        for (seg in combined.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/")
    }

    companion object {
        // XmlPullParserFactory 构建有一次性开销，全局复用（TC-UT-04c 性能）
        private val pullFactory: XmlPullParserFactory by lazy {
            XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        }

        private fun newPullParser(): XmlPullParser = pullFactory.newPullParser()
    }
}
