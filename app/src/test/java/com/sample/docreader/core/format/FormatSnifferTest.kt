package com.sample.docreader.core.format

import com.sample.docreader.core.model.DocFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC-UT-10（嗅探部分）：扩展名识别 + epub 文件头嗅探 + octet-stream/未知回退。 */
class FormatSnifferTest {

    @Test
    fun sniff_byExtension() {
        assertEquals(DocFormat.TXT, FormatSniffer.sniff("a.txt"))
        assertEquals(DocFormat.MD, FormatSniffer.sniff("a.md"))
        assertEquals(DocFormat.MD, FormatSniffer.sniff("a.MARKDOWN"))
        assertEquals(DocFormat.XML, FormatSniffer.sniff("a.xml"))
        assertEquals(DocFormat.EPUB, FormatSniffer.sniff("a.epub"))
    }

    @Test
    fun sniff_epubMagic_withoutExtension() {
        val head = zipHeadWith("application/epub+zip")
        assertEquals(DocFormat.EPUB, FormatSniffer.sniff(null, head))
        assertEquals(DocFormat.EPUB, FormatSniffer.sniff("未知.bin", head))
    }

    @Test
    fun sniff_unknownExtension_noEpubMagic_returnsNull() {
        assertNull(FormatSniffer.sniff("a.bin"))
        assertNull(FormatSniffer.sniff(null))
        // 有 ZIP 头但非 epub mimetype，不应判为 epub
        val zipNotEpub = ByteArray(256) { 0x00 }.also { a ->
            a[0] = 0x50; a[1] = 0x4B; a[2] = 0x03; a[3] = 0x04
        }
        assertNull(FormatSniffer.sniff(null, zipNotEpub))
    }

    @Test
    fun isEpub_detectsMagicWithinFirst256Bytes() {
        assertTrue(FormatSniffer.isEpub(zipHeadWith("application/epub+zip")))
        assertFalse(FormatSniffer.isEpub(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(FormatSniffer.isEpub(ByteArray(3)))
    }

    /** 构造一个以 ZIP 头开头、且前 256 字节内含指定 ASCII 字符串的头部样本 */
    private fun zipHeadWith(magic: String): ByteArray {
        val bytes = ByteArray(256)
        bytes[0] = 0x50; bytes[1] = 0x4B; bytes[2] = 0x03; bytes[3] = 0x04
        val payload = magic.toByteArray(Charsets.US_ASCII)
        val offset = 64
        payload.copyInto(bytes, offset)
        return bytes
    }
}
