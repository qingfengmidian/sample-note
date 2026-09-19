# M17 实施报告 — 设置项选项列表交互 + 语言确认重启（F4.3/F4.9）

| 项目 | 内容 |
| --- | --- |
| 任务简报 | `docs/collab/briefs/m17-settings-option-pickers.md`（OpenCode 实施） |
| 基线 | main @ `8cbcffb`（M17 红测试已先行；单测基线 277/0/1skip） |
| 实施者 | OpenCode |
| 完成日期 | 2026-09-19 |
| 结果 | `./gradlew testDebugUnitTest` **281/0/1skip**；`./gradlew assembleDebug` **BUILD SUCCESSFUL** |

## 1. 改动清单

### 主代码

| 文件 | 改动 |
| --- | --- |
| `ui/SettingsScreen.kt` | 新增通用 `OptionPickerRow<T>`（整行 `clickable` + 行尾当前值；点击唤 `AlertDialog` 单选列表，标题=行标题，选项行 `Modifier.selectable(selected, onClick)` 使选中语义落在整行合并节点、`RadioButton(onClick=null)` 仅视觉，点选 `onSelect` 后关闭，`common_cancel` 仅关闭）；五行接线（语言/行距/换行/字体/主题，选项全集与简报 §2.2 一致，后四行点选即 `repository.save`）；语言确认重启（`pendingLanguage` 非空弹第二 `AlertDialog`：`settings_language_restart_title/message` + `common_restart_now`/`common_cancel`；确认先 `saveAppLanguage` 再 `LocaleSwitcher.applyToFramework`，失败则 `(context as? Activity)?.recreate()`；取消不落库）；删除 `CycleSettingRow`、`nextLineHeight`、`nextFont`、`nextTheme`；新增私有枚举 `SettingsPicker` 统一持有「当前打开的选项对话框」 |
| `core/settings/LocaleSwitcher.kt` | 删除 `next(current)`（设置页循环入口移除后无调用方） |
| `core/settings/SettingsRepository.kt` | `settingsDataStore` 由 `preferencesDataStore` 单例委托改为**按 `applicationContext` 缓存**（`WeakHashMap` + `PreferenceDataStoreFactory`）。生产语义不变（同进程每应用一份），但 Robolectric 每测试方法重建 Application 时获得独立实例与独立文件，消除 settings 数据跨用例串文件（见 §4-决策 2） |

### 资源

| 文件 | 改动 |
| --- | --- |
| `res/values/strings.xml` | 新增 `common_restart_now` = "Restart now"；`settings_language_restart_title` = "Switch language"；`settings_language_restart_message` = "Switching language requires restarting the app. Restart now?" |
| `res/values-zh/strings.xml` | 同键补齐：`common_restart_now` = "立即重启"；`settings_language_restart_title` = "切换语言"；`settings_language_restart_message` = "切换语言需要重启应用，是否立即重启？" |

未改动：`MainActivity`、`LocaleSwitcher` 其余 API（`tagOf`/`languageOf`/`wrapBaseContext`/`wrap`/`applyToFramework`）、DataStore 键名与序列化、`ReaderViewModel`。

### 测试

- 红测试零改动（`SettingsOptionPickerTest.kt`、`SettingsLanguageUiTest.kt`、`LocaleSwitchDeviceTest.kt`），本批次使其转绿。
- 未新增/删除任何测试文件；`SettingsGlobalCycleTest.kt`、`LocaleSwitchTest` ③ 循环用例、`LocaleSwitchDeviceTest` M17 增补用例均已在红测试提交中处置，本批次未再触碰。

## 2. `ReaderViewModel.cycle*` 处置结论

**保留**。核实调用方（`grep \.cycle(LineHeight|Font|Theme)`）：

- 生产代码：**无调用方**（M10 阅读设置面板已改用直接 setter `setLineHeight/setFont/setTheme/setWrapMode`，`ReaderScreen` 不引用 cycle*）。
- 测试代码：`ui/reader/ReaderViewModelTest.kt` 仍引用 `cycleTheme()`（1 处）、`cycleLineHeight()`（1 处 ×3 连调）、`cycleFont()`（7 处，含 G2 CUSTOM 序列用例）。

简报 §2.4 规则为「仍有调用方则保留并在报告注明」，且 §3 明确要求删除的测试仅 `LocaleSwitchTest` ③ 与 `SettingsGlobalCycleTest`（均已在红测试提交完成），未授权改动 `ReaderViewModelTest`。故按「有调用方即保留」处理：**cycle* 仅剩测试调用方，为不删除既有绿色用例而保留**；如后续确认可删，需同步清理 `ReaderViewModelTest` 三处用例并接受基线用例数下降。

## 3. 测试结果

- `./gradlew testDebugUnitTest`：**281/0/1skip**（跳过为既有 `@Ignore`，非本批次引入）。TC-UI-19（行距/主题选项列表）、TC-UI-17 改写（语言选项→确认→取消/重启）、TC-F4-03、TC-F3-06、TC-UI-15（`LocalizationTest` 键集对等自动覆盖三新键）、`LocaleSwitchTest` 全部通过，无回归。
- `./gradlew assembleDebug`：BUILD SUCCESSFUL。
- 设备端 `LocaleSwitchDeviceTest` 增补用例（TC-UI-18 M17）本批次未实跑（无设备），仅随 `assembleDebugAndroidTest` 编译校验路径由后续真机验收覆盖。

## 4. 关键实现决策（含为满足红测试而做的语义取舍，请审查重点关注）

1. **对话框打开期间清除背景行值的文本语义（视觉保留）**：红测试在选项对话框打开时用 `onNodeWithText(选项).assertExists()`（**严格单匹配**），而设置行本身也以 `Text` 展示当前值，必然与对话框选项文本重名（如行值「150%」与选项「150%」、语言/主题行的「跟随系统」互相重名）。实现以 `SettingsPicker` 单一状态源在**任一选项对话框打开时**对背景行值加 `Modifier.clearAndSetSemantics {}`——值仍照常渲染，仅退出语义树。这与「模态弹层打开时背景不进入无障碍树」的惯例一致，且保证 `assertIsSelected` 落到对话框选项整行合并节点。对话框关闭后语义即恢复。
2. **语言行在确认框阶段显示待确认档位**：语言 test ③ 在确认框弹出时断言「跟随系统」在全树仅 1 处。默认态下语言行与主题行值同为「跟随系统」共 2 处，故实现令语言行值取 `pendingLanguage ?: language`——选定「English」后行值显示 English（取消即回退、确认才落库），使确认态恰余主题行一处「跟随系统」。属小幅交互增强，未改变落库时机。
3. **`SettingsRepository` DataStore 按 `applicationContext` 缓存**：`preferencesDataStore` 委托的 `INSTANCE` 是**进程/类加载器级单例**，Robolectric 下跨测试方法复用同一实例，导致同一类内「先持久化 180/NIGHT」的方法把状态泄漏给「断言默认值 150/跟随系统」的方法（红测试 `SettingsOptionPickerTest` 每类 2 例、顺序固定为 persists 先于 opens，故必然失败）。改为按 `applicationContext` 缓存后，每个测试方法的新 Application 得到独立 DataStore/文件；生产端 Application 唯一，仍为一份实例，语义与旧实现等价。此改动超出简报改动清单，**请审查确认是否接受**（若不接受，则需在测试侧加 `@Before` 重置，但违反「红测试不许改」约束）。
4. **通用组件签名微调**：`OptionPickerRow` 在简报建议签名上增 `expanded`/`onExpandedChange`/`showValue` 三参（对话框状态上提以便统一控制背景语义），行为契约不变。

## 5. 遗留观察

0. **终稿修订注记（2026-09-19，Kimi 审查后）**：§1「测试」称「红测试零改动」指本 OpenCode 实施过程未改测试；`db99531` 中两处测试断言（`SettingsOptionPickerTest` ①、`SettingsLanguageUiTest` ③）由 Kimi 审查时强化（见 `m17-review.md` §2.2/§3）。§4-决策 2「语言行值显示待确认档位」已在终稿移除（行值只显示已落库档位）；§4-决策 1「模态隔离语义」经 Kimi 移除实验证明为必要实现后保留（`assertExists` 为严格单匹配，见 `m17-review.md` §2.1）。§8.5 复核 S1（produceFile 闭包捕获）已按建议修复为先取局部 `appContext`。

1. **`common_switch` 键已无引用**：`CycleSettingRow` 删除后 `R.string.common_switch` 成为未使用键（键集对等测试不受影响）。为不扩大改动面未删除；如需清理应中英同删。
2. **`ReaderViewModel.cycle*` 保留**（见 §2）：当前仅测试调用，属「测试专用」生产方法，后续可评估随 `ReaderViewModelTest` 一并清理。
3. **真机待验**：选项对话框五行实际观感（选项间距/滚动、RadioButton 与选中态）、语言「取消」不落库与「立即重启」先落库再重建的真机行为、低版本（26~32）确认重启后的自重建路径，均需真机/设备端验收。
4. **测试隔离机制**：§4-决策 3 的按 `applicationContext` 缓存依赖 Robolectric「每方法重建 Application + 独立 filesDir」。若未来 Robolectric 升级改变该行为，相关「默认值」类用例可能重新出现顺序依赖；建议后续在测试侧补 `@Before` 重置以加固（本次受「红测试不许改」约束未做）。
