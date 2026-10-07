# M19 清理批次 审查记录

- 日期：2026-10-07
- 实现：OpenCode（`opencode run`，简报 `docs/collab/briefs/m19-cleanup.md`）
- 审查：Kimi Code（diff 全量核对 + 独立复跑 `--rerun-tasks`）
- 提交：`28ad807`（chore: M19 清理批次）

## 1. 批次范围（无行为变更，纯清理）

设计文档 REV17 §3「死代码清理」授权删除 `ReaderViewModel.cycle*`（"已无调用方则一并删除"），M17 时遗留未做，本批补齐 + 同类核查出的两项：

| 对象 | 处置 | 依据 |
| --- | --- | --- |
| `cycleLineHeight()`/`cycleFont()`/`cycleTheme()` | 删 | 生产零调用方（仅 `ReaderViewModelTest`），REV17 §3 已授权 |
| `toggleWrap()` | 删 | 生产零调用方（换行走 `setWrapMode`，`ReaderScreen.kt:185` 方法引用）——REV17 未列此项，系本批调用方核查新发现，同批处置 |
| `common_switch` 字符串键 | 中英同删 | 全仓零引用；`LocalizationTest` 只硬编码 `app_name`，键集对等是集合互比不受影响 |
| `wrapToggle_andCycles_followSpecSteps` | 删，字号端点断言迁移为新测 `fontSizeStep_endpointNoOverflow` | 循环断言前提已随 M17 消失；stepFontSize 端点钳制为仍有效的生产行为 |
| `g2_cycleFont_includesCustomWhenPathSet` | 删，「applyCustomFont 切 CUSTOM」断言迁入 `g2_applyCustomFont_deletesOldFile_onlyOnReimport` | 循环序列前提消失；切换断言原测试缺失，迁入补齐 |
| `settings_updateEffectiveImmediately_andPersisted` | `cycleTheme()` → `setTheme(ReadingTheme.DEFAULT)` | 同语义的生产在用法 |

**无红阶段说明**：本批为删除 + 既有行为断言迁移，改写后断言在删除前同样通过（`setTheme`/`setFont`/`applyCustomFont` 均 M10 已在产线），不存在可复现的红态，故无红提交；验证手段为编译 + 全量套件 + 引用零残留核查。

## 2. 审查发现与处置

### 必修（2，均返工）

1. **陈旧注释**：`ReaderViewModel.kt:472` 节注释「语义与 **cycle**/step 一致」中 cycle 指代已删除方法 → 改「语义与 step 一致」。（OpenCode 首轮曾意外改写该注释又还原，净效果 = 未改，审查拦下。）
2. **未用导入**：`ReaderViewModelTest.kt:11` `import ...WrapMode` 因删测试成为唯一未用导入 → 删除。

必修返工后复验：`testDebugUnitTest` BUILD SUCCESSFUL。

### 建议（0）

无。OpenCode 自报的「tests=290 与简报预测 289 差 1」经核为简报预测算术遗漏（291 − 2 删 + 1 新 = 290），非实现偏差。

## 3. Kimi 独立复跑（`--rerun-tasks`）

- `./gradlew testDebugUnitTest --rerun-tasks` → **290/0/1skip**（exit 0；skip = 既有 F4.8 @Ignore）
- `./gradlew compileDebugAndroidTestKotlin` → exit 0
- 引用零残留：`grep "cycleLineHeight|cycleFont|cycleTheme|toggleWrap|common_switch" app/src` → 无匹配
- diff 全量核对：4 文件 +5/−72，与简报清单逐条一致，无范围外改动

## 4. 结论

可进入收口。真机项：无（纯删除死代码，无 UI/行为变更；D 项清单无需新增）。待用户终审 → 打 `m19` tag → 回写 `docs/开发计划.md` §6 与 `AGENTS.md` §8 进度段。
