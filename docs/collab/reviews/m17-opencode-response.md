# M17 OpenCode 复核意见处置 — Kimi Code 侧

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-19 |
| 复核报告 | `docs/collab/reviews/m17-opencode-review.md`（结论：**可接受进入收口**，必修 0 / 建议 4） |
| 处置者 | Kimi Code |

## 逐条处置

| 意见 | 分级 | 处置 |
| --- | --- | --- |
| **S1** `settingsDataStore()` 的 `produceFile` 闭包捕获外层 Context（可能为 Activity），新增进程级强引用、弱键语义失效 | 高优先 | **已修复**：先取局部 `val appContext = applicationContext`，闭包仅持有 Application（与旧 `preferencesDataStore` 委托行为一致），`WeakHashMap` 弱键语义成立。不改变任何行为契约。 |
| **S2** 实施报告与终稿不符（「红测试零改动」口径、§4-决策 2 待确认档显示） | 文档一致性 | **已处置**：`m17-opencode-report.md` §5 新增「终稿修订注记」，指向 Kimi 审查记录 §2.2/§3 与 S1 修复。 |
| **S3** `common_switch` 未用键、`ReaderViewModel.cycle*` 测试专用生产 API | 清理（遗留） | **记录不修**：均已在实施报告 §5 记录；`common_switch` 删除需中英同删，留待后续资源清理批次；`cycle*` 随 `ReaderViewModelTest` 评估一并清理（用例数下降需显式接受）。 |
| **S4** picker 默认值断言隐含顺序依赖（耦合「Robolectric 每方法重建 Application」生产实现细节）；kdoc 仍提 `assertIsSelected` | 测试加固 | **已处置**：两个 picker 测试类补 `@Before` 显式 `save(ReadingSettings())` 重置，隔离不依赖生产实现细节；kdoc 订正为 `hasText + expectValue(Selected, true)` 联合匹配并说明缘由。 |

## 验证

- `./gradlew testDebugUnitTest` 全量复跑（处置后）：见本提交前最新运行结果，预期保持 281/0/1skip（S1/S4 均不改变行为契约与断言判别力）。
