package com.sample.docreader.core.format

import com.sample.docreader.core.model.DocFormat

/**
 * 文档格式识别（设计§3.6）：先按显示名扩展名判定；
 * 扩展名不可靠（如 SAF 返回 application/octet-stream）时按文件头嗅探。
 */
object FormatSniffer {

    /** 返回 null 表示不支持的格式（F1.4） */
    fun sniff(displayName: String?, head: ByteArray? = null): DocFormat? {
        when (displayName?.substringAfterLast('.', "")?.lowercase()) {
            "txt" -> return DocFormat.TXT
            "md", "markdown" -> return DocFormat.MD
            "xml" -> return DocFormat.XML
            "epub" -> return DocFormat.EPUB
        }
        if (head != null && isEpub(head)) return DocFormat.EPUB
        return null
    }

    /** epub 是 ZIP 容器且含 mimetype 条目 "application/epub+zip"（前 256 字节内嗅探） */
    fun isEpub(head: ByteArray): Boolean {
        if (head.size < 4) return false
        // ZIP local file header magic: PK\x03\x04
        if (head[0] != 0x50.toByte() || head[1] != 0x4B.toByte() ||
            head[2] != 0x03.toByte() || head[3] != 0x04.toByte()
        ) return false
        val magic = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val window = head.take(256).toByteArray()
        return window.indices.any { i ->
            i + magic.size <= window.size && magic.indices.all { j -> window[i + j] == magic[j] }
        }
    }
}
