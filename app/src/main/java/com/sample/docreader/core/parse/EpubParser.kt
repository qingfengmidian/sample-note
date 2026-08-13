package com.sample.docreader.core.parse

import android.content.ContentResolver
import android.net.Uri
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.ParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.util.zip.ZipInputStream

/** 目录条目：标题、目标 href、嵌套层级（1 起） */
internal data class TocEntry(val title: String, val href: String, val level: Int)

/**
 * EPUB 解析器（《软件设计文档》§3.2）：
 * container.xml 定位 OPF → manifest + spine 得章节顺序 → 逐章 XmlPullParser 抽取正文；
 * 标题优先 epub3 nav.xhtml，回退 NCX navLabel，再退化为"第 N 章"。
 */
class EpubParser : DocumentParser {

    override suspend fun parse(uri: Uri, resolver: ContentResolver): Document {
        val (bytes, name) = readAllBytes(uri, resolver)
        return parseBytes(bytes, name, uri.toString())
    }

    /** 直接喂字节解析（单测不经 ContentResolver） */
    internal suspend fun parseBytes(bytes: ByteArray, name: String, uri: String = name): Document =
        withContext(Dispatchers.IO) {
            val entries = try {
                readZip(bytes)
            } catch (e: IOException) {
                throw ParseException.MalformedContent(e) // zip 容器损坏
            }
            // 1. container.xml 定位 OPF，缺失抛 MissingOpf
            val opfPath = findOpfPath(entries) ?: throw ParseException.MissingOpf()
            val opfBytes = entries[opfPath] ?: throw ParseException.MissingOpf()
            // 2. manifest + spine 得章节顺序
            val opf = try {
                parseOpf(opfBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ParseException.MalformedContent(e)
            }
            val base = opfPath.substringBeforeLast('/', "")
            // 3. 目录：优先 nav.xhtml，回退 NCX
            val toc = parseToc(entries, opf, base)
            val titleByPath = LinkedHashMap<String, String>() // 先出现的条目优先
            for (t in toc) titleByPath.putIfAbsent(resolvePath(base, t.href), t.title)

            val chapters = ArrayList<Chapter>(opf.spine.size)
            opf.spine.forEachIndexed { index, idref ->
                ensureActive() // 逐章循环内响应协程取消
                val href = opf.manifest[idref]
                    ?: throw ParseException.MalformedContent(IllegalStateException("spine 引用未知 id：$idref"))
                val path = resolvePath(base, href)
                val chapterBytes = entries[path]
                    ?: throw ParseException.MalformedContent(IllegalStateException("章节文件缺失：$path"))
                val paragraphs = try {
                    extractParagraphs(chapterBytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw ParseException.MalformedContent(e)
                }
                chapters += Chapter(titleByPath[path] ?: "第 ${index + 1} 章", paragraphs)
            }
            Document(uri, name, DocFormat.EPUB, chapters)
        }

    /** 目录条目（含嵌套层级），便于单测验证嵌套 toc */
    internal fun tocEntries(bytes: ByteArray): List<TocEntry> {
        val entries = readZip(bytes)
        val opfPath = findOpfPath(entries) ?: throw ParseException.MissingOpf()
        val opf = parseOpf(entries.getValue(opfPath))
        return parseToc(entries, opf, opfPath.substringBeforeLast('/', ""))
    }

    // ---------------- zip / container / opf ----------------

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                map[entry.name] = zip.readBytes()
            }
        }
        return map
    }

    private fun resolvePath(base: String, href: String): String =
        if (base.isEmpty()) href else "$base/$href"

    /** 读 META-INF/container.xml 取 rootfile 的 full-path；读不到返回 null */
    private fun findOpfPath(entries: Map<String, ByteArray>): String? {
        val container = entries["META-INF/container.xml"] ?: return null
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
                            if (mediaType == "application/x-dtbncx+xml") opf.ncxHref = href
                            if (properties.split(' ').contains("nav")) opf.navHref = href
                        }
                    }
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let { opf.spine += it }
                }
            }
            event = parser.next()
        }
        return opf
    }

    // ---------------- 目录（nav.xhtml 优先，NCX 回退） ----------------

    private fun parseToc(entries: Map<String, ByteArray>, opf: OpfData, base: String): List<TocEntry> {
        opf.navHref?.let { entries[resolvePath(base, it)] }?.let { return parseNavXhtml(it) }
        opf.ncxHref?.let { entries[resolvePath(base, it)] }?.let { return parseNcx(it) }
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

    // ---------------- 章节正文抽取 ----------------

    /**
     * 抽取章节 XHTML 正文。编码防御：优先信任 XML 声明，
     * XmlPullParserException 时按 UTF-8 重解析一次；再失败（或 IOException）走剥标签正则降级。
     */
    internal fun extractParagraphs(bytes: ByteArray): List<String> = try {
        extractWithPull(bytes, encoding = null)
    } catch (e: XmlPullParserException) {
        try {
            extractWithPull(bytes, encoding = "UTF-8")
        } catch (e2: XmlPullParserException) {
            stripTagsFallback(bytes)
        } catch (io: IOException) {
            stripTagsFallback(bytes)
        }
    } catch (io: IOException) {
        stripTagsFallback(bytes)
    }

    /** 遇 p/h1~h6/li/br 结束当前段落；丢弃标签与属性；忽略 img/script/style */
    private fun extractWithPull(bytes: ByteArray, encoding: String?): List<String> {
        val parser = newPullParser()
        parser.setInput(bytes.inputStream(), encoding)
        val paragraphs = mutableListOf<String>()
        val sb = StringBuilder()
        var skipTag: String? = null // script/style 忽略中的标签名
        var skipDepth = 0

        fun flush() {
            val s = sb.toString().trim()
            if (s.isNotEmpty()) paragraphs += s
            sb.clear()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    if (skipDepth > 0) {
                        if (tag == skipTag) skipDepth++
                    } else when (tag) {
                        "script", "style" -> {
                            skipTag = tag
                            skipDepth = 1
                        }
                        "img" -> Unit // 忽略图片
                        "br", "p", "li", "h1", "h2", "h3", "h4", "h5", "h6" -> flush()
                    }
                }
                XmlPullParser.TEXT -> if (skipDepth == 0) sb.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    if (skipDepth > 0) {
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
        return paragraphs
    }

    /** 剥标签正则降级：仅在 XmlPullParserException/IOException 时触发 */
    private fun stripTagsFallback(bytes: ByteArray): List<String> {
        var text = bytes.toString(Charsets.UTF_8) // 宽松解码，乱码可接受
        text = Regex("(?is)<(script|style)[^>]*>.*?</\\1>").replace(text, "")
        text = Regex("(?i)<img[^>]*>").replace(text, "")
        text = Regex("(?i)</?(?:p|li|h[1-6]|br)[^>]*>").replace(text, "\n")
        text = Regex("<[^>]+>").replace(text, "")
        return text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    companion object {
        // XmlPullParserFactory 构建有一次性开销，全局复用（TC-UT-04c 性能）
        private val pullFactory: XmlPullParserFactory by lazy {
            XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        }

        private fun newPullParser(): XmlPullParser = pullFactory.newPullParser()
    }
}
