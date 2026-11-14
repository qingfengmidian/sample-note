# M21 审查记录 — 表格列宽按内容自适应（F2.18 REV16）

- 批次：分支 `feat/m21-table-column-width`；红态 `29e6590` → 转绿 `357c866`（红绿可复现）。
- 实现者：OpenCode；审查者：Kimi Code；简报：`docs/collab/briefs/m21-table-column-width.md`。

## 验证

- `./gradlew testDebugUnitTest`：**315 / 0 / 1 skip**（基线 309 + TC-UI-23 六例；skip 为既有 F4.8 @Ignore）。
- 定向 TC-UI-22 五例零改动回归全绿（M20 行为不回归）。

## 独立审查（完整 diff）

- 改动面 = `MarkdownPreview.kt`（+测量/分配）+ `MarkdownTableColumnWidthTest.kt`（一处机械修正）；解析层/映射层/字符串资源零改动，无任务外修改。

## OpenCode 上报事项处置

1. **分配公式补充钳制（简报偏差）**：简报 §2 字面公式「余量按占比再分配」会让触顶列超过 CAP=50% 上限（与 TC-UI-23 例 2 矛盾）。实现统一为「占比再分配后钳制 [MIN, CAP]」。**处置：予以确认**——「单列上限 50%」是用户拍板的硬约束，钳制是字面公式的必要补充而非语义变更；已知副作用：当某列触顶 CAP 时表格不撑满屏宽（余量留在 capped 列无法回收），观感项由 D18 真机终审。
2. **红态测试机械修正**：性能用例原调两次 `setPreview`（`setContent` 每测试仅一次，必抛）。OpenCode 删预热调用、保留单次计时，断言语义（80×6 测量+组合 ≤3000ms）不变。**处置：确认**——属 Kimi 红态测试的缺陷修复，非语义修改。

## 逐条核验（对照简报 §2/§3）

- 测量：`rememberTextMeasurer` + `softWrap=false/maxLines=1` 取 maxIntrinsicWidth，`buildInlineAnnotatedString` 抽取后测量与渲染共用同一文本构造（防漂移）✅；`target_j = max(表头加粗宽, 表体均值)` ✅；`remember(header, rows, base, …)` 按块缓存 ✅
- 分配纯函数 `allocateTableColumnWidths`（internal 可单测）：CAP=50%（`maxOf(available*0.5, MIN)` 防窄屏 cap<min 越界崩溃）✅；sum≤W 余量占比再分配 + 钳制 ✅；sum>W 缩放 + MIN 保底、仍超屏回退 cap 保留横向滚动 ✅；全 0 特判等分 W/n ✅
- needsScroll 改由最终 Σwidth 判定（同 M20 语义）✅；`MdTableColumnWidthsKey` 挂表格容器节点 ✅；表头加粗/分隔线/对齐/长按复制/行列数语义不回归（TC-UI-22 零改动绿）✅
- 测试判别力：长短列/上限/近似等宽/撑满/超宽滚动/性能六例成对正反断言 ✅

## 结论

必修 0。代码层面完成；待 D18 真机走查（列宽观感、四主题、长按复制不回归）后收口。
