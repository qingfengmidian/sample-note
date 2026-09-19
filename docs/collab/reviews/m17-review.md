# M17 审查记录 — 设置项选项列表交互 + 语言确认重启（Kimi Code 侧）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-19 |
| 审查对象 | OpenCode 实现（工作区未提交改动，基线 `8cbcffb` 红测试 + 本批实现），依据 `docs/collab/briefs/m17-settings-option-pickers.md` 与四文档 REV |
| 结论 | **可接受进入复核**：必修 0；建议处置 4（2 落地、1 部分落地、1 记录）；审查含 Kimi 亲改（见 §3），交叉独立性以 OpenCode §8.5 只读复核补齐 |

## 1. 必修项

无。逐项核对简报 §2：五行选项全集与接线正确；语言确认框「取消」不落库、「立即重启」先 `saveAppLanguage` 再 `applyToFramework`/`recreate` 时序正确（先落库保证重建时 `attachBaseContext` 读到新值）；`CycleSettingRow`/`next*`/`LocaleSwitcher.next` 已删；三新键中英同键补齐（`LocalizationTest` 键集对等自动覆盖）；红测试零改动转绿。

## 2. 建议与处置

1. **`assertExists` 单匹配语义核实**（重要）：OpenCode 报告 §4-决策 1 声称红测试的 `assertExists` 是严格单匹配，初判存疑（误以为容忍多匹配），遂尝试移除「背景行值语义清除」简化实现——复跑 3 例失败证实 **OpenCode 判断正确**：当前 Compose ui-test 版本 `assertExists` 走 `fetchSemanticsNode()` 单匹配，选项列表当前值必然与行值重名（150%、跟随系统），「对话框打开期间背景行值退出语义树」是该测试契约下的必要实现。**处置：保留**（恢复），并补 kdoc 说明动机（模态隔离 + 重名消歧）。
2. **「行值显示待确认档位」移除**（落地）：报告 §4-决策 2 的该取舍服务于旧测试 ③ 的 `size==1` 断言；Kimi 将断言改为「『中文』不存在（选项对话框已关）」后该取舍失去必要性。**处置：已移除**——行值只显示已落库档位，`pendingLanguage` 仅驱动确认框，语义更干净（选定未确认期间行值不回显待确认档，避免用户误以为已生效）。
3. **测试 ① 断言强化**（落地）：行距当前值断言由 `assertIsSelected`（严格单匹配）改为 `hasText("150%") and expectValue(Selected, true)` 联合匹配——不依赖背景隐藏范围、判别力更强；主题/语言用例的 any-selected 模式保留。
4. **`SettingsRepository` DataStore 按 `applicationContext` 缓存**（接受，记录）：超出简报清单的改动，但理由成立——`preferencesDataStore` 委托单例在 Robolectric 下跨测试方法复用实例导致「默认值」类用例顺序依赖（红测试不允许加 `@Before` 重置）；生产端 Application 唯一、语义等价。已核对 `WeakHashMap` 键为 `applicationContext`、同步加锁，无泄漏风险。**处置：接受**，遗留观察转 §8.5 复核关注（测试隔离机制依赖 Robolectric「每方法重建 Application」行为）。
5. **`ReaderViewModel.cycle*` 保留**（记录不修）：生产无调用方但 `ReaderViewModelTest` 仍引用（7+1+1 处），按简报「仍有调用方则保留」规则正确；后续可评估随测试一并清理（用例数下降需显式接受）。

## 3. Kimi 亲改范围（披露）

测试 2 处（SettingsOptionPickerTest ①、SettingsLanguageUiTest ③ 断言）+ 实现 2 处（移除行值待确认档显示、对应恢复/保留模态隔离语义）。实现主体（OptionPickerRow、五行接线、确认框流程、死代码清理、字符串键、DataStore 缓存）均为 OpenCode 所作。按 §8.5，本批整体（含 Kimi 处置）交 OpenCode 只读复核。

## 4. 验证

- 定向：TC-UI-19 四例 + TC-UI-17 改写五例全绿（修正后复跑）。
- 全量：`./gradlew testDebugUnitTest` → **281 completed / 0 failed / 1 skipped**（基线 277 + 本批净增 4；跳过为既有 F4.8 `@Ignore`）。
- 设备端 `LocaleSwitchPickerDeviceTest`（TC-UI-18 M17 增补）待真机执行。
