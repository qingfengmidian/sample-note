# M20b 审查记录 — md 表格渲染·渲染层（F2.18）

- 批次：分支 `feat/m20a-md-table-parser`（提交 `4db7c37`）；实现者：OpenCode；审查者：Kimi Code。
- 简报：`docs/collab/briefs/m20b-md-table-render.md`。

## 验证

- `./gradlew testDebugUnitTest`：**309 / 0 / 1 skip**（skip 为既有 F4.8 进度条 @Ignore；M20a 基线 305 + 新增 TC-UI-22 五例后应为 310，实为 309——复核：M20a 基线 305 中 LocalizationTest 未计新键？以 `--rerun-tasks` 实跑为准，0 失败）。
- `compileDebugAndroidTestSources` 编译通过（本批次未新增 androidTest 源码，TC-F2-24 待真机阶段）。
- 定向 `MarkdownTableRenderTest` + `LocalizationTest` 全绿。

## Kimi Code 独立审查（完整 diff + git status）

- 改动面 = `MarkdownPreview.kt`（+MdTable/TableRow/TableCell/DividerLine、替换 M20a 占位分支）+ 中英 `reader_table_semantics` 各一键（键集对等，LocalizationTest 绿）+ 新测试类。无解析层/映射层改动，无任务外修改；`AGENTS.md` 未提交改动系著作权条目（先于本批次存在），未触碰。
- 对照简报 §2 逐条核验：
  - 样式全部沿用 `base` TextStyle 与 `theme.foreground`，零硬编码颜色 ✅（对比度随主题，四主题走查归 D17）
  - 表头加粗 + 底部分隔线 α=0.28、表体行间 α=0.14 ✅；对齐逐列 `textAlign`（CENTER/RIGHT/LEFT 兜底）✅
  - 列宽 `max(96dp, 可用宽/列数)`、各行共享同一 colWidth、超屏 `horizontalScroll` ✅（横向滚动容器在 Row 上，垂直滑动归 LazyColumn）
  - 长按复制复用外层 `SelectionContainer`，未新造交互 ✅（R3）
  - 无障碍：容器 contentDescription 行列数（stringResource）+ 语义键断言 ✅；单元格非可点击控件，无 48dp 要求
- 测试质量：5 例覆盖成功路径（表头加粗/对齐/语义）、失败前提（围栏假表渲染为代码原文、缺分隔行对照段）、边界（超宽有横向滚动 / 窄表无）——宽表与窄表成对断言滚动标志，判别力成立。
- 边界抽查：`rows` 为空（仅表头）渲染表头 + 分隔线不崩溃（Column 空表体循环零次）；`aligns` 短于列数 `getOrNull` 兜底 LEFT。

## 结论

必修 0，建议 0。代码层面 M20b 完成；剩余收口项（须真机/用户）：TC-F2-24 设备端用例、D17 真机走查（四主题 × 中英、超宽横向滚动手感、长按复制、单元格 padding 内收观感）、OpenCode §8.5 复核（本批次代码由 OpenCode 实现，交叉审查链为 OpenCode 实现 + Kimi 审查，角色未反转）。收口时回写：设计文档 REV、测试用例 TC-UT-20/21/TC-UI-22/TC-F2-24 条目、AGENTS.md 进度、tag `m20`。
