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

        // 无导航文件（标题退化为"第 N 章"）
        epub(dir, "no_nav.epub",
            opf(chapters, ncx = false, nav = false),
            chapters.toMap())

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

    private fun epub(dir: File, name: String, opf: String?, files: Map<String, Any>) {
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
            "malformed.epub", "nested_nav.epub", "bad_spine.epub")
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
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
