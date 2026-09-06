package com.sample.docreader.core.parse

import android.content.ContentResolver
import android.net.Uri
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.render.MarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * md/xml 解析器：直接按纯文本读入（编码逻辑同 TxtParser，复用 TextDecoding），
 * 输出单章 Document；xml 的渲染与高亮在渲染层处理，不进解析层。
 * md 额外提取目录（F2.13）：与 MarkdownParser 共用同一标题扫描函数 scanHeadings，
 * 防两版标题/围栏逻辑漂移（评审 S10）。
 */
class PlainTextParser internal constructor(
    val format: DocFormat,
    private val probe: CharsetProbe = StrictUtf8Probe
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
            // F2.13：md 提取目录（单次线性扫描，无额外 IO）；xml 无 toc
            val toc = if (format == DocFormat.MD) MarkdownParser.scanHeadings(paragraphs) else emptyList()
            Document(uri, name, format, listOf(Chapter(null, paragraphs)), toc = toc)
        }
}
