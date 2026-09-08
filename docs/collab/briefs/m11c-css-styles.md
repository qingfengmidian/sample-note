# M11c 任务简报 — epub 基本 CSS 文字样式渲染（F2.15）

> 协作模式：OpenCode 按本简报实施；Kimi Code 审查。全程中文注释/提交信息。
> 版本基线：main @ `577178b`（M11a/b 已合，tag m11a/m11b），单测基线 **198 通过 / 0 失败**；
> 设备端 androidTest 基线 **8/8**（Pixel 7，含 MdTocDeviceTest/MdImageDeviceTest）。
> 验证约定：面板/渲染类交互先 Pixel 7（虚拟环境）设备端断言 + 视觉留真机清单（`docs/collab/pending-real-device-tests.md`）。

## 1. 目标与范围

**做**（仅以下）——F2.15 epub 基本 CSS 文字样式（`<style>` 与内联 `style` 属性的**子集**），即 M11 最后一块：

1. **fixtures**（`FixtureGenerator.kt`）：新增 `styled.epub`（内联 style + `<style>` 规则覆盖字重/斜体/对齐/颜色/相对字号）与 `bad_css.epub`（样式表含非法语法/未知选择器混排），定义见 `docs/测试用例.md` §0（M11 新增清单）；含 textChecks 自检。
2. **数据模型**（Models.kt）：`Chapter` 增 `styles: Map<Int, ParagraphStyleHints> = emptyMap()`（仅 epub 非空；**不新增字段到其它格式路径**，默认空表零影响）；新建 `ParagraphStyleHints`（字段与语义严格对齐设计 §3.3 合并规则：`fontSizeEm/lineHeightEm: Float?`、`bold: Boolean?`、`italic: Boolean?`、`textAlign: 左中右?`、`colorArgb: Int?`、`paragraphSpacing: Boolean`，null = 未指定走全局）。
3. **解析层**（EpubParser.kt）：XHTML 抽取时对 `<style>` 与内联 `style` 属性按 **CSS 子集** 解析（设计 §3.2 第 6 点）：
   - 选择器仅元素 / `element.class` / `.class` / `#id`（后代/组合忽略）；属性仅 `font-size`（px/pt/em/%，px/pt 按 16sp 基准转倍率）、`line-height`（同规则）、`font-weight`（≥600 或 bold/bolder→bold）、`font-style: italic/oblique`、`text-align`、`color`（#rgb/#rrggbb/rgb()/命名色子集）、`margin/padding` 垂直分量（0/非 0 两档段间距）；
   - 继承：块级元素样式沿 DOM 树向子代继承；内联 `style` 优先级最高，`<style>` 按"后定义覆盖先定义、id > class > 元素"合并；解析后写入 `Chapter.styles[段落索引]`；
   - **一切未识别属性/选择器静默忽略；CSS 子集解析失败不抛异常**——该书该章回退纯文本（保底可读）；`script` 仍忽略。
   - 保留 F2.14 已合的图片/封面逻辑（img/style 同处抽取，别互相破坏）。
4. **渲染层**（ReaderTextView.kt）：段落 `Text` 应用样式合并（设计 §3.3「样式合并规则」，全局设置为**绝对基准**）：
   - `fontSizeEm/lineHeightEm` **clamp 0.5~3.0 后乘全局字号/行距**；
   - `bold/italic/textAlign` 直接采用；`colorArgb` 与**当前阅读主题背景**算相对亮度对比度，< 4.5:1 一律回退主题前景色；段间距只做 0/非 0 两档（item 间隔）；
   - 单段样式解析异常回退全局默认，**任何书不因样式而不可读**；styles 空（txt/md/xml/无样式 epub）路径与旧版一致零改动。
5. **测试**：
   - 解析层 TDD（先红后绿）：CSS 子集解析（四类选择器、px/pt/em/% 倍率换算、字重/斜体/对齐/颜色、继承、内联优先、层叠顺序、非法语法与未知选择器静默忽略、整章回退纯文本）；styles[段落索引] 正确性（含 h1~h6/li 行）。
   - 合并逻辑纯函数单测：clamp 上下界、颜色对比度回退（用与 ReadingTheme 四套背景核对边界 4.5:1）、0/非 0 段距。
   - 设备端新增轻量用例（MdCssDeviceTest 或并入现有）：打开 `styled.epub`/`bad_css.epub` 正常显示不崩、正文可读（保底），章节数正常。
6. **不做**（越界即违规）：任何排版引擎级 CSS（后代/组合选择器、背景图/浮动/定位/媒体查询、box 模型精确排版、默认 h1~h6 字号层级——无 CSS 时各元素仍用全局字号）；F2.14/F2.13/M11a-b 已合代码的回归改动；release 配置；docs 除简报/报告外文档。

## 2. 设计依据（先读，严格照做）

- `docs/软件设计文档.md`：§3.1（ParagraphStyleHints 引入说明——目前 Models.kt **尚无** styles/ParagraphStyleHints，按设计加）、§3.2 第 3/6 点（抽取规则 + CSS 子集）、§3.3「epub 富文本渲染」样式合并规则（全局绝对基准/clamp/对比度回退/段距两档/失败保底）。
- `docs/需求说明书.md` F2.15。
- `docs/测试用例.md` REV4：TC-F2-17e / 17f、§0 fixtures（styled/bad_css 定义）。
- `docs/collab/reviews/f2-13-15-docs-review.md` S7（合并优先级与对比度兜底）。
- AGENTS §5（循环内取消，CSS 扫描同样遵守）。

## 3. 代码落点

允许：`core/model/Models.kt`、`core/parse/EpubParser.kt`、`ui/reader/ReaderTextView.kt`（含段落样式应用与纯函数合并）、`buildSrc/.../FixtureGenerator.kt`、`app/src/test/**`、`app/src/androidTest/**`。
禁止：EpubParser 图片/封面逻辑破坏性改动、M11a（md toc/映射）代码、release、docs 除本简报/报告。**不手改 fixtures 产物**（只改 FixtureGenerator.kt）。

## 4. 测试要求

- 解析层 TDD 先红后绿（PR 历史可复现）；覆盖失败前提与边界（非法语法、未知选择器、超范围 em、空 style）。
- 颜色对比度：延续评审附录 WCAG 相对亮度公式（对数值非截图）。
- 既有基线 198 全绿不回归（epub 无样式书 styles 恒空、既有 fixtures 断言不变）。
- 设备端 Pixel 7：`connectedDebugAndroidTest` 全绿（新用例 + 既有 8 例）。
- 报告写 `docs/collab/reviews/m11c-opencode-report.md`。

## 5. 实施纪律（既往踩坑，逐条遵守）

1. 不建调试文件；不用 `rm`；临时验证用 Write 覆盖既有测试或留待清理。
2. **不用任何 bash 命令**（echo/true/ls/find/rg 都会被权限系统拒，这是环境限制）——列文件用 Glob、读文件用 Read、搜代码用 Grep、改文件用 Edit/Write；**权限被拒不是停止理由**，换内置工具继续跑完全量。
3. 不翻 Gradle 缓存依赖源码。4. 中文注释；**不自行 git commit**。
5. 完成后跑 `./gradlew testDebugUnitTest` 全绿（基线 198 + 新增），写报告（改动清单/设计决策/测试结果/遗留）。

## 6. 验收（Kimi Code 侧，不在你的范围）

diff 审查 → 独立 `--rerun-tasks` 单测 → Pixel 7 `connectedDebugAndroidTest` + 打开 styled/bad_css 语义确认（TC-F2-17e/f 功能）→ 视觉项（字重/斜体/对齐/颜色观感、夜间主题下对比度回退）追加到真机清单 → 中文提交 → `m11c` tag（M11 收口）。
