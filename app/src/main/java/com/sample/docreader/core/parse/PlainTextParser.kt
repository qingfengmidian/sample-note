package com.sample.docreader.core.parse

import android.content.ContentResolver
import android.net.Uri
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * md/xml 解析器：直接按纯文本读入（编码逻辑同 TxtParser，复用 TextDecoding），
 * 输出单章 Document；md/xml 的渲染与高亮在渲染层处理，不进解析层。
 */
class PlainTextParser internal constructor(
    val format: DocFormat,
    private val probe: CharsetProbe = IcuCharsetProbe
) : DocumentParser {

    init {
        require(format == DocFormat.MD || format == DocFormat.XML) { "PlainTextParser 仅支持 MD/XML" }
    }

    override suspend fun parse(uri: Uri, resolver: ContentResolver): Document {
        val (bytes, name) = readAllBytes(uri, resolver)
        return parseBytes(bytes, name, uri.toString())
    }

    /** 直接喂字节解析（单测不经 ContentResolver） */
    internal suspend fun parseBytes(bytes: ByteArray, name: String, uri: String = name): Document =
        withContext(Dispatchers.IO) {
            val (_, paragraphs) = TextDecoding.toParagraphs(bytes, probe)
            Document(uri, name, format, listOf(Chapter(null, paragraphs)))
        }
}
