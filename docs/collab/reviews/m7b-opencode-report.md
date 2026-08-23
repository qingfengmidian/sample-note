# M7b 保存流程 + 超大文件只读 — OpenCode 完成报告

> 简报：`docs/collab/briefs/m7b-save-flow.md`。状态：**交付，待 Kimi Code/人审查验收**。

## 改动清单

### 新增（main）

- `app/src/main/java/com/sample/docreader/core/io/DocumentWriter.kt`
  - `interface DocumentWriter { fun openOutput(uri: String, mode: String): OutputStream }`（与 `FileOpener` 对称，测试注入假实现即可驱动保存流程）。
  - `ContentResolverDocumentWriter`：生产实现走 `ContentResolver.openOutputStream(Uri.parse(uri), mode)`，`null` 时抛 `FileNotFoundException`。

- `app/src/main/java/com/sample/docreader/ui/reader/ReaderReloadNotifier.kt`
  - 进程内单例 `MutableStateFlow<Set<String>>`，记录"已保存 uri"：`notifySaved(uri)` / `consume(uri)`。
  - **为何不用 savedStateHandle**：简报要求不得改 `MainActivity.kt`，而 `EditorScreen` 拿不到 `navController.previousBackStackEntry.savedStateHandle`（Navigation Compose 无公开的 `LocalNavController`），故用等价回传通道；进程被杀后集合清空，此时保存已落盘、重开自然读到新内容，无副作用。

### 修改（main）

- `app/src/main/java/com/sample/docreader/ui/editor/EditorViewModel.kt`
  - 构造新增可选参数 `documentWriter: DocumentWriter? = null`、`cacheDir: File? = null`（`EditorViewModel("hello")` 单参调用不受影响）。
  - 新增 `SaveResult`（sealed：`Success`/`FailedSaveAs`/`ProviderKilled`）与状态 `saveResult: SaveResult?`。
  - 新增 `save()`（TC-UT-09/09a 语义）：非 dirty 空操作 → 文本 UTF-8 写 `cacheDir` 临时文件并读回校验字节完整性 → `openOutput(uri, "wt")` 一次性写回 → 捕获 `FileNotFoundException`/`UnsupportedOperationException` 回退 `"w"` → 仍失败保留临时文件、`saveResult = FailedSaveAs`、dirty 保持；异常消息含 `"No content provider"` 时 `saveResult = ProviderKilled`（S1 探针结论）。成功：基线更新为当前文本（撤销回旧文本重新置脏）、dirty=false、临时文件删除、`saveResult = Success`。
  - 新增 `consumeSaveResult()` 清除一次性事件；`NO_CONTENT_PROVIDER` 常量。

- `app/src/main/java/com/sample/docreader/ui/reader/ReaderViewModel.kt`
  - `ReaderUiState` 新增 `editable: Boolean = true`，加载时按 `bytes.size <= MAX_EDITABLE_BYTES(10MB)` 判定（TC-F3-09）。

- `app/src/main/java/com/sample/docreader/ui/reader/ReaderScreen.kt`
  - 编辑菜单项：`state.editable == false` 时置灰并显示说明文字 `编辑（文件超过 10MB，仅可只读）`（常量 `EDIT_HINT_TOO_LARGE`），可只读浏览。
  - 保存后重载：观察 `ReaderReloadNotifier.savedUris`，命中本 uri 即 `consume` 并 `viewModel.load(...)` 重载一次。

- `app/src/main/java/com/sample/docreader/ui/editor/EditorScreen.kt`
  - `EditorScreen` 构造 ViewModel 时注入 `ContentResolverDocumentWriter(context.contentResolver)` 与 `cacheDir = context.cacheDir`。
  - 保存按钮 `enabled = dirty && !loading`；顶部栏保存成功提示"已保存"留在页内。
  - 三选对话框"保存"分支接通：点保存 → `exitAfterSave = true` + `save()`；成功经 `ReaderReloadNotifier.notifySaved(uri)` 后回退阅读页，失败留在编辑页并提示。
  - 新增 `saveResultMessage(result)` 与 `feedback` AlertDialog：Success/FailedSaveAs/ProviderKilled 分别提示（ProviderKilled 文案与阅读页对齐）。

### 修改（test）

- `app/src/test/java/com/sample/docreader/ui/editor/EditorExitDialogTest.kt`
  - `tcUi04_saveDisabled_withContentDescription` → `tcUi04_saveEnabled_withContentDescription`（M7b 保存项不再 disabled）。
  - `editorContent_undoRedoSave_buttonsReflectState` 适配 `EditorContent` 新签名（新增 `dirty`/`onSaveAndExit`），保存 enable 改按 `dirty && !loading` 断言。
  - 新增 2 用例：非 dirty 保存禁用、加载中保存禁用。

### 新增（test）

- `app/src/test/java/com/sample/docreader/ui/reader/ReaderEditableTest.kt`（TC-F3-09 UI，2 用例）：editable=false 编辑项置灰 + 说明文字；editable=true 编辑项可点且无说明。
- `app/src/test/java/com/sample/docreader/ui/editor/EditorSaveResultTest.kt`（2 用例）：`saveResult` 一次性事件经 `consumeSaveResult` 清空；`saveResultMessage` 三类结果文案。

### 未改动（按简报）

- 解析层（`core/parse/`、`core/render/`）、fixtures、`FixtureGenerator.kt`、`SettingsRepository`、Room schema、`MainActivity.kt` 均未触碰；未新增依赖。
- 草稿机制（TC-UT-09 中"草稿被删除"一条）、另存为实功能、超大文件编辑性能优化均属非目标。

## 已验证

- `./gradlew testDebugUnitTest` → **BUILD SUCCESSFUL**（103 个单测，0 失败 0 错误）。
  - `EditorViewModelTest`：10 用例（TC-UT-08 五用例 + TC-UT-09/09a 五用例）全绿。
  - `ReaderViewModelTest`：16 用例（含 TC-F3-09 VM 侧：huge.txt→editable=false、sample_utf8.txt→true）。
  - `EditorExitDialogTest`：6 用例；`EditorSaveResultTest`：2 用例；`ReaderEditableTest`：2 用例，全绿。
  - 既有单测无回归（全部 XML `failures=0 errors=0`）。
- `./gradlew assembleDebug` → **BUILD SUCCESSFUL**。

## 未验证

- TC-F3-01/02/09（手工，真机）：SAF 实际写回（Drive 截断、`wt`/`w` 回退）、md 保存后预览按新内容渲染、huge.txt 编辑按钮置灰 + 只读浏览，需真机/手工验收。
- 保存后阅读页重载的完整链路（编辑保存 → 回退 → 阅读页展示新内容）为真机/手工项；自动化仅覆盖 `ReaderReloadNotifier` 的消费语义与菜单项 enable 状态，未做跨屏导航集成测试。
- `EditorScreen` 内 `BackHandler` + `LaunchedEffect(saveResult)` 的"成功退出/失败留页"分支为 Compose 行为，未做 Robolectric 直驱（因依赖 `viewModel`/`navController`）。

## 剩余风险

- 阅读页重载用进程内单例回传（非 savedStateHandle），跨屏通信在进程存活期内有效；进程被杀后需用户重新打开（保存已落盘，无数据风险），与简报建议方案的差异已在此说明。
- `save()` 沿 `load()` 既有约定在 `viewModelScope`（Main）内做临时文件与写回，未单独切 `Dispatchers.IO`；与既有 `load` 读字节一致，超大文件已由 10MB 上限约束，未做进一步优化（属 M8）。
- 未做 git 写操作（bash 权限被拒未重试，改用内置工具）；提交与合入交由协作者执行。
