package com.sample.docreader.ui.reader

import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.ParagraphImage
import com.sample.docreader.core.model.ParagraphStyleHints

/**
 * 渲染项（F4.7，M12c）：连续滚动下 LazyColumn 的一个 item。
 * [chapter]/[paragraph] 为**稳定复合键**（只 append 不重排，索引契约不变），
 * [images]/[styles] 已按段落组装完毕——渲染层不再关心章节边界（F2.14/F2.15 内部逻辑不变）。
 */
data class ReaderItem(
    val chapter: Int,
    val paragraph: Int,
    val text: String,
    val images: List<ParagraphImage>,
    val styles: ParagraphStyleHints?,
    val isChapterFirst: Boolean, // 章首项（M12c 章间分隔/标题语义用）
    /** F2.17（M15）标题层级：1~6 = h1~h6，null = 非标题（由组装层从 Chapter.headingLevels 填充） */
    val headingLevel: Int? = null
)

/**
 * 多章 flatten 组装（F4.7，M12c）——纯函数，无 Android 依赖，便于单测钉死「图片/样式不错位挂段」。
 *
 * 语义约定：
 * - 只组装前 [materializedCount] 章（clamp 到 `chapters.size`），实现章级渐进物化的渲染侧；
 * - 每章按段落顺序产出 item，段落数为 0 的章不产出 item（全局索引契约与 `ChapterItemMap` 一致）；
 * - `images` 取 `ParagraphImage.paragraphIndex == 段落索引` 的子集（保持原相对顺序）；
 * - `styles` 取 `chapter.styles[段落索引]`（无则 null）；
 * - `isChapterFirst` 仅在该章首个 item 上为 true（纯图章若段落非空，首个宿主段即章首）。
 */
fun assembleReaderItems(chapters: List<Chapter>, materializedCount: Int): List<ReaderItem> {
    if (chapters.isEmpty() || materializedCount <= 0) return emptyList()
    val count = materializedCount.coerceAtMost(chapters.size)
    val result = ArrayList<ReaderItem>()
    for (chapterIndex in 0 until count) {
        val chapter = chapters[chapterIndex]
        // 图片按所属段落分组一次（保持原相对顺序），避免逐段线性扫描
        val imagesByParagraph = chapter.images.groupBy { it.paragraphIndex }
        chapter.paragraphs.forEachIndexed { paragraphIndex, text ->
            result += ReaderItem(
                chapter = chapterIndex,
                paragraph = paragraphIndex,
                text = text,
                images = imagesByParagraph[paragraphIndex].orEmpty(),
                styles = chapter.styles[paragraphIndex],
                isChapterFirst = paragraphIndex == 0,
                headingLevel = chapter.headingLevels.getOrNull(paragraphIndex)
            )
        }
    }
    return result
}
