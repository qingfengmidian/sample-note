# M7c 草稿恢复 + 自动保存 + 新建文件 — 任务简报（Kimi Code → OpenCode）

> 你是本批次的实现者（OpenCode）。请在指定范围内完成任务。
> 依据：`docs/开发计划.md` M7c、`docs/需求说明书.md` F3.5/F3.6/F3.7/F4.6、`docs/软件设计文档.md` §3.4（草稿段）/§4.1（首页）、`docs/测试用例.md` TC-F3-06/07/08、TC-F4-06/06a、TC-UT-09b、TC-UI-05、`spike/saf-probe/REPORT.md`。
> 协作规则见 `docs/协同开发规范.md`；完成前自查实际 diff 并如实报告，**不要 git 写操作**（opencode.json 已 deny）。

## 目标

编辑过程可跨进程死亡恢复：正文落私有草稿文件（`filesDir/edit_recovery/`），uri/光标落 `SavedStateHandle`；内容变化防抖 1s 写草稿、退回基线删草稿、保存/放弃删草稿；首页"新建"（`ACTION_CREATE_DOCUMENT`）创建 md/txt 空文件并进入编辑器；最近列表 remove/clear 联动删草稿；启动清理 7 天前孤儿草稿；设置页开放"自动保存"开关（默认关），开启后停顿 3s 自动保存。

## 背景和现状

- M7b 已完成：`EditorViewModel`（历史栈/dirty/load/save/saveResult）、`EditorScreen`（三选对话框保存项已接通）、`DocumentWriter`、`ReaderViewModel.editable`。真机验收已过（`docs/collab/reviews/m7b-save-flow.md`）。
- `ReadingSettings.autoSave`（默认 `false`）与 `SettingsRepository.Keys.AUTO_SAVE` **已存在**（`core/settings/SettingsRepository.kt:37,50,63`），无需改模型/存储。
- `EditorViewModel` 现有构造：`(initialText, fileOpener, settingsRepository, documentWriter, cacheDir)`，全为可选带默认值；`save()` 成功已 `tempFile?.delete()`（不含草稿删除——那是本批次要接的）。
- `HomeViewModel`（`ui/home/HomeViewModel.kt`）现有 `onPicked`/`openRecent`/`remove(uri)`/`clear()`；`RecentFilesRepository.touch(uri, name, format)` 已就绪（`core/recent/RecentFilesRepository.kt:25`）。
- `MainActivity.kt` 现有路由 `home`/`reader/{format}/{uri}/{name}`/`editor/{format}/{uri}/{name}`/`settings`/`fixtures`；editor 路由已接 `EditorScreen(uri, format, displayName, onBack)`。
- `EditorScreen` 用 `viewModelFactory` 构造 `EditorViewModel`（`ui/editor/EditorScreen.kt:69-78`），需在此补 `draftStore`/`savedStateHandle` 注入。
- 草稿路径约定：`filesDir/edit_recovery/<md5(uri) hex 前 16 字符>.draft`（MD5 只作文件名散列，非安全用途）。

## 允许修改

- 新建 `app/src/main/java/com/sample/docreader/core/io/DraftStore.kt`
- `app/src/main/java/com/sample/docreader/ui/editor/EditorViewModel.kt`（草稿写/恢复/删、`discardDraft()`、`setAutoSave(Boolean)`、自动保存 debounce）
- `app/src/main/java/com/sample/docreader/ui/editor/EditorScreen.kt`（注入 draftStore/savedStateHandle；"放弃"分支改调 `viewModel.discardDraft()` 再 onBack）
- `app/src/main/java/com/sample/docreader/ui/home/HomeViewModel.kt`（新建文件 `createNew(format)`；remove/clear 联动删草稿；init 启动孤儿清理）
- `app/src/main/java/com/sample/docreader/ui/HomeScreen.kt`（"新建"入口）
- `app/src/main/java/com/sample/docreader/ui/SettingsScreen.kt`（"自动保存"开关）
- `app/src/main/java/com/sample/docreader/MainActivity.kt`（新建文件路由接线；必要时传递 SavedStateHandle）
- 新建/扩展 `app/src/test` 下测试（`ui/home/`、`ui/editor/`）
- `docs/collab/` 下本批次记录

## 不得修改

- 解析层（`core/parse/`、`core/render/`）、fixtures 及 `FixtureGenerator.kt`
- `SettingsRepository`、Room schema/DAO/entity、`core/recent/RecentFilesRepository.kt`
- `core/model/`（ReadingSettings 已含 autoSave，不动）
- `AGENTS.md` 与 `docs/` 下除 `docs/collab/` 外的文档

## 必须保持的不变量

- 既有全部单测全绿；M7a/M7b 已验收行为不回归（撤销/重做、三选拦截、保存落盘、超大文件只读）
- `EditorViewModel(initialText)` 单参构造仍可用；所有新参数带默认值，`savedStateHandle`/`draftStore` 为 `null` 时草稿/恢复功能安全降级为无操作（不 NPE）
- 草稿读写只落 `filesDir` 私有目录，不碰 SAF、不申请任何新权限、不联网
- 中文注释 + TC 编号交叉引用；无障碍（contentDescription、可点击目标 ≥ 48dp）
- 大文件约束：`DraftStore.write` 需全量读回校验（同 save 的临时文件校验精神），防抖任务在 `viewModelScope` 内且正文写入走 IO 调度（不得卡 UI）

## 非目标（本次明确不做）

- 草稿加密/压缩/多版本；跨文件多草稿恢复优先级提示
- "另存为"实际功能（M7b 已界定只做失败提示）
- 自动保存的可配置间隔（固定 3s debounce 即达本里程碑目标）

## 验收标准

### 自动化（先行测试已由 Kimi Code 写好，当前编译失败 = 红）

先行失败测试：`app/src/test/java/com/sample/docreader/ui/editor/EditorDraftTest.kt`（8 用例，TC-UT-09b + TC-F3-06/07 VM 侧）。实现以下 API 使其转绿，**语义以测试注释与断言为准**：

- `core/io/DraftStore.kt`：
  - `class DraftStore(filesDir: File)`
  - 草稿目录 `filesDir/edit_recovery/`（惰性 mkdirs）
  - `fun write(uri: String, text: String)`：写 `<md5(uri) hex 前 16>.draft`（MD5 用 `java.security.MessageDigest`）
  - `fun read(uri: String): String?`：不存在返回 null
  - `fun delete(uri: String)`
  - `fun draftFileFor(uri: String): File`（纯路径计算，不要求文件已存在）
  - `fun cleanupOrphans(olderThanMs: Long = 7 天, now: Long = System.currentTimeMillis()): Int`：删除 `edit_recovery` 下 `lastModified < now - olderThanMs` 的 `.draft`，返回删除数量
- `EditorViewModel` 构造新增 `draftStore: DraftStore? = null`、`savedStateHandle: SavedStateHandle? = null`
- 内容变化防抖 **1s** 写草稿：仅当 `dirty == true` 时写；`content.text == baseline`（退回基线/撤销回基线）时删除草稿
- uri/光标存 `SavedStateHandle`（只存轻量 uri + 光标位置，**不存正文**）；`load()` 载入原文为基线后，若草稿文件存在则恢复草稿文本 + 光标 + `dirty=true`（不伪造 undo 历史，undo 栈为空）
- `discardDraft()`：删除对应草稿（用户三选"放弃"）
- `save()` 成功（`SaveResult.Success`）后删除对应草稿
- `setAutoSave(Boolean)`：VM 侧行为开关（持久化由 UI 层经 SettingsRepository 负责）；开启时内容变化 debounce **3s** 自动调 `save()`，关闭则不落盘
- 注意：测试用 `runTest(mainDispatcher)` + `advanceTimeBy` 驱动防抖，`load()` 走真实 IO 线程需 `awaitLoaded` 轮询（同 ReaderViewModelTest）——实现不要用会逃逸测试调度器的真实 delay 计时，防抖计时器须可被 `advanceTimeBy` 推进（即用协程 `delay`，勿用 `System.nanoTime`/线程 sleep）

### 自动化（由你编写）

- `DraftStore` 单测（可选，若先行测试已充分覆盖则说明即可）：md5 文件名、读写回环、delete、cleanupOrphans 边界（含 7 天临界）
- 新建文件 VM 侧：`HomeViewModel.createNew(format)` —— 对可写 SAF uri（`ACTION_CREATE_DOCUMENT` 返回），`takePersistableUriPermission(WRITE)` + `RecentFilesRepository.touch` + 置 `openTarget` 指向 editor；失败给 message。用假 `ContentResolver`/假 repository 直驱
- remove/clear 联动删草稿：`HomeViewModel.remove(uri)` / `clear()` 挂 `DraftStore.delete`（不动 Repository），直驱测草稿被删
- 自动保存开关 UI：`SettingsScreen` 出现"自动保存"开关，默认关闭，切换后 `SettingsRepository.save(settings.copy(autoSave=…))`（对 `SettingsScreen` 直驱或至少 VM/仓储层测持久化回环）
- **TC-UI-05**（进程重建）：优先尝试在 Robolectric 下用 `createSavedStateHandle`/重建 `EditorScreen` 模拟进程重建；若 Robolectric 下无法真正重建 Activity 级 SavedState，退化为 VM 级重建测试（重建 `EditorViewModel` + 复用 `SavedStateHandle` + 草稿文件，等价验证"正文从草稿恢复"），并在报告中明确注明"真机 TC-F4-06 兜底"

### 手工（真机，验收时由 Kimi Code 驱动）

- **TC-F4-06**：编辑页输入若干字符 → 按 HOME 进后台 → `adb shell am kill com.sample.docreader` → 重进 → 最近列表重开该文件 → 编辑页恢复未保存文本、光标、dirty
- **TC-F4-06a**：① 保存/放弃后 `run-as com.sample.docreader ls files/edit_recovery/` 无对应 `.draft`；③ 用 `run-as` 伪造 7 天前草稿文件 + `touch` 改 mtime，冷启动后该孤儿草稿被清理
- **TC-F3-06**：自动保存默认关闭——编辑后停留数秒，草稿存在但 SAF 文件未被写回（`cat` 仍是原文），退出后草稿可恢复
- **TC-F3-07**：设置页开启自动保存 → 编辑后停顿 > 3s → `cat` SAF 文件已落盘新内容、dirty 归 false、草稿删除
- **TC-F3-08**：首页"新建"→ 选 md/txt → picker 命名 → 进入空编辑器 → 输入保存 → 最近列表出现该文件

## 新建文件的路由接线（F3.5/TC-F3-08，本批次允许改 MainActivity）

推荐方案（可调整并说明理由）：HomeScreen 加"新建"按钮 → `rememberLauncherForActivityResult(CreateDocument(mimeType))` 选 md 或 txt → `HomeViewModel.createNew(uri)` 持久化写权限 + touch 最近 → 通过 `openTarget` 事件导航到 editor 路由（空文件）；EditorViewModel.load 对空/新建 SAF 文件应解析为 `""`（不报错）。若导航参数需区分"新建"与"打开"，在路由上复用 `editor/{format}/{uri}/{name}` 即可（创建即得到 uri）。

## 应运行的验证

```bash
./gradlew testDebugUnitTest   # 全绿（既有 + EditorDraftTest 8 用例 + 你新增）
./gradlew assembleDebug       # 通过
```

## 允许的工具和权限

- 上述"允许修改"内文件编辑；上述验证命令；`git status/diff/log` 只读
- **过程教训（M7a/M7b 已踩坑）**：bash 命令被权限拒绝时**不要换着花样重试**（无头模式 ask=自动拒绝），改用已放行的内置工具（Read/Glob/Grep/Write）或直接报告；统计测试结果用 Read 读 XML 而不是 python3；**写报告只用 Write 工具**，不要用 heredoc/echo 重定向

## 升级条件（暂停并交回 Kimi Code/人）

- 先行测试钉死的 API 设计（文件名契约、防抖时长、`SavedStateHandle` 只存轻量状态）与现实冲突需要改测试
- 新建文件路由需要改动 reader 路由结构或引入新 Navigation 依赖
- Robolectric 下 TC-UI-05 连 VM 级重建都无法表达，或需要真机专属测试框架
- 任何需要改动"不得修改"清单的情形

## 完成前请

1. 检查实际 diff（`git status` 含未跟踪文件 + `git diff`）；
2. 运行上述验证并附结果；
3. 把完成报告写到 `docs/collab/reviews/m7c-opencode-report.md`（改动清单 / 已验证（命令+结果）/ 未验证 / 剩余风险）；
4. 不要自行提交。
