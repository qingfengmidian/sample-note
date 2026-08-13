package com.sample.docreader.core.parse

import com.sample.docreader.core.model.DocFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M2：ParserFactory 分发与 PlainTextParser（md/xml 按纯文本读入，单章）。 */
@RunWith(RobolectricTestRunner::class)
class ParserFactoryTest {

    @Test
    fun factoryDispatch() {
        assertTrue(ParserFactory.forFormat(DocFormat.TXT) is TxtParser)
        assertTrue(ParserFactory.forFormat(DocFormat.EPUB) is EpubParser)
        assertTrue(ParserFactory.forFormat(DocFormat.MD) is PlainTextParser)
        assertTrue(ParserFactory.forFormat(DocFormat.XML) is PlainTextParser)
        assertEquals(DocFormat.MD, (ParserFactory.forFormat(DocFormat.MD) as PlainTextParser).format)
        assertEquals(DocFormat.XML, (ParserFactory.forFormat(DocFormat.XML) as PlainTextParser).format)
    }

    /** md 按纯文本读入：单章、段落按行结构、标记原文保留 */
    @Test
    fun mdAsPlainText() = runBlocking {
        val parser = ParserFactory.forFormat(DocFormat.MD) as PlainTextParser
        val doc = parser.parseBytes(Fixtures.bytes("sample.md"), "sample.md")
        assertEquals(DocFormat.MD, doc.format)
        assertEquals(1, doc.chapters.size)
        val paragraphs = doc.chapters[0].paragraphs
        assertTrue(paragraphs.contains("# 标题一"))
        assertTrue(paragraphs.any { it.contains("**加粗**") })
    }

    /** xml 按纯文本读入：标签原文保留（高亮在渲染层，不进解析层） */
    @Test
    fun xmlAsPlainText() = runBlocking {
        val parser = ParserFactory.forFormat(DocFormat.XML) as PlainTextParser
        val doc = parser.parseBytes(Fixtures.bytes("sample.xml"), "sample.xml")
        assertEquals(DocFormat.XML, doc.format)
        assertEquals(1, doc.chapters.size)
        assertTrue(doc.chapters[0].paragraphs.any { it.contains("<contact>") })
    }
}
