# M16 OpenCode 复核处置 — 应用内语言切换（F4.9）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-18 |
| 对象 | `docs/collab/reviews/m16-opencode-review.md`——OpenCode 按协同规范 §8.5 做的只读复核：**无必修项，总体结论「可接受进入收口」**，独立重跑单测 276/0/1skip |
| 总体处置 | 建议 7 项：**6 项已修、1 项接受缺口并记录**；复核「未验证项」#2（compileDebugAndroidTestKotlin）已由本人补跑通过 |
| 复验 | 单测 **277 / 0 failed / 1 skipped**（+键级隔离 1 例）；`compileDebugAndroidTestKotlin` 通过 |

## 一、建议逐条处置

| # | 复核建议 | 处置 | 证据 |
| --- | --- | --- | --- |
| 1 | `MainActivity.kt` 注释引用已删除的 `needsManualRecreate`（返工漏改） | ✅ 已修 | 注释改为「`applyToFramework`（返回 false 时由 UI 自行 recreate）」 |
| 2 | TC-UI-17 编号在文档与测试注释间错位一位 | ✅ 已修 | `docs/测试用例.md` 该行重写：①~⑥ 与 `LocaleSwitchTest` 注释一一对应，设置页用例（`SettingsLanguageUiTest`）另述不编号 |
| 3 | `<33`/框架不可用的 UI 自重建分支无直接自动化回归 | ⏸ **接受缺口并记录**（见 §二） | 机制已由测试 ⑥ 固化；该判定行暂无受支持的 compose 层直驱方案 |
| 4 | 设备端包装例判别力依赖系统语言非英文（CI 英文模拟器会假绿） | ✅ 已修 | `LocaleSwitchDeviceTest` 改为「先测无覆盖的基础 locale，再取**相反**语言作覆盖目标」，中英文系统均有判别力（行为待真机复跑确认） |
| 5 | 设备端用例缺 `@Before` 前置清理 | ✅ 已修 | `@Before`/`@After` 同构清理（DataStore + 33+ 框架态） |
| 6 | `save(ReadingSettings)` 与 `app_language` 键级隔离无显式测试 | ✅ 已修 | `SettingsRepositoryTest.appLanguage_isolatedFromReadingSettings`（双向隔离断言，+1 例） |
| 7 | `SettingsScreen.kt` 内联全限定名 `android.app.Activity` | ✅ 已修 | 补 `import android.app.Activity` |

## 二、建议 3（接受缺口）的完整理由

`SettingsScreen.kt` 的判定行 `if (!LocaleSwitcher.applyToFramework(context, next)) recreate()` 在自动化中零直接覆盖。不补的原因：

1. 其依赖的两个机制均有覆盖：`applyToFramework` 在 API<33 返回 false（测试 ⑥ 断言）、`recreate()` 后 `attachBaseContext` 按新持久值重新包装（测试 ⑥ 的 buildActivity/recreate 断言）；
2. compose 测试宿主上直驱 `Activity.recreate()` 无受支持路径——`createComposeRule` 的宿主 Activity 重建后 composition 丢失，测试自身先崩；
3. CI 仪器任务（API 26 模拟器）+ 建议 4 修复后的设备端 ①，在平台层对包装路径提供判别式覆盖；
4. **重估触发**：未来需要 `<33` 的 UI 层自动化回归时，再评估 `createAndroidComposeRule` + `ActivityScenario` 组合方案。

## 三、对复核其余结论的回应

- **§3.4 红态留痕**：认同「⑤/⑥ 自然红→绿未在提交历史中单独留下、依赖空壳反证」。空壳反证完整记录在 `5c6178c` 提交说明与 `build/probe/redproof.log`（6/6 全红）。**流程改进（后续批次执行）**：返工类修复把「测试返工」与「实现返工」拆成两个提交，保留自然红态可查。
- **§四 遗漏边界表**：全部认可。「DataStore 损坏后设置页收集仍抛 `CorruptionException`」属既有风险类别（M16 前 `settings` flow 即如此），记录不修；「33+ OEM 静默忽略 `setApplicationLocales`」为设计接受的剩余风险（见 D-2026-09-18-01 补记），后续版本可用框架态回读补偿。
- **§五 未验证项**：#2 已由本人补跑 `./gradlew compileDebugAndroidTestKotlin` 通过；#1（真机）维持待真机重连；#3（空壳反证）有日志留档；#4（性能测量）维持设计取舍记录。

## 四、收口状态

- 审查链闭合：Kimi 侧审查（必修 1 假绿已返工）→ **OpenCode §8.5 交叉复核（无必修，建议 7 已处置）** → 单测 277/0/1skip、androidTest 可编译。
- 剩余均为真机/人工环节（不因此复核豁免）：TC-UI-18 真机复跑 + D14 走查（待真机重连）、中英各一遍 P0 回归、用户终审、`m16` tag。
