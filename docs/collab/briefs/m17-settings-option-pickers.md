# M17 任务简报 — 设置项选项列表交互 + 语言确认重启

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-19 |
| 范围 | F4.3/F4.9 交互修订（需求 REV13 / 设计 REV17 §3.7 M17 块 / 测试用例 REV12；决策 D-2026-09-19-01/02） |
| 实现者 | OpenCode（Kimi Code 以 `opencode run` 喂本简报；无 git 写权限，提交由 Kimi 执行） |
| 红测试 | 已先行提交（同 PR 前一提交，见 git log；本批次使其转绿） |

## 1. 背景与目标

设置页语言/默认行距/默认换行模式/默认字体/默认阅读主题五个设置行当前是 `CycleSettingRow`（行 + 当前值 + 「切换」按钮顺次循环），用户看不到可选值全集。改为**选项列表交互**：点击行唤出单选对话框，列出全部可选值并标记当前值，点选即生效（语言行例外，需确认重启）。语言切换当前选中即重建 Activity 闪屏，改为**确认后重启**。

## 2. 具体改动

### 2.1 新组件 `OptionPickerRow`（SettingsScreen.kt）

替换 `CycleSettingRow`。签名建议（实现可微调，行为契约不变）：

```kotlin
@Composable
private fun <T> OptionPickerRow(
    title: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
)
```

- 整行可点（`defaultMinSize(minHeight = 48.dp)`，行尾不再放「切换」按钮；行右侧仍显示当前值文本）。
- 点击 → `AlertDialog`：标题 = 行标题文本；正文 = 选项纵向列表，每项一行 `Row`（`RadioButton(selected = isSelected(option))` + 选项文本），整行可点（≥48dp），点选 → `onSelect(option)` + 关闭对话框。
- 「取消」按钮（`common_cancel`）仅关闭对话框。
- 选项行 RadioButton 自带选中语义，无需额外语义键。

### 2.2 五行接线（选项全集）

| 行 | 选项 | onSelect 行为 |
| --- | --- | --- |
| 语言（`settings_language`） | `AppLanguage.SYSTEM/ZH/EN`（标签 `settings_language_system/zh/en`） | 与当前档相同仅关对话框；不同 → 弹确认框（见 2.3） |
| 默认行距 | `ReaderViewModel.LINE_HEIGHT_STEPS`（120/150/180/200，标签 `"$it%"`） | `repository.save(settings.copy(lineHeightPercent = it))` |
| 默认换行模式 | `WrapMode.WRAP/NO_WRAP`（`reader_auto_wrap`/`reader_no_wrap`） | 同上存 `wrapMode` |
| 默认字体 | `ReaderFont.CODE/SERIF/SANS`（`customFontPath` 非空时追加 CUSTOM，沿用 G2 语义；标签 `settings_font_code`/`reader_font_serif`/`reader_font_sans`/`reader_font_custom`） | 同上存 `font` |
| 默认阅读主题 | `null/DEFAULT/EYE_CARE/SOFT_LIGHT/NIGHT`（`reader_theme_system`/`reader_theme_default`/`reader_theme_eye_care`/`reader_theme_soft`/`reader_theme_night`） | 同上存 `theme` |

后四行点选即持久化（DataStore 写异步，行值订阅 flow 随动），无确认框。

### 2.3 语言确认重启流程

语言行 onSelect（选项 ≠ 当前档）→ 关闭选项对话框 → 弹第二个 `AlertDialog`：

- 标题 `settings_language_restart_title`；正文 `settings_language_restart_message`。
- 「取消」（`common_cancel`）：**不做任何持久化**，关闭。
- 「立即重启」（`common_restart_now`）：`scope.launch { repository.saveAppLanguage(选项); if (!LocaleSwitcher.applyToFramework(context, 选项)) (context as? Activity)?.recreate() }`——**先落库再重建**，与 M16 机制完全一致（33+ 框架重建 / 低版本与框架不可用自行 recreate），仅时机后移至确认。

### 2.4 死代码清理

- 删 `CycleSettingRow`、`SettingsScreen` 私有 `nextLineHeight`/`nextFont`/`nextTheme`、`LocaleSwitcher.next`。
- `ReaderViewModel.cycle*`（cycleLineHeight/cycleFont/cycleTheme 等）：核实调用方——已无调用方则一并删除，仍有调用方（阅读器面板等）则保留并在报告注明。
- `LocaleSwitcher` 其余 API（`tagOf`/`languageOf`/`wrapBaseContext`/`wrap`/`applyToFramework`）、DataStore 键、`MainActivity` 零改动。

### 2.5 新字符串键（`values/strings.xml` 默认英文 + `values-zh/strings.xml` 中文，同键补齐）

```xml
<string name="settings_language_restart_title">Switch language</string>
<string name="settings_language_restart_message">Switching language requires restarting the app. Restart now?</string>
<string name="common_restart_now">Restart now</string>
```

```xml
<string name="settings_language_restart_title">切换语言</string>
<string name="settings_language_restart_message">切换语言需要重启应用，是否立即重启？</string>
<string name="common_restart_now">立即重启</string>
```

「取消」复用既有 `common_cancel`。新增键后 `LocalizationTest`（TC-UI-15 ③ 键集反射对等）会自动覆盖，无需改测试。

## 3. 测试口径（红测试已先行，本批次转绿）

- **新增** `SettingsOptionPickerTest`（Robolectric，`qualifiers="zh"`，TC-UI-19）：行点击 → 对话框列全部选项 + 当前值选中态；点选 → 持久化 + 对话框关闭。行距/主题两例。
- **改写** `SettingsLanguageUiTest`（TC-UI-17 另类）：点语言行 → 选项对话框三项 → 选 English → 确认框出现 → 取消 → 持久值不变；重选 English → 立即重启 → 持久化 EN。
- **删除** `LocaleSwitchTest` ③ 档位循环用例（`LocaleSwitcher.next` 移除）；`SettingsGlobalCycleTest`（行距/主题循环两例）已由 `SettingsOptionPickerTest` 取代，整文件删除。
- **设备端** `LocaleSwitchDeviceTest` 增补真实导航「选项对话框 → 确认框 → 取消不落库」判别力用例（TC-UI-18 M17 增补）。
- 注意 Robolectric `createComposeRule` 下 `LocalContext` 是 Application——点「立即重启」走 `applyToFramework` 返回 false 且 `context as? Activity` 为 null，不会真重建，测试安全。

## 4. 验收标准（DoD）

1. `./gradlew testDebugUnitTest` 全绿（基线 277 + 本批新增/改写，无回归）。
2. `./gradlew assembleDebug` 通过。
3. 死代码清理完成且无残留引用（编译即证）。
4. 五个设置行均为选项对话框交互；语言确认框「取消」不落库、「立即重启」先落库再重建。
5. 阅读器内阅读设置面板（M10 分段/单选）不回归。
6. 报告：`docs/collab/reviews/m17-opencode-report.md`——改动清单、`ReaderViewModel.cycle*` 处置结论、遗留观察。

## 5. 执行约束

- 命令**单条执行**（复合命令触发权限前缀误杀）。
- 注释/报告使用中文，引用 TC-* 编号。
- 不引入新依赖；不动 `LocaleSwitcher` 既有 API 语义与 `MainActivity`。
