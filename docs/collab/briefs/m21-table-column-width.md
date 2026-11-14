# 任务简报：M21 — md 表格列宽按内容自适应（F2.18 修订，需求 REV16）

> 需求依据：`docs/需求说明书.md` REV16 / F2.18 修订（评审点用户已拍板 2026-11-14：按内容占比分配 / 保留超屏横向滚动 / 单列上限 50%）。
> 前置：M20 已合入（`MarkdownBlock.Table`/`MdAlign`、`MarkdownPreview.kt` 的 `MdTable`，语义键 `MdTableCellBoldKey`/`MdTableCellAlignKey`/`MdTableHorizontalScrollKey`，Robolectric `MarkdownTableRenderTest`）。
> 本批次：**只改渲染层列宽策略 + 语义键 + 测试**，解析层/映射层/字符串资源零改动（无新文案）。

## 1. 背景与目标

M20 真机走查：等宽列（M20 R2 方案）导致长文列折行多、行很高（竞品调研宽表一屏只显示一两行）。M21 把列宽改为按内容占比自适应：短列窄、长列宽，行高更均匀。

## 2. 设计要求（接口与算法以本节为准）

### 2.1 列宽测量

- 用 Compose `rememberTextMeasurer`（`androidx.compose.ui.text`）测量每个单元格文本在给定 `base` TextStyle（含全局字号/字体）下的 **maxIntrinsicWidth**（单行不换行宽度）。
- 单元格文本即行内渲染所用的 AnnotatedString（与 `InlineText` 同一构造逻辑——把 `InlineText` 的 buildAnnotatedString 提取为可复用函数，测量与渲染共用，防止两版文本漂移）。
- **列代表宽度** `target_j` = max（表头单元格宽度， 表体该列所有单元格宽度的**平均值**）——平均避免单个超长单元格主导；表头参与保证短列表头不被压没。
- 测量结果按 `Table` 块内容缓存（remember(block) 键），同一表格不重复测量；大表（如 100 行 × 8 列）测量耗时须在测试中兜底（见 §3 性能断言）。

### 2.2 列宽分配（纯函数，可单测）

输入：各列 `target_j`、可用宽度 `W`（BoxWithConstraints maxWidth，同 M20）、列数 n、最小列宽 `MIN = 96.dp`、单列上限 `CAP = 0.5 × W`。

```
cap_j = min(target_j, CAP)                 # 单列上限 50%
sum   = Σ cap_j
若 sum ≤ W:  每列 width_j = cap_j + (W - sum) × cap_j / sum   # 余量按占比再分配（撑满屏宽）
若 sum > W:  先按 MIN 保底：width_j = max(MIN, cap_j × W / sum)
             若仍 Σ > W（列数极多）：width_j = cap_j，表格超屏 → 保留横向滚动（M20 行为）
```

- 同一列各行共享同一 `width_j`（表头/表体对齐，同 M20）。
- 新增语义键 `MdTableColumnWidthsKey = SemanticsPropertyKey<List<Dp>>`（挂在表格容器节点，与 `MdTableHorizontalScrollKey` 同处），供测试断言每列实际宽度。
- `needsScroll` 判定改为基于最终 Σ width_j > W（同 M20 语义）。

### 2.3 不变量（不得破坏）

- 表头加粗、分级分隔线、逐列 textAlign、长按复制（SelectionContainer）、行列数语义描述——M20 行为全部保留。
- 等宽退化：内容相近的列测得宽度应近似相等（`width_j` 差异在断言容差内，见 §3）。
- 极端输入：`target_j` 全 0（空单元格表格）→ 走 sum ≤ W 分支按占比再分配退化为等分（cap 全 0 时除零，需特判 = 等分 W/n）；单列 100 行长文本的平均值可能超过 CAP → cap 生效。
- 颜色/字号/字体继续跟随 `base` 与主题，禁止硬编码颜色。

## 3. 测试（先红后绿，红态由 Kimi Code 先行提交）

**TC-UI-23**（Robolectric，新增 `MarkdownTableColumnWidthTest`，注释引用编号；红态期语义键无值 → 失败）：

1. **长短列区分**：2 列表（短列「编号」类 2 字、长列一段长文）→ `MdTableColumnWidthsKey` 两个宽度**明显不等**，长列 > 短列；
2. **列宽上限**：长列内容极长（远超屏宽）→ 该列宽度 ≤ 可用宽 50%（+1dp 容差），另一列仍 ≥ MIN；
3. **近似等宽退化**：两列内容长度接近 → 两列宽度差 ≤ 2dp；
4. **窄表撑满**：短内容 2 列表 → Σ 宽度 == 可用宽度（容差 1dp）且无横向滚动（`MdTableHorizontalScrollKey == false`）；
5. **超宽多列表**：8 列 × 96dp+ 内容 → 横向滚动为 true（M20 行为保留）；
6. **性能兜底**：构造 80 行 × 6 列表，断言测量+组合不超时（参照既有性能断言写法，阈值自定合理值）。

既有 `MarkdownTableRenderTest`（TC-UI-22）必须零改动全绿（M20 行为不回归；其中宽表/窄表两例的滚动标志语义不变）。

## 4. 验收（M21 DoD）

1. 红→绿可复现；`./gradlew testDebugUnitTest` 全绿（基线 309/0/1skip 之上只增不减）；
2. 审查通过；真机走查（D18，入 `pending-real-device-tests.md`）：真实含表文档（摩斯两份 + tables.md 夹具）列宽观感——长文列更宽、行高更均匀，四主题不回归，长按复制不回归；
3. 提交信息中文、引用 F2.18/TC-UI-23。

## 5. 明确不做

- 解析层、MdViewMapping、字符串资源（无新文案）、编辑/源码视图；
- 行高最小化迭代分配（更优算法，拍板未选）；
- 设备端新增用例（D18 为手工真机走查，沿用 TC-F2-30 场景复验）。
