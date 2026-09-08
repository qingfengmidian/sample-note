# M11c 实施报告 — epub 基本 CSS 文字样式渲染（F2.15）

| 项目 | 内容 |
| --- | --- |
| 任务简报 | `docs/collab/briefs/m11c-css-styles.md`（OpenCode 实施） |
| 基线 | main @ `577178b`（M11a/b 已合，tag m11a/m11b），单测基线 **198 通过 / 0 失败** |
| 实施者 | OpenCode |
| 完成日期 | 2026-09-09 |

## 1. 改动清单

### 主代码
| 文件 | 改动 |
| --- | --- |
| `core/model/Models.kt` | `Chapter` 增 `styles: Map<Int, ParagraphStyleHints> = emptyMap()`（仅 epub 非空，默认空表零影响）；新增 `ParagraphStyleHints`（字段按简报与设计 §3.3 合并规则对齐：`fontSizeEm/lineHeightEm: Float?`、`bold/italic: Boolean?` 三态支持"显式非粗/非斜"覆盖继承、`textAlign: Int?`（0 左 1 中 2 右）、`colorArgb: Int?`（#AARRGGBB 不透明）、`paragraphSpacing: Boolean` 0/非 0 两档段距；null = 未指定走全局） |
| `core/parse/EpubParser.kt` | 新增 CSS 子集解析（顶层 internal `CssSelector`/`CssDecls`/`CssRule` + `CssSubset` 纯函数对象）；XHTML 抽取重构：`<style>`（head/body 内均可）收集解析为规则，非 void 元素开/闭标签压/弹**样式帧**（父级继承 + 命中规则 id>class>元素 + 后定义覆盖先定义 + 内联 style 最高），段落 flush 时以最近块级祖先帧产出 hints 记入 `Chapter.styles[段落索引]`；段样式抽取与 F2.14 图片/封面逻辑共存互不破坏；`ExtractedChapter` 增 `styles`。CSS 解析失败/未知属性/未知选择器一律静默忽略，整章回退纯文本不抛异常 |
| `ui/reader/ReaderTextView.kt` | 新增纯函数合并层 `clampStyleEm` / `wcagContrast` / `keepCssColor` / `argbToColor` / `applyParagraphHints`（可单测）；`ReaderTextView` 增 `styles: Map<Int, ParagraphStyleHints>` 参数，段落 `Text` 应用合并后 `TextStyle`（字号/行距 clamp 0.5~3.0 乘全局值，仅字号变化时行距随字号等比缩放保全局行距比例；bold/italic/textAlign 直接采用；CSS 色与主题背景对比度 <4.5:1 回退主题前景；非 0 段距加大 item 垂直内边距 0/8dp 两档） |
| `ui/reader/ReaderScreen.kt` | 阅读正文调用点传 `chapter.styles`（txt/md/xml/无样式 epub 恒空，渲染路径零改动） |

### 测试与夹具
| 文件 | 内容 |
| --- | --- |
| `buildSrc/.../FixtureGenerator.kt` | 新增 `styled.epub`（`<style>` 规则 h1/.big/#intro/li + 内联 style 覆盖字号(pt)/字重/斜体/对齐/颜色/段距，章 2 无样式对照）与 `bad_css.epub`（`???`/后代/组合/@media/未知属性混排 + 合法 p 规则）两件套；`epubChecks` 补条目入列 + 内容自检。**未手改 fixtures 产物**（产物由 Gradle 生成不入库） |
| `app/src/test/.../core/parse/EpubCssStyleTest.kt`（新建） | **+16**：四类选择器解析/拒绝、px/pt/em/%/无单位长度换算、字重阈值、颜色格式与命名色子集；段落级映射（h2/element.class/.class/#id + 层叠）、id>class>元素 + 后定义覆盖 + 内联最高、块级继承（div/body→p）、h1~h6/li 行 + margin 0/非 0 两档段距、未知/后代选择器静默忽略、样式表整体失败回退纯文本、内联非法值忽略合法值生效、无样式章恒空、styled/bad_css fixtures 全链路 styles 映射、**跨多 `<style>` 块层叠后表赢（审查修复）**、**margin/padding 简写 2/3 值垂直取位（审查修复）** |
| `app/src/test/.../ui/reader/ReaderStyleMergeTest.kt`（新建） | **+6**：clamp 0.5~3.0 上下界、字号 clamp 乘全局 + 行距随字号等比、行距独立 clamp 乘全局、bold/italic/textAlign 直接采用 + null 零改动、CSS 色与四套 ReadingTheme 背景对比度边界（主题前景必过/背景同色必退）+ 合并回退主题前景、WCAG 对比度参考值（黑/白 21:1、同色 1:1） |
| `app/src/androidTest/.../MdCssDeviceTest.kt`（新建） | 设备端 TC-F2-17e/f 语义断言：styled.epub 正常显示、样式段落（含 h1/内联样式/li）滚动可达；bad_css.epub 正常打开、正文可读不崩溃（`connectedDebugAndroidTest`，Pixel 7 执行） |

## 2. 关键设计决策

1. **段落归属样式 = 最近块级祖先帧**（设计 §3.2-6「块级样式沿 DOM 向子代继承」）：每个非 void 元素开标签压一个计算样式帧（父级值继承 + 命中规则 + 内联覆盖），文本 flush 成段时取栈内最近的非行内（块级）祖先帧产 hints。行内元素（span/em/strong…）不打断段落、不成为归属，故段内 run 级富文本不可表达——v1 子集明确只做**整段**样式（设计 §3.3 段落 item 语义，属既定取舍）。
2. **帧内继承是"解析值"链**：字号/行距以相对 16sp 基准的绝对倍率存帧，px/pt 与父无关（24px=18pt=1.5），em/%（及行距无单位）乘父级倍率——子代继承的是解析后倍率而非原始字符串，嵌套 `div{font-size:1.5em}>p` 自然累乘，渲染层统一 clamp 0.5~3.0 兜底。
3. **CSS 声明仅存原始值、帧构建时结合父级换算**：层叠归并按"命中规则 specificity 升序 + 定义顺序"覆盖（属性级 last-wins），内联 style 最后 override；length/color/weight 等在 `buildFrame` 用父级上下文解析，非法值回退父级（= CSS"非法声明忽略→继承"）。
4. **CSS 文本单遍收集、head 先行即 body 全量命中**：`<style>` 在 head/body 内均收集；head 中 style 先于正文出现，故正文元素命中全部规则（`.a .b`/组合/@media 等一律 parseSelector 拒绝静默忽略）。样式表整体未闭合花括号时丢弃余下返回空表——整章回退纯文本（保底可读，不抛异常）。
5. **解析与图片/封面共存**：段样式随既有 flush 边界（块级起止）同步产出，`flush()` 先记 styles[新索引] 再入段落，段落索引 == LazyColumn item 索引不变量保持；img owner 语义、封面解析零改动（M11b 回归全绿）。
6. **颜色存储 Android argb Int**（`0xFFrrggbb`，alpha=FF），渲染经 `argbToColor` 转 Compose `Color`（`toLong() and 0xFFFFFFFF` 避免符号位问题）；`keepCssColor` 用评审附录 WCAG 相对亮度公式（数值比较非截图）。
7. **渲染合并的"绝对基准"**：字号/行距仅乘（clamp 后）不改全局基准；仅字号变化时行距随字号等比缩放保持全局行距比例（如 16sp/1.5 → 48sp/3.0 语义）。`hints == null` 直接返回 base（styles 空路径与旧版逐字节一致）。

## 3. 测试结果

- **TDD 红绿**：先写 `EpubCssStyleTest` + `ReaderStyleMergeTest`（引用尚不存在的 `ParagraphStyleHints`/`Chapter.styles`/`CssSubset`/`applyParagraphHints`/`clampStyleEm` 等），`./gradlew testDebugUnitTest --tests <两新类> --offline` **红（编译失败：未解析引用清单）**；实现后转绿，随后 fixture 集成用例全绿。
- **最终**：`./gradlew testDebugUnitTest`（含 generateFixtures 自动再生成）**220 通过 / 0 失败 / 0 跳过**（基线 198 + 新增 22：EpubCssStyleTest +16、ReaderStyleMergeTest +6）。既有基线无回归：EpubParserTest（图片/封面/宿主段落/C4 取消）、ReaderImageTest、ReaderState/ReaderThemeFont 等全部通过。
- `validateFixtures` 通过（含新增 styled/bad_css 结构与内容自检）。
- `assembleDebugAndroidTest` 编译级校验（MdCssDeviceTest）；运行需 Pixel 7 `connectedDebugAndroidTest`，见遗留。

## 4. 审查后修复（Kimi Code 复查，可验收无必修）

1. **跨 `<style>` 块层叠 order 复位（真 bug，已修）**：`CssSubset.parseStyleSheet` 原先每张样式表内部 `rules.size` 从 0 重计 order——当「前表含多条同优先级规则、后表也定义同属性同优先级规则」时，前表表内靠后规则 order 较大，会错误压过后表（CSS 语义后定义的样式表应赢）。修法：`parseStyleSheet(css, startOrder = 0)` 改为**全局递增 order**，调用处传入 `startOrder = cssRules.size`。测试：`EpubCssStyleTest.cascade_acrossStyleBlocks_laterBlockWins`（前表红→绿两条、后表蓝一条 → 断言蓝）。
2. **margin/padding 简写 2/3 值垂直取位错误（真 bug，已修）**：2 值 `[上下, 左右]` 的 bottom 应取 `tokens[0]`（与 top 同值）；3 值 `[上, 左右, 下]` 的 bottom 应取 `tokens[2]`。原实现均误取 `tokens[1]`（左右分量）。修法：重写简写分支（1 值四边同；2 值 top=bottom=token0；3/4 值 bottom=token2）。测试：`EpubCssStyleTest.shorthandSpacing_twoAndThreeValueVerticalPlacement`（`margin:0 1em` 垂直为零无段距、`margin:0 0 1em` 仅 bottom 非零 → 段距、`padding:1em 0 0` 仅 top 非零 → 段距、`padding:0 0` 无段距）。
3. **非法内联值遮断（`style="color:notacolor"` 使同元素样式表命中色丢失）**：确认属本报告 §2-决策 3「非法声明在帧构建期回退父级」的既定近似语义（把"忽略非法声明"近似为"覆盖后回退"），影响仅是回退默认色、仍可读，**不改代码**，记入 §5-遗留 3。另两条低优先级观察（line-height 与 font-size 同元素叠加的近似语义、段落边界由"块级起止"扩展带来的观察项）一并记入 §5-遗留 6/7。

修复后 `./gradlew testDebugUnitTest` **220 通过 / 0 失败**（218 + 新增 2 用例）。

## 5. 遗留问题

1. **设备端用例未实跑**：本机无设备，`MdCssDeviceTest` 仅编译通过；需 Kimi Code 在 Pixel 7 上 `connectedDebugAndroidTest` 执行 TC-F2-17e/f（打开 styled/bad_css 语义确认），并将视觉项（字重/斜体/对齐/颜色观感、夜间主题对比度回退）追加到真机清单 `docs/collab/pending-real-device-tests.md`。
2. **段内 run 级样式不可表达**：行内元素（span/em/strong）的样式只能近似到整段（取最近块级祖先帧），无法做到"段中某词加粗/变色"；正文 `<em>` 等由作者内联样式渲染富文本的书本期不完整（设计 §3.3 段落 item 模型既有取舍，后续如需做 run 需引入段落内 span 模型）。
3. **非法内联声明对同一元素"覆盖"而非"忽略"**（审查已确认属近似语义、不改代码，见 §4-3）：如 `p` 有样式表 `color:#222`，元素内联 `style="color: notacolor"` 会整体丢弃颜色而非保留样式表值（CSS 语义为忽略非法声明）。少见边界，影响仅是回退默认色（仍可读）。
4. **样式表位于正文之后的规则对先前段落不回溯生效**（CSS 单遍收集语义）：实际 epub 样式均在 head，影响面为零；若遇正文尾部 `<style>` 仅影响其后的段落。
5. **js/伪类/compound 类（`.a.b`）不支持**：按简报「只做列举子集，越界即违规」，未突破。
6. **低优先级观察——line-height 与 font-size 同元素叠加的近似语义**：行距独立成链（em/%/无单位乘父级行距倍率），未按 CSS"行距 em 相对该元素字号"精确联动；字号与行距同元素同时声明时是各自 clamp 后乘全局，与排版引擎的精确盒模型不同。v1 子集明确接受该近似（渲染不精确、不崩、可读）。
7. **低优先级观察——段落边界扩展**：为正确归属段样式，flush 边界由原「p/h1~h6/li/br」扩展为「所有块级元素起止」（纯文本置于 div/body 下也自然成段）。既有 fixture 文本契约无回归；对"块内裸文本 + 后续内容"的切分更接近 CSS 阅读直觉，但非 EPUB 排版引擎精确语义，留观察。
8. 未自行 `git commit`/`tag`；fixtures 产物由 Gradle 生成不入库（只改 `FixtureGenerator.kt`）。

## 6. 附：验证命令

```bash
./gradlew testDebugUnitTest          # 220 通过 / 0 失败（含 generateFixtures）
./gradlew assembleDebugAndroidTest   # 编译通过（MdCssDeviceTest 运行待 Pixel 7）
```
