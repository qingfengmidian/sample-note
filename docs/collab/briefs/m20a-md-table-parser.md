# 任务简报：M20a — md 表格渲染·解析层（F2.18）

> 需求依据：`docs/需求说明书.md` REV15 / F2.18（评审点 R1~R3 用户已拍板 2026-11-09）。
> 本里程碑只做**解析层 + 测试夹具 + 单测**（TDD：先红后绿，PR 历史可复现）；渲染层（MarkdownPreview 的 MdTable 可组合项）归 M20b，本阶段 Table 块**不需要被任何 UI 消费**。
> 双智能体流程按《协同开发规范》：本简报由 Kimi Code 发出，OpenCode 实现，Kimi Code 审查后提交。

## 1. 背景与目标

真机反馈：md 含表格文档无法渲染，管道表按原文竖线输出、不直观。F2.18 要求在 md 预览视图渲染 GFM 管道表。M20a 目标：`core/render/MarkdownParser.kt` 的块模型新增 `MarkdownBlock.Table`，`parse()` 识别 GFM 管道表；新增测试夹具与解析层/映射层单测。

## 2. 现状关键代码

- `app/src/main/java/com/sample/docreader/core/render/MarkdownParser.kt`：块模型 `MarkdownBlock`（Heading/Paragraph/ListItem/Quote/CodeBlock，均带 `startParagraphIndex`）、`parse(paragraphs)` 逐行解析、``` 围栏合并为 CodeBlock、`parseInline` 行内标记（Bold/Italic/Code）、`scanHeadings` 目录扫描（跳过围栏、查 `Thread.currentThread().isInterrupted`）。
- `app/src/main/java/com/sample/docreader/ui/reader/MdViewMapping.kt`：`blockIndexForParagraph` 取「最后一个 startParagraphIndex ≤ p 的块」——Table 块只要正确携带 `startParagraphIndex` 即天然兼容，**不得改动映射函数**。
- 夹具生成器：`buildSrc/src/main/kotlin/FixtureGenerator.kt`（产物不入库，禁止手改 fixtures 产物文件）。

## 3. 设计要求（接口签名以本节为准）

### 3.1 块模型新增

```kotlin
/** 列对齐（F2.18）：GFM 分隔行冒号位置决定；无冒号默认左对齐 */
enum class MdAlign { LEFT, CENTER, RIGHT }

/**
 * GFM 管道表（F2.18）：表头 + 分隔行必需；行数不齐容错 = 短行补空单元格、长行截断。
 * startParagraphIndex = 表头行行号（源码段落流坐标，供 U4 块↔段落映射）。
 */
data class Table(
    val header: List<List<MdInline>>,          // 表头各单元格（行内标记已解析）
    val aligns: List<MdAlign>,                 // 每列对齐，长度 = 列数
    val rows: List<List<List<MdInline>>>,      // 表体各行；每个单元格是 MdInline 列表
    override val startParagraphIndex: Int
) : MarkdownBlock()
```

- `aligns` 长度恒等于列数（以表头列数为准），补空/截断按列数归一后填充对齐值。

### 3.2 `parse()` 识别规则（严格 GFM 语义）

1. **成表条件**：当前行「疑似表头」（trim 后含未转义 `|`）且**下一行是分隔行**；否则当前行按既有逻辑走 `parseBlock`（缺分隔行的竖线文本 = 普通段落，F2.18 明确需求）。
2. **分隔行**正则（整行）：允许省略首尾竖线，单元格内容为 `-` 序列加可选冒号：
   `^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$`
   冒号规则：`:---` LEFT、`:-:`/`:--:` CENTER、`---:` RIGHT、无冒号 LEFT。
3. **列切分**：按未转义 `|` 切分；支持省略的首尾竖线；单元格 trim；`\|` 还原为字面 `|`（切分时不得把 `\|` 当分隔符，转义符本身剥除）。空单元格 = 空 List 或空 Text 段，实现自定但单测固定语义。
4. **行内标记**：单元格文本过 `parseInline`（加粗/斜体/行内代码生效）。
5. **表体吞行**：分隔行之后，后续**连续**的「含未转义 `|` 的非空行」为表体行；遇空行、不含 `|` 的行、或新块起始行（#/>/-/*等——注意列表行 `- a | b` 是否入表按 GFM：GFM 以「该行能切出与表头同列数的单元格」判定，实现取简化口径：表体行必须含 `|` 且 trim 后不以块起始语法开头）截止。
6. **列数归一**：以表头列数为准；短行补空单元格，长行截断（R1 拍板）。
7. **围栏互斥**：``` 围栏内的行不识别为表格（现有 fence 分支优先，表格识别只在非围栏路径生效）。围栏不闭合到文末时现有行为（吞到结尾）不变。
8. **取消**：表体吞行行循环内查 `Thread.currentThread().isInterrupted`，中断即中止（沿用 scanHeadings 约定；`parse` 其余部分维持现状，不强加取消检查）。
9. **单表退化**：表头+分隔行后无表体行 → 允许渲染仅表头的表格（GFM 合法），rows = emptyList。

### 3.3 既有行为零回归

- Heading/ListItem/Quote/CodeBlock/Paragraph 识别优先级不变；不成表的竖线文本、成表判定失败的第二行（如分隔行写法不合法）都按原文输出。
- `scanHeadings` 不改动（表格行不以 `#` 起始成标题，天然无冲突；`#` 出现在单元格内的行因整行非 ATX 标题也不受影响）。
- `MdViewMapping` 不改动；新增单测覆盖「表体中间段落 → Table 块」的映射。

## 4. 夹具（FixtureGenerator）

新增 1 个 md 夹具（命名自定、入既有 fixtures 清单与校验逻辑）：

- `tables.md`：至少含——① 标准三列对齐表（左/中/右）多行表体；② 省略首尾竖线的表；③ 单元格含 `\|` 转义与加粗/行内代码；④ 围栏代码块内的假表格（不得识别）；⑤ 缺分隔行的竖线文本（对照，不得识别）；⑥ 行数不齐（短行/长行各一）；⑦ 仅表头+分隔行无表体。

## 5. 测试（先红后绿）

新增解析层单测（测试类自定，注释引用编号）：

- **TC-UT-20**（解析识别）：成表/不成表边界（缺分隔行、分隔行非法）、对齐冒号四种形态、首尾竖线省略、`\|` 转义、行内标记、行数不齐补空/截断、围栏内不识别、无表体退化、吞行截止（空行/普通段落/列表行）、`startParagraphIndex` 锚定表头行。
- **TC-UT-21**（映射兼容）：`MdViewMapping.blockIndexForParagraph` 对含 Table 块的块表（表头/表体中间/表后段落）映射正确。
- 性能兜底：大段落流下含多表解析计时断言（沿用既有 epub 解析 ≤200ms 类单测风格，阈值自定合理值）。
- 命令：`./gradlew testDebugUnitTest` 全绿（基线 290/0/1skip 之上有增无减）；红态由首次提交（仅测试）在 CI/本地可复现。

## 6. 验收（M20a DoD）

1. 红→绿可复现（先提交仅测试、后提交实现）。
2. `./gradlew testDebugUnitTest` 全绿；`./gradlew check`（含 validateFixtures）通过。
3. 既有单测零回归；源码/预览行为本阶段无 UI 变化（Table 块暂不被渲染，预览落入 `else`/未覆盖分支不崩溃——如 `MarkdownPreview` 的 `when` 非穷尽，实现需在 M20a 给 Table 加最小占位渲染或 when 的兜底分支，编译优先，完整渲染归 M20b）。
4. 提交信息中文、引用 F2.18/TC-UT-20/21；Kimi Code 审查通过后方可提交。

## 7. 明确不做（M20a 边界）

- MdTable Compose 渲染（列宽/横向滚动/分隔线/长按复制）→ M20b。
- 需求说明书/设计文档/测试用例文档修订 → Kimi Code 已在 REV15 落地需求条目，设计文档 REV 与测试用例编号在 M20 收口时统一回写。
- HTML `<table>`、单元格内换行、嵌套表格等非 GFM 管道表语法。
