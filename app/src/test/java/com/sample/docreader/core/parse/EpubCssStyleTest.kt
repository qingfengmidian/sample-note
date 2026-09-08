package com.sample.docreader.core.parse

import com.sample.docreader.core.model.ParagraphStyleHints
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M11c（F2.15 epub 基本 CSS 文字样式）解析层 TDD（《软件设计文档》§3.2-6 CSS 子集）：
 * - 选择器：仅元素 / element.class / .class / #id（后代/组合/伪类/@ 等一律忽略）；
 * - 属性：font-size / line-height（px/pt/em/% 换算）、font-weight、font-style、text-align、
 *   color（#rgb/#rrggbb/rgb()/命名色子集）、margin/padding 垂直分量（0/非 0 两档段距）；
 * - 继承：块级元素样式沿 DOM 向子代继承；内联 style 优先级最高；`<style>` 后定义覆盖先定义、
 *   id > class > 元素；
 * - 非法语法/未知选择器静默忽略；整章回退纯文本不抛异常。
 *
 * 注：颜色 argb 为 Android 约定不透明（alpha=FF）Int（0xFFrrggbb）。
 */
@RunWith(RobolectricTestRunner::class)
class EpubCssStyleTest {

    private val parser = EpubParser()

    /** 组装一章含 head 内 <style> 的 XHTML 字节（epub 章节正文抽取统一入口） */
    private fun chapter(cssHead: String = "", bodyHtml: String): ByteArray = """
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head><title>t</title>$cssHead</head>
        <body>$bodyHtml</body>
        </html>
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun styleBlock(css: String) = "<style type=\"text/css\">$css</style>"

    // ---------------- CSS 子集纯函数 ----------------

    /** 四类选择器：元素 / element.class / .class / #id 合法；其余（后代/组合/伪类/属性/@ 等）静默忽略 */
    @Test
    fun selectors_fourKindsParsedOthersRejected() {
        assertEquals(CssSelector(tag = "p"), CssSubset.parseSelector("p"))
        assertEquals(CssSelector(tag = "p", className = "note"), CssSubset.parseSelector("p.note"))
        assertEquals(CssSelector(className = "note"), CssSubset.parseSelector(".note"))
        assertEquals(CssSelector(idName = "x"), CssSubset.parseSelector("#x"))

        // 后代/组合/伪类/属性/媒体/@import 等：不支持 → null（静默忽略）
        assertNull(CssSubset.parseSelector(".a .b"))
        assertNull(CssSubset.parseSelector("p > span"))
        assertNull(CssSubset.parseSelector("p:hover"))
        assertNull(CssSubset.parseSelector("[data-x]"))
        assertNull(CssSubset.parseSelector("@media"))
        assertNull(CssSubset.parseSelector("div p"))
        assertNull(CssSubset.parseSelector(".a.b"))
        assertNull(CssSubset.parseSelector("p#id"))
        assertNull(CssSubset.parseSelector(""))
    }

    /** 长度换算：px/pt 按 16sp 基准转绝对倍率；em/%（及行距无单位）为相对父级乘子 */
    @Test
    fun lengthToEm_pxPtEmPercentUnitless() {
        // px：24px = 1.5 × 16sp 基准
        assertEquals(1.5f, CssSubset.lengthToEm("24px", 1f, allowUnitless = false)!!, 1e-3f)
        // pt：18pt = 24px = 1.5 × 基准
        assertEquals(1.5f, CssSubset.lengthToEm("18pt", 1f, allowUnitless = false)!!, 1e-3f)
        // em / %：相对父级乘子
        assertEquals(2f, CssSubset.lengthToEm("2em", 1f, allowUnitless = false)!!, 1e-3f)
        assertEquals(3f, CssSubset.lengthToEm("1.5em", 2f, allowUnitless = false)!!, 1e-3f)
        assertEquals(1.5f, CssSubset.lengthToEm("150%", 1f, allowUnitless = false)!!, 1e-3f)
        assertEquals(3f, CssSubset.lengthToEm("150%", 2f, allowUnitless = false)!!, 1e-3f)
        // 行距无单位倍率允许；字号无单位不支持
        assertEquals(1.4f, CssSubset.lengthToEm("1.4", 1f, allowUnitless = true)!!, 1e-3f)
        assertNull(CssSubset.lengthToEm("1.4", 1f, allowUnitless = false))
        // 不支持单位/关键字：rem、small、normal 等 → null
        assertNull(CssSubset.lengthToEm("1.5rem", 1f, allowUnitless = false))
        assertNull(CssSubset.lengthToEm("small", 1f, allowUnitless = false))
        assertNull(CssSubset.lengthToEm("normal", 1f, allowUnitless = false))
        assertNull(CssSubset.lengthToEm("", 1f, allowUnitless = false))
    }

    /** 字重：≥600 或 bold/bolder → bold；normal/lighter/<600 → 非粗；其余 null */
    @Test
    fun weightToBold_thresholdAndKeywords() {
        assertEquals(true, CssSubset.weightToBold("bold"))
        assertEquals(true, CssSubset.weightToBold("bolder"))
        assertEquals(true, CssSubset.weightToBold("700"))
        assertEquals(true, CssSubset.weightToBold("600"))
        assertEquals(false, CssSubset.weightToBold("normal"))
        assertEquals(false, CssSubset.weightToBold("lighter"))
        assertEquals(false, CssSubset.weightToBold("400"))
        assertEquals(null, CssSubset.weightToBold("abc"))
    }

    /** 颜色：#rgb / #rrggbb / rgb() / 命名色子集；非法或 rgba（不在子集）→ null */
    @Test
    fun parseColor_formatsAndNamedSubset() {
        assertEquals(0xFFFF0000.toInt(), CssSubset.parseColor("#f00"))
        assertEquals(0xFFFF0000.toInt(), CssSubset.parseColor("#ff0000"))
        assertEquals(0xFF3366CC.toInt(), CssSubset.parseColor("#3366CC"))
        assertEquals(0xFFFF0000.toInt(), CssSubset.parseColor("rgb(255, 0, 0)"))
        assertEquals(0xFFFF0000.toInt(), CssSubset.parseColor("rgb(100%, 0%, 0%)"))
        assertEquals(0xFF000080.toInt(), CssSubset.parseColor("navy"))
        assertEquals(0xFF3366CC.toInt(), CssSubset.parseColor("#3366CC"))

        assertNull(CssSubset.parseColor("transparent"))
        assertNull(CssSubset.parseColor("notacolor"))
        assertNull(CssSubset.parseColor("#xyz"))
        assertNull(CssSubset.parseColor("rgba(0,0,0,0.5)"))
        assertNull(CssSubset.parseColor(""))
    }

    /** 段落级映射集成：四类选择器 + 层叠（后定义覆盖先定义）+ 段内 h 行/li 行的正确性 */
    @Test
    fun styledChapter_h2ClassIdSelectorsAndLineHeight() {
        val extracted = parser.extractChapterFrom(chapter(
            cssHead = styleBlock("""
                h2 { text-align: center; }
                p.note { color: #3366CC; }
                .emph { font-weight: bold; }
                #special { font-style: italic; }
                p { line-height: 1.4; }
            """.trimIndent()),
            bodyHtml = """
                <h2>标题居中</h2>
                <p class="note">蓝色备注文字。</p>
                <p class="emph note">加粗蓝字。</p>
                <p id="special">斜体段文字。</p>
                <p>普通段文字。</p>
            """.trimIndent()
        ))
        assertEquals(
            listOf("标题居中", "蓝色备注文字。", "加粗蓝字。", "斜体段文字。", "普通段文字。"),
            extracted.paragraphs
        )
        assertEquals(mapOf(
            0 to ParagraphStyleHints(textAlign = 1),
            1 to ParagraphStyleHints(colorArgb = 0xFF3366CC.toInt(), lineHeightEm = 1.4f),
            2 to ParagraphStyleHints(bold = true, colorArgb = 0xFF3366CC.toInt(), lineHeightEm = 1.4f),
            3 to ParagraphStyleHints(italic = true, lineHeightEm = 1.4f),
            4 to ParagraphStyleHints(lineHeightEm = 1.4f)
        ), extracted.styles)
    }

    /** 层叠：id > class > 元素；同优先级后定义覆盖先定义；内联 style 最高 */
    @Test
    fun cascade_idOverClassOverElement_laterWins_inlineHighest() {
        val extracted = parser.extractChapterFrom(chapter(
            cssHead = styleBlock("""
                p { color: #FF0000; font-size: 1.5em; }
                p { color: #0000FF; }
                p.note { color: #00FF00; }
                #x { color: #CCCCCC; }
            """.trimIndent()),
            bodyHtml = """
                <p>红色被后规则覆盖为蓝。</p>
                <p class="note" id="x">id 优先 class。</p>
                <p style="color:#00AA00">行内覆盖样式表。</p>
                <p style="font-size:1em">字体内联覆盖回基准。</p>
            """.trimIndent()
        ))
        // p 后规则覆盖先规则 → 蓝；字号来自先规则 1.5em
        assertEquals(
            ParagraphStyleHints(colorArgb = 0xFF0000FF.toInt(), fontSizeEm = 1.5f),
            extracted.styles[0]
        )
        // id(#x) > class(.note) > 元素(p)：颜色取 #CCCCCC；字号仍由 p 规则 1.5em
        assertEquals(
            ParagraphStyleHints(colorArgb = 0xFFCCCCCC.toInt(), fontSizeEm = 1.5f),
            extracted.styles[1]
        )
        // 内联 color 覆盖样式表，字号继承 1.5em
        assertEquals(
            ParagraphStyleHints(colorArgb = 0xFF00AA00.toInt(), fontSizeEm = 1.5f),
            extracted.styles[2]
        )
        // 内联 font-size:1em 覆盖样式表 1.5em → 解析回基准倍率（不落 hint），颜色仍为样式表蓝
        assertEquals(
            ParagraphStyleHints(colorArgb = 0xFF0000FF.toInt()),
            extracted.styles[3]
        )
    }

    /** 继承：块级（div/body）样式沿 DOM 向子代 p 继承（字号/对齐/颜色） */
    @Test
    fun inheritance_blockContainerStylesReachDescendants() {
        val extracted = parser.extractChapterFrom(chapter(
            cssHead = styleBlock("""
                body { color: #333333; }
                div.poem { font-size: 1.5em; text-align: center; }
                div.foot { color: #888888; }
            """.trimIndent()),
            bodyHtml = """
                <div class="poem"><p>诗行一。</p><p>诗行二。</p></div>
                <p>正文一。</p>
                <div class="foot"><p>注释一。</p></div>
                <p>正文二。</p>
            """.trimIndent()
        ))
        assertEquals(
            listOf("诗行一。", "诗行二。", "正文一。", "注释一。", "正文二。"),
            extracted.paragraphs
        )
        val poem = ParagraphStyleHints(fontSizeEm = 1.5f, textAlign = 1, colorArgb = 0xFF333333.toInt())
        assertEquals(mapOf(
            0 to poem,
            1 to poem,
            2 to ParagraphStyleHints(colorArgb = 0xFF333333.toInt()),
            3 to ParagraphStyleHints(colorArgb = 0xFF888888.toInt()),
            4 to ParagraphStyleHints(colorArgb = 0xFF333333.toInt())
        ), extracted.styles)
    }

    /** h1~h6 / li 行样式；margin/padding 垂直分量 0/非 0 两档段距 */
    @Test
    fun headingAndListItem_spacingTwoLevels() {
        val extracted = parser.extractChapterFrom(chapter(
            cssHead = styleBlock("""
                h1 { font-size: 1.6em; }
                h3 { margin-top: 2em; }
                li { margin: 0; }
                ul { margin-top: 1em; }
            """.trimIndent()),
            bodyHtml = """
                <h1>一级标题。</h1>
                <h3>三级标题带段距。</h3>
                <ul><li>条目一。</li><li>条目二。</li></ul>
            """.trimIndent()
        ))
        assertEquals(
            listOf("一级标题。", "三级标题带段距。", "条目一。", "条目二。"),
            extracted.paragraphs
        )
        assertEquals(mapOf(
            0 to ParagraphStyleHints(fontSizeEm = 1.6f),
            // h3 自身 margin-top 非 0 → 段距 true
            1 to ParagraphStyleHints(paragraphSpacing = true)
            // li { margin: 0 } 显式 0 → 段距 false；ul 的 margin 属于父块不落到 li 段落 → 无 hint
        ), extracted.styles)
    }

    /** 未知选择器/后代选择器混排静默忽略，合法子集不受影响 */
    @Test
    fun unknownAndDescendantSelectorsSilentlyIgnored() {
        val extracted = parser.extractChapterFrom(chapter(
            cssHead = styleBlock("""
                ??? { color: red; }
                .a .b { font-size: 40px; }
                p > span { color: #00FF00; }
                div p { font-weight: bold; }
                p { text-align: right; }
                .real { color: #FF0000; }
            """.trimIndent()),
            bodyHtml = """
                <p class="a b">正文未知选择器不生效。</p>
                <p class="real"><span>真实样式生效段。</span></p>
            """.trimIndent()
        ))
        // .real 的 color 应生效（合法规则在非法规则之后仍被解析）
        assertEquals(ParagraphStyleHints(textAlign = 2, colorArgb = 0xFFFF0000.toInt()), extracted.styles[1])
        // 第一段只命中合法 p{text-align:right}；后代选择器忽略，不得有 bold/font 40px
        assertEquals(ParagraphStyleHints(textAlign = 2), extracted.styles[0])
        // 不崩溃、正文完整
        assertEquals(listOf("正文未知选择器不生效。", "真实样式生效段。"), extracted.paragraphs)
    }

    /** CSS 解析整体失败（未闭合花括号等）→ 该章回退纯文本：正文可读、styles 空、不抛异常 */
    @Test
    fun malformedStyleSheet_fallsBackToPlainText() {
        val extracted = parser.extractChapterFrom(chapter(
            cssHead = styleBlock("p { color: red;"),
            bodyHtml = "<p>正文仍可读。</p>"
        ))
        assertEquals(listOf("正文仍可读。"), extracted.paragraphs)
        assertTrue("未闭合 style 应整体不产生样式（回退纯文本）", extracted.styles.isEmpty())
    }

    /** 内联 style 非法值静默忽略：颜色非法→不生效，字号合法→生效，正文不崩 */
    @Test
    fun inlineStyle_badValueIgnoredGoodValueKept() {
        val extracted = parser.extractChapterFrom(chapter(
            bodyHtml = """<p style="color: notacolor; font-size: 2em">非法颜色忽略字号生效。</p>"""
        ))
        assertEquals(listOf("非法颜色忽略字号生效。"), extracted.paragraphs)
        assertEquals(ParagraphStyleHints(fontSizeEm = 2f), extracted.styles[0])
    }

    /** 跨多张 `<style>` 表层叠（审查修复）：order 须全书递增，后表同优先级规则应赢——前表表内靠后规则不得压过后表 */
    @Test
    fun cascade_acrossStyleBlocks_laterBlockWins() {
        // 前表两条同优先级（红→绿），后表一条（蓝）；CSS 语义：定义在后的后表蓝应赢
        val extracted = parser.extractChapterFrom(chapter(
            cssHead = styleBlock("p { color: #FF0000; } p { color: #00FF00; }") +
                styleBlock("p { color: #0000FF; }"),
            bodyHtml = "<p>后表应赢。</p>"
        ))
        assertEquals(listOf("后表应赢。"), extracted.paragraphs)
        assertEquals(
            "后一张 <style> 的同优先级规则应覆盖前表表内靠后规则",
            ParagraphStyleHints(colorArgb = 0xFF0000FF.toInt()),
            extracted.styles[0]
        )
    }

    /** margin/padding 简写 2/3 值垂直分量取位（审查修复）：2 值 bottom=token0、3 值 bottom=token2 */
    @Test
    fun shorthandSpacing_twoAndThreeValueVerticalPlacement() {
        val extracted = parser.extractChapterFrom(chapter(
            bodyHtml = """
                <p style="margin: 0 1em">二值垂直为零。</p>
                <p style="margin: 0 0 1em">三值仅 bottom 非零。</p>
                <p style="padding: 1em 0 0">三值仅 padding top 非零。</p>
                <p style="padding: 0 0">二值 padding 垂直为零。</p>
            """.trimIndent()
        ))
        assertEquals(
            listOf("二值垂直为零。", "三值仅 bottom 非零。", "三值仅 padding top 非零。", "二值 padding 垂直为零。"),
            extracted.paragraphs
        )
        // 2 值 [上下=0, 左右=1em]：垂直为零 → 无段距
        assertNull(extracted.styles[0])
        // 3 值 [上=0, 左右=0, 下=1em]：bottom 非零 → 段距 true
        assertEquals(ParagraphStyleHints(paragraphSpacing = true), extracted.styles[1])
        // 3 值 [上=1em, ...下=0]：top 非零 → 段距 true
        assertEquals(ParagraphStyleHints(paragraphSpacing = true), extracted.styles[2])
        // 2 值 [上下=0, 左右=0]：垂直为零 → 无段距
        assertNull(extracted.styles[3])
    }

    /** 无样式 epub 章节：styles 恒空（与旧版一致零影响） */
    @Test
    fun styleFreeChapter_stylesEmpty() {
        val extracted = parser.extractChapterFrom(chapter(
            bodyHtml = "<p>无样式段落。</p><h2>无样式标题。</h2><ul><li>列表项。</li></ul>"
        ))
        assertEquals(listOf("无样式段落。", "无样式标题。", "列表项。"), extracted.paragraphs)
        assertTrue(extracted.styles.isEmpty())
    }

    // ---------------- fixtures 集成（styled.epub / bad_css.epub，TC-F2-17e/17f） ----------------

    /** styled.epub：全链路解析后 Chapter.styles[段落索引] 与生成器设计一一对应；章 2 无样式恒空 */
    @Test
    fun styledFixture_chapterStylesMapMatchesDesign() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("styled.epub"), "styled.epub")
        assertEquals(listOf("样式章", "纯文本章"), doc.chapters.map { it.title })
        val ch1 = doc.chapters[0]
        assertEquals(
            listOf("居中大标题", "引言文字内容。", "带段间距的段落。", "行内样式右对齐蓝字。", "普通段落无样式。", "无序列表项一。", "无序列表项二。"),
            ch1.paragraphs
        )
        assertEquals(mapOf(
            0 to ParagraphStyleHints(fontSizeEm = 2f, bold = true, textAlign = 1),
            1 to ParagraphStyleHints(colorArgb = 0xFFCC0000.toInt(), italic = true),
            2 to ParagraphStyleHints(paragraphSpacing = true),
            3 to ParagraphStyleHints(fontSizeEm = 1.5f, bold = true, textAlign = 2, colorArgb = 0xFF0000FF.toInt())
        ), ch1.styles)
        // 章 2（无样式）styles 恒空，无样式 epub 不产生任何条目
        assertTrue(doc.chapters[1].styles.isEmpty())
    }

    /** bad_css.epub：非法语法/未知选择器混排不崩溃，正文完整可读（保底），合法子集仍生效 */
    @Test
    fun badCssFixture_illegalPartsIgnoredTextReadable() = runBlocking {
        val doc = parser.parseBytes(Fixtures.bytes("bad_css.epub"), "bad_css.epub")
        assertEquals(listOf("坏样式章"), doc.chapters.map { it.title })
        val ch = doc.chapters[0]
        assertTrue(ch.paragraphs.any { it.contains("正文第一段可读。") })
        assertTrue(ch.paragraphs.any { it.contains("未知后代选择器不生效。") })
        assertTrue(ch.paragraphs.any { it.contains("非法颜色忽略、正文可读。") })
        assertTrue("非法 @media/??? 段不得把整个样式表拖垮", ch.paragraphs.all { !it.contains('<') && !it.contains("???") })
        // 合法子集 p 规则仍生效：color #222222 + font-size 24px(=1.5) 落到每个 p 段落
        val hints0 = ch.styles[0]
        assertEquals(ParagraphStyleHints(colorArgb = 0xFF222222.toInt(), fontSizeEm = 1.5f), hints0)
        // 非法后代选择器段落：只受合法 p 规则影响，不得有 bold/40px
        assertEquals(ParagraphStyleHints(colorArgb = 0xFF222222.toInt(), fontSizeEm = 1.5f), ch.styles[1])
        // 内联非法颜色段：无颜色 hint（非法声明被忽略）
        assertEquals(ParagraphStyleHints(fontSizeEm = 1.5f), ch.styles[2])
    }
}
