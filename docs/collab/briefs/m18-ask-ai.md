# M18 任务简报 — 复制后问 AI（F4.10）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-19 |
| 范围 | F4.10 复制后问 AI（需求 REV14 / 设计 REV18 §3.10 / 测试用例 REV13；方案决策 D-2026-09-19-03） |
| 实现者 | OpenCode（Kimi Code 以 `opencode run` 喂本简报；无 git 写权限，提交由 Kimi 执行） |
| 红测试 | 已先行提交（TC-UT-19 / TC-UI-20 / TC-UI-21，见 git log 前一提交），本批次使其转绿 |

## 0. 红线（不可越界）

不联网、不采集、**不新增权限与依赖**；文本只在用户点按后经系统 `ACTION_SEND` 交给第三方应用；不内置任何问答/模型能力。

## 1. 目标

复制正文文本后，应用在前台时给 Snackbar 提示，用户点按即把「前缀 + 原文」发给**用户指定**的第三方 AI 应用；并保证不打扰（同文不重复提示）、可控（开关/目标应用未选时不动作）。

## 2. 具体改动

### 2.1 设置仓库（`core/settings/SettingsRepository.kt`）

新增三个**独立键**（同 M16 `app_language` 先例，不进 `ReadingSettings`）+ Flow 与 save 方法：

| 键 | 类型 | 默认 | 语义 |
| --- | --- | --- | --- |
| `ask_ai_enabled` | Boolean | `true` | 总开关 |
| `ask_ai_target_package` | String? | 缺失 | 目标应用包名；缺失 = 未选择 |
| `ask_ai_prefix` | String? | 缺失 | **缺失 = 资源默认前缀**；**显式空串 = 原文直发**；非空 = 自定义前缀 |

### 2.2 核心逻辑（新建 `core/askai/AskAi.kt`）

按设计 §3.10 的签名实现（`AskAiTarget` + `AskAi`）：

- `candidates(context)`：`queryIntentActivities(Intent(ACTION_SEND).setType("text/plain"))` → 按包名去重（同包多 Activity 取首个）→ 按 label 排序 → **排除本应用包名**（`context.packageName`）。
- `composePayload(prefix, defaultPrefix, text)`：`prefix == null` → `defaultPrefix + text`；`prefix.isEmpty()` → `text`；否则 `prefix + text`。
- `shouldOffer(enabled, targetPackage, text, lastOffered)`：四项全满足才为 true——开启、目标非空、`text` 非空白、`text != lastOffered`。
- `excerpt(text, max = 20)`：长度 ≤ max 原样；否则取前 max 字符 + `…`。
- `buildSendIntent(targetPackage, payload)`：`ACTION_SEND` + `type = "text/plain"` + `setPackage(targetPackage)` + `EXTRA_TEXT = payload`。
- `send(context, targetPackage, payload)`：`startActivity(buildSendIntent(...))`，捕 `ActivityNotFoundException`/`SecurityException` 等 → 返回 false（不崩）。

### 2.3 主界面宿主与剪贴板监听（`MainActivity.kt`）

- `setContent` 内：`AppTheme { Box { NavHost(...) ; SnackbarHost(hostState, Modifier.align(Alignment.BottomCenter)) } }`（`SnackbarHostState` 经 `remember`）。
- 剪贴板监听封装为私有 composable（如 `AskAiClipboardEffect`）：`LifecycleResumeEffect`（`androidx.lifecycle.compose`，依赖已有）在 RESUMED 注册 `ClipboardManager.OnPrimaryClipChangedListener`、其它状态注销；回调内取 `clipboard.primaryClip` → `coerceToText(context)` → `trim()` → `AskAi.shouldOffer(...)`；通过则更新 `lastOffered` 并 `snackbarHostState.showSnackbar(message, actionLabel, duration = Short)`。
- action（=「问 AI」）点击 → `AskAi.send(context, target, AskAi.composePayload(prefix, 资源默认前缀, text))`；返回 false → 错误 Snackbar「目标应用不可用，请在设置中重新选择」。
- 三个设置值经 `SettingsRepository` 收集（`collectAsStateWithLifecycle`），资源默认前缀取 `stringResource(R.string.ask_ai_default_prefix)`。
- **阅读器菜单兜底**：`ReaderScreen` 新增回调参数 `onAskAi: () -> Unit`，⋮ 菜单在「阅读设置」之后加「问 AI」项（`reader_menu_ask_ai`）；`MainActivity` 传入的处理逻辑：读剪贴板 → 空则提示「剪贴板没有可用文本」→ 未选目标则提示「请先在设置中选择目标应用」→ 否则同 2.3 发送路径（同样失败提示）。

### 2.4 设置页「问 AI」小节（`SettingsScreen.kt`）

位置：「编辑器」小节之后、「阅读默认值」之前。三行：

1. **启用「问 AI」**：`Switch` 行（可参考既有 `AutoSaveRow` 写法自建 `AskAiToggleRow`；**开关 `contentDescription` = `settings_ask_ai_enabled`（与行文本同串）**——红测试经 cd 点击），说明文案为第二行小字；即时 `saveAskAiEnabled`。
2. **目标应用**：复用 M17 的 `OptionPickerRow` —— 值 = 已选 label（从 `candidates` 按包名查得）或「未选择」；选项 = `candidates` 的 label；**无候选时对话框显示空态文案**「未发现可接收文本的应用」；选中 → `saveAskAiTargetPackage(包名)`。
3. **提问前缀**：`BasicTextField`（或 `OutlinedTextField`）+ 说明「留空则原文直发」；**输入框 `contentDescription` = `settings_ask_ai_prefix`（与行文本同串）**——红测试经 cd 聚焦/输入；初值：`prefix ?: 资源默认值`；`onValueChange` 即时 `saveAskAiPrefix(文本)`——**注意语义区分**：用户清空为 `""`（原文直发），而「从未设置过」（null）用资源默认值填充显示；即输入框初始值始终可见（默认前缀或自定义）。

> **红提交内的既有改动（Kimi 所作，披露）**：`AskAi.kt` 接口桩（仅签名 + `TODO()`，实现全部留给本批次）、`SettingsRepository` 三个键与其 Flow/save 方法（纯数据管道，行为语义按 §1 表）；红测试本身不可改。

### 2.5 新字符串键（`values` 默认英文 + `values-zh` 中文，同键补齐）

```xml
<string name="settings_ask_ai_section">Ask AI</string>
<string name="settings_ask_ai_enabled">Enable \"Ask AI\"</string>
<string name="settings_ask_ai_enabled_desc">Shows an in-app hint after you copy text; sends the content to your chosen app only when you tap (this app stays offline)</string>
<string name="settings_ask_ai_target">Target app</string>
<string name="settings_ask_ai_target_none">Not selected</string>
<string name="settings_ask_ai_no_candidates">No app found that can receive text</string>
<string name="settings_ask_ai_prefix">Question prefix</string>
<string name="settings_ask_ai_prefix_default">Explain: </string>
<string name="settings_ask_ai_prefix_desc">Leave empty to send the text as is</string>
<string name="ask_ai_snackbar_action">Ask AI</string>
<string name="ask_ai_snackbar_message">Copied: %1$s</string>
<string name="ask_ai_clipboard_empty">No text available in the clipboard</string>
<string name="ask_ai_no_target">Choose a target app in Settings first</string>
<string name="ask_ai_send_failed">Target app unavailable; choose another in Settings</string>
<string name="reader_menu_ask_ai">Ask AI</string>
```

zh 对应值：问 AI / 启用「问 AI」/ 复制文本后应用内提示，点按才把内容发给所选应用（应用自身不联网）/ 目标应用 / 未选择 / 未发现可接收文本的应用 / 提问前缀 / 请解释：/ 留空则原文直发 / 问 AI / 已复制：%1$s / 剪贴板没有可用文本 / 请先在设置中选择目标应用 / 目标应用不可用，请在设置中重新选择 / 问 AI。

> 键名注意：默认前缀键在设计中写作 `ask_ai_default_prefix`，本文简报名为 `settings_ask_ai_prefix_default` —— **以实现时二者取一为准并在报告中注明**（`LocalizationTest` 键集对等会自动覆盖）。

## 3. 测试口径（红测试已先行）

- **TC-UT-19** `core/askai/AskAiTest.kt`（Robolectric）：`composePayload` 三态、`buildSendIntent` 字段、`shouldOffer` 四门控、`excerpt` 截断、`candidates` 经 `ShadowPackageManager` 注入候选 → 去重/排序/排除自身。
- **TC-UI-20** `ui/SettingsAskAiTest.kt`（Robolectric，`qualifiers="zh"`）：小节与三行存在；开关默认开、关闭即持久化；目标应用未选显示「未选择」、无候选时对话框空态；前缀输入即时持久化（清空 → 回读空串）。
- **TC-UI-21** `androidTest/.../AskAiDeviceTest.kt`（设备端）：阅读器 ⋮ 菜单含「问 AI」项（语义断言）；`candidates(context)` 包名唯一且不含本应用。
- 红测试**不许改**；若认为契约与简报冲突，按简报修正实现并在报告中说明。

## 4. 验收标准（DoD）

1. `./gradlew testDebugUnitTest` 全绿（基线 281 + 本批新增，无回归）。
2. `./gradlew assembleDebug` 通过；`./gradlew compileDebugAndroidTestKotlin` 通过（androidTest 编译路径必须补验——M17 曾在此漏过 import）。
3. 三个新键中英同键补齐（`LocalizationTest` 键集对等绿）。
4. 设置页三行行为符合 §2.4；开关关闭 / 未选目标时**不监听、不提示、不发送**。
5. 阅读器 ⋮ 菜单「问 AI」项存在且走同一发送路径。
6. 报告：`docs/collab/reviews/m18-opencode-report.md`——改动清单、前缀键名取舍、`ReaderScreen` 回调签名变更、遗留观察。

## 5. 执行约束

- 命令**单条执行**（复合命令/`for` 循环/重定向组合会触发权限拦截、导致会话中止——M17 已两次踩坑）；禁止创建临时/探针测试文件；禁止阅读第三方库源码（`unzip`/`jar` 单条允许但不必要）。
- 注释与报告使用中文，引用 TC-* 编号；不动 `LocaleSwitcher`、`ReadingSettings` 既有字段、既有测试。
- OpenCode 无 git 写权限；提交由 Kimi 执行。
