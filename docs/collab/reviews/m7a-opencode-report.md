# M7a 编辑器基础 — OpenCode 完成报告

> 简报：`docs/collab/briefs/m7a-editor-basic.md`。状态：**交付，待 Kimi Code/人审查验收**。

## 改动清单

### 新增（main）

- `app/src/main/java/com/sample/docreader/ui/editor/EditorViewModel.kt`
  - 构造函数 `EditorViewModel(initialText: String = "", fileOpener: FileOpener? = null, settingsRepository: SettingsRepository? = null)`，满足 TC-UT-08 的 `EditorViewModel("hello")` 单参调用。
  - 暴露 `content: TextFieldValue`、`dirty`、`canUndo`、`canRedo`（均 `mutableStateOf` 驱动，供 Compose 重组）；`onContentChange/undo/redo` 按设计§3.4 用 `TextFieldValue` 历史栈（`HISTORY_LIMIT = 100`，满后 `removeFirst` 丢弃最早记录）。
  - 仅 text 变化入栈；仅光标/选区变化不入栈；`dirty = content.text != baseline`，撤销回基线归 false。
  - `load(uri, format, displayName)` 经 `FileOpener` + `ParserFactory` 读取并拼接 `Chapter.paragraphs` 全文为编辑基线（解析层与 UI 层解耦）；错误按 `ParseException` 文案提示；`settings` 订阅 `SettingsRepository.settings`（F3.3）。

- `app/src/main/java/com/sample/docreader/ui/editor/EditorScreen.kt`
  - `EditorScreen`：路由入口，`BackHandler(enabled = dirty)` 拦截返回（非 dirty 不注册，直接退出）；构造 `EditorViewModel` 并 `load`。
  - `EditorContent`（无状态，供测试直驱）：顶部栏（返回/文件名/撤销/重做/保存）+ 全屏 `BasicTextField`，样式复用 `resolveReadingTheme`/`lineHeightSp`/`fontFamilyFor`（F3.3）；保存按钮本批次 `enabled = false`。
  - `EditorExitDialog`（`AlertDialog` 包裹）+ `EditorExitDialogContent`（internal，三选：保存 disabled/放弃/取消，均带 contentDescription、点击目标 ≥ 48dp）。

### 新增（test）

- `app/src/test/java/com/sample/docreader/ui/editor/EditorExitDialogTest.kt`（TC-UI-04，4 用例）：保存项 disabled + contentDescription、取消留页不触发放弃/保存、放弃退出不触发保存、顶部栏撤销/重做/保存 enable 状态一致。

### 修改

- `app/src/main/java/com/sample/docreader/MainActivity.kt`：新增 `editor/{format}/{uri}/{name}` 路由；`ReaderScreen` 调用处接入 `onEdit` 导航。
- `app/src/main/java/com/sample/docreader/ui/reader/ReaderScreen.kt`：`ReaderScreen`/`ReaderContent` 增加 `onEdit` 回调；菜单原"编辑（M7 里程碑）"占位项替换为仅 `MD/TXT` 显示的"编辑"入口（epub/xml 无入口，F3.1/F4.1）。

### 未改动

- 解析层（`core/parse/`、`core/render/`）、fixtures、`FixtureGenerator.kt`、`ReaderViewModel`、设置存储结构、Room schema、`AGENTS.md` 及 `docs/` 下非 collab 文档均未触碰；未新增依赖。

## 已验证

- `./gradlew testDebugUnitTest` → **BUILD SUCCESSFUL**
  - `EditorViewModelTest`（TC-UT-08）：5 用例，0 失败、0 错误。
  - `EditorExitDialogTest`（TC-UI-04）：4 用例，0 失败、0 错误。
  - 既有 82 个单测保持全绿（无回归）。
- `./gradlew assembleDebug` → **BUILD SUCCESSFUL**。

## 未验证

- TC-F3-03/04（手工）：dirty 返回弹三选的完整 BackHandler 链路、撤销/重做操作历史，需真机/手工验收。
- TC-F3-05（F3.3 编辑器样式与阅读界面一致）：仅按代码复用同一套 `ReadingSettings` 保证，未做真机目视确认。
- TC-UI-04 的"dirty 返回弹框、非 dirty 不拦截"触发链：Robolectric 下 `AlertDialog` 窗口内容不进语义树，故测试直接驱动 `EditorExitDialogContent`；`BackHandler` 拦截触发本身未自动化覆盖。

## 剩余风险

- 保存落盘、超大文件只读置灰（M7b）、草稿恢复/自动保存/新建文件（M7c）均属后续里程碑，本批次"保存"按钮为 disabled 占位，点击无行为。
- 编辑器 `load` 复用 `ParserFactory` 解析 md/txt；epub/xml 若被误路由进入会抛 `UnsupportedFormat` 提示（入口侧已限定 md/txt，正常不可达）。
- 未做 git 写操作（仅只读 diff 自检），提交与合入交由协作者执行。
