package com.sample.docreader.core.parse

import android.content.ContentResolver
import android.net.Uri
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.ParagraphImage
import com.sample.docreader.core.model.ParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** 目录条目：标题、目标 href、嵌套层级（1 起） */
internal data class TocEntry(val title: String, val href: String, val level: Int)

/** 章节抽取结果：段落纯文本 + 段落内图片（F2.14，图片挂所属段落索引，不新增 LazyColumn 项） */
internal data class ExtractedChapter(
    val paragraphs: List<String>,
    val images: List<ParagraphImage>
)

/**
 * EPUB 解析器（《软件设计文档》§3.2/§5.1）：
 * container.xml 定位 OPF → manifest + spine 得章节顺序 → 逐章 XmlPullParser 抽取正文与图片；
 * 标题优先 epub3 nav.xhtml，回退 NCX navLabel，再退化为"第 N 章"。
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
        val titleByPath = LinkedHashMap<String, TocEntry>() // 先出现的条目优先
        // calibre 等生成器常在 toc href 后带锚点（如 "text/ch1.html#sec1"，Flatland.epub 实测），
        // 剥离 #fragment 后才能与 spine/manifest 的 href 匹配，否则章节标题全部退化"第 N 章"（真机缺陷，2026-08-31）
        for (t in toc) titleByPath.putIfAbsent(resolvePath(base, t.href.substringBefore('#')), t)

        val chapters = ArrayList<Chapter>(opf.spine.size)
        opf.spine.forEachIndexed { index, idref ->
            checkInterrupted() // 逐章循环内响应取消（C4 与逐章粒度并存）
            val href = opf.manifest[idref]
                ?: throw ParseException.MalformedContent(IllegalStateException("spine 引用未知 id：$idref"))
            val path = resolvePath(base, href)
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
            val tocEntry = titleByPath[path]
            chapters += Chapter(
                title = tocEntry?.title ?: "第 ${index + 1} 章",
                paragraphs = paragraphs,
                level = tocEntry?.level ?: 1,
                images = extracted.images
            )
        }
        // F2.14 封面：OPF meta name="cover" 或 manifest properties="cover-image" → isCover 挂首章段落 0
        if (chapters.isNotEmpty()) {
            resolveCover(files, opf, base)?.let { cover ->
                val head = chapters[0]
                chapters[0] = head.copy(images = listOf(cover) + head.images)
            }
        }
        return Document(uri, name, DocFormat.EPUB, chapters, imageRoot = imageRoot)
    }

    /** 目录条目（含嵌套层级），便于单测验证嵌套 toc */
    internal fun tocEntries(bytes: ByteArray): List<TocEntry> {
        val files = MapFiles(readZip(bytes))
        val opfPath = findOpfPath(files) ?: throw ParseException.MissingOpf()
        val opf = parseOpf(files.read(opfPath) ?: ByteArray(0))
        return parseToc(files, opf, opfPath.substringBeforeLast('/', ""))
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
     * 段落级抽取（C4：**段级循环内响应线程中断**）。遇 p/h1~h6/li/br 结束当前段落；丢弃标签与属性；
     * <img> 提取为 ParagraphImage（规则见文件头 [extractChapterFrom]）；script 忽略；
     * <head> 内的文本（title/style 等）不属于正文，整体跳过（真机缺陷，2026-08-31）。
     * 编码防御：优先信任 XML 声明，XmlPullParserException 时按 UTF-8 重解析一次（在 [extractChapterFrom] 处理）。
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
        val sb = StringBuilder()
        var skipTag: String? = null // script/style 忽略中的标签名
        var skipDepth = 0
        var headDepth = 0 // <head> 嵌套深度（head 内不入正文，防 <title>Cover</title> 泄漏）

        fun flush() {
            val s = sb.toString().trim()
            if (s.isNotEmpty()) paragraphs += s
            sb.clear()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (cancelCheck()) throw CancellationException("epub 章节抽取被取消") // C4：段级循环取消检查
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    if (tag == "head") {
                        headDepth++
                    } else if (headDepth > 0) {
                        // head 内其余标签一律忽略（含 style/script，不进 skipTag 逻辑）
                    } else if (skipDepth > 0) {
                        if (tag == skipTag) skipDepth++
                    } else when (tag) {
                        "script", "style" -> {
                            skipTag = tag
                            skipDepth = 1
                        }
                        "img" -> {
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
                        "br", "p", "li", "h1", "h2", "h3", "h4", "h5", "h6" -> flush()
                    }
                }
                XmlPullParser.TEXT -> if (headDepth == 0 && skipDepth == 0) sb.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    if (tag == "head") {
                        headDepth--
                    } else if (headDepth > 0) {
                        // head 内其余结束标签忽略
                    } else if (skipDepth > 0) {
                        if (tag == skipTag) skipDepth--
                    } else if (tag == "p" || tag == "li" || tag == "h1" || tag == "h2" ||
                        tag == "h3" || tag == "h4" || tag == "h5" || tag == "h6"
                    ) {
                        flush()
                    }
                }
            }
            event = parser.next()
        }
        flush()
        return ExtractedChapter(paragraphs, images)
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
