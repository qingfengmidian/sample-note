# M21 验收记录 — 表格列宽按内容自适应（F2.18 REV16）

- 分支：`feat/m21-table-column-width`；tag `m21`。
- 立项：M20 真机观感终审——等宽列致长文列折行多、行很高，用户裁定转 M21；评审点用户拍板（2026-11-14）：按内容占比分配 / 保留超屏横向滚动 / 单列上限 50%。

## 一、交付内容

- 需求 REV16（F2.18 列宽规则修订）；设计 REV20（§3.3 表格节修订 + M20「已知取舍」标记已解决）；测试用例 REV15（TC-UI-23 + 补登 M20 遗漏的 TC-UI-22 §6 行）；简报 `docs/collab/briefs/m21-table-column-width.md`。
- 实现（唯一生产文件 `MarkdownPreview.kt`）：`buildInlineAnnotatedString` 抽取共用、`maxIntrinsicWidthDp`（rememberTextMeasurer）、`measureTableColumnTargets`（remember 按块缓存）、`allocateTableColumnWidths` internal 纯函数、语义键 `MdTableColumnWidthsKey`。

## 二、验证结果

| 项 | 结果 |
|---|---|
| 红绿可复现 | `29e6590`（TC-UI-23 六例 5 红）→ `357c866`（全绿） |
| 单测 | **315 / 0 / 1 skip**（基线 309 + TC-UI-23 六例）；TC-UI-22 五例零改动回归 |
| 审查 | Kimi 必修 0（`reviews/m21-review.md`）；OpenCode 上报两处（分配钳制偏差/红态测试 setContent 机械修正）均已确认 |
| 解析层/映射层/字符串资源 | 零改动；无新文案 |
| D18 真机（OPPO PADT00，2026-11-14） | ① 长短列表：编号列窄/故事列宽，US-1 由约 10 行降为 4 行；② 6 列宽表：名称/备注列宽、状态列窄、横向滚动正常；③ 夜间黑主题表头加粗与分隔线清晰；④ 用户指示收口 |

## 三、过程记录（环境）

- PHM110 无线调试掉线后，改用 USB 连接 OPPO PADT00（新设备）：排查仅充电线 → USB 用途 → 开发者选项「USB 调试」→ 授权弹窗，四步后 `adb` 可见。
- 摩斯原始文档仅存于 PHM110（全盘 mdfind/find 无），本轮用构造验证件 `m21-check.md`（长短列/多列宽表/等宽对照）覆盖 M21 场景；原档复验可在 PHM110 可用后补（不阻塞收口，原始表格 D17 已走过真机）。

## 四、遗留

1. 触顶 CAP 时表格不撑满屏宽（余量不可回收）——残余取舍，记录在案；如观感投诉再议。
2. 摩斯原档在 PADT00 上的复验（可选，PHM110 连上后 pull→push 即可）。
