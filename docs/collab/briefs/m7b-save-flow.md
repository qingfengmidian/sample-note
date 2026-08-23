# M7b 保存流程 + 超大文件只读 — 任务简报（Kimi Code → OpenCode）

> 你是本批次的实现者（OpenCode）。请在指定范围内完成任务。
> 依据：`docs/开发计划.md` M7b、`docs/需求说明书.md` F3.1/F3.2、`docs/软件设计文档.md` §3.4（保存段）/§8（风险表）、`docs/测试用例.md` TC-F3-01/02/09、TC-UT-09/09a、`spike/saf-probe/REPORT.md`（S1 结论）。
> 协作规则见 `docs/collab/协同开发规范.md`（即 docs/协同开发规范.md）；完成前自查实际 diff 并如实报告，**不要 git 写操作**（opencode.json 已 deny）。

## 目标

用户在编辑页可将修改真正保存回 SAF 文件：保存走"临时文件校验 → `"wt"` 写回 → 回退 `"w"` → 失败提示另存"全链路；退出三选的"保存"分支接通；超过 10MB 的 md/txt 文件编辑入口置灰并附说明（只读浏览）。

## 背景和现状

- M7a 已完成：`ui/editor/EditorViewModel.kt`（历史栈/dirty/load）、`EditorScreen.kt`（保存按钮 disabled 占位、三选对话框保存项 disabled）、`editor/{format}/{uri}/{name}` 路由。真机验收已过（`docs/collab/reviews/m7a-editor-basic.md`）。
- `ReaderViewModel.load`（`ui/reader/ReaderViewModel.kt:66`）自己持有 `bytes`（`fileOpener.open(uri).use { it.readBytes() }`），字节数直接可得。
- S1 探针结论（必须落实）：
  - Drive 上 `"wt"`/`"w"` 均成功且正确截断；回退策略为其他 Provider 兜底；
  - Provider 进程被 OEM 杀死时抛 `FileNotFoundException: No content provider`——**不得**按权限失效处理，提示"文件提供方（如云盘应用）未运行，请启动对应应用后重试"（ReaderViewModel.kt:89-98 已有同款处理，可参考对齐文案）。
- 草稿删除（TC-UT-09 中"草稿被删除"一条）属 M7c——本批次尚无草稿机制，该条记为 N/A，在报告中说明即可。

## 允许修改

- `app/src/main/java/com/sample/docreader/ui/editor/`（EditorViewModel/EditorScreen 扩展保存）
- 新建 `app/src/main/java/com/sample/docreader/core/io/DocumentWriter.kt`
- `app/src/main/java/com/sample/docreader/ui/reader/ReaderViewModel.kt`（editable 标记）
- `app/src/main/java/com/sample/docreader/ui/reader/ReaderScreen.kt`（编辑入口置灰+说明；保存后重载）
- 新建/扩展 `app/src/test` 下测试
- `docs/collab/` 下本批次记录

## 不得修改

- 解析层（`core/parse/`、`core/render/`）、fixtures 及 `FixtureGenerator.kt`
- `SettingsRepository`、Room schema、`MainActivity.kt`（路由已够，保存结果回传走 savedStateHandle，见下）
- `AGENTS.md` 与 `docs/` 下除 `docs/collab/` 外的文档

## 必须保持的不变量

- 既有 91 个单测全绿；M7a 已验收行为不回归（撤销/重做、三选拦截、样式共享）
- M7a 先行测试的既有约定不破：`EditorViewModel(initialText)` 单参构造仍可用（新参数全部带默认值）
- 项目红线：不联网、无新依赖、SAF only、中文注释、TC 编号引用、无障碍（contentDescription、≥48dp）

## 非目标（本次明确不做）

- 草稿恢复/自动保存/新建文件（M7c，TC-UT-09b 等）
- "另存为"实际功能——只做失败提示文案，不做 SAF 另存交互
- 超大文件的编辑性能优化（只读置灰即完成本里程碑目标）

## 验收标准

### 自动化（先行测试已由 Kimi Code 写好，当前编译失败=红）

- **TC-UT-09/09a**（`app/src/test/java/com/sample/docreader/ui/editor/EditorViewModelTest.kt` 新增 5 用例）：实现以下 API 使其转绿，语义以测试注释为准：
  - `core/io/DocumentWriter.kt`：`interface DocumentWriter { fun openOutput(uri: String, mode: String): OutputStream }`；生产实现走 `ContentResolver.openOutputStream`（可放 `core/io/` 或并入既有 SAF 工具类，保持最小）
  - `EditorViewModel` 构造新增 `documentWriter: DocumentWriter? = null`、`cacheDir: File? = null`；`save()`；`saveResult: SaveResult?`（sealed：`Success`/`FailedSaveAs`/`ProviderKilled`）
  - 保存流程：写前 dirty 校验（非 dirty 空操作）→ 文本 UTF-8 写 `cacheDir` 临时文件并读回校验字节完整性 → `openOutput(uri, "wt")` 一次性写回 → 捕获 `FileNotFoundException`/`UnsupportedOperationException` 回退 `"w"` → 仍失败：保留临时文件、`saveResult = FailedSaveAs`、dirty 保持；异常消息含 "No content provider" 时 `saveResult = ProviderKilled`
  - 成功：基线更新为当前文本（撤销回旧文本应重新置脏）、dirty=false、临时文件删除、`saveResult = Success`
- **TC-F3-09 VM 侧**（`ReaderViewModelTest.kt` 新增用例）：`ReaderUiState` 新增 `editable: Boolean = true`，按 `bytes.size <= 10 * 1024 * 1024` 判定；huge.txt（15MB）→ false，sample_utf8.txt → true

### 自动化（由你编写）

- 超大文件 UI：`ReaderContent` 直驱测试——`editable=false` 时编辑菜单项 disabled 且附说明文字（如"编辑（文件超过 10MB，仅可只读）"），参照 `ReaderMarkdownPreviewTest`/`ReaderChapterListTest` 模式
- 编辑器保存 UI：保存按钮 `enabled = dirty && !loading`；三选对话框"保存"分支接通——成功→退出编辑页，失败→留在编辑页并提示（对 `EditorExitDialogContent`/`EditorContent` 直驱测：保存项不再 disabled）
- 保存结果反馈：`saveResult` 为一次性事件，UI 消费后清除（加 `consumeSaveResult()`），Success/FailedSaveAs/ProviderKilled 分别给提示（Snackbar 或 AlertDialog，从简）

### 手工（真机，验收时由 Kimi Code 驱动）

- TC-F3-01/02：txt/md 编辑保存后重开内容一致；md 保存后预览按新内容渲染
- TC-F3-09：huge.txt 编辑按钮置灰 + 说明，可只读浏览

### 保存后阅读页刷新（TC-F3-02 依赖）

编辑页保存成功退出后，阅读页须展示新内容。建议方案（可调整，说明理由）：EditorScreen 保存成功退出前向 `navController.previousBackStackEntry.savedStateHandle` 写标记，ReaderScreen 观察并在回到前台时 `viewModel.load(uri, ...)` 重载。注意重载会走进度恢复（epub 不受影响，md/txt 单章无妨）。

## 应运行的验证

```bash
./gradlew testDebugUnitTest   # 全绿（既有 91 + 新增）
./gradlew assembleDebug       # 通过
```

## 允许的工具和权限

- 上述"允许修改"内文件编辑；上述验证命令；`git status/diff/log` 只读
- **过程教训（M7a 三次中断）**：bash 命令被权限拒绝时**不要换着花样重试**（无头模式 ask=自动拒绝），改用已放行的内置工具（Read/Glob/Grep/Write）或直接报告；统计测试结果用 Read 读 XML 而不是 python3

## 升级条件（暂停并交回 Kimi Code/人）

- 先行测试的 API 设计与现实冲突需要改测试
- 保存后阅读页刷新需要改 `MainActivity.kt` 路由结构
- 任何需要改动"不得修改"清单的情形

## 完成前请

1. 检查实际 diff（`git status` 含未跟踪文件 + `git diff`）；
2. 运行上述验证并附结果；
3. 把完成报告写到 `docs/collab/reviews/m7b-opencode-report.md`（改动清单 / 已验证（命令+结果）/ 未验证 / 剩余风险）；
4. 不要自行提交。
