# 任务简报：M20b — md 表格渲染·渲染层（F2.18）

> 前置：M20a 已完成并合入（`MarkdownBlock.Table` / `MdAlign` 已存在，夹具 `tables.md` 已就绪）。
> 本里程碑做 **MarkdownPreview 的 MdTable 渲染 + UI 测试 + 设备端测试**，完成后走真机走查与验收。
> 需求依据：`docs/需求说明书.md` REV15 / F2.18（R1~R3 已拍板）。R2/R3 在本阶段落地。

## 1. 目标

md 预览视图（md 阅读默认视图，`MarkdownPreview.kt`）把 `MarkdownBlock.Table` 渲染为表格：表头加粗 + 列对齐生效 + 行间分隔线；列等宽、最小列宽；总宽超屏时表格容器横向滚动；单元格内长按可选择复制；提供无障碍语义。

## 2. 设计要求

### 2.1 MdTable 可组合项（`MarkdownPreview.kt` 内或同包新文件 `MdTable.kt`）

- **结构**：Column 包多个 Row（表头一行 + 表体每行一行）；单元格文本经既有 `InlineText`/行内渲染路径（加粗/斜体/行内代码生效）；字号/字体/颜色全部跟随当前阅读设置与主题（与预览其他块同一 TextStyle 基准，不得硬编码颜色——对比度沿用主题前景，夜间等主题零特判）。
- **表头**：加粗；表头行底部分隔线（主题前景低透明度，透明度值自定但深/浅主题均须可见）；表体行间细分隔线（更浅一档）。
- **对齐**：按 `aligns` 给每列单元格 Text 设 `textAlign`（LEFT/CENTER/RIGHT）。
- **列宽（R2 拍板）**：等权重分配；每列设最小宽度（默认 96dp，实现可调常量）；各行（含表头）同列同宽（共享列宽约束，禁止逐行独立测量导致错位）。总宽 ≤ 屏宽时均分撑满可用宽；超屏时容器 `horizontalScroll`，正文 LazyColumn 垂直滚动不受横向滚动影响（横向滚动不抢占垂直滑动）。
- **长按复制（R3 拍板）**：单元格文本支持长按选择复制，行为与预览普通段落（F2.12）一致——优先复用既有选择/复制机制，不得为表格新造一套交互。
- **无障碍**：表格容器 `semantics` 描述行列数（如「表格，3 列 5 行」；文案走 stringResource 中英双语，新增字符串键，键集对等——LocalizationTest 会校验）；表头单元格保持 bold 可辨即可，不做复杂 table 语义角色。
- **退化**：单元格空文本渲染为空单元格；`rows` 为空时仅渲染表头。

### 2.2 集成点

- `MarkdownPreview` 的块 `when` 新增 `is MarkdownBlock.Table -> MdTable(...)` 分支（M20a 若有占位/兜底分支，本阶段替换为正式实现）。
- U4 源码↔预览切换、目录跳转映射 M20a 已兼容，本阶段仅验证不改代码；若发现坐标错位，回填 M20a 修复并记录。

## 3. 测试

- **TC-UI-22**（Robolectric Compose UI Test，新增测试类，注释引用编号）：tables 夹具打开预览——表格节点存在且语义含行列数；表头单元格加粗样式断言（fontWeight）；对齐列 textAlign 断言；围栏内假表格渲染为原文文本（非表格节点）；缺分隔行对照段渲染为普通段落；横向滚动容器在超宽表存在、窄表不存在。
- **TC-F2-24**（设备端，Espresso/Compose，设备端套件新增）：预览渲染 tables 夹具滚动流畅无崩溃；如设备端断言 `getString` 语义文案需跟随语言（参照 TC-UI-16 做法）。
- 真机走查 D17（本阶段新增，入 `docs/collab/pending-real-device-tests.md`）：真实含表 md（用户实际反馈文档优先）四主题 × 中英各过一遍——表格分隔线可见、对齐正确、超宽横向滚动顺手、长按复制可用、深主题对比度正常。
- `./gradlew testDebugUnitTest` 全绿（M20a 基线之上只增不减）；`./gradlew connectedDebugAndroidTest` 全绿。

## 4. 验收（M20b DoD / M20 收口）

1. 单测 + 设备端全绿；真机 D17 走查通过（用户观感终审）。
2. Kimi Code 审查 + OpenCode §8.5 交叉复核通过。
3. Kimi Code 回写：设计文档 REV（§3.3 MarkdownParser 接口签名与 MdTable 渲染节）、测试用例文档 TC-UT-20/21/TC-UI-22/TC-F2-24 条目、AGENTS.md 进度段（M20 完成记录）、`docs/collab/reviews/m20-acceptance.md`。
4. 打 tag `m20`。

## 5. 明确不做

- 源码视图/编辑器任何变化；epub/txt/xml；HTML `<table>`；单元格内多段落；表格内图片。
