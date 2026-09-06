package com.sample.docreader.core.parse

import com.sample.docreader.core.model.DocFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlainTextParser md 目录提取（F2.13，TC-F2-20a 解析层）：
 * 解析 sample_toc.md → Document.toc 应含多级标题（H1~H6）、跳过围栏代码块内 `#` 注释行、
 * paragraphIndex 精确锚定源码段落流；xml 不提取 toc。
 */
class PlainTextParserTest {

    private val mdParser = PlainTextParser(DocFormat.MD)
    private val xmlParser = PlainTextParser(DocFormat.XML)

    /** F2.13：md 解析产物携带 toc；标题层级/标题文本/段落索引与源码段落流严格对应 */
    @Test
    fun md_parse_extractsToc() = runBlocking {
        val bytes = Fixtures.bytes("sample_toc.md")
        val doc = mdParser.parseBytes(bytes, "sample_toc.md")

        assertEquals(DocFormat.MD, doc.format)
        assertEquals(1, doc.chapters.size)
        val paragraphs = doc.chapters[0].paragraphs
        val toc = doc.toc

        // 预期 7 个真实标题（代码块外 H1~H6 与结尾 H2），逐条校验锚点
        assertEquals(7, toc.size)
        val expectedLevels = listOf(1, 2, 3, 4, 5, 6, 2)
        assertEquals(expectedLevels, toc.map { it.level })

        // 每个目录项的段落索引都精确指向"以对应级别 # 开头"的标题行，标题文本与行内剥离后一致
        toc.forEach { entry ->
            val line = paragraphs.getOrElse(entry.paragraphIndex) { error("toc 段落索引越界") }
            assertTrue("段落 ${entry.paragraphIndex} 应为 #${entry.level} 标题：$line",
                line.startsWith("#".repeat(entry.level) + " "))
            assertEquals(entry.title, line.removePrefix("#".repeat(entry.level)).trim())
        }

        // 代码块内 `# 这是代码注释...` 不得进入目录
        assertTrue(toc.none { it.title.contains("代码注释") })
        // paragraphIndex 按扫描顺序递增（单次线性扫描产物）
        assertTrue(toc.zipWithNext().all { (a, b) -> a.paragraphIndex < b.paragraphIndex })
        // 首个标题锚定到 H1 行
        assertTrue(paragraphs[toc.first().paragraphIndex].startsWith("# 标题一"))
    }

    /** F2.13：xml 不提取 toc */
    @Test
    fun xml_parse_hasEmptyToc() = runBlocking {
        val doc = xmlParser.parseBytes(Fixtures.bytes("sample.xml"), "sample.xml")
        assertEquals(DocFormat.XML, doc.format)
        assertTrue(doc.toc.isEmpty())
    }

    /** F2.13（S10）：Setext 中下划线式 `---` 不识别为标题——`标题甲` 与其下 `---` 均不入目录 */
    @Test
    fun md_parse_setextDashes_notToc() = runBlocking {
        val bytes = "标题甲\n---\n\n## 真标题\n".toByteArray(Charsets.UTF_8)
        val doc = mdParser.parseBytes(bytes, "setext.md")
        // 仅"## 真标题"（段落 3）入目录；标题甲与 --- 都不是 ATX 标题
        assertEquals(1, doc.toc.size)
        val entry = doc.toc[0]
        assertEquals(2, entry.level)
        assertEquals("真标题", entry.title)
        assertEquals(3, entry.paragraphIndex)
    }
}
