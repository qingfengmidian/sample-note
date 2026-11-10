# M20 验收记录 — md 表格渲染（F2.18）

- 分支：`feat/m20a-md-table-parser`；tag `m20`（收口时打）。
- 里程碑拆分：M20a 解析层（TDD 红绿可复现）→ M20b 渲染层 → 真机 D17 走查 → 收口。
- 评审点 R1~R3 用户拍板（2026-09-09 立项问询）：R1 行数不齐短行补空/长行截断；R2 等宽列 + 最小列宽 + 超屏横向滚动；R3 单元格长按复制。观感终审（2026-11-10）：R2 等宽方案本期收口，**列宽按内容自适应转 M21 优化候选**（用户裁定）。

## 一、交付内容

- 需求 REV15 / F2.18；设计 REV19（§3.3 md 表格渲染）；测试用例 REV14（TC-UT-20/21、TC-UI-22、TC-F2-30）；fixtures 新增 `tables.md`（七类场景）。
- 解析层：`MarkdownBlock.Table` / `MdAlign`、`parse()` GFM 管道表识别（`parseTable`/`splitCells`/`hasUnescapedPipe`/`alignOf`/`startsWithBlockSyntax`），U4 映射零改动。
- 渲染层：`MdTable`/`TableRow`/`TableCell`/`DividerLine`（MarkdownPreview.kt），新键 `reader_table_semantics` 中英对等。

## 二、验证结果

| 项 | 结果 |
|---|---|
| 红绿可复现 | `363867f`（17 跑 11 红）→ `111cf59`（全绿），红绿两次提交历史可复现 |
| 单测 | `--rerun-tasks` 全量 **309 / 0 / 1 skip**（skip = 既有 F4.8 进度条 @Ignore）；`validateFixtures` 绿 |
| `./gradlew check` | lint 任务因本机代理失效（127.0.0.1:7890 拒连）无法拉取 lint-gradle——既有环境问题（AGENTS.md 已记录），CI 覆盖；其余任务绿 |
| 设备端全量 | 本轮跑套件时无线 adb 掉线（device offline，0 例执行），**非测试失败**；重连后补跑（见「遗留」） |
| D17 真机走查（PHM110，2026-11-10） | 用户真实反馈文档「摩斯求救App-需求文档.md」「摩斯求救App-调研报告.md」：① 用户故事表/使用场景表/竞品宽表渲染正确（表头加粗、分隔线清晰、单元格内加粗生效、省略竖线与 `|---|` 无空格写法识别）；② 围栏内假表、缺分隔行文本按原文不误判；③ 宽表横向滚动与垂直滚动互不抢占；④ 四主题分隔线可辨识度用户确认 OK；⑤ 长按单元格复制用户确认 OK；⑥ 等宽列长文折行观感——用户裁定可接受本期收口，自适应列宽转 M21 |

## 三、审查链

- OpenCode 实现（M20a/M20b 两批次，简报 `docs/collab/briefs/m20{a,b}-*.md`）；Kimi Code 审查必修 **0 / 0**（`reviews/m20a-review.md`、`m20b-review.md`）。
- M20a 过程事件：OpenCode 正确命中升级条件暂停（红态测试 trim 断言笔误），Kimi 按 F2.18/GFM 语义修正后转绿——测试语义权威在需求/简报，实现无误。
- 本批次代码均由 OpenCode 实现、Kimi 审查，角色未反转；§8.5 复核适用于 Kimi 亲自写代码的情形，本批次不适用。

## 四、遗留与后续

1. **设备端全量套件补跑**（无线 adb 掉线导致本轮 0 执行）：重连后执行 `./gradlew connectedDebugAndroidTest` 并回填本表；注意该任务结束会卸载应用，需重装 debug 包。
2. **M21 候选：表格列宽按内容自适应**（用户裁定）——目标：长文列/短文列宽度按内容分配，降低高行与折行；需自带测量与最小/最大列宽约束，实施前出简报评审。
3. 等宽方案的既有取舍已记录于设计 REV19「已知取舍」。
