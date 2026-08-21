package com.sample.docreader.core.parse

import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.ParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M2：EpubParser（《测试用例》TC-UT-04/04a/04b/04c/04d/05/06/06a/06b/13）。
 * XmlPullParserFactory 来自平台（kxml2），须 Robolectric 运行。
 */
@RunWith(RobolectricTestRunner::class)
class EpubParserTest {

    private val parser = EpubParser()

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
}
