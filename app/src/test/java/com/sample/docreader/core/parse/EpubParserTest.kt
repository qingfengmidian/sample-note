package com.sample.docreader.core.parse

import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.ParseException
import com.sample.docreader.core.model.ParagraphImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * M2：EpubParser（《测试用例》TC-UT-04/04a/04b/04c/04d/05/06/06a/06b/13）。
 * XmlPullParserFactory 来自平台（kxml2），须 Robolectric 运行。
 */
@RunWith(RobolectricTestRunner::class)
class EpubParserTest {

    private val parser = EpubParser()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    /** TC-UT-04：epub2 sample.epub → 章节顺序 = spine；标题 = NCX navLabel；段落为去标签纯文本 */
    @Test
    fun tcUt04_epub2() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("sample.epub"), "sample.epub")
        assertEquals(DocFormat.EPUB, doc.format)
        assertEquals(listOf("第 1 章", "第 2 章", "第 3 章"), doc.chapters.map { it.title })

        val ch1 = doc.chapters[0].paragraphs
        assertTrue(ch1.contains("第 1 章第一段。"))
        assertTrue(ch1.contains("第 1 章第二段。"))
        assertTrue(ch1.contains("含图段落。"))
        assertTrue(ch1.contains("图片后段落。"))
        assertFalse("img 标签应被忽略", ch1.any { it.contains("pic.png") })
        val anyTagChar = doc.chapters.any { c -> c.paragraphs.any { it.contains('<') || it.contains('>') } }
        assertFalse("段落应为去标签纯文本", anyTagChar)
    }

    /** TC-UT-04a：epub3 sample3.epub → 标题取 nav.xhtml <a> 文本；nested_toc.epub 验 NCX 4 级嵌套 */
    @Test
    fun tcUt04a_epub3NavAndNestedToc() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("sample3.epub"), "sample3.epub")
        assertEquals(listOf("第 1 章", "第 2 章", "第 3 章"), doc.chapters.map { it.title })

        val toc = parser.tocEntries(Fixtures.bytes("nested_toc.epub"))
        assertEquals(listOf("第一级", "第二级", "第三级", "第四级"), toc.map { it.title })
        assertEquals("NCX 嵌套层级应为 1..4", listOf(1, 2, 3, 4), toc.map { it.level })
    }

    /** TC-UT-04b：nav.xhtml 与 NCX 并存 → 优先 nav.xhtml */
    @Test
    fun tcUt04b_navPreferredOverNcx() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("hybrid.epub"), "hybrid.epub")
        assertEquals(listOf("NAV第一章", "NAV第二章", "NAV第三章"), doc.chapters.map { it.title })
    }

    /** TC-UT-04c：最小 epub fixture 解析耗时 ≤ 200ms（纳入 CI 断言） */
    @Test
    fun tcUt04c_performance() = runBlocking {
        val bytes = Fixtures.bytes("sample.epub")
        parser.parseBytes(bytes, "warmup.epub") // 预热：工厂类加载等一次性开销不计入
        val start = System.nanoTime()
        parser.parseBytes(bytes, "sample.epub")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("解析耗时 ${elapsedMs}ms，应 ≤ 200ms", elapsedMs <= 200)
    }

    /** TC-UT-04d：声明 UTF-8 实为 GBK → XmlPullParserException 后按 UTF-8 重解析一次，不抛未受控异常 */
    @Test
    fun tcUt04d_badDeclaration() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("bad_decl.epub"), "bad_decl.epub")
        assertEquals(1, doc.chapters.size)
        assertEquals("坏章节", doc.chapters[0].title)
        assertTrue("降级路径应产出内容（乱码可接受）", doc.chapters[0].paragraphs.isNotEmpty())
    }

    /** 正例对照：章节声明 GBK 且实际为 GBK → 信任声明正确解码 */
    @Test
    fun mixedEncoding_declaredGbk() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("mixed_encoding.epub"), "mixed_encoding.epub")
        assertEquals("GBK章节", doc.chapters[0].title)
        assertTrue(doc.chapters[0].paragraphs.any { it.contains("这是 GBK 编码的章节内容。") })
    }

    /** TC-UT-05：无导航文件 → 标题退化"第 N 章"，正文仍可解析 */
    @Test
    fun tcUt05_noNav() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("no_nav.epub"), "no_nav.epub")
        assertEquals(listOf("第 1 章", "第 2 章", "第 3 章"), doc.chapters.map { it.title })
        assertTrue(doc.chapters.all { it.paragraphs.isNotEmpty() })
        assertTrue(doc.chapters[0].paragraphs.contains("第 1 章第一段。"))
    }

    /** TC-UT-06：broken.epub 缺 OPF → ParseException.MissingOpf */
    @Test
    fun tcUt06_missingOpf() = runBlocking {
        try {
            parser.parseBytes(Fixtures.bytes("broken.epub"), "broken.epub")
            fail("应抛出 ParseException.MissingOpf")
        } catch (e: ParseException.MissingOpf) {
            // 预期
        }
    }

    /** TC-UT-06a：spine 引用缺失章节（malformed.epub）→ ParseException.MalformedContent */
    @Test
    fun tcUt06a_malformedContent() = runBlocking {
        try {
            parser.parseBytes(Fixtures.bytes("malformed.epub"), "malformed.epub")
            fail("应抛出 ParseException.MalformedContent")
        } catch (e: ParseException.MalformedContent) {
            // 预期
        }
        // cause.message 为 null 时提示文案不得含 "null" 字样
        val msg = ParseException.MalformedContent(IllegalArgumentException()).message!!
        assertFalse("提示文案不应含 \"null\" 字样：$msg", msg.contains("null"))
    }

    /** TC-UT-04a 补充：nav.xhtml 嵌套 <ol>（olDepth>1）→ toc 与 chapter.level 反映嵌套层级 */
    @Test
    fun tcUt04a_nestedNavOlDepth() = runBlocking {
        val toc = parser.tocEntries(Fixtures.bytes("nested_nav.epub"))
        assertEquals(listOf("第一部", "第二章", "第三节"), toc.map { it.title })
        assertEquals("嵌套 <ol> 深度即层级", listOf(1, 2, 3), toc.map { it.level })

        val doc = parser.parseBytes(Fixtures.bytes("nested_nav.epub"), "nested_nav.epub")
        assertEquals(listOf("第一部", "第二章", "第三节"), doc.chapters.map { it.title })
        assertEquals(listOf(1, 2, 3), doc.chapters.map { it.level })
    }

    /** TC-UT-04a 补充：多个 toc 条目指向同一 href → putIfAbsent 先出现者优先（nested_toc 第一/二级同指 ch1） */
    @Test
    fun tcUt04a_sameHref_firstEntryWins() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("nested_toc.epub"), "nested_toc.epub")
        // ch1：第一级（lv1）先于第二级（lv2）；ch2 首现于第三级；ch3 首现于第四级
        assertEquals(listOf("第一级", "第三级", "第四级"), doc.chapters.map { it.title })
        assertEquals(listOf(1, 3, 4), doc.chapters.map { it.level })
    }

    /** TC-UT-06a 补充：spine idref 不在 manifest（bad_spine.epub）→ ParseException.MalformedContent */
    @Test
    fun tcUt06a_spineIdrefNotInManifest() = runBlocking {
        try {
            parser.parseBytes(Fixtures.bytes("bad_spine.epub"), "bad_spine.epub")
            fail("应抛出 ParseException.MalformedContent")
        } catch (e: ParseException.MalformedContent) {
            // 预期
        }
    }

    /** TC-UT-06b：XmlPullParserException → 触发剥标签降级，产出可读文本 */
    @Test
    fun tcUt06b_tagStripFallback() {
        // 含裸 & 且未闭合：XmlPullParser 两次尝试（声明/UTF-8）均失败 → 正则降级
        val broken = "<html><body><p>第一段 甲 & 乙</p><p>第二段 丙".toByteArray(Charsets.UTF_8)
        val paragraphs = parser.extractParagraphs(broken)
        assertTrue(paragraphs.any { it.contains("第一段 甲") })
        assertTrue(paragraphs.any { it.contains("第二段 丙") })
        assertFalse(paragraphs.any { it.contains('<') })
    }

    /** TC-UT-13（epub 侧）：解析 many_chapters.epub 进行中取消 → ensureActive 生效 */
    @Test(timeout = 60_000)
    fun tcUt13_cancelDuringParse() = runBlocking {
        val bytes = Fixtures.bytes("many_chapters.epub")
        var caught: Throwable? = null
        // 循环解析直到被取消，避免"解析先于 cancel 完成"的时序竞争：
        // 只有 ensureActive 生效抛出 CancellationException 才能退出
        val job = launch(Dispatchers.Default) {
            try {
                while (true) parser.parseBytes(bytes, "many_chapters.epub")
            } catch (t: Throwable) {
                caught = t
                throw t
            }
        }
        delay(50) // 让解析进入逐章循环
        job.cancel()
        job.join()
        assertTrue("取消后应抛出 CancellationException，实际 $caught", caught is kotlinx.coroutines.CancellationException)
    }

    // ---------------- 真机回归（2026-08-31，Z-Library/calibre 系 epub 暴露的缺陷） ----------------

    /**
     * 真机缺陷 1（回归）：NCX/nav.xhtml 的 content href 带锚点（calibre 生成的书几乎都带 #fragment），
     * 如 "text/ch1.html#sec1"。解析须剥离 #fragment 后与 spine/manifest 的 href 匹配，
     * 否则章节标题全部退化为"第 N 章"（Flatland.epub 实测）。
     */
    @Test
    fun ncxHrefWithFragment_stripsFragmentAndMatchesTitle() = runBlocking {
        val doc = parser.parseBytes(
            buildMinimalEpub(navHref = "text/ch1.html#sec1", navLabel = "真实标题"),
            "fragment_href.epub"
        )
        assertEquals("带 #fragment 的 NCX href 应匹配到章节标题", "真实标题", doc.chapters[0].title)
        assertEquals(1, doc.chapters.size)
        assertTrue(doc.chapters[0].paragraphs.contains("正文第一段"))
    }

    /** 真机缺陷 1（epub3 侧）：nav.xhtml 的 <a href> 同样可能带 #fragment，须一并剥离 */
    @Test
    fun navHrefWithFragment_stripsFragmentAndMatchesTitle() = runBlocking {
        val doc = parser.parseBytes(
            buildMinimalEpub(
                useNav = true,
                navHref = "text/ch1.html#sec1",
                navLabel = "NAV标题"
            ),
            "fragment_href_nav.epub"
        )
        assertEquals("带 #fragment 的 nav href 应匹配到章节标题", "NAV标题", doc.chapters[0].title)
    }

    /**
     * 真机缺陷 2（回归）：<head> 内的 <title>/<style> 等文本不得泄漏进正文段落。
     * 实测 Flatland.epub 封面页的 <title>Cover</title> 被抽成正文段"Cover"，
     * 导致封面页显示伪内容（TC-UT-04 的"段落为去标签纯文本"契约的一部分）。
     */
    @Test
    fun headTitle_notLeakedIntoParagraphs() {
        val xhtml = """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>Cover</title><style type="text/css">.x { color: red; }</style></head>
              <body>
                <p>正文第一段</p>
              </body>
            </html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val paragraphs = parser.extractParagraphs(xhtml)
        assertTrue("正文段落应正常抽取", paragraphs.contains("正文第一段"))
        assertFalse("head 内 <title> 文本不得泄漏", paragraphs.any { it.contains("Cover") })
        assertFalse("head 内 <style> 内容不得泄漏", paragraphs.any { it.contains("color: red") })
    }

    // ---------------- M11（F2.14）图片/封面解析（TC-F2-17/17c/17d，测试用例 REV4 §0 含图夹具） ----------------

    /** sample_images.epub：块级 img 挂前一段、行内 img 归所在段、svg/带空格文件名图保留；正文文本不含 img 残留 */
    @Test
    fun m11_images_blockInlineSvgExtracted() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("sample_images.epub"), "sample_images.epub")
        val ch = doc.chapters[0]
        // 图归属：PNG（块级，紧随段 0 之后）→ 0；JPG（第二段文字内行内）→ 1；svg（尾随）→ 1；空格文件名图（尾随）→ 1
        assertEquals(listOf(0, 1, 1, 1), ch.images.map { it.paragraphIndex })
        assertEquals(4, ch.images.size)

        val png = ch.images[0]
        assertEquals("示例PNG插图", png.alt)
        assertFalse(png.isCover)
        assertTrue("src 应为 manifest 规范化 zip 路径", png.src.endsWith("OEBPS/images/fig1.png"))

        val jpg = ch.images[1]
        assertEquals("示例JPG插图", jpg.alt)
        assertTrue(jpg.src.endsWith("OEBPS/images/fig2.jpg"))

        val svg = ch.images[2]
        assertNull(svg.alt)
        assertTrue("svg 仅保留 src 由渲染层占位", svg.src.endsWith("OEBPS/images/fig.svg"))

        // L3：src 百分号编码保留在解析路径中（渲染层查找时再 URLDecoder）
        val spaced = ch.images[3]
        assertEquals("空格文件名图", spaced.alt)
        assertEquals("OEBPS/images/with%20space.png", spaced.src)

        assertTrue(ch.paragraphs.contains("第一段：前言文字。"))
        assertTrue(ch.paragraphs.contains("第二段开头。第二段结尾。"))
        assertTrue(ch.paragraphs.contains("尾段文字。"))
        assertFalse("段落应为纯文本，不含 img/src 残留", ch.paragraphs.any { it.contains("fig") || it.contains('<') })
    }

    /** sample_cover.epub：OPF <meta name="cover"> 指向位图 → isCover 挂首章段落 0，正文文字不含封面 */
    @Test
    fun m11_cover_metaNameCover() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("sample_cover.epub"), "sample_cover.epub")
        val ch = doc.chapters[0]
        assertEquals(1, ch.images.size)
        val cover = ch.images[0]
        assertTrue(cover.isCover)
        assertEquals(0, cover.paragraphIndex)
        assertEquals("OEBPS/images/cover.png", cover.src)
        assertNull(cover.alt)
        assertEquals("封面不占用正文段落", listOf("封面之后的第一段文字。", "第二段文字。"), ch.paragraphs)
    }

    /** 封面识别 2：manifest item properties="cover-image"（epub3 形态） */
    @Test
    fun m11_cover_propertiesCoverImage() = runBlocking {
        val bytes = buildRawEpub(
            opf = """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>t</dc:title></metadata>
                |<manifest>
                |<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                |<item id="cv" href="images/c.png" media-type="image/png" properties="cover-image"/>
                |</manifest>
                |<spine><itemref idref="ch1"/></spine>
                |</package>
                |""".trimMargin(),
            files = mapOf(
                "OEBPS/ch1.xhtml" to minimalChapter("<p>正文。</p>"),
                "OEBPS/images/c.png" to byteArrayOf(0x01)
            )
        )
        val ch = parser.parseBytes(bytes, "prop_cover.epub").chapters[0]
        assertEquals(1, ch.images.size)
        assertTrue(ch.images[0].isCover)
        assertEquals("OEBPS/images/c.png", ch.images[0].src)
    }

    /** svg 封面内嵌位图引用 → 提取内嵌位图路径作封面；纯矢量 svg（无 <image>）→ 不提取封面 */
    @Test
    fun m11_cover_svgInnerBitmapExtractedButPureVectorNot() = runBlocking {
        val chWithInner = parser.parseBytes(
            buildRawEpub(
                opf = """
                    |<?xml version="1.0" encoding="UTF-8"?>
                    |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                    |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>t</dc:title>
                    |<meta name="cover" content="cv"/></metadata>
                    |<manifest>
                    |<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    |<item id="cv" href="images/cover.svg" media-type="image/svg+xml"/>
                    |</manifest>
                    |<spine><itemref idref="ch1"/></spine>
                    |</package>
                    |""".trimMargin(),
                files = mapOf(
                    "OEBPS/ch1.xhtml" to minimalChapter("<p>正文。</p>"),
                    "OEBPS/images/cover.svg" to
                        ("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">""" +
                            """<image xlink:href="cover.png" width="300" height="400"/></svg>""").toByteArray(),
                    "OEBPS/images/cover.png" to byteArrayOf(0x02)
                )
            ),
            "svg_cover_inner.epub"
        ).chapters[0]
        assertEquals("内嵌位图封面应提取为 isCover，src 指向位图", 1, chWithInner.images.size)
        val inner = chWithInner.images[0]
        assertTrue(inner.isCover)
        assertEquals("OEBPS/images/cover.png", inner.src)

        val chPureVector = parser.parseBytes(
            buildRawEpub(
                opf = """
                    |<?xml version="1.0" encoding="UTF-8"?>
                    |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                    |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>t</dc:title>
                    |<meta name="cover" content="cv"/></metadata>
                    |<manifest>
                    |<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    |<item id="cv" href="images/cover.svg" media-type="image/svg+xml"/>
                    |</manifest>
                    |<spine><itemref idref="ch1"/></spine>
                    |</package>
                    |""".trimMargin(),
                files = mapOf(
                    "OEBPS/ch1.xhtml" to minimalChapter("<p>正文。</p>"),
                    "OEBPS/images/cover.svg" to
                        """<svg xmlns="http://www.w3.org/2000/svg"><rect width="300" height="400"/></svg>""".toByteArray()
                )
            ),
            "svg_cover_pure.epub"
        ).chapters[0]
        assertTrue("纯矢量 svg 封面不提取", chPureVector.images.isEmpty())
    }

    /** broken_image.epub：缺失条目与截断位图都提取为 ParagraphImage（渲染层占位），正文文本完整 */
    @Test
    fun m11_brokenImage_stillParsedAndTextIntact() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("broken_image.epub"), "broken_image.epub")
        val ch = doc.chapters[0]
        assertEquals(2, ch.images.size)
        val lost = ch.images[0]
        assertTrue(lost.src.endsWith("OEBPS/images/lost.png"))
        assertEquals("失联配图", lost.alt)
        assertNull(ch.images[1].alt)
        assertTrue(ch.paragraphs.contains("正文仍在的首段。"))
        assertTrue(ch.paragraphs.contains("正文结尾段。"))
    }

    /** 无图 epub：images 恒空（mixed_encoding 单章无图；no_nav/sample 等复用基础章节含图，不适合此断言） */
    @Test
    fun m11_noImageEpub_imagesEmpty() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("mixed_encoding.epub"), "mixed_encoding.epub")
        assertTrue(doc.chapters.all { it.images.isEmpty() })
        assertTrue(doc.chapters[0].paragraphs.isNotEmpty())
    }

    /** M3：整章纯图（图版/扉页，无文本段落）→ 合成空字符串宿主段落承载图片，不判空 */
    @Test
    fun m3_pureImageChapter_hostParagraphSynthesized() = runBlocking {
        val bytes = buildRawEpub(
            opf = """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>t</dc:title></metadata>
                |<manifest>
                |<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                |<item id="p" href="images/plate.png" media-type="image/png"/>
                |</manifest>
                |<spine><itemref idref="ch1"/></spine>
                |</package>
                |""".trimMargin(),
            files = mapOf(
                "OEBPS/ch1.xhtml" to minimalChapter("""<p><img src="images/plate.png" alt="图版页"/></p>"""),
                "OEBPS/images/plate.png" to byteArrayOf(0x03)
            )
        )
        val ch = parser.parseBytes(bytes, "plate.epub").chapters[0]
        assertEquals("纯图章应合成一条空宿主段落承载图片", listOf(""), ch.paragraphs)
        assertEquals(1, ch.images.size)
        assertEquals(0, ch.images[0].paragraphIndex)
        assertTrue(ch.images[0].src.endsWith("OEBPS/images/plate.png"))
    }

    /** I2：读侧 zip-slip——container full-path 逃逸到缓存目录外时拒绝读取（按缺失 → MissingOpf），不泄露外部文件 */
    @Test
    fun i2_readSideEscape_rejectedAsMissing() {
        val cacheDir = tmpFolder.newFolder("cache")
        File(cacheDir, "META-INF").mkdirs()
        File(cacheDir, "META-INF/container.xml").writeText("""
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="../secret.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent())
        // 若未做读侧校验，../secret.opf 会被读出并按 OPF 解析（manifest 引用缺失章节 → MalformedContent）
        File(cacheDir.parentFile, "secret.opf").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>t</dc:title></metadata>
            <manifest><item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
            <spine><itemref idref="ch1"/></spine>
            </package>
        """.trimIndent())
        runBlocking {
            try {
                parser.parseFromDir(cacheDir, "escape://x", "escape.epub")
                fail("逃逸路径应被拒绝并抛 MissingOpf")
            } catch (e: ParseException.MissingOpf) {
                // 预期：read 返回 null（拒绝越界），OPF 视为缺失
            } catch (e: ParseException.MalformedContent) {
                fail("读侧 zip-slip 防护失效：越界 OPF 被读出解析（应拒绝为缺失）")
            }
        }
    }

    /**
     * C4（一致性评审 §5）：XHTML 抽取循环为段级，超大单章须响应取消。
     * 阻塞式同步解析不主动让出协程，取消靠段级 cancelCheck 及时止损（生产默认为线程中断，
     * 经 runInterruptible 由协程取消触发）；注入确定性 cancelCheck 断言"解析中途即抛、而非跑完整章"。
     */
    @Test
    fun c4_interruptMidChapter_throwsCancellation() {
        val bigBody = buildString {
            repeat(20_000) { append("<p>第 $it 段取消粒度测试文字。</p>") }
        }
        val bytes = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$bigBody</body></html>"
            .toByteArray(Charsets.UTF_8)
        var iterations = 0
        var caught: Throwable? = null
        try {
            // 取消检查在第 50 个事件后触发——解析中途被取消，不应返回完整段落
            parser.extractChapterFrom(bytes, cancelCheck = { ++iterations >= 50 })
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(
            "段级循环应响应取消抛 CancellationException，实际: ${caught?.let { it::class.simpleName }} $caught",
            caught is CancellationException
        )
        assertTrue("取消应发生在解析中途（不应跑完 ${bytes.size} 字节）", iterations < 1_000)
    }

    /** 造一个简单 OEBPS/ 布局的最小 epub（供封面等自包含用例），容器指向 OEBPS/content.opf */
    private fun buildRawEpub(opf: String, files: Map<String, ByteArray>): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val entries = linkedMapOf<String, ByteArray>(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray()
        )
        entries.putAll(files)
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun minimalChapter(body: String): ByteArray =
        """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>章</title></head><body>$body</body></html>"""
            .toByteArray(Charsets.UTF_8)

    /** 构造最小 epub2（container + opf + 导航 + 单章），供缺陷回归测试自包含使用（不依赖 fixture） */
    private fun buildMinimalEpub(
        navHref: String,
        navLabel: String,
        useNav: Boolean = false
    ): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>t</dc:title></metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                ${if (useNav) "<item id=\"nav\" href=\"text/nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" else ""}
                <item id="ch1" href="text/ch1.html" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="ch1"/></spine>
            </package>
        """.trimIndent()
        val ncx = """
            <?xml version="1.0" encoding="utf-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1"><navLabel><text>$navLabel</text></navLabel><content src="$navHref"/></navPoint>
              </navMap>
            </ncx>
        """.trimIndent()
        val ch1 = """
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>内页标题</title></head>
            <body><p>正文第一段</p></body></html>
        """.trimIndent()
        val entries = linkedMapOf<String, ByteArray>(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container.toByteArray(),
            "content.opf" to opf.toByteArray(),
            if (useNav) "text/nav.xhtml" to
                """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>nav</title></head>
                <body><nav epub:type="toc"><ol><li><a href="$navHref">$navLabel</a></li></ol></nav></body></html>
                """.trimIndent().toByteArray() else "toc.ncx" to ncx.toByteArray(),
            "text/ch1.html" to ch1.toByteArray()
        )
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
