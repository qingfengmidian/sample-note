package com.sample.docreader.core.parse

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.SequenceInputStream
import java.nio.charset.Charset

/**
 * 分块文本加载器（M8b，F4.2/TC-F4-02，《软件设计文档》§3.2）：
 * txt 首屏只载前 [CHUNK_PARAGRAPHS] 段即上屏，滚动接近底部（余量 [PREFETCH_MARGIN] 段）追加下一块。
 *
 * 契约（实现方必须逐条满足，由 PagedTextLoaderTest 黄金对照钉死）：
 * - **段落语义与 `String.lines()` 完全一致**：`\n` / `\r\n` / `\r` 均为行分隔符；末尾无换行的尾行照样成段；
 *   **末尾换行产生的尾部空段保留**（`"a\n"` → `["a", ""]`）；空文件首块返回 `[""]`。
 *   判据：任意字节样本，持续 `nextChunk()` 至 [hasMore] = false，拼接结果 ==
 *   `TextDecoding.toParagraphs(全量字节)` 的段落列表。
 * - **编码探测沿用头部级联**：打开时先读 ≤ [HEAD_BYTES] 头部字节做 `TextDecoding.detectCharset`
 *   （BOM → 严格 UTF-8 → GBK → Latin-1），探测读取的字节不得丢失（拼接回流前）；BOM 字节须剥离，
 *   首段不得含 `\uFEFF`（含 UTF-16LE/BE 的 2 字节 BOM）。
 * - **流式解码**：逐块解码，多字节字符跨读缓冲边界不得产生乱码或丢段；流式模式用 REPLACE
 *   （不做全量严格回退——"头 8KB 可解、后段损坏"的极端样本与全量路径存在已知偏差，设计已记录可接受）。
 * - **取消响应**：`nextChunk()` 循环内 `ensureActive()`。
 * - **资源卫生**：[close] 关闭底层流；EOF 后（[hasMore] = false）底层流应已关闭或可安全重复 close。
 *
 * 非目标：md/xml 仍走全量解析（PlainTextParser 不变）；epub 按章天然分页（EpubParser 不变）。
 */
class PagedTextLoader private constructor(
    private val reader: BufferedReader,
    private val lastChar: LastCharTrackingReader,
    private val counting: CountingInputStream
) : Closeable {

    /** 一行前瞻：已读出但未被消费的下一行；null = 未读或已遇 EOF */
    private var lookahead: String? = null

    /** 底层流是否已到 EOF（readLine 返回 null） */
    private var eof = false

    /** EOF 时是否还欠一个尾部空段（String.lines() 语义：末尾是行终止符或空文件时补 ""） */
    private var trailingEmptyPending = false

    private var closed = false
    private var linesRead = 0L

    init {
        pump()
    }

    /** 是否还有未读出的段落（EOF = false） */
    val hasMore: Boolean
        get() = !closed && (lookahead != null || trailingEmptyPending)

    /** 已累计读出的底层字节数（含探测头部；EOF 时 == 文件总字节数），供 editable 判定修正（TC-F3-09） */
    internal val bytesRead: Long get() = counting.count

    /**
     * 读出下一块，最多 [CHUNK_PARAGRAPHS] 段；EOF 后返回空列表。
     * 取消：循环内 ensureActive()。
     */
    suspend fun nextChunk(): List<String> {
        if (closed) return emptyList()
        val out = ArrayList<String>(CHUNK_PARAGRAPHS)
        while (out.size < CHUNK_PARAGRAPHS && hasMore) {
            currentCoroutineContext().ensureActive()
            val line = lookahead
            if (line != null) {
                lookahead = null
                out += line
                linesRead++
                pump()
            } else if (trailingEmptyPending) {
                trailingEmptyPending = false
                out += ""
            }
        }
        return out
    }

    /** 关闭底层流；幂等。close 后 [hasMore] = false、[nextChunk] 返回空 */
    override fun close() {
        if (closed) return
        closed = true
        lookahead = null
        trailingEmptyPending = false
        reader.close()
    }

    /** 预读一行到 [lookahead]；EOF 时按"最后一个字符是行终止符 / 空文件"判定是否补尾部空段 */
    private fun pump() {
        if (eof || closed) return
        val line = reader.readLine() // readLine 天然把 \n / \r\n / \r 都当作行分隔符，与 lines() 一致
        if (line != null) {
            lookahead = line
        } else {
            eof = true
            // readLine 返回 null 时缓冲已耗尽，lastChar 即流的真实末字符
            trailingEmptyPending = linesRead == 0L ||
                lastChar.value == '\n'.code || lastChar.value == '\r'.code
        }
    }

    /** 记录解码后最后一个字符的 Reader 包装（用于 EOF 时判断是否以行终止符结尾） */
    private class LastCharTrackingReader(source: Reader) : Reader() {
        private val source = source
        var value: Int = -1
            private set

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            val n = source.read(cbuf, off, len)
            if (n > 0) value = cbuf[off + n - 1].code
            return n
        }

        override fun close() = source.close()
    }

    /** 累计底层读字节数的流包装（含探测头部，供 bytesRead 统计） */
    private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) count++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }
    }

    companion object {
        /** 每块段落数（设计§6：首屏 200 段） */
        const val CHUNK_PARAGRAPHS = 200

        /** 滚动距底多少段触发预取下一块（设计§6 阈值） */
        const val PREFETCH_MARGIN = 50

        /** 编码探测头部字节数（与 TextDecoding 一致） */
        internal const val HEAD_BYTES = 8 * 1024

        /**
         * 打开 [stream]（定位在文件开头）并完成编码探测，返回可逐块读取的加载器。
         * 探测所读的头部字节必须保留在流内语义中（不得吞掉）。
         * internal：CharsetProbe 为模块内可替换探测点（同 TxtParser 构造约定）。
         * [charsetOverride] 非空时跳过探测级联、按指定编码流式解码（G1 手动切换编码，F2.6；
         * 默认 null = 自动探测）；BOM 剥离规则与自动路径一致。
         */
        internal fun open(
            stream: InputStream,
            probe: CharsetProbe = StrictUtf8Probe,
            charsetOverride: Charset? = null
        ): PagedTextLoader {
            val counting = CountingInputStream(stream)
            // 读 ≤ HEAD_BYTES 头部做编码探测（不足则到 EOF）
            val head = ByteArray(HEAD_BYTES)
            var headLen = 0
            while (headLen < head.size) {
                val n = counting.read(head, headLen, head.size - headLen)
                if (n < 0) break
                headLen += n
            }
            val headBytes = head.copyOf(headLen)
            // G1：手动覆盖时跳过探测级联，直接使用指定编码
            val charset = charsetOverride
                ?: detectHeadCharset(headBytes, truncated = headLen == head.size, probe)
            // 剥离 BOM（UTF-8 3 字节 / UTF-16LE|BE 2 字节），首段不得含 \uFEFF
            val bomLen = when {
                headLen >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() &&
                    head[2] == 0xBF.toByte() -> 3
                headLen >= 2 && ((head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) ||
                    (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte())) -> 2
                else -> 0
            }
            // 头部余下字节拼接回流前：探测字节不丢失，多字节字符跨头边界由 InputStreamReader 流式解码
            val combined = SequenceInputStream(ByteArrayInputStream(headBytes, bomLen, headLen - bomLen), counting)
            // 流式模式用 REPLACE（InputStreamReader 默认错误动作），不做全量严格回退（已知偏差见类 KDoc）
            val tracking = LastCharTrackingReader(InputStreamReader(combined, charset))
            return PagedTextLoader(BufferedReader(tracking), tracking, counting)
        }

        /**
         * 头部编码探测：与全量路径同一条 detectCharset 级联。
         * 头部被截断（文件大于 HEAD_BYTES）时截尾 0~3 字节重试，避免截断切开多字节字符
         * 导致严格回退误判 Latin-1（StrictUtf8Probe 内部对 UTF-8 已有同类处理，这里覆盖 GBK 回退）。
         */
        private fun detectHeadCharset(head: ByteArray, truncated: Boolean, probe: CharsetProbe): Charset {
            if (!truncated) return TextDecoding.detectCharset(head, probe)
            for (cut in 0..3) {
                val candidate = if (cut == 0) head else head.copyOf(head.size - cut)
                val charset = TextDecoding.detectCharset(candidate, probe)
                if (charset != Charsets.ISO_8859_1) return charset
            }
            return Charsets.ISO_8859_1
        }
    }
}
