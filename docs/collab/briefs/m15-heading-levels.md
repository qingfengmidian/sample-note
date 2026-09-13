# M15 epub 章节标题层级排版（F2.17）— 实现简报（Kimi Code → OpenCode）

> 分支：`feat/m15-heading-levels`。红测试随本简报一并提交（TDD 红），你的任务是把它们做绿。
> 需求：F2.17（需求说明书 REV9，验收标准 11）；设计：`docs/软件设计文档.md` REV11 §3.1/§3.3；用例：`docs/测试用例.md` REV7（TC-F2-28/29、TC-UT-17/18）；决策：`docs/collab/决策日志.md` D-2026-09-13-01。

## 背景（一句话）

epub 章节标题（`h1`~`h6`）当前与正文同字号同字重（F2.15 只应用书自带 CSS 子集，不做默认标题字号），多数书未声明标题字号 → 层级不可辨。本次为 epub 标题加**阅读器默认标题层级**，书 CSS 声明时以书为准。

**范围边界（不可扩大）**：只做 epub；md 预览/源码视图、正文段落样式、全局设置基准、F2.15 既有合并与对比度回退规则**均不得改变**；不引入 `p` 首行缩进/外边距等其他 UA 默认样式。

## 已定型的设计（不可擅自更改）

### 1. 模型：`Chapter.headingLevels`（`core/model/Models.kt`）

```kotlin
data class Chapter(
    // ... 既有字段（title/paragraphs/level/images/styles）不变 ...
    val headingLevels: List<Int?> = emptyList() // F2.17：与 paragraphs 等长、按段落索引对齐；
                                                // 1~6 = h1~h6，null = 非标题；仅 epub 非空
)
```

- **与 `styles` 解耦**：标题段可以没有任何 CSS 命中（`styles` 无该索引），此时 `headingLevels[i]` 仍须为层级值。不要为了携带层级而往 `styles` 塞空对象（既有单测断言「无样式书 styles 恒空」，不得破坏）。
- 默认值 `emptyList()` 保证 txt/md/xml 与既有构造调用零改动。

### 2. 解析层：`EpubParser` 记录标题层级

- 在抽章（`extractChapterFrom` / `extractWithPull2`，按 `p/h1~h6/li/br` 结束段落处）按**标签名**记录当前段落层级：`h1`→1 … `h6`→6；`p/li/div/br` 等→`null`；输出与 `paragraphs` **等长**的 `List<Int?>`。
- 与既有行为的关系：段落切分规则、图片挂段、CSS 命中的 `styles`、锚点映射**一律不变**；层级记录是纯增量。
- 纯图章的合成宿主段（`paragraphs=[] && images` 时合成 `listOf("")`）层级记 `null`。
- 降级路径（剥标签正则 `stripTagsFallback2`）无标签信息 → 层级全 `null`（与 anchors 为空同策略）。

### 3. 渲染数据通路：`ReaderItem.headingLevel`

- `ReaderItem` 增 `headingLevel: Int? = null`；`assembleReaderItems` 填 `chapters[c].headingLevels.getOrNull(paragraphIndex)`（越界/缺省 = null）。
- **不新增 LazyColumn 项**，item 索引契约（== 全局 item、"章:段" key）不变。

### 4. 合并规则（`ReaderTextView` 段落样式合并处）

新增纯函数（便于单测钉死，放既有 `applyParagraphHints` 同文件或同组）：

```kotlin
/** F2.17：默认标题层级倍率（真机观感可微调，改动须同步设计文档） */
fun headingFontScale(level: Int): Float = when (level) {
    1 -> 1.6f; 2 -> 1.45f; 3 -> 1.3f; 4 -> 1.2f; 5 -> 1.1f; 6 -> 1.05f; else -> 1f
}
```

合并语义（在既有「全局基准 → CSS 相对偏移」之前插入默认标题层）：

| 情形 | 字号 | 字重 |
| --- | --- | --- |
| 段非标题（`headingLevel == null`） | 与 M15 前**完全一致** | 与 M15 前完全一致 |
| 标题段、书 CSS 未声明 `font-size` | `全局字号 × headingFontScale(level)` | **加粗** |
| 标题段、书 CSS 声明了 `font-size`（`fontSizeEm != null`） | `全局字号 × clamp(fontSizeEm)`（**覆盖**，不与默认倍率相乘） | 见下 |
| 标题段的 `bold` 三态 | — | `null` → 加粗；`true` → 加粗；`false` → 不加粗 |

- 行距/字体/对齐/段距/`colorArgb` 对比度回退（<4.5:1 → 主题前景色）**沿用既有规则不变**；WRAP 与 NO_WRAP 两条渲染路径行为一致。
- `clamp 0.5~3.0` 沿用既有实现（对 `fontSizeEm` 生效；默认层级倍率已在表内，无需 clamp）。

## 实现任务清单

1. `Models.kt`：`Chapter.headingLevels` 字段（含 KDoc）。
2. `EpubParser`：抽章时记录层级（含降级路径全 null、纯图章宿主段 null）。
3. `ReaderItemAssembly.kt`：`ReaderItem.headingLevel` + 组装填充。
4. `ReaderTextView.kt`：`headingFontScale` + 合并逻辑接线（含 WRAP/NO_WRAP 双路径）。
5. `FixtureGenerator.kt`：新增 `headings.epub`（见下）+ `validateFixtures` 校验（层级结构可用、无字号声明）。
6. 跑绿红测试；不得修改任何红测试断言（如认为断言有误，回报 Kimi 而非自行改）。

## fixtures 要求（`FixtureGenerator.kt`，产物不入库）

`headings.epub`：
- 第 1 章：`h1`~`h6` 各一段（各带唯一可断言文本）+ 正文 `p` 一段；**整书不声明任何标题字号**。
- 第 2 章（覆盖语义）：`h2`（CSS 声明 `font-size: 1.1em; font-weight: normal`）、`h3`（CSS 声明 `font-size: 2em`）、`h1`（无声明）、正文 `p`；样式用内联 `style` 或 `<style>` 均可。
- nav/NCX 正常（既有夹具风格），供设备端打开。

## DoD（验收自检）

- 红测试全绿；既有单测 **254/0 无回归**；`./gradlew check` 过（含 `validateFixtures`）。
- 覆盖：标题段无 CSS（走默认倍率）/ 有 CSS 字号（覆盖不相乘）/ `bold` 三态 / 非标题段零变化 / 降级路径全 null / `headingLevels` 与 `paragraphs` 等长。
- 真机（由 Kimi 执行）：`headings.epub` 六级可辨且逐级递减、《邓小平文选》章节标题可辨、Flatland（真 CSS 书）与 `styled.epub` 不回归。
- 提交：中文信息，TDD 红→绿两个提交可复现（红测试已在 `test(red)` 提交中）。

## 已知近似（实现照做即可，勿额外发明）

- 从 `body`/`html` 继承来的字号倍率视为「书已声明」→ 覆盖默认比例（与 M11c 继承模型一致）。
- 标题无独立行距处理（沿用全局行距）；默认层级不改变段距两档行为。
