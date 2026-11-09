# M20a 审查记录 — md 表格渲染·解析层（F2.18）

- 批次：`feat/m20a-md-table-parser`；红态 `363867f` → 转绿 `111cf59`（红绿可复现）。
- 实现者：OpenCode（按简报 `docs/collab/briefs/m20a-md-table-parser.md`）；审查者：Kimi Code。

## 升级条件处置（OpenCode 触发 1 次）

1. **测试语义自相矛盾**（MarkdownTableTest.kt:48）：单元格按简报 §3.2 规则 3「trim」断言应为 `[Bold(香蕉)]`，红态测试误写 `[Bold(香蕉), Text(" ")]`，且与同测试其它全量 trim 断言互斥。
   处置：**已修复**（Kimi Code 修正断言为 `[Bold(香蕉)]`，提交 `111cf59`）。单元格 trim 为 F2.18/GFM 语义，实现与简报无误。

## Kimi Code 独立审查（完整 diff + git status）

- 改动仅 `MarkdownParser.kt`（+111 行）：`parse()` 非围栏路径先试 `parseTable`，新增加 `DELIMITER_ROW`、`splitCells`（未转义竖线切分/`\|` 还原/首尾竖线省略/trim）、`hasUnescapedPipe`、`alignOf`、`startsWithBlockSyntax`、`isTableHeaderCandidate`；无任务外修改、无 `MdViewMapping`/既有测试改动、`AGENTS.md` 既有未提交改动未被触碰。
- 对照简报 §3.2 逐条核验：成表条件（表头候选 + 分隔行必需）、分隔行正则、对齐四形态、吞行三截止条件（空行/块起始语法/无未转义竖线）、列数不齐补空/截断（R1）、围栏互斥、`startParagraphIndex` 锚定表头行、表体循环查 `Thread.currentThread().isInterrupted`——全部落实。
- 边界抽查：`splitCells` 显式首尾竖线与省略两种形态、中间空单元格保留、其余反斜杠原样保留；无表体退化（rows 空）；表头候选「非块起始语法」护栏保持 Heading/列表/引用优先级不变（简报未明示，属防御性实现，无测试冲突，**予以保留**）。
- 测试覆盖：成功路径 + 失败前提（缺分隔行/非法分隔行/围栏内假表/吞行截止/行数不齐/空单元格）+ 性能基准（4000 行含表 ≤2000ms）+ 映射纯函数与 parse 集成两条路径。
- 验证：`./gradlew testDebugUnitTest --rerun-tasks` 全绿（基线 290 + 新增 15，1 skip 为既有 F4.8）；`validateFixtures` 绿。`check` 中 lint 任务因本机代理失效（127.0.0.1:7890 拒连，lint-gradle 31.6.1 未缓存）无法拉取——既有环境问题（AGENTS.md 已记录同类），CI 覆盖。

## 结论

必修项 0；升级条件 1 已闭合。M20a 完成，可进入 M20b（渲染层，简报 `docs/collab/briefs/m20b-md-table-render.md`）。
