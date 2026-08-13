package com.sample.docreader.core.parse

import android.content.ContentResolver
import android.net.Uri
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.ParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 文档解析层统一接口（《软件设计文档》§3.2） */
interface DocumentParser {
    /** 全量解析；实现须在循环内调用 ensureActive() 以响应协程取消 */
    suspend fun parse(uri: Uri, resolver: ContentResolver): Document
}

/** 按格式分发解析器 */
object ParserFactory {
    fun forFormat(format: DocFormat): DocumentParser = when (format) {
        DocFormat.TXT -> TxtParser()
        DocFormat.MD -> PlainTextParser(DocFormat.MD)
        DocFormat.XML -> PlainTextParser(DocFormat.XML)
        DocFormat.EPUB -> EpubParser()
    }
}

/** 读取 uri 全部字节；读取失败视为无权限/文件已移动 */
internal suspend fun readAllBytes(uri: Uri, resolver: ContentResolver): Pair<ByteArray, String> =
    withContext(Dispatchers.IO) {
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: SecurityException) {
            throw ParseException.NoPermission()
        } ?: throw ParseException.NoPermission()
        bytes to (uri.lastPathSegment ?: uri.toString())
    }
