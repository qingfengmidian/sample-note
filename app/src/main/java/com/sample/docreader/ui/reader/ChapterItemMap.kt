package com.sample.docreader.ui.reader

import com.sample.docreader.core.model.Chapter

/** 全局 item 坐标（F4.7）：章索引 + 章内段落索引 */
data class ChapterPosition(val chapter: Int, val paragraph: Int)

/**
 * 章级前缀和映射（F4.7，M12b）：把「第 i 章第 j 段」与「全书第 n 个 item」互转。
 *
 * 连续滚动下 LazyColumn 使用**全局 item 索引**，而阅读进度落库坐标仍是 `(chapter, paragraph)`
 * （`ProgressEntity` 零迁移）。本类为纯函数映射，不持有任何 Android 依赖，便于单测钉死边界。
 *
 * 语义约定：
 * - `totalItems` = 各章段落数之和；**段落数为 0 的章宽度为 0**（全局索引无法指向它）；
 * - `locate` 与 `globalItem` 均对越界输入做 clamp，不抛异常；
 * - 空书（无章或全为空章）：`totalItems == 0`，`locate` 返回 (0, 0)，`firstItemOfChapter`/`globalItem` 返回 0。
 */
class ChapterItemMap(private val chapters: List<Chapter>) {

    /** 各章起始全局 item 索引（长度 = 章数；第 i 项 = 前 i 章段落数之和） */
    val offsets: IntArray = TODO("M12b 实现")

    /** 全书 item 总数 */
    val totalItems: Int = TODO("M12b 实现")

    val chapterCount: Int = chapters.size

    /** 第 [chapter] 章的起始全局 item（clamp 到 [0, chapterCount-1]；空书返回 0） */
    fun firstItemOfChapter(chapter: Int): Int = TODO("M12b 实现")

    /** 全局 item → (章, 段)；clamp：负数取首项、越界取末项；空书返回 (0, 0) */
    fun locate(itemIndex: Int): ChapterPosition = TODO("M12b 实现")

    /** (章, 段) → 全局 item；越界 clamp；段落数 0 的章返回该章起始 item */
    fun globalItem(chapter: Int, paragraph: Int): Int = TODO("M12b 实现")
}
