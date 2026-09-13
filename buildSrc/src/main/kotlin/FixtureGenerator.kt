import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * M1 测试夹具生成器。按《测试用例》V1.0-REV3 §0 清单生成全部 fixtures。
 * 由 app 模块的 generateFixtures task 调用，输出到 test resources 与 debug assets。
 *
 * 注意：生成的文件不入库（gitignore），构建/测试前由 Gradle task 自动生成。
 */
object FixtureGenerator {

    fun generate(outDir: File) {
        outDir.mkdirs()
        textFixtures(outDir)
        epubFixtures(outDir)
    }

    // ---------------- txt/md/xml 夹具 ----------------

    private fun textFixtures(dir: File) {
        val longLine = "abcdefghijklmnopqrstuvwxyz0123456789 中英文混排超长行测试 The quick brown fox jumps over the lazy dog。".repeat(10)
        check(longLine.length >= 500)

        write(dir, "sample_utf8.txt", Charsets.UTF_8, buildString {
            appendLine("第一章 开始")
            appendLine("Hello, 世界！这是 UTF-8 无 BOM 样本。")
            repeat(5) { appendLine("第 ${it + 1} 段：普通段落内容。") }
            appendLine(longLine)
        })

        write(dir, "sample_gbk.txt", charset("GBK"),
            "GBK 编码样本。\n包含 GBK 特有字符：镕。\n第二行结束。\n")

        write(dir, "sample_latin1.txt", charset("ISO-8859-1"),
            "Café, naïve, über.\nLatin-1 encoded sample with é and ü.\n")

        write(dir, "sample_bom.txt", Charsets.UTF_8,
            "带 BOM 的 UTF-8 样本。\n第二行。\n", bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

        write(dir, "sample_utf16le.txt", charset("UTF-16LE"),
            "UTF-16LE 样本。\nSecond line.\n", bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()))

        write(dir, "sample_utf16be.txt", charset("UTF-16BE"),
            "UTF-16BE 样本。\nSecond line.\n", bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte()))

        write(dir, "sample.md", Charsets.UTF_8, """
            |# 标题一
            |
            |## 标题二
            |
            |普通段落，含 **加粗** 与 *斜体*。
            |
            |- 列表项一
            |- 列表项二
            |
            |> 引用段落。
            |
            |```kotlin
            |fun main() { println("code") }
            |```
            |
            |未识别语法按原文输出 ~~删除线~~。
            |""".trimMargin())

        // M11 新增（F2.13/TC-F2-20a）：多级 ATX 标题（H1~H6）混合正文/列表/围栏代码块，
        // 围栏代码块内含 `#` 注释行——目录提取必须跳过（F2.13）。
        write(dir, "sample_toc.md", Charsets.UTF_8, """
            |# 标题一（H1）
            |
            |## 标题二（H2）
            |
            |正文段落，含 **加粗** 与 *斜体*。
            |
            |### 标题三（H3）
            |
            |- 列表项一
            |- 列表项二
            |
            |#### 标题四（H4）
            |
            |```text
            |# 这是代码注释，不应出现在目录
            |def code():
            |    pass
            |```
            |
            |##### 标题五（H5）
            |
            |###### 标题六（H6）
            |
            |## 结尾标题（H2B）
            |
            |结尾段落。
            |""".trimMargin())

        write(dir, "sample.xml", Charsets.UTF_8, """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<library>
            |  <book id="1">
            |    <title>示例书</title>
            |    <author>
            |      <name>作者</name>
            |      <contact>
            |        <email>a@b.c</email>
            |      </contact>
            |    </author>
            |  </book>
            |</library>
            |""".trimMargin())

        // large.txt ≈ 8 MB；huge.txt ≈ 15 MB（超出 10 MB 编辑上限）
        write(dir, "large.txt", Charsets.UTF_8, repeatedText(8 * 1024 * 1024))
        write(dir, "huge.txt", Charsets.UTF_8, repeatedText(15 * 1024 * 1024))

        // M3 追加（TC-F2-24）：0 字节空文件
        write(dir, "empty.txt", Charsets.UTF_8, "")
    }

    private fun repeatedText(targetBytes: Int): String {
        val line = "这是一行用于填充大文件的重复文本 The quick brown fox jumps over the lazy dog 0123456789。\n"
        // 按 UTF-8 字节数（而非字符数）计目标体积：中文 3 字节/字
        val repeats = targetBytes / line.toByteArray(Charsets.UTF_8).size
        return line.repeat(repeats)
    }

    private fun write(dir: File, name: String, charset: java.nio.charset.Charset, content: String, bom: ByteArray? = null) {
        val bytes = content.toByteArray(charset)
        File(dir, name).writeBytes(if (bom != null) bom + bytes else bytes)
    }

    // ---------------- epub 夹具 ----------------

    private fun epubFixtures(dir: File) {
        val chapters = (1..3).map { i ->
            "ch$i.xhtml" to xhtml("第 $i 章", "<p>第 $i 章第一段。</p><p>第 $i 章第二段。</p>" +
                if (i == 1) """<p>含图段落。</p><img src="pic.png"/><p>图片后段落。</p>""" else "")
        }

        // epub2：NCX 导航
        epub(dir, "sample.epub",
            opf(chapters, ncx = true, nav = false),
            buildMap {
                putAll(chapters)
                put("toc.ncx", ncx(listOf("第 1 章" to "ch1.xhtml", "第 2 章" to "ch2.xhtml", "第 3 章" to "ch3.xhtml")))
            })

        // epub3：nav.xhtml 导航
        epub(dir, "sample3.epub",
            opf(chapters, ncx = false, nav = true),
            buildMap {
                putAll(chapters)
                put("nav.xhtml", navXhtml(listOf("第 1 章" to "ch1.xhtml", "第 2 章" to "ch2.xhtml", "第 3 章" to "ch3.xhtml")))
            })

        // epub3 + NCX 并存，标题刻意不同 → 验证 nav.xhtml 优先
        epub(dir, "hybrid.epub",
            opf(chapters, ncx = true, nav = true),
            buildMap {
                putAll(chapters)
                put("nav.xhtml", navXhtml(listOf("NAV第一章" to "ch1.xhtml", "NAV第二章" to "ch2.xhtml", "NAV第三章" to "ch3.xhtml")))
                put("toc.ncx", ncx(listOf("NCX第一章" to "ch1.xhtml", "NCX第二章" to "ch2.xhtml", "NCX第三章" to "ch3.xhtml")))
            })

        // >100 章
        val manyChapters = (1..120).map { i -> "ch$i.xhtml" to xhtml("第 $i 章", "<p>第 $i 章内容。</p>") }
        epub(dir, "many_chapters.epub",
            opf(manyChapters, ncx = false, nav = true),
            buildMap {
                putAll(manyChapters)
                put("nav.xhtml", navXhtml((1..120).map { "第 $it 章" to "ch$it.xhtml" }))
            })

        // 嵌套目录 >3 级（epub2 NCX 四层）
        epub(dir, "nested_toc.epub",
            opf(chapters, ncx = true, nav = false),
            buildMap {
                putAll(chapters)
                put("toc.ncx", """
                    |<?xml version="1.0" encoding="UTF-8"?>
                    |<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                    |<navMap>
                    |<navPoint id="l1"><navLabel><text>第一级</text></navLabel><content src="ch1.xhtml"/>
                    |  <navPoint id="l2"><navLabel><text>第二级</text></navLabel><content src="ch1.xhtml"/>
                    |    <navPoint id="l3"><navLabel><text>第三级</text></navLabel><content src="ch2.xhtml"/>
                    |      <navPoint id="l4"><navLabel><text>第四级</text></navLabel><content src="ch3.xhtml"/>
                    |      </navPoint>
                    |    </navPoint>
                    |  </navPoint>
                    |</navPoint>
                    |</navMap>
                    |</ncx>
                    |""".trimMargin())
            })

        // 内嵌 XHTML 声明 GBK（正例）
        epub(dir, "mixed_encoding.epub",
            opf(listOf("ch1.xhtml" to ""), ncx = true, nav = false),
            buildMap {
                put("ch1.xhtml", xhtml("GBK章节", "<p>这是 GBK 编码的章节内容。</p>", encoding = "GBK").toByteArray(charset("GBK")))
                put("toc.ncx", ncx(listOf("GBK章节" to "ch1.xhtml")))
            })

        // XHTML 声明 UTF-8 但实际是 GBK 字节（负例：XmlPullParser 应抛异常）
        epub(dir, "bad_decl.epub",
            opf(listOf("ch1.xhtml" to ""), ncx = true, nav = false),
            buildMap {
                put("ch1.xhtml", xhtml("坏章节", "<p>声明与实际编码不符的内容。</p>", encoding = "UTF-8").toByteArray(charset("GBK")))
                put("toc.ncx", ncx(listOf("坏章节" to "ch1.xhtml")))
            })

        // 缺 OPF
        epub(dir, "broken.epub",
            opf = null, // container.xml 指向不存在的 OPF
            files = mapOf("ch1.xhtml" to xhtml("孤儿章", "<p>内容。</p>")))

        // 无导航文件（F2.16/M12：无 NCX/nav → Document.toc 为空、章节 title 全 null，不再退化"第 N 章"）
        epub(dir, "no_nav.epub",
            opf(chapters, ncx = false, nav = false),
            chapters.toMap())

        // M12 新增（F2.16/TC-UT-05 改写、TC-UT-14/15）：epub 原生目录 + 锚点映射夹具。
        // NCX 原样条目：嵌套层级（第二节嵌套于第一节）+ 带 fragment 的 content src（命中 sec1/sec2/mid、未命中 nope）；
        // p3.xhtml 不编入 NCX（验证章节 title 为 null、无生成式兜底条目）。
        // p1 段落索引契约（测试断言据此）：0 开头段、1 锚点段(sec1)、2 后续段、3 容器首段(sec2)、4 容器次段。
        // p4 边界：`<a name="mid">` 位于段落文字中段（段落尚未 flush）→ 应映射到所在段落索引 1，而非上一段。
        epub(dir, "anchor_toc.epub",
            opf = """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                |<metadata><dc:identifier xmlns:dc="http://purl.org/dc/elements/1.1/" id="id">fixture-anchor</dc:identifier>
                |<dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">anchor</dc:title>
                |<dc:language xmlns:dc="http://purl.org/dc/elements/1.1/">zh</dc:language></metadata>
                |<manifest>
                |<item id="p0" href="Text/p0.xhtml" media-type="application/xhtml+xml"/>
                |<item id="p1" href="Text/p1.xhtml" media-type="application/xhtml+xml"/>
                |<item id="p2" href="Text/p2.xhtml" media-type="application/xhtml+xml"/>
                |<item id="p3" href="Text/p3.xhtml" media-type="application/xhtml+xml"/>
                |<item id="p4" href="Text/p4.xhtml" media-type="application/xhtml+xml"/>
                |<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                |</manifest>
                |<spine toc="ncx">
                |<itemref idref="p0"/><itemref idref="p1"/><itemref idref="p2"/><itemref idref="p3"/><itemref idref="p4"/>
                |</spine>
                |</package>
                |""".trimMargin(),
            files = linkedMapOf(
                "Text/p0.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                    |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>封面页</title></head>
                    |<body><p>封面页段落。</p></body></html>
                    |""".trimMargin(),
                "Text/p1.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                    |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一节</title></head>
                    |<body><p>第一节开头段。</p><p id="sec1">第一节锚点段。</p><p>第一节后续段。</p><div id="sec2"><p>第二节容器内首段。</p><p>第二节容器内次段。</p></div></body></html>
                    |""".trimMargin(),
                "Text/p2.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                    |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>未命中锚点</title></head>
                    |<body><p>未命中锚点章第一段。</p><p>未命中锚点章第二段。</p></body></html>
                    |""".trimMargin(),
                "Text/p3.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                    |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>未编入目录</title></head>
                    |<body><p>未编入目录的章节段落。</p></body></html>
                    |""".trimMargin(),
                "Text/p4.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                    |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>中间锚点</title></head>
                    |<body><p>中段锚点章第一段。</p><p>第二段前文<a name="mid">锚点后文</a>。</p></body></html>
                    |""".trimMargin(),
                "toc.ncx" to """
                    |<?xml version="1.0" encoding="UTF-8"?>
                    |<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                    |<navMap>
                    |<navPoint id="np0"><navLabel><text>封面页</text></navLabel><content src="Text/p0.xhtml"/></navPoint>
                    |<navPoint id="np1"><navLabel><text>第一节</text></navLabel><content src="Text/p1.xhtml#sec1"/>
                    |<navPoint id="np2"><navLabel><text>第二节</text></navLabel><content src="Text/p1.xhtml#sec2"/></navPoint>
                    |</navPoint>
                    |<navPoint id="np3"><navLabel><text>未命中锚点</text></navLabel><content src="Text/p2.xhtml#nope"/></navPoint>
                    |<navPoint id="np4"><navLabel><text>中间锚点</text></navLabel><content src="Text/p4.xhtml#mid"/></navPoint>
                    |</navMap>
                    |</ncx>
                    |""".trimMargin()
            ))

        // epub3 nav.xhtml 嵌套 <ol>（olDepth>1 → chapter.level>1）
        epub(dir, "nested_nav.epub",
            opf(chapters, ncx = false, nav = true),
            buildMap {
                putAll(chapters)
                put("nav.xhtml", """
                    |<?xml version="1.0" encoding="UTF-8"?>
                    |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                    |<head><title>目录</title></head>
                    |<body><nav epub:type="toc"><ol>
                    |<li><a href="ch1.xhtml">第一部</a><ol>
                    |<li><a href="ch2.xhtml">第二章</a><ol>
                    |<li><a href="ch3.xhtml">第三节</a></li>
                    |</ol></li>
                    |</ol></li>
                    |</ol></nav></body>
                    |</html>
                    |""".trimMargin())
            })

        // spine idref 不在 manifest 中（结构错误）→ 应抛 ParseException.MalformedContent
        epub(dir, "bad_spine.epub",
            opf = """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                |<metadata><dc:identifier xmlns:dc="http://purl.org/dc/elements/1.1/" id="id">fixture</dc:identifier>
                |<dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">fixture</dc:title>
                |<dc:language xmlns:dc="http://purl.org/dc/elements/1.1/">zh</dc:language></metadata>
                |<manifest><item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
                |<spine><itemref idref="ghost"/></spine>
                |</package>
                |""".trimMargin(),
            files = mapOf("ch1.xhtml" to xhtml("孤立章", "<p>内容。</p>")))

        // M2 追加（TC-UT-06a）：OPF 的 manifest/spine 引用 ch1.xhtml，但 zip 中并不存在该条目
        // → 结构错误且无任何字节可供降级，应抛 ParseException.MalformedContent
        epub(dir, "malformed.epub",
            opf(listOf("ch1.xhtml" to ""), ncx = false, nav = false),
            emptyMap())

        // M11 新增（F2.14/TC-F2-17/17c/17d）：含图 epub 三件套（图片用程序生成的小位图，评审 S2/S12）
        imageEpubFixtures(dir)

        // M11 新增（F2.15/TC-F2-17e/17f）：CSS 子集样式 epub 两件套
        cssEpubFixtures(dir)
    }

    // ---------------- M11 含图 epub 夹具（F2.14） ----------------

    /** 程序生成 PNG/JPEG 位图：纯色底 + 白色对角叉线，便于肉眼/截图区分（ImageIO 纯软件编码，无显示依赖） */
    private fun pngBytes(width: Int, height: Int, rgb: Int): ByteArray {
        val img = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(rgb)
        g.fillRect(0, 0, width, height)
        g.color = java.awt.Color.WHITE
        g.drawLine(0, 0, width - 1, height - 1)
        g.drawLine(0, height - 1, width - 1, 0)
        g.dispose()
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun jpgBytes(width: Int, height: Int, rgb: Int): ByteArray {
        val img = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(rgb)
        g.fillRect(0, 0, width, height)
        g.color = java.awt.Color.WHITE
        g.drawLine(width / 2, 0, width / 2, height - 1)
        g.dispose()
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    /** svg 夹具（纯矢量，渲染层须给占位，不做任何 CSS/矢量解析） */
    private val svgFixture = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><circle cx="32" cy="32" r="24" fill="#888"/></svg>"""

    private fun imageEpubFixtures(dir: File) {
        val png = pngBytes(480, 320, 0xCC3333)   // 暗红底（正文按屏宽等比显示，位图留足分辨率不过糊）
        val jpg = jpgBytes(480, 320, 0x339933)   // 绿底
        val svg = svgFixture
        val spacedPng = pngBytes(480, 320, 0x9966CC) // 文件名含空格（src 经 %20 编码，验证渲染层 URL 解码查找）

        // 1) sample_images.epub（TC-F2-17）：正文含 png/jpg 位图各一（带 alt）+ 一张 svg（无 alt）
        //    + 一张文件名含空格、src 用 %20 编码的位图（L3 覆盖）。
        //    布局刻意覆盖多种 img 语义：独立 img（块级）挂前一段、段落文字内 img 归所在段、尾随独立 svg/位图。
        //    各段落索引 → [0]图注段 [1]内联图段 [2]尾段；图归属 0 / 1 / 1 / 1。
        //    显式 zip 目录条目 "OEBPS/"（zip -r 常见形态，I1 覆盖：缓存解压须跳过目录条目而非当文件写）。
        epub(dir, "sample_images.epub",
            opf = """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                |<metadata><dc:identifier xmlns:dc="http://purl.org/dc/elements/1.1/" id="id">fixture-imgs</dc:identifier>
                |<dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">images</dc:title>
                |<dc:language xmlns:dc="http://purl.org/dc/elements/1.1/">zh</dc:language></metadata>
                |<manifest>
                |<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                |<item id="fig1" href="images/fig1.png" media-type="image/png"/>
                |<item id="fig2" href="images/fig2.jpg" media-type="image/jpeg"/>
                |<item id="fig3" href="images/fig.svg" media-type="image/svg+xml"/>
                |</manifest>
                |<spine><itemref idref="ch1"/></spine>
                |</package>
                |""".trimMargin(),
            files = linkedMapOf<String, Any>(
                "ch1.xhtml" to """
                    |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>images</title></head>
                    |<body>
                    |<p>第一段：前言文字。</p>
                    |<img src="images/fig1.png" alt="示例PNG插图"/>
                    |<p>第二段开头。<img src="images/fig2.jpg" alt="示例JPG插图"/>第二段结尾。</p>
                    |<img src="images/fig.svg"/>
                    |<img src="images/with%20space.png" alt="空格文件名图"/>
                    |<p>尾段文字。</p>
                    |</body></html>
                    |""".trimMargin(),
                "images/fig1.png" to png,
                "images/fig2.jpg" to jpg,
                "images/fig.svg" to svg,
                "images/with space.png" to spacedPng
            ),
            directories = setOf("OEBPS/"))

        // 2) sample_cover.epub（TC-F2-17c）：OPF <meta name="cover" content="cover-img"> 指向位图封面。
        //    封面不出现在正文，靠解析 OPF 声明挂到 chapter[0].images 首条（isCover, paragraphIndex=0）。
        epub(dir, "sample_cover.epub",
            opf = """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                |<metadata><dc:identifier xmlns:dc="http://purl.org/dc/elements/1.1/" id="id">fixture-cover</dc:identifier>
                |<dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">cover</dc:title>
                |<dc:language xmlns:dc="http://purl.org/dc/elements/1.1/">zh</dc:language>
                |<meta name="cover" content="cover-img"/></metadata>
                |<manifest>
                |<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                |<item id="cover-img" href="images/cover.png" media-type="image/png"/>
                |</manifest>
                |<spine><itemref idref="ch1"/></spine>
                |</package>
                |""".trimMargin(),
            files = linkedMapOf<String, Any>(
                "ch1.xhtml" to """
                    |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>cover</title></head>
                    |<body><p>封面之后的第一段文字。</p><p>第二段文字。</p></body></html>
                    |""".trimMargin(),
                "images/cover.png" to pngBytes(480, 640, 0x3366CC)
            ))

        // 3) broken_image.epub（TC-F2-17d）：img 引用 zip 中不存在的条目（带 alt）+ 一张截断位图（无 alt）。
        //    lost.png 不出现在 files（缺失条目）；broken.png 为**头部损坏**的 PNG——前半段截断不足以让
        //    BitmapFactory 必失败（实测可返回部分位图），改清零前 24 字节（签名+IHDR 头）保证解码必返 null。
        val brokenPng = pngBytes(96, 64, 0x666666).also { java.util.Arrays.fill(it, 0, 24, 0) }
        epub(dir, "broken_image.epub",
            opf = """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                |<metadata><dc:identifier xmlns:dc="http://purl.org/dc/elements/1.1/" id="id">fixture-broken</dc:identifier>
                |<dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">broken</dc:title>
                |<dc:language xmlns:dc="http://purl.org/dc/elements/1.1/">zh</dc:language></metadata>
                |<manifest>
                |<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                |<item id="broken" href="images/broken.png" media-type="image/png"/>
                |</manifest>
                |<spine><itemref idref="ch1"/></spine>
                |</package>
                |""".trimMargin(),
            files = linkedMapOf<String, Any>(
                "ch1.xhtml" to """
                    |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>broken</title></head>
                    |<body>
                    |<p>正文仍在的首段。</p>
                    |<img src="images/lost.png" alt="失联配图"/>
                    |<img src="images/broken.png"/>
                    |<p>正文结尾段。</p>
                    |</body></html>
                    |""".trimMargin(),
                "images/broken.png" to brokenPng
            ))
    }

    // ---------------- M11 新增（F2.15）CSS 子集样式 epub 夹具 ----------------

    /**
     * 1) styled.epub（TC-F2-17e）：`<style>` + 内联 style 覆盖字号/字重/斜体/对齐/颜色/段距。
     *    章 1 段落索引 → 期望样式（与解析层测试/渲染合并严格对齐）：
     *    [0] 居中大标题：h1(align center + bold) × .big(font-size 2em) → {bold,fontSizeEm2,align1}
     *    [1] 引言：id=#intro → {color #CC0000, italic}
     *    [2] 带段距：class=.note margin-top:1.2em → {paragraphSpacing=true}
     *    [3] 行内：font-size 18pt(→1.5) + color #0000FF + bold + right → {fontSizeEm1.5,bold,align2,color蓝}
     *    [4] 普通段落：无样式
     *    [5][6] 列表项：li{margin:0} → 无段距无样式（0 档）
     *    章 2 纯文本无样式（styles 空，验证仅 epub 样式章非空）。
     */
    private fun cssEpubFixtures(dir: File) {
        epub(dir, "styled.epub",
            opf(listOf("ch1.xhtml" to "", "ch2.xhtml" to ""), ncx = true, nav = false),
            buildMap {
                put("ch1.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>样式章</title>
                    <style type="text/css">
                    h1 { text-align: center; font-weight: bold; }
                    .big { font-size: 2em; }
                    #intro { color: #CC0000; font-style: italic; }
                    li { margin-top: 0; margin-bottom: 0; }
                    .note { margin-top: 1.2em; }
                    </style></head>
                    <body>
                    <h1 class="big">居中大标题</h1>
                    <p id="intro">引言文字内容。</p>
                    <p class="note">带段间距的段落。</p>
                    <p style="font-size: 18pt; color: #0000FF; font-weight: bold; text-align: right;">行内样式右对齐蓝字。</p>
                    <p>普通段落无样式。</p>
                    <ul><li>无序列表项一。</li><li>无序列表项二。</li></ul>
                    </body></html>
                """.trimIndent())
                put("ch2.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>纯文本章</title></head>
                    <body><p>第二章普通段落一。</p><p>第二章普通段落二。</p></body></html>
                """.trimIndent())
                put("toc.ncx", ncx(listOf("样式章" to "ch1.xhtml", "纯文本章" to "ch2.xhtml")))
            })

        // 2) bad_css.epub（TC-F2-17f）：样式表含非法语法/未知选择器混排——非法部分静默忽略，
        //    合法子集（p 规则 color+font-size）仍生效，正文可读不崩溃。
        epub(dir, "bad_css.epub",
            opf(listOf("ch1.xhtml" to ""), ncx = true, nav = false),
            buildMap {
                put("ch1.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>坏样式章</title>
                    <style type="text/css">
                    ??? { color: red; }
                    p { color: #222222; font-size: 24px; }
                    .a .b { font-weight: bold; }
                    p > span { font-style: italic; }
                    @media screen { p { color: blue; } }
                    .x { margin-left: 30px; }
                    </style></head>
                    <body>
                    <p>正文第一段可读。</p>
                    <p class="a b"><span>未知后代选择器不生效。</span></p>
                    <p style="color: notacolor">非法颜色忽略、正文可读。</p>
                    </body></html>
                """.trimIndent())
                put("toc.ncx", ncx(listOf("坏样式章" to "ch1.xhtml")))
            })

        // 3) headings.epub（TC-F2-28/29，F2.17/M15）：章 1 无任何样式声明（h1~h6 各一段 + 正文段）
        //    → 全部走阅读器默认标题层级；章 2 混合（h1/h4 无声明、h2 声明 font-size+font-weight:normal、
        //    h3 声明 font-size:2em）→ 验证「书 CSS 覆盖而非叠加」与 bold 三态。
        //    期望（与解析层/合并单测严格对齐）：
        //    章 1 headingLevels = [1, null, 2, null, 3, 4, 5, 6, null]，styles 全空；
        //    章 2 headingLevels = [1, 2, 3, 4, null]，styles[1] = {fontSizeEm 1.1, bold false}、
        //    styles[2] = {fontSizeEm 2.0}（bold 未声明 → 走默认加粗）。
        epub(dir, "headings.epub",
            opf(listOf("ch1.xhtml" to "", "ch2.xhtml" to ""), ncx = true, nav = false),
            buildMap {
                put("ch1.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>默认标题层级章</title></head>
                    <body>
                    <h1>一级标题</h1>
                    <p>一级标题后正文一。</p>
                    <h2>二级标题</h2>
                    <p>二级标题后正文二。</p>
                    <h3>三级标题</h3>
                    <h4>四级标题</h4>
                    <h5>五级标题</h5>
                    <h6>六级标题</h6>
                    <p>正文段落无样式。</p>
                    </body></html>
                """.trimIndent())
                put("ch2.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>覆盖章</title>
                    <style type="text/css">
                    h2 { font-size: 1.1em; font-weight: normal; }
                    h3 { font-size: 2em; }
                    </style></head>
                    <body>
                    <h1>覆盖章一级标题</h1>
                    <h2>覆盖章二级标题</h2>
                    <h3>覆盖章三级标题</h3>
                    <h4>覆盖章四级标题</h4>
                    <p>覆盖章正文。</p>
                    </body></html>
                """.trimIndent())
                put("toc.ncx", ncx(listOf("默认标题层级章" to "ch1.xhtml", "覆盖章" to "ch2.xhtml")))
            })
    }

    private fun xhtml(title: String, body: String, encoding: String = "UTF-8") = """
        |<?xml version="1.0" encoding="$encoding"?>
        |<html xmlns="http://www.w3.org/1999/xhtml">
        |<head><title>$title</title></head>
        |<body><h1>$title</h1>$body</body>
        |</html>
        |""".trimMargin()

    private fun opf(chapters: List<Pair<String, String>>, ncx: Boolean, nav: Boolean) = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<package xmlns="http://www.idpf.org/2007/opf" version="${if (nav) "3.0" else "2.0"}" unique-identifier="id">
        |<metadata><dc:identifier xmlns:dc="http://purl.org/dc/elements/1.1/" id="id">fixture</dc:identifier>
        |<dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">fixture</dc:title>
        |<dc:language xmlns:dc="http://purl.org/dc/elements/1.1/">zh</dc:language></metadata>
        |<manifest>
        |${chapters.joinToString("\n") { (href, _) -> """<item id="${href.removeSuffix(".xhtml")}" href="$href" media-type="application/xhtml+xml"/>""" }}
        |${if (nav) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" else ""}
        |${if (ncx) """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""" else ""}
        |</manifest>
        |<spine${if (ncx) """ toc="ncx"""" else ""}>
        |${chapters.joinToString("\n") { (href, _) -> """<itemref idref="${href.removeSuffix(".xhtml")}"/>""" }}
        |</spine>
        |</package>
        |""".trimMargin()

    private fun ncx(entries: List<Pair<String, String>>) = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
        |<navMap>
        |${entries.mapIndexed { i, (label, src) -> """<navPoint id="np$i"><navLabel><text>$label</text></navLabel><content src="$src"/></navPoint>""" }.joinToString("\n")}
        |</navMap>
        |</ncx>
        |""".trimMargin()

    private fun navXhtml(entries: List<Pair<String, String>>) = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        |<head><title>目录</title></head>
        |<body><nav epub:type="toc"><ol>
        |${entries.joinToString("\n") { (label, src) -> """<li><a href="$src">$label</a></li>""" }}
        |</ol></nav></body>
        |</html>
        |""".trimMargin()

    private fun epub(dir: File, name: String, opf: String?, files: Map<String, Any>, directories: Set<String> = emptySet()) {
        File(dir, name).outputStream().use { fos ->
            ZipOutputStream(fos).use { zip ->
                // mimetype 必须第一个且不压缩（STORED 需预先设置 size/crc）
                val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
                zip.putNextEntry(ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimeBytes.size.toLong()
                    compressedSize = mimeBytes.size.toLong()
                    crc = java.util.zip.CRC32().apply { update(mimeBytes) }.value
                })
                zip.write(mimeBytes)
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write("""
                    |<?xml version="1.0" encoding="UTF-8"?>
                    |<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    |<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    |</container>
                    |""".trimMargin().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                if (opf != null) {
                    zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
                    zip.write(opf.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                // 显式 zip 目录条目（如 "OEBPS/"）：zip -r 打包 epub 的常见形态，I1 需覆盖
                directories.forEach { d ->
                    val dirEntry = if (d.endsWith('/')) d else "$d/"
                    zip.putNextEntry(ZipEntry(dirEntry))
                    zip.closeEntry()
                }

                files.forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry("OEBPS/$path"))
                    when (content) {
                        is ByteArray -> zip.write(content)
                        else -> zip.write(content.toString().toByteArray(Charsets.UTF_8))
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    // ---------------- fixtures 校验（M1 验证：编码与结构符合《测试用例》§0 描述） ----------------

    /** 校验 [dir] 下的 fixtures；不符合描述时抛出 IllegalStateException。 */
    fun validate(dir: File) {
        textChecks(dir)
        epubChecks(dir)
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw IllegalStateException("fixtures 校验失败：$message")
    }

    private fun textChecks(dir: File) {
        fun bytes(name: String) = File(dir, name).also {
            check(it.isFile, "$name 不存在")
        }.readBytes()

        val utf8 = bytes("sample_utf8.txt")
        check(utf8.toString(Charsets.UTF_8).lines().any { it.length >= 500 },
            "sample_utf8.txt 缺少 ≥500 字符的超长行")
        check(!utf8.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
            "sample_utf8.txt 不应带 BOM")

        check(String(bytes("sample_gbk.txt"), charset("GBK")).contains("镕"),
            "sample_gbk.txt 应可用 GBK 解码且含 GBK 特有字符'镕'")
        check(String(bytes("sample_latin1.txt"), charset("ISO-8859-1")).contains("é"),
            "sample_latin1.txt 应可用 Latin-1 解码且含 é")
        check(bytes("sample_bom.txt").startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
            "sample_bom.txt 应以 UTF-8 BOM 开头")
        check(bytes("sample_utf16le.txt").startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
            "sample_utf16le.txt 应以 FF FE 开头")
        check(bytes("sample_utf16be.txt").startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
            "sample_utf16be.txt 应以 FE FF 开头")

        val md = bytes("sample.md").toString(Charsets.UTF_8)
        check(md.contains("# ") && md.contains("```") && md.contains("**") && md.contains("> "),
            "sample.md 应含标题/代码块/加粗/引用")

        // M11 新增（F2.13）：sample_toc.md 应含多级 ATX 标题与围栏代码块（块内含 `#` 注释行）
        val tocMd = bytes("sample_toc.md").toString(Charsets.UTF_8)
        val headingLevels = Regex("^#{1,6} ", RegexOption.MULTILINE).findAll(tocMd).count()
        check(headingLevels >= 6, "sample_toc.md 应含多级 ATX 标题（至少 H1~H6 各一）")
        check(tocMd.contains("```text") && tocMd.lines().any { it.startsWith("# 这是代码注释") },
            "sample_toc.md 应含围栏代码块且块内带 `#` 注释行")

        check(bytes("sample.xml").toString(Charsets.UTF_8).contains("<contact>"),
            "sample.xml 嵌套层级应 ≥ 3")

        check(File(dir, "large.txt").length() in (7 * 1024 * 1024)..(9 * 1024 * 1024),
            "large.txt 应约 8 MB")
        check(File(dir, "huge.txt").length() in (14 * 1024 * 1024)..(16 * 1024 * 1024),
            "huge.txt 应约 15 MB")

        // M3 追加（TC-F2-24）
        check(File(dir, "empty.txt").let { it.isFile && it.length() == 0L },
            "empty.txt 应为 0 字节空文件")
    }

    private fun epubChecks(dir: File) {
        fun entries(name: String): Map<String, ByteArray> {
            val file = File(dir, name)
            check(file.isFile, "$name 不存在")
            val map = linkedMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(file.inputStream()).use { zip ->
                var e = zip.nextEntry
                check(e?.name == "mimetype", "$name 首条目必须是 mimetype")
                while (e != null) {
                    map[e.name] = zip.readBytes()
                    e = zip.nextEntry
                }
            }
            return map
        }

        listOf("sample.epub", "sample3.epub", "hybrid.epub", "many_chapters.epub",
            "nested_toc.epub", "mixed_encoding.epub", "bad_decl.epub", "broken.epub", "no_nav.epub",
            "malformed.epub", "nested_nav.epub", "bad_spine.epub",
            "sample_images.epub", "sample_cover.epub", "broken_image.epub",
            "styled.epub", "bad_css.epub", "anchor_toc.epub", "headings.epub")
            .forEach { name ->
                val e = entries(name)
                check(e["mimetype"]?.toString(Charsets.US_ASCII) == "application/epub+zip",
                    "$name mimetype 内容错误")
                check(e.containsKey("META-INF/container.xml"), "$name 缺少 container.xml")
                if (name == "broken.epub") {
                    check(!e.containsKey("OEBPS/content.opf"), "broken.epub 应缺 OPF")
                } else {
                    check(e.containsKey("OEBPS/content.opf"), "$name 缺少 OPF")
                }
            }

        val sample = entries("sample.epub")
        check(sample.containsKey("OEBPS/toc.ncx") && !sample.containsKey("OEBPS/nav.xhtml"),
            "sample.epub 应为 epub2 NCX 导航")
        check(sample["OEBPS/ch1.xhtml"]?.toString(Charsets.UTF_8)?.contains("<img") == true,
            "sample.epub 第 1 章应含图片标签")

        val sample3 = entries("sample3.epub")
        check(sample3.containsKey("OEBPS/nav.xhtml") && !sample3.containsKey("OEBPS/toc.ncx"),
            "sample3.epub 应为 epub3 nav.xhtml 导航")

        val hybrid = entries("hybrid.epub")
        val navText = hybrid["OEBPS/nav.xhtml"]?.toString(Charsets.UTF_8) ?: ""
        val ncxText = hybrid["OEBPS/toc.ncx"]?.toString(Charsets.UTF_8) ?: ""
        check(navText.contains("NAV第一章") && ncxText.contains("NCX第一章"),
            "hybrid.epub 的 nav 与 NCX 标题应刻意不同")

        check(entries("many_chapters.epub").keys.count { it.endsWith(".xhtml") && it.contains("/ch") } > 100,
            "many_chapters.epub 应 > 100 章")
        check(entries("nested_toc.epub")["OEBPS/toc.ncx"]?.toString(Charsets.UTF_8)?.contains("第四级") == true,
            "nested_toc.epub 目录嵌套应 > 3 级")

        val mixed = entries("mixed_encoding.epub")["OEBPS/ch1.xhtml"] ?: ByteArray(0)
        check(String(mixed, charset("GBK")).contains("GBK 编码"),
            "mixed_encoding.epub 章节应可用 GBK 解码")
        check(entries("no_nav.epub").keys.none { it.endsWith("nav.xhtml") || it.endsWith("toc.ncx") },
            "no_nav.epub 应不含导航文件")

        // M2 追加（TC-UT-06a）
        val malformed = entries("malformed.epub")
        check(malformed["OEBPS/content.opf"]?.toString(Charsets.UTF_8)?.contains("ch1.xhtml") == true
            && !malformed.containsKey("OEBPS/ch1.xhtml"),
            "malformed.epub 的 manifest/spine 应引用 zip 中不存在的 ch1.xhtml")

        // nav.xhtml 嵌套 <ol>：至少 3 层 <ol> 开标签
        val nestedNav = entries("nested_nav.epub")["OEBPS/nav.xhtml"]?.toString(Charsets.UTF_8) ?: ""
        check(Regex("<ol>").findAll(nestedNav).count() >= 3,
            "nested_nav.epub 的 nav.xhtml 应含 ≥3 层嵌套 <ol>")

        // spine 引用 manifest 中不存在的 id
        val badSpine = entries("bad_spine.epub")["OEBPS/content.opf"]?.toString(Charsets.UTF_8) ?: ""
        check(badSpine.contains("""idref="ghost"""") && !badSpine.contains("""id="ghost""""),
            "bad_spine.epub 的 spine 应引用 manifest 中不存在的 idref=ghost")

        // M11 含图夹具（F2.14/TC-F2-17/17c/17d）：图片媒体类型、章节内 img 语义、缺失/截断条目
        val sampleImagesEntries = entries("sample_images.epub")
        val sampleImagesOpf = sampleImagesEntries["OEBPS/content.opf"]?.toString(Charsets.UTF_8) ?: ""
        check(sampleImagesOpf.contains("media-type=\"image/png\"") &&
            sampleImagesOpf.contains("media-type=\"image/jpeg\"") &&
            sampleImagesOpf.contains("media-type=\"image/svg+xml\""),
            "sample_images.epub 的 manifest 应声明 png/jpg/svg 图片条目")
        val sampleImagesCh = sampleImagesEntries["OEBPS/ch1.xhtml"]?.toString(Charsets.UTF_8) ?: ""
        check(Regex("""<img[^>]*alt="示例PNG插图"""").containsMatchIn(sampleImagesCh) &&
            Regex("""<img[^>]*alt="示例JPG插图"""").containsMatchIn(sampleImagesCh),
            "sample_images.epub 的位图 img 应带 alt")
        check(sampleImagesEntries.containsKey("OEBPS/"),
            "sample_images.epub 应含显式 zip 目录条目 OEBPS/（I1 覆盖）")
        check(sampleImagesEntries.containsKey("OEBPS/images/with space.png") &&
            sampleImagesCh.contains("images/with%20space.png"),
            "sample_images.epub 应含文件名含空格、src 以 %20 编码的位图（L3 覆盖）")

        val coverOpf = entries("sample_cover.epub")["OEBPS/content.opf"]?.toString(Charsets.UTF_8) ?: ""
        check(coverOpf.contains("""<meta name="cover" content="cover-img"/>""") &&
            coverOpf.contains("href=\"images/cover.png\""),
            "sample_cover.epub 应含 meta name=cover 指向位图封面")

        val broken = entries("broken_image.epub")
        check(!broken.containsKey("OEBPS/images/lost.png"),
            "broken_image.epub 应缺失 lost.png 条目")
        val fullPngBytes = pngBytes(96, 64, 0x666666).size
        val brokenPngBytes = broken.getValue("OEBPS/images/broken.png")
        check(brokenPngBytes.size == fullPngBytes &&
            brokenPngBytes.copyOfRange(0, 24).all { it == 0.toByte() },
            "broken_image.epub 的 broken.png 应为头部清零的损坏 PNG（体积不变、前 24 字节全 0，保证 BitmapFactory 解码必失败）")

        // M11 新增（F2.15/TC-F2-17e/17f）：styled/bad_css 内容自检
        val styledCh1 = entries("styled.epub")["OEBPS/ch1.xhtml"]?.toString(Charsets.UTF_8) ?: ""
        check(styledCh1.contains("<style type=\"text/css\">") &&
            styledCh1.contains("h1 { text-align: center; font-weight: bold; }") &&
            styledCh1.contains(".big { font-size: 2em; }") &&
            styledCh1.contains("#intro { color: #CC0000; font-style: italic; }") &&
            styledCh1.contains("li { margin-top: 0; margin-bottom: 0; }") &&
            styledCh1.contains("""<p style="font-size: 18pt; color: #0000FF; font-weight: bold; text-align: right;">"""),
            "styled.epub 第 1 章应含 <style> 规则（h1/.big/#intro/li）与内联 style 行")
        check(styledCh1.contains("居中大标题") && styledCh1.contains("无序列表项一。"),
            "styled.epub 第 1 章正文应完整")
        val styledCh2 = entries("styled.epub")["OEBPS/ch2.xhtml"]?.toString(Charsets.UTF_8) ?: ""
        check(styledCh2.contains("第二章普通段落一。") && !styledCh2.contains("<style"),
            "styled.epub 第 2 章应为无样式纯文本章")
        check(entries("styled.epub").containsKey("OEBPS/toc.ncx"),
            "styled.epub 应含 NCX 目录")

        val badCssCh1 = entries("bad_css.epub")["OEBPS/ch1.xhtml"]?.toString(Charsets.UTF_8) ?: ""
        check(badCssCh1.contains("??? { color: red; }") &&
            badCssCh1.contains("@media screen") &&
            badCssCh1.contains("p { color: #222222; font-size: 24px; }"),
            "bad_css.epub 第 1 章应含非法语法（???/@media 段）与合法子集规则")
        check(badCssCh1.contains("正文第一段可读。"),
            "bad_css.epub 第 1 章正文应完整")

        // M15（F2.17/TC-UT-17）：headings.epub 结构自检——章 1 六种标题且**无任何字号声明**（默认层级前提），
        // 章 2 含 h2/h3 样式声明（覆盖语义）且 h1/h4 无声明
        val headingsCh1 = entries("headings.epub")["OEBPS/ch1.xhtml"]?.toString(Charsets.UTF_8) ?: ""
        check((1..6).all { headingsCh1.contains("<h$it>") },
            "headings.epub 第 1 章应含 h1~h6 各一段")
        check(headingsCh1.contains("正文段落无样式。") && !headingsCh1.contains("<style") &&
            !headingsCh1.contains("font-size"),
            "headings.epub 第 1 章应无任何样式/字号声明（默认层级走查前提）")
        val headingsCh2 = entries("headings.epub")["OEBPS/ch2.xhtml"]?.toString(Charsets.UTF_8) ?: ""
        check(headingsCh2.contains("h2 { font-size: 1.1em; font-weight: normal; }") &&
            headingsCh2.contains("h3 { font-size: 2em; }"),
            "headings.epub 第 2 章应含 h2/h3 字号声明")
        check((1..4).all { headingsCh2.contains("<h$it>") },
            "headings.epub 第 2 章应含 h1~h4 各一段（h1/h4 无声明）")
        check(entries("headings.epub").containsKey("OEBPS/toc.ncx"),
            "headings.epub 应含 NCX 目录")

        // M12 新增（F2.16/TC-UT-14/15）：anchor_toc.epub 结构自检——navPoint 数量/嵌套层级/fragment/锚点 id/未编入章节
        val anchorEntries = entries("anchor_toc.epub")
        val anchorNcx = anchorEntries["OEBPS/toc.ncx"]?.toString(Charsets.UTF_8) ?: ""
        check(Regex("<navPoint\\b").findAll(anchorNcx).count() == 5,
            "anchor_toc.epub 的 NCX 应含 5 条 navPoint")
        check(anchorNcx.contains("""src="Text/p0.xhtml"""") &&
            anchorNcx.contains("""src="Text/p1.xhtml#sec1"""") &&
            anchorNcx.contains("""src="Text/p1.xhtml#sec2"""") &&
            anchorNcx.contains("""src="Text/p2.xhtml#nope"""") &&
            anchorNcx.contains("""src="Text/p4.xhtml#mid""""),
            "anchor_toc.epub 的 NCX href 应原样保留 fragment（p0 无 fragment、p1 两条、p2 未命中、p4 段中）")
        check(!anchorNcx.contains("p3.xhtml"),
            "anchor_toc.epub 的 p3.xhtml 不应编入 NCX")
        val anchorP1 = anchorEntries["OEBPS/Text/p1.xhtml"]?.toString(Charsets.UTF_8) ?: ""
        check(anchorP1.contains("""id="sec1"""") && anchorP1.contains("""id="sec2""""),
            "anchor_toc.epub 的 p1.xhtml 应含 sec1/sec2 锚点 id")
        val anchorP4 = anchorEntries["OEBPS/Text/p4.xhtml"]?.toString(Charsets.UTF_8) ?: ""
        check(anchorP4.contains("""<a name="mid">锚点后文</a>"""),
            "anchor_toc.epub 的 p4.xhtml 应含段中 <a name=\"mid\"> 锚点")
        val iNp1 = anchorNcx.indexOf("""<navPoint id="np1">""")
        val iNp2 = anchorNcx.indexOf("""<navPoint id="np2">""")
        val iNp3 = anchorNcx.indexOf("""<navPoint id="np3">""")
        val iNp4 = anchorNcx.indexOf("""<navPoint id="np4">""")
        check(iNp1 in 0 until iNp2 && iNp2 < iNp3 && iNp3 < iNp4,
            "anchor_toc.epub 的 NCX 应先序为 np1 → 嵌套 np2 → np3 → np4")
        check(Regex("</navPoint>").findAll(anchorNcx.substring(iNp2, iNp3)).count() == 2,
            "anchor_toc.epub 的第二节应嵌套于第一节内（np2 与 np1 依次闭合，层级 1/1/2/1）")
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
