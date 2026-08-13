package com.sample.docreader.core.parse

import com.sample.docreader.core.model.DocFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M2：TxtParser 编码探测与纯文本解析（《测试用例》TC-UT-01/02/02a/03/03a/03b/13）。
 * android.icu.text.CharsetDetector 属 Android 框架类，须 Robolectric 运行。
 */
@RunWith(RobolectricTestRunner::class)
class TxtParserTest {

    private val parser = TxtParser()

    /** TC-UT-01：UTF-8 无 BOM → 判定 UTF-8，段落数与行结构一致 */
    @Test
    fun tcUt01_utf8NoBom() = runBlocking {
        val bytes = Fixtures.bytes("sample_utf8.txt")
        assertEquals(Charsets.UTF_8, parser.detectCharset(bytes))

        val doc = parser.parseBytes(bytes, "sample_utf8.txt")
        assertEquals(DocFormat.TXT, doc.format)
        assertEquals(1, doc.chapters.size)

        val lines = String(bytes, Charsets.UTF_8).lines()
        val paragraphs = doc.chapters[0].paragraphs
        assertEquals("段落数应与源文件行数一致", lines.size, paragraphs.size)
        assertEquals("第一章 开始", paragraphs[0])
        assertTrue("超长行应完整保留", paragraphs.any { it.length >= 500 })
        assertFalse(paragraphs.any { it.contains('\uFFFD') })
    }

    /** TC-UT-02：GBK 样本（含"镕"）→ 判定 GB 系编码，无乱码 */
    @Test
    fun tcUt02_gbk() = runBlocking {
        val bytes = Fixtures.bytes("sample_gbk.txt")
        val cs = parser.detectCharset(bytes)
        assertTrue("应判定为 GB 系编码，实际 $cs", cs.name().uppercase().startsWith("GB"))

        val doc = parser.parseBytes(bytes, "sample_gbk.txt")
        val text = doc.chapters[0].paragraphs.joinToString("\n")
        assertTrue(text.contains("镕"))
        assertFalse(text.contains('\uFFFD'))
    }

    /** TC-UT-02a：Latin-1 样本 → 判定 Latin-1 或兜底路径正确解码，无乱码 */
    @Test
    fun tcUt02a_latin1() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("sample_latin1.txt"), "sample_latin1.txt")
        val text = doc.chapters[0].paragraphs.joinToString("\n")
        assertTrue(text.contains("Café"))
        assertTrue(text.contains("über"))
        assertFalse(text.contains('\uFFFD'))
    }

    /** TC-UT-03：UTF-8 BOM → 识别并剥离，首段不含 \uFEFF */
    @Test
    fun tcUt03_utf8Bom() = runBlocking {
        val bytes = Fixtures.bytes("sample_bom.txt")
        assertEquals(Charsets.UTF_8, parser.detectCharset(bytes))

        val paragraphs = parser.parseBytes(bytes, "sample_bom.txt").chapters[0].paragraphs
        assertEquals("带 BOM 的 UTF-8 样本。", paragraphs[0])
        assertFalse(paragraphs[0].contains('\uFEFF'))
    }

    /** TC-UT-03a：UTF-16LE/BE 带 BOM → BOM 判定端序，无乱码 */
    @Test
    fun tcUt03a_utf16() = runBlocking {
        val le = Fixtures.bytes("sample_utf16le.txt")
        assertEquals(Charsets.UTF_16LE, parser.detectCharset(le))
        assertEquals("UTF-16LE 样本。", parser.parseBytes(le, "le.txt").chapters[0].paragraphs[0])

        val be = Fixtures.bytes("sample_utf16be.txt")
        assertEquals(Charsets.UTF_16BE, parser.detectCharset(be))
        assertEquals("UTF-16BE 样本。", parser.parseBytes(be, "be.txt").chapters[0].paragraphs[0])
    }

    /** TC-UT-03b：探测失败/极端字节流 → 回退 GBK → Latin-1，不抛异常 */
    @Test
    fun tcUt03b_fallback() = runBlocking {
        // 极端字节流：不应抛出任何异常
        val garbage = ByteArray(4096) { (it * 37 % 251).toByte() }
        parser.parseBytes(garbage, "garbage.bin")
        parser.parseBytes(ByteArray(0), "empty.txt")

        // 探测失败（fake probe 恒 null）→ 回退 GBK（严格解码）→ Latin-1
        val noProbe = TxtParser { null }
        val gbk = noProbe.parseBytes(Fixtures.bytes("sample_gbk.txt"), "g.txt")
        assertTrue("GBK 样本应经 GBK 回退正确解码", gbk.chapters[0].paragraphs.any { it.contains("镕") })

        val latin1 = noProbe.parseBytes(Fixtures.bytes("sample_latin1.txt"), "l.txt")
        assertTrue("Latin-1 样本 GBK 严格解码失败，应落到 Latin-1",
            latin1.chapters[0].paragraphs.joinToString("\n").contains("Café"))
    }

    /** TC-UT-13（txt 侧）：解析 large.txt 进行中取消 → ensureActive 生效，及时中止 */
    @Test(timeout = 60_000)
    fun tcUt13_cancelDuringParse() = runBlocking {
        val bytes = Fixtures.bytes("large.txt")
        var caught: Throwable? = null
        // 循环解析直到被取消，避免"解析先于 cancel 完成"的时序竞争
        val job = launch(Dispatchers.Default) {
            try {
                while (true) parser.parseBytes(bytes, "large.txt")
            } catch (t: Throwable) {
                caught = t
                throw t
            }
        }
        delay(50) // 让解析进入逐行循环
        job.cancel()
        job.join()
        assertTrue("取消后应抛出 CancellationException，实际 $caught", caught is kotlinx.coroutines.CancellationException)
    }
}
