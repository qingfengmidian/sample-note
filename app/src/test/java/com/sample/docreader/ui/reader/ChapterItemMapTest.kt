package com.sample.docreader.ui.reader

import com.sample.docreader.core.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M12b（F4.7）：章级前缀和映射边界用例（TC-UI-10/11 的纯函数底座）。
 * 契约见 `ChapterItemMap` KDoc：clamp 不抛异常、空章宽度 0、空书退化。
 */
@RunWith(RobolectricTestRunner::class)
class ChapterItemMapTest {

    private fun chapters(vararg sizes: Int): List<Chapter> =
        sizes.mapIndexed { i, n -> Chapter(title = "第 ${i + 1} 章", paragraphs = List(n) { "段 $it" }) }

    /** 前缀和：offsets/totalItems 覆盖多章、含空章 */
    @Test
    fun offsetsAndTotal() {
        val map = ChapterItemMap(chapters(3, 0, 2))
        assertEquals(intArrayOf(0, 3, 3).toList(), map.offsets.toList())
        assertEquals(5, map.totalItems)
        assertEquals(3, map.chapterCount)
    }

    /** locate：章首/章尾/跨章边界/越界 clamp；空章不可达（宽度 0） */
    @Test
    fun locateBoundaries() {
        val map = ChapterItemMap(chapters(3, 0, 2))
        assertEquals(ChapterPosition(0, 0), map.locate(0))
        assertEquals(ChapterPosition(0, 2), map.locate(2))
        assertEquals("第 1 章末项之后即第 3 章首项（第 2 章宽 0）", ChapterPosition(2, 0), map.locate(3))
        assertEquals(ChapterPosition(2, 1), map.locate(4))
        assertEquals("负索引 clamp 到首项", ChapterPosition(0, 0), map.locate(-5))
        assertEquals("越界 clamp 到末项", ChapterPosition(2, 1), map.locate(99))
    }

    /** globalItem：正向换算、越界 clamp、空章返回章起始、段超限 clamp 到该章末段 */
    @Test
    fun globalItemBoundaries() {
        val map = ChapterItemMap(chapters(3, 0, 2))
        assertEquals(0, map.globalItem(0, 0))
        assertEquals(2, map.globalItem(0, 2))
        assertEquals(3, map.globalItem(2, 0))
        assertEquals(4, map.globalItem(2, 5))
        assertEquals("空章（段落数 0）返回其起始 item", 3, map.globalItem(1, 0))
        assertEquals("章索引越界 clamp 到末章", 4, map.globalItem(9, 1))
        assertEquals("负章索引 clamp 到首章", 0, map.globalItem(-1, 0))
    }

    /** 首项换算：firstItemOfChapter 越界 clamp；空书全退化 0 */
    @Test
    fun firstItemAndEmptyBook() {
        val map = ChapterItemMap(chapters(3, 0, 2))
        assertEquals(0, map.firstItemOfChapter(0))
        assertEquals(3, map.firstItemOfChapter(1))
        assertEquals(4, map.firstItemOfChapter(9))

        val empty = ChapterItemMap(emptyList())
        assertEquals(0, empty.totalItems)
        assertEquals(0, empty.firstItemOfChapter(0))
        assertEquals(ChapterPosition(0, 0), empty.locate(3))
        assertEquals(0, empty.globalItem(0, 0))

        val zeroWidth = ChapterItemMap(chapters(0, 0))
        assertEquals(0, zeroWidth.totalItems)
        assertEquals(ChapterPosition(0, 0), zeroWidth.locate(0))
    }

    /** 往返一致：locate(globalItem(c, p)) == (c, p)（非空章、合法段落） */
    @Test
    fun roundTrip() {
        val sizes = listOf(4, 1, 3)
        val map = ChapterItemMap(chapters(*sizes.toIntArray()))
        for ((c, n) in sizes.withIndex()) {
            for (p in 0 until n) {
                assertEquals(ChapterPosition(c, p), map.locate(map.globalItem(c, p)))
            }
        }
    }
}
