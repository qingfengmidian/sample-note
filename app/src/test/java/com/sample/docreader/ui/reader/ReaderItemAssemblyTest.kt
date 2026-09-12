package com.sample.docreader.ui.reader

import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.ParagraphImage
import com.sample.docreader.core.model.ParagraphStyleHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M12c（F4.7/F2.14/F2.15）：多章 flatten 组装——图片/样式必须严格挂到所属段落，
 * 物化窗口只影响产出范围，不影响已产出项的坐标与内容（TC-UI-10 的渲染侧底座）。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderItemAssemblyTest {

    /** 基本组装：坐标、文本、章首标记、物化窗口截断 */
    @Test
    fun assemblesCoordinatesAndWindow() {
        val chapters = listOf(
            chapter("A", "a0", "a1"),
            chapter("B", "b0"),
            chapter("C", "c0", "c1", "c2")
        )
        val all = assembleReaderItems(chapters, materializedCount = 3)
        assertEquals(
            listOf(0 to 0, 0 to 1, 1 to 0, 2 to 0, 2 to 1, 2 to 2),
            all.map { it.chapter to it.paragraph }
        )
        assertEquals(listOf("a0", "a1", "b0", "c0", "c1", "c2"), all.map { it.text })
        assertEquals("每章首个 item 才是章首", listOf(true, false, true, true, false, false), all.map { it.isChapterFirst })

        val windowed = assembleReaderItems(chapters, materializedCount = 2)
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0), windowed.map { it.chapter to it.paragraph })
        // 物化窗口变化不影响已产出项的内容与坐标
        assertEquals(all.take(3), windowed)

        assertEquals("窗口越界 clamp 到章数", all, assembleReaderItems(chapters, materializedCount = 99))
        assertTrue("物化 0 章不产出 item", assembleReaderItems(chapters, materializedCount = 0).isEmpty())
    }

    /** 图片挂段：只挂 paragraphIndex 命中的段落，保持原相对顺序；不跨段、不丢失 */
    @Test
    fun attachesImagesToOwningParagraph() {
        val img0 = image(0, "cover.png", isCover = true)
        val img2a = image(2, "a.png")
        val img2b = image(2, "b.png")
        val chapters = listOf(
            Chapter(title = "A", paragraphs = listOf("p0", "p1", "p2"), images = listOf(img0, img2a, img2b))
        )
        val items = assembleReaderItems(chapters, materializedCount = 1)
        assertEquals(listOf(img0), items[0].images)
        assertTrue("段 1 无图", items[1].images.isEmpty())
        assertEquals("同段多图保持相对顺序", listOf(img2a, img2b), items[2].images)
    }

    /** 样式挂段：按章内段落索引取，缺省为 null；不串段 */
    @Test
    fun attachesStylesByParagraphIndex() {
        val bold = ParagraphStyleHints(bold = true)
        val chapters = listOf(
            Chapter(title = "A", paragraphs = listOf("p0", "p1"), styles = mapOf(1 to bold))
        )
        val items = assembleReaderItems(chapters, materializedCount = 1)
        assertNull("未声明样式的段落为 null", items[0].styles)
        assertEquals(bold, items[1].styles)
    }

    /** 空章与纯图章宿主段：段落数 0 的章不产出 item（全局索引契约）；宿主空段照常产出 */
    @Test
    fun emptyChapterProducesNoItem() {
        val hostImage = image(0, "plate.png")
        val chapters = listOf(
            chapter("空章", *emptyArray()),
            Chapter(title = "图版章", paragraphs = listOf(""), images = listOf(hostImage)),
            chapter("尾章", "z0")
        )
        val items = assembleReaderItems(chapters, materializedCount = 3)
        assertEquals(listOf(1 to 0, 2 to 0), items.map { it.chapter to it.paragraph })
        assertEquals("纯图章宿主段承载图片", listOf(hostImage), items[0].images)
        assertTrue("图版章的宿主段即章首", items[0].isChapterFirst)

        assertTrue("全空章书不产出 item", assembleReaderItems(sizedChapters(0, 0, 0), materializedCount = 3).isEmpty())
    }

    /** 空书 / 越界窗口：不抛异常 */
    @Test
    fun emptyBookAndNegativeWindow() {
        assertTrue(assembleReaderItems(emptyList(), materializedCount = 5).isEmpty())
        val chapters = listOf(chapter("A", "a0"))
        assertTrue(assembleReaderItems(chapters, materializedCount = -1).isEmpty())
    }

    private fun chapter(title: String, vararg paragraphs: String) =
        Chapter(title = title, paragraphs = paragraphs.toList())

    private fun sizedChapters(vararg sizes: Int) =
        sizes.mapIndexed { i, n -> Chapter(title = "第 ${i + 1} 章", paragraphs = List(n) { "段 $it" }) }

    private fun image(paragraphIndex: Int, src: String, isCover: Boolean = false) =
        ParagraphImage(paragraphIndex = paragraphIndex, src = src, alt = null, isCover = isCover)
}
