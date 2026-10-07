# M19 清理批次简报（死代码与未用资源）

> 执行者：OpenCode（Kimi Code 以 `opencode run` 非交互喂入）。提交由 Kimi Code 执行（opencode.json 禁 git 写）。
> 本批为纯清理：无行为变更、无新增功能、不改 fixtures、不改 `docs/` 权威文档（回写由 Kimi 收口时做）。

## 1. 背景

M17（设置项改选项列表交互，决策 D-2026-09-19-01）删除循环切换后，设计文档 REV17 §3「死代码清理」已授权删除 `ReaderViewModel.cycle*`（"已无调用方则一并删除"），但当时遗留未做。本批补齐该遗留 + 同类核查出的两项死代码 + 一个未用字符串键。

Kimi 已完成调用方核查（结论可直接采信，你复核即可）：

| 对象 | 位置 | 核查结论 |
| --- | --- | --- |
| `cycleLineHeight()` / `cycleFont()` / `cycleTheme()` | `app/src/main/java/com/sample/docreader/ui/reader/ReaderViewModel.kt:477-500` | 生产零调用方（仅 `ReaderViewModelTest` 调用） |
| `toggleWrap()` | `ReaderViewModel.kt:466-468` | 生产零调用方（换行模式走 `setWrapMode`，面板 `ReaderScreen.kt:185` 方法引用；同文件 502 行注释已说明「直接设置方法替代循环」） |
| `common_switch` 字符串键 | `app/src/main/res/values/strings.xml:14`、`values-zh/strings.xml:14` | Kotlin/XML 全仓零引用（LocalizationTest 只硬编码 `app_name`，键集对等是集合互比，中英同删不影响） |

## 2. 任务清单

### 2.1 生产代码删除

1. 删 `ReaderViewModel` 的 `cycleLineHeight()`、`cycleFont()`、`cycleTheme()` 三个方法（含各自 KDoc 注释块）。
2. 删 `ReaderViewModel` 的 `toggleWrap()` 方法（含注释）。
3. 删 `values/strings.xml` 与 `values-zh/strings.xml` 中的 `<string name="common_switch">` 行。

保留不动：`setWrapMode`/`setFont`/`setTheme`/`setLineHeight`（生产在用）、`stepFontSize` 与 `FONT_SIZE_STEPS`/`LINE_HEIGHT_STEPS` 常量（`stepFontSize`、`ReaderScreen.kt:853`、`SettingsScreen.kt:201/564` 在用）。

### 2.2 测试改写（`app/src/test/java/com/sample/docreader/ui/reader/ReaderViewModelTest.kt`）

1. `settings_updateEffectiveImmediately_andPersisted`（约 327-342 行）：`vm.cycleTheme()` 改 `vm.setTheme(ReadingTheme.DEFAULT)`，行内注释「跟随系统 → 默认白」保留。
2. 删 `wrapToggle_andCycles_followSpecSteps`（约 344-362 行）：其中 `toggleWrap`/`cycleLineHeight` 均为死代码断言；**有价值的 `stepFontSize` 端点不越界断言须迁移**——新建测试 `fontSizeStep_endpointNoOverflow` 保留 357-361 行原断言与注释（12~24 六档端点钳制），不加 TC 编号（与原文一致）。
3. 删 `g2_cycleFont_includesCustomWhenPathSet`（约 442-467 行）：循环序列前提已随 M17 消失。其中「`applyCustomFont` 导入后切到 CUSTOM」是仍有效的生产行为，迁移进 `g2_applyCustomFont_deletesOldFile_onlyOnReimport`：在首次 `applyCustomFont` 后补一行 `assertEquals(ReaderFont.CUSTOM, vm.uiState.value.settings.font)`（该校验现测试缺失）。

### 2.3 验收标准

- `grep -rn "cycleLineHeight\|cycleFont\|cycleTheme\|toggleWrap\|common_switch" app/src` 仅剩 `fun nextChunk`（`PagedTextLoader`，无关同名不同物）与历史注释之外的零残留——即本批对象的引用必须为零。
- `./gradlew testDebugUnitTest` 全绿（基线 291/0/1skip，删 2 个测试后应为 289/0/1skip，以实际为准但**不得有失败**）。
- `./gradlew compileDebugAndroidTestKotlin` 编译通过。
- 不新增任何文件（除本简报与 Kimi 的审查/验收记录外）、不改 fixtures、不碰 `docs/` 权威文档。

## 3. 执行约束

- shell 命令**单条执行**，不拼复合命令（权限前缀误杀教训）。
- 不执行任何 git 写操作（add/commit/push 均被 deny，也不要尝试）。
- 完成后输出：改动文件清单 + 每条验收标准的验证结果（贴关键输出）。
