# M12b 审查记录（Kimi Code → OpenCode 实现）

> 批次：M12b 章级窗口与全局 item 坐标（ViewModel 层，F4.7 底座）。简报：`docs/collab/briefs/m12b-chapter-window.md`。
> 实现方：OpenCode（`opencode run --auto`）。审查方：Kimi Code。红状态提交：`dc45b7f`。

## 一、OpenCode 报告摘要（含暂停交回）

- 已完成 `ChapterItemMap` 实现（前缀和、locate/globalItem、clamp）与 `ReaderViewModel` 加法式扩展（3 个状态 + 4 个方法 + load/loadMore 接入），新增 `ReaderViewModelWindowTest` 6 例自测全绿。
- **暂停交回**：`ChapterItemMapTest.kt:59`（`firstItemOfChapter(9)` 期望 4）与同文件 `:23`/`:34` 及 KDoc 契约互斥，未改测试、请裁决。
- 自报：`testDebugUnitTest --rerun-tasks` → 234 用例 1 失败（唯一失败即上述断言）；`generateFixtures validateFixtures assembleDebug` 成功。

## 二、审查发现与处置

### F1（测试笔误，已由 Kimi 更正）firstItemOfChapter 越界期望值写错

- 我写的 `assertEquals(4, map.firstItemOfChapter(9))` 把 `locate` 的越界语义（`totalItems-1`）错套到「章起始项」上；按 KDoc「clamp 到 [0, chapterCount-1]」，`firstItemOfChapter(9)` 应等于 `offsets[2] = 3`。
- OpenCode 按升级条件暂停而非改测试，判断正确（这是本项目第二次「实现方拒改断言」的正样本，前一次见 M12a 审查 F1 插曲）。
- 处置：Kimi 更正为 `3`（仅此一处，其余断言未动）→ 全量转绿。

### F2（转 M12c 硬要求）新增状态与方法暂无 UI 消费方

- `materializedChapterCount`/`totalItems`/`restoreItem`/`appendNextChapter`/`ensureMaterialized`/`onFirstVisibleItemChanged`/`consumeRestoreItem` 属加法式中间态，当前 UI 未接线。**M12c 必须接线**，否则视为死状态；同时落实 M12a 审查 F3：清除 `ReaderScreen.kt:775` 的「第 N 章」兜底与 `:360` 顶栏书名回落。

### F3（简报更正）用例数预估错误

- 简报写「应为 228 用例」，实际 **234**（M12a 基线 223 + 本批次红测 5 + 新增 6）。已在 M12c 简报中避免同类预估。

## 三、Kimi 独立复验

- `./gradlew testDebugUnitTest --rerun-tasks` → 全量执行、**tests=234 failures=0 errors=0 skipped=0**（自解析 test-results XML）。
- 逐项核对（读完整 diff）：

| 检查项 | 结论 |
| --- | --- |
| `locate` 空章语义 | ✅ 取「最后一个起始偏移 ≤ clamped」的章：中间空章被后续非空章覆盖、末尾空章不可达，与 KDoc/断言一致 |
| `globalItem` clamp | ✅ 段超限取该章末段；段落数 0 的章返回章起始 |
| 加法式不破坏既有行为 | ✅ diff 仅新增；`selectChapter`/`onFirstVisibleParagraphChanged`/`restoreParagraph`/`loadMore` 语义未动，既有 223 用例仍绿 |
| 进度坐标零迁移 | ✅ 落库仍 `(chapter, paragraph)`；`ProgressRepository`/`AppDatabase` 未被改动 |
| 改动范围 | ✅ 恰好 `ChapterItemMap.kt` + `ReaderViewModel.kt` + 新增测试（+ Kimi 的 1 行测试更正） |

## 四、遗留

- 新状态与方法的实际效果需 M12c 接线后由设备端用例（TC-UI-10/11/12）验证；本批次仅在 Robolectric 下单测覆盖。
