package com.sample.docreader.ui.reader

import com.sample.docreader.core.model.TocEntry
import com.sample.docreader.core.render.MarkdownBlock

/**
 * 源码段落流 ↔ 预览 MarkdownBlock ↔ 目录（toc）三者间的纯函数映射（S4/U4）。
 *
 * 坐标约定：源码 LazyColumn item 索引 == 段落索引（恒成立）；预览 item 是 MarkdownBlock，
 * 索引与段落不同义，靠 [MarkdownBlock.startParagraphIndex]（块起始行在段落流中的行号）换算。
 * 全部函数为无状态纯计算，便于单测与 Compose 中 derivedStateOf/snapshot 复用。
 */
object MdViewMapping {

    /**
     * 段落索引 [paragraphIndex] → 所属预览块索引：取最后一个 startParagraphIndex ≤ p 的块
     * （标题/正文/列表/引用各占一块；落在围栏代码块内的行归其 CodeBlock）。p 位于首个块
     * 之前（前导空行）时返回 0；块表为空时返回 0（调用方应避免在空预览下滚动）。
     */
    fun blockIndexForParagraph(blocks: List<MarkdownBlock>, paragraphIndex: Int): Int {
        if (blocks.isEmpty()) return 0
        var result = 0
        for (index in blocks.indices) {
            if (blocks[index].startParagraphIndex <= paragraphIndex) result = index else break
        }
        return result
    }

    /** 预览块 [blockIndex] → 起始段落索引（反向经 startParagraphIndex 换算，S3 方案①）；越界返回 0 */
    fun paragraphIndexForBlock(blocks: List<MarkdownBlock>, blockIndex: Int): Int =
        blocks.getOrNull(blockIndex)?.startParagraphIndex ?: 0

    /**
     * 当前可见段落 [paragraphIndex] → 所属目录项索引：取最后一个 paragraphIndex ≤ p 的目录项
     * （toc 按扫描顺序递增）。首个可见段落位于第一个目录标题之前时返回 -1（无高亮，评审 S11）。
     */
    fun tocIndexAtParagraph(paragraphIndex: Int, toc: List<TocEntry>): Int {
        var found = -1
        for (index in toc.indices) {
            if (toc[index].paragraphIndex <= paragraphIndex) found = index else break
        }
        return found
    }
}
