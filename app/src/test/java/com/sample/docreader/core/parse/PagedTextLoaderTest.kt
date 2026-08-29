package com.sample.docreader.core.parse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * M8b：PagedTextLoader 分块加载（F4.2/TC-F4-02，《软件设计文档》§3.2）。
 * 核心判据为**黄金对照**：任意样本持续 nextChunk() 至 EOF 的拼接结果
 * 必须与 TextDecoding.toParagraphs（即 String.lines() 语义）完全一致。
 */
class PagedTextLoaderTest {

    /** 持续读取至 EOF，返回拼接后的全部段落 */
    private fun readAll(loader: PagedTextLoader): List<String> {
        val out = ArrayList<String>()
        while (loader.hasMore) {
            val chunk = runBlocking { loader.nextChunk() }
            assertTrue("hasMore=true 时块不得为空（防死循环）", chunk.isNotEmpty())
            out += chunk
        }
        return out
    }

    /** 全量基准：与 TxtParser 同一条解码路径 */
    private fun fullParse(bytes: ByteArray): List<String> =
        runBlocking { TextDecoding.toParagraphs(bytes, StrictUtf8Probe).second }

    /** TC-F4-02 兜底：5000 行含中文/emoji 样本，分块拼接 == 全量解析 */
    @Test
    fun chunkedRead_matchesFullParse_golden() {
        val text = buildString {
            repeat(5000) { i -> appendLine("第 $i 行：多字节字符 中文🎉混排 $i") }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val loader = PagedTextLoader.open(ByteArrayInputStream(bytes))
        assertEquals(fullParse(bytes), readAll(loader))
    }

    /** 多字节字符跨探测头部（8KB）边界不得乱码/丢段："中" 的前 2 字节在头内、第 3 字节在头外 */
    @Test
    fun multibyteChar_straddlingHeadBoundary_decodesCleanly() {
        // "中" UTF-8 = 3 字节；让它从第 8190 字节开始（8190, 8191 在头内，8192 在头外）
        val line = "a".repeat(8190) + "中"
        val text = "$line\n尾行"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val loader = PagedTextLoader.open(ByteArrayInputStream(bytes))
        assertEquals(text.lines(), readAll(loader))
    }

    /** \r\n 跨头边界：\r 为头部最后字节、\n 落在外部，不得拆出多余空段 */
    @Test
    fun crlf_straddlingHeadBoundary_singleParagraphBreak() {
        val text = "x".repeat(8191) + "\r\n" + "y"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val loader = PagedTextLoader.open(ByteArrayInputStream(bytes))
        assertEquals(text.lines(), readAll(loader))
    }

    /** 行尾语义钉死（与 lines() 一致）：末尾换行保留尾部空段 */
    @Test
    fun lineSemantics_matchStringLines() {
        listOf(
            "a\n",       // → ["a", ""]
            "a\n\n",     // → ["a", "", ""]
            "a",         // → ["a"]
            "",          // → [""]
            "\n",        // → ["", ""]
            "a\rb\r\nc"  // \r 与 \r\n 混合
        ).forEach { text ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            val loader = PagedTextLoader.open(ByteArrayInputStream(bytes))
            assertEquals("样本 ${text.replace("\n", "\\n").replace("\r", "\\r")} 分块结果须 == lines()",
                text.lines(), readAll(loader))
        }
    }

    /** GBK 样本（含"镕"，严格 GBK 回退路径）：分块 == 全量 */
    @Test
    fun gbk_chunkedMatchesFull() {
        val bytes = Fixtures.bytes("sample_gbk.txt")
        val loader = PagedTextLoader.open(ByteArrayInputStream(bytes))
        assertEquals(fullParse(bytes), readAll(loader))
    }

    /** UTF-16LE（BOM）：BOM 剥离、首段不含 \uFEFF，分块 == 全量 */
    @Test
    fun utf16le_bomStripped_chunkedMatchesFull() {
        val bytes = Fixtures.bytes("sample_utf16le.txt")
        val loader = PagedTextLoader.open(ByteArrayInputStream(bytes))
        val paragraphs = readAll(loader)
        assertEquals(fullParse(bytes), paragraphs)
        assertFalse(paragraphs.first().startsWith('\uFEFF'))
    }

    /** 小文件首块即全文，读完后 hasMore=false、再读返回空 */
    @Test
    fun smallFile_singleChunkThenEof() = runBlocking {
        val text = "一\n二\n三"
        val loader = PagedTextLoader.open(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        val first = loader.nextChunk()
        assertEquals(text.lines(), first)
        assertFalse(loader.hasMore)
        assertTrue(loader.nextChunk().isEmpty())
    }

    /** 取消响应：在已取消的协程上下文里 nextChunk 必须抛 CancellationException */
    @Test
    fun nextChunk_honoursCancellation() {
        val text = buildString { repeat(10000) { appendLine("line $it") } }
        val loader = PagedTextLoader.open(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        val job = Job().apply { cancel() }
        try {
            runBlocking { withContext(job) { loader.nextChunk() } }
            fail("已取消上下文中 nextChunk 应抛 CancellationException")
        } catch (e: CancellationException) {
            // 预期
        }
    }

    /** close 幂等；close 后 hasMore=false、nextChunk 返回空（不再读底层流） */
    @Test
    fun close_isIdempotentAndStopsReading() = runBlocking {
        val text = buildString { repeat(1000) { appendLine("line $it") } }
        val loader = PagedTextLoader.open(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        loader.close()
        loader.close()
        assertFalse(loader.hasMore)
        assertTrue(loader.nextChunk().isEmpty())
    }
}
