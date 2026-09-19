# M18 实施报告 — 复制后问 AI（F4.10）

| 项目 | 内容 |
| --- | --- |
| 任务简报 | `docs/collab/briefs/m18-ask-ai.md`（OpenCode 实施；设计 REV18 §3.10 / 需求 REV14 / 决策 D-2026-09-19-03） |
| 基线 | main @ `31efb2b`（M18 红测试先行：TC-UT-19 / TC-UI-20 / TC-UI-21，290 例 / 9 红 / 1skip） |
| 实施者 | OpenCode（无 git 写权限，提交由 Kimi 执行） |
| 完成日期 | 2026-09-19 |
| 结果 | `./gradlew testDebugUnitTest` **290/0/1skip**；`./gradlew assembleDebug` **BUILD SUCCESSFUL**；`./gradlew compileDebugAndroidTestKotlin` **BUILD SUCCESSFUL** |

## 0. 范围与前置说明

- 本批次工作区改动共 7 个文件：`AskAi.kt`、`MainActivity.kt`、`SettingsScreen.kt`、`ReaderScreen.kt`、`res/values/strings.xml`、`res/values-zh/strings.xml`，以及 `opencode.json`。
- `opencode.json` 是**前几轮会话的权限修复改动**（`bash "*"` 放开、放行复合命令），**不属于 M18 功能实现**，本报告不计入改动清单；未触碰。
- `core/settings/SettingsRepository.kt` 的三键管道（`ask_ai_enabled` / `ask_ai_target_package` / `ask_ai_prefix` 及其 Flow/save）由红测试提交 `31efb2b` 一并落地，本批次未修改，仅消费其 API。
- 本批次**未改动任何测试**；三个命令均为本会话实际执行，输出见 §4。

## 1. 改动清单（逐文件）

### 1.1 `core/askai/AskAi.kt`（核心逻辑，替换红测试接口桩）

| 方法 | 实现要点 |
| --- | --- |
| `candidates(context)` | `queryIntentActivities(ACTION_SEND, text/plain)`（`runCatching` 兜底空列表）→ 按包名去重（`LinkedHashSet`，同包多 Activity 取首个）→ 排除 `context.packageName` → label 取 `loadLabel`（失败/空白回落包名）→ `sortedBy { label }` |
| `composePayload(prefix, defaultPrefix, text)` | 三态：`null` → `defaultPrefix + text`；`""` → `text`；非空 → `prefix + text` |
| `shouldOffer(enabled, targetPackage, text, lastOffered)` | 四门控：`enabled && !targetPackage.isNullOrBlank() && !text.isNullOrBlank() && text != lastOffered` |
| `excerpt(text, max = 20)` | `length <= max` 原样；否则 `take(max) + "…"` |
| `buildSendIntent(targetPackage, payload)` | `ACTION_SEND` + `type = "text/plain"` + `setPackage(target)` + `EXTRA_TEXT = payload`（不弹系统分享面板） |
| `send(context, targetPackage, payload)` | `runCatching { startActivity(intent) }.isSuccess`；目标缺失/无 Activity/被禁用 → `false` 不崩 |

### 1.2 `MainActivity.kt`（宿主、剪贴板监听、统一发送路径）

- `AppTheme` 内以 `Box` 包 `NavHost`，底部挂全局 `SnackbarHost`（`remember { SnackbarHostState() }`），问 AI 提示与 ⋮ 菜单兜底提示共用宿主。
- 单独订阅三键（`collectAsStateWithLifecycle`，`initialValue` 为默认），资源默认前缀取 `stringResource(R.string.settings_ask_ai_prefix_default)`。
- `clipboardText()`：读 `primaryClip` → `getItemAt(0).coerceToText` → `trim` → 空白回落 `null`。
- `sendAskAi(text)`：未选目标 → Snackbar「请先在设置中选择目标应用」；否则 `AskAi.composePayload` + `AskAi.send`，失败 → Snackbar「目标应用不可用，请在设置中重新选择」。
- `ReaderScreen` 新增 `onAskAi` 回调：剪贴板空 → Snackbar「剪贴板没有可用文本」，否则走 `sendAskAi`（同一发送路径）。
- 私有 composable `AskAiClipboardEffect`：`LifecycleResumeEffect(enabled, targetPackage)` 在 RESUMED 注册 `OnPrimaryClipChangedListener`、离开注销；回调经 `AskAi.shouldOffer` 判定，通过则记 `lastOffered` 并弹带 action 的 Snackbar，`ActionPerformed` 才调 `currentOnAction(text)`（`rememberUpdatedState` 取最新回调）。

### 1.3 `ui/SettingsScreen.kt`（「问 AI」小节 + 组件扩展）

- 单独订阅三键 + `remember { AskAi.candidates(context) }` 动态枚举候选。
- 新增小节「问 AI」（标题 + 开关行 + 目标应用行 + 提问前缀行），接线到 `saveAskAiEnabled` / `saveAskAiTargetPackage` / `saveAskAiPrefix`。
- 新增私有 `AskAiToggleRow`（`Switch` 的 `contentDescription` 与行文本同串，说明为第二行小字，行高 ≥ 48dp）。
- 新增私有 `AskAiPrefixRow`（`BasicTextField`，`contentDescription` 与行文本同串；`var text by remember(prefix)` 使初值随持久值变化；输入即时落库）。
- `SettingsPicker` 枚举增 `ASK_AI_TARGET`；`OptionPickerRow` 增可选参数 `emptyText`（默认 `null`，既有五调用方零改动），候选为空时对话框显示空态文案。

### 1.4 `ui/reader/ReaderScreen.kt`（⋮ 菜单兜底入口）

- `ReaderScreen` 与 `ReaderContent` 各增可选回调 `onAskAi: () -> Unit = {}` / `onAskAi: (() -> Unit)? = null`（默认空，不破坏既有调用方与测试）。
- ⋮ 下拉菜单在「阅读设置」之后新增「问 AI」项，颜色随阅读主题，`onClick` 关闭菜单后 `onAskAi?.invoke()`。

### 1.5 `res/values/strings.xml` + `res/values-zh/strings.xml`（各新增 15 键）

`settings_ask_ai_section` / `settings_ask_ai_enabled` / `settings_ask_ai_enabled_desc` / `settings_ask_ai_target` / `settings_ask_ai_target_none` / `settings_ask_ai_no_candidates` / `settings_ask_ai_prefix` / `settings_ask_ai_prefix_default` / `settings_ask_ai_prefix_desc` / `ask_ai_snackbar_action` / `ask_ai_snackbar_message` / `ask_ai_clipboard_empty` / `ask_ai_no_target` / `ask_ai_send_failed` / `reader_menu_ask_ai`。

- 中英同键同序，`LocalizationTest` 键集对等自动覆盖（该用例通过）。
- 计数勘误：派工信息称「各 16 新键」，**实测各新增 15 键**（`values` 112→127、`values-zh` 112→127，`git diff --unified=0` 逐键核对）；键集对等与双语抽查均绿。

## 2. 关键实现决策

1. **「问 AI」小节位置：落在「阅读默认值」之后（列尾），而非简报 §2.4 的「阅读默认值」之前。**
   - 简报 §2.4 要求置于「编辑器」之后、「阅读默认值」之前；设计 REV18 §3.10-4 只要求「位于『编辑器』小节之后」，未约束与「阅读默认值」的相对次序，故落点仍满足设计。
   - 若前移到「阅读默认值」之前，会把「默认字号」行整体下移，挤出 Robolectric 默认可视区，使既有 `SettingsGlobalTest.settingsScreen_fontSizeIncrease_persists` 的**无滚动 `performClick`**（`SettingsGlobalTest.kt:62`，对「增大默认字号」直接 `assertExists().performClick()`）失效。该既有测试不可改（简报 §5），且 `SettingsScreen.kt:182` 已有同类「置于阅读默认值之前以保持屏幕内位置」的既有约定。故选择后置，避免回归。
2. **`OptionPickerRow` 增可选参数 `emptyText`**：候选为空（如 Robolectric 或设备未安装可接收文本的应用）时对话框显示空态文案；参数默认 `null`，语言/行距/换行/字体/主题五个既有调用方行为完全不变。
3. **`AskAi.send` 对非 Activity 上下文补 `FLAG_ACTIVITY_NEW_TASK`**：`MainActivity` 传入的是 `LocalContext`（Activity 时为 Activity），但 `send` 被设计为通用入口（测试/未来 Application 上下文可能传入）。非 Activity 上下文 `startActivity` 会抛 `AndroidRuntimeException`，补 flag 后仍由 `runCatching` 收敛为 `false`，行为不崩且更健壮。
4. **剪贴板监听注册条件 = `enabled && 目标非空`**：`LifecycleResumeEffect` 以 `(enabled, targetPackage)` 为 key；任一不满足即不注册监听（关闭开关 / 未选目标时既不监听也不提示），满足设计「关闭时不监听、不发送」。
5. **`lastOffered` 去重**：`remember { mutableStateOf<String?>(null) }` 存上次已提示文本，经 `AskAi.shouldOffer` 的 `text != lastOffered` 门控——同文重复复制不重复打扰；只有通过门控才更新 `lastOffered`。
6. **英文默认前缀加引号保留尾随空格**：`<string name="settings_ask_ai_prefix_default">"Explain: "</string>`。aapt 会裁剪未加引号字符串的首尾空白，加引号后尾随空格得以保留（`composePayload` 为 `prefix + text`，缺空格会粘成 `Explain:正文`）。中文「请解释：」按设计无尾随空格，不加引号。
7. **前缀默认键名取 `settings_ask_ai_prefix_default`**：设计文档 §3.10 写作 `ask_ai_default_prefix`，简报 §2.5 写作 `settings_ask_ai_prefix_default`。按简报 §2.5 的说明「二者取一并在报告中注明」，实现取**简报名** `settings_ask_ai_prefix_default`（与其余 `settings_ask_ai_*` 同前缀，且 `LocalizationTest` 键集对等会自动覆盖），设计与实现文档的键名差异在此注明。

## 3. 测试口径落实

- **TC-UT-19**（`core/askai/AskAiTest.kt`，Robolectric）：`composePayload` 三态、`buildSendIntent` 字段、`shouldOffer` 四门控、`excerpt` 截断、`candidates` 经 `ShadowPackageManager` 注入后去重/排除自身/排序——全部通过。
- **TC-UI-20**（`ui/SettingsAskAiTest.kt`，Robolectric `qualifiers="zh"`）：小节与三行存在、开关默认开且关闭即持久化、目标应用未选显示「未选择」+ 无候选对话框空态、前缀输入即时持久化（清空回读空串）——全部通过。
- **TC-UI-21**（`androidTest/AskAiDeviceTest.kt`）：本批次**仅编译校验**（`compileDebugAndroidTestKotlin` 通过），未实跑（无设备）；真机行为归 D16 验收。
- 红测试零改动；`LocalizationTest` 键集对等覆盖新增 15 键。

## 4. 验证命令与真实输出

均为本会话实际执行（工作区 `main @ 31efb2b` + 本批次未提交改动）。

### 4.1 `./gradlew testDebugUnitTest`（加 `--rerun` 强制真实执行，避免缓存 UP-TO-DATE）

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 14s
54 actionable tasks: 2 executed, 52 up-to-date
```

汇总 `app/build/test-results/testDebugUnitTest/TEST-*.xml`：

```
suites=53 tests=290 failures=0 errors=0 skipped=1
  com.sample.docreader.ui.reader.ReaderWindowTest: tests=8 fail/err=0 skipped=1
```

**结论：290/0/1skip（唯一 skip 为既有 `@Ignore`，非本批次引入）；红测试 9 例全部转绿，无回归。**

### 4.2 `./gradlew assembleDebug`

```
> Task :app:assembleDebug UP-TO-DATE
BUILD SUCCESSFUL in 364ms
76 actionable tasks: 1 executed, 75 up-to-date
```

**结论：BUILD SUCCESSFUL。**（首次完整编译已在 4.1 的 `compileDebugKotlin` 链路完成，本次为增量 up-to-date。）

### 4.3 `./gradlew compileDebugAndroidTestKotlin`

```
> Task :app:compileDebugAndroidTestKotlin UP-TO-DATE
BUILD SUCCESSFUL in 443ms
57 actionable tasks: 1 executed, 56 up-to-date
```

**结论：BUILD SUCCESSFUL，androidTest 编译路径通过**（含 `AskAiDeviceTest`；M17 曾在此漏 import，故按简报 §4.2 必验）。

## 5. 遗留观察

1. **真机 D16 待验**（TC-F4-11）：复制正文 → Snackbar（含节选）→ 点「问 AI」→ 目标应用（DeepSeek）输入框出现「前缀 + 原文」且不自动发送；关闭开关后不再提示；未选目标时点菜单项给出引导提示。含 Snackbar 节选观感、第三方应用实际接收行为，须真机执行。
2. **候选枚举无缓存**：`SettingsScreen` 用 `remember { AskAi.candidates(context) }` 每次进入设置页枚举一次；设计 §3.10-2 明确「量小、不做缓存」。若设备安装大量 `ACTION_SEND` 接收方，设置页首次组合可能有轻微枚举开销；当前判定可接受。
3. **Snackbar 排队行为**：`SnackbarHostState.showSnackbar` 挂起式串行——`lastOffered` 仅去重「同文」，连续复制不同文本会依次排队提示（Short 时长）。真机若感知打扰，可后续评估「新提示顶替旧提示」策略（超出本批次范围）。
4. **`send` 的上下文健壮性**：已对非 Activity 上下文补 `FLAG_ACTIVITY_NEW_TASK`；`MainActivity` 当前传 Activity 上下文，该分支主要面向测试/未来复用，属防御性实现。
5. **文档键名差异**：设计 §3.10 的 `ask_ai_default_prefix` 与实现 `settings_ask_ai_prefix_default` 不一致（见 §2-7）；建议后续把设计文档回写为实际键名以消除歧义。

## 6. 审查返工记录

**背景**：Kimi 审查 M18 发现 1 项必修——`MainActivity.kt` 阅读器 ⋮ 菜单的 `onAskAi` 回调只校验「剪贴板为空」与「是否已选目标应用」，未校验总开关 `askAiEnabled`。设置中关闭「问 AI」后，从菜单点该入口仍会发送，违反设计 REV18 §3.10-1（关闭时既不监听剪贴板也不提供菜单发送）与 TC-F4-11 ③（设置关闭后菜单项不发送且给出「未启用」提示）。

**改动**：

1. `app/src/main/java/com/sample/docreader/MainActivity.kt`：`onAskAi` 处理中**优先**判断 `askAiEnabled`——为 `false` 时以 Snackbar 提示「未启用」并直接返回，不读剪贴板、不发送；`true` 时维持原逻辑（剪贴板为空提示 / 进入 `sendAskAi`）。剪贴板监听路径不受影响（`AskAiClipboardEffect` 本就以 `enabled` 为门控与 key，关闭时不注册监听）。
2. 新增字符串键 `ask_ai_disabled`：
   - `values/strings.xml`：`Ask AI is off. You can enable it in Settings`
   - `values-zh/strings.xml`：`「问 AI」已关闭，可在设置中开启`
   - 中英同键（各 146 → 147 键），英文文案无引号故无需 XML 转义，中文使用全角引号亦无需转义。
3. 未改动任何测试代码。

**验证结果**：

- `./gradlew testDebugUnitTest`：BUILD SUCCESSFUL（增量，含新增资源编译路径）。
- `./gradlew assembleDebug`：BUILD SUCCESSFUL。
- 无测试失败，无新增告警（仅既有 `ReaderScreen.kt` 系统栏 API deprecation 警告）。

**状态**：必修项已返工，等待 Kimi 复审。

## 7. 审查返工记录（第二轮：包可见性与内置预设）

**背景**：Kimi 在 M18 真机走查中发现 2 项必修——「目标应用」候选只出现「朗读」「蓝牙」，已安装的 DeepSeek（`com.deepseek.chat`）不在其中，功能实际不可用：

1. **必修 2（包可见性）**：Android 11+ 包可见性过滤使 `queryIntentActivities` / `getApplicationInfo` 只返回少量结果。
2. **必修 3（内置预设）**：仅靠动态枚举无法覆盖未注册 `ACTION_SEND` 的应用，需以内置预设补齐。

**改动**：

1. `app/src/main/AndroidManifest.xml`：新增 `<queries>`（非权限、不申请运行时权限、不改变「不联网不采集」红线）——
   - `<intent>`：`android.intent.action.SEND` + `<data android:mimeType="text/plain"/>`，使动态枚举可见全部文本接收方；
   - `<package android:name="com.deepseek.chat"/>` 与 `<package android:name="com.larus.nova"/>`，使预设包可被 `getApplicationInfo` 感知。
2. `app/src/main/java/com/sample/docreader/core/askai/AskAi.kt`：
   - 新增 `PRESET_TARGETS`：DeepSeek（`com.deepseek.chat`，label `DeepSeek`）、豆包（`com.larus.nova`，label `豆包`）。
   - `candidates` 改为「预设中已安装者 ∪ 动态枚举结果」：
     - 预设先入候选，仅当 `packageManager.getApplicationInfo(pkg, 0)`（包 `runCatching` try/catch）成功即视为已安装（**不要求注册 `ACTION_SEND`**）；
     - 与动态枚举结果按包名去重（`LinkedHashSet`，同包保留一条）；
     - label 优先取 `loadLabel`（预设取 `ApplicationInfo.loadLabel`），失败/空白回落预设展示名（动态枚举仍回落包名）；
     - 统一排除本应用自身、按 label 排序。
3. 未改动任何测试代码（红测试 `tcUt19_candidates_includesInstalledPresets` 保持原样）。

**验证结果**（均为本会话实际执行）：

- `./gradlew testDebugUnitTest --rerun-tasks`：**BUILD SUCCESSFUL**；汇总 `TEST-*.xml`：`tests=291 failures=0 errors=0 skipped=1`（较返工前 290 例 +1，即新增红测试转绿；唯一 skip 为既有 `@Ignore`，非本批次引入）。
- `./gradlew assembleDebug`：**BUILD SUCCESSFUL**。
- `./gradlew compileDebugAndroidTestKotlin`：**BUILD SUCCESSFUL**。
- 无测试失败，无新增告警（仅既有 `ReaderScreen.kt` 系统栏 API deprecation 与 `AskAiTest.kt` Shadow API deprecation 警告）。

**状态**：必修 2 / 必修 3 已返工，等待 Kimi 复审。
