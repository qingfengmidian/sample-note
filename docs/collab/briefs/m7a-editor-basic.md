# M7a 编辑器基础 — 任务简报（Kimi Code → OpenCode）

> 你是本批次的实现者（OpenCode）。请在指定范围内完成任务。
> 依据：`docs/开发计划.md` M7a、`docs/需求说明书.md` F3.1/F3.2、`docs/软件设计文档.md` §3.4/§4（EditorScreen）、`docs/测试用例.md` TC-F3-01/03/04、TC-UT-08、TC-UI-04。
> 协作规则见 `docs/协同开发规范.md`；完成前自查实际 diff 并如实报告，**不要自行 git commit/push**。

## 目标

用户在阅读页可将 md/txt 文件切入编辑模式：全屏纯文本编辑、撤销/重做（100 步历史栈）、有未保存修改时退出弹出三选拦截。用户可观察到的行为对应 TC-F3-01（仅"进入编辑模式并修改"部分）/TC-F3-03/TC-F3-04。

## 背景和现状

- 尚无 editor 模块。现有路由（`MainActivity.kt`）：`home`、`reader/{format}/{uri}/{name}`、`settings`、`fixtures`。
- 阅读页 `ui/reader/ReaderScreen.kt` 已有工具条/菜单结构（章节列表、md 预览切换等），编辑入口仿其模式添加。
- 设置共享：`SettingsRepository.settings: Flow<ReadingSettings>`（`core/settings/SettingsRepository.kt`），阅读页在 `ReaderScreen.kt:76` 附近构造；编辑器读取同一套 `ReadingSettings`（字号/行距/字体/主题色），满足 F3.3。
- 行距换算可复用 `ui/reader/ReaderTextView.kt:69` 的 `lineHeightSp`（internal，可移用或同包复用思路自行决定，保持最小改动）。
- 本里程碑**不含保存落盘**（M7b）、不含草稿恢复/自动保存/新建文件（M7c）。

## 允许修改

- 新建 `app/src/main/java/com/sample/docreader/ui/editor/`（`EditorScreen.kt`、`EditorViewModel.kt`，可拆分小组件文件）
- `MainActivity.kt`（新增 `editor/{format}/{uri}/{name}` 路由）
- `app/src/main/java/com/sample/docreader/ui/reader/ReaderScreen.kt`（仅加"编辑"入口，仅 md/txt 显示）
- 新建 `app/src/test/java/com/sample/docreader/ui/editor/` 下测试
- `docs/collab/` 下本批次记录

## 不得修改

- 解析层（`core/parse/`、`core/render/`）、fixtures 及 `FixtureGenerator.kt`
- `ReaderViewModel` 既有行为、设置存储结构、Room schema
- `AGENTS.md` 与 `docs/` 下除 `docs/collab/` 外的文档

## 必须保持的不变量

- 既有 82 个单测全绿；阅读页既有行为（含 M5/M6 修复）不回归
- 解析层与 UI 层解耦；编辑器直接消费 `Chapter.paragraphs` 拼接的全文文本
- `AGENTS.md` §5 全部约定：中文注释、TC 编号引用、无障碍（contentDescription、可点击目标 ≥ 48dp）、Compose `Color`
- 项目红线：不联网、不引入新依赖、SAF only

## 非目标（本次明确不做）

- 保存写回文件（M7b：临时文件校验 → `"wt"` → 回退 `"w"` → 另存提示）
- 超大文件只读置灰（M7b，TC-F3-09）
- 草稿恢复、自动保存、新建文件（M7c）
- md 编辑态语法着色（设计§3.4 标注"可选"，V1.0 不做）

## 验收标准

- **TC-UT-08（自动化，先行测试已由 Kimi Code 提供在 `app/src/test/java/com/sample/docreader/ui/editor/EditorViewModelTest.kt`，当前编译失败=红）**：实现 `EditorViewModel` 使其转绿。API 以测试为准：`content: TextFieldValue`、`dirty`、`canUndo/canRedo`、`onContentChange`/`undo`/`redo`；历史栈 100 步上限、满后丢弃最早记录；仅光标变化（text 相同）不产生历史；撤销回到初始文本时 dirty 归 false。
- **TC-UI-04（自动化，由你编写）**：dirty 状态下返回弹出三选对话框；"取消"留在编辑页且内容不变；"放弃"退出编辑页且不写文件。"保存"项本次渲染为 **enabled=false** 并带 contentDescription（M7b 接入，属已知缺口，TC-F3-03 保存分支手工验收推迟到 M7b）。
- **TC-F3-03/04（手工，交由人验收）**：三选对话、撤销/重做操作历史正确。
- 非 dirty 状态返回不拦截，直接退出。
- 编辑器样式（字号/行距/字体/背景前景色）与阅读界面一致（F3.3）。

## 应运行的验证

```bash
./gradlew testDebugUnitTest   # 必须全绿（含新增 TC-UT-08 / TC-UI-04）
./gradlew assembleDebug       # 构建通过
```

## 允许的工具和权限

- 上述"允许修改"内文件的编辑；上述验证命令；`git status/diff/log` 只读
- 禁止：git 写操作、新增依赖、触碰 fixtures、联网

## 升级条件（暂停并交回 Kimi Code/人）

- 发现先行测试（TC-UT-08）的 API 设计与 Compose/Robolectric 现实冲突，需要改测试
- 路由参数编码问题（M5 曾踩二次解码坑，见提交 37e252b；复用 reader 路由的既有处理方式，若需改动既有导航逻辑先询问）
- 任何需要改动"不得修改"清单的情形

## 设计要点提示（非强制实现细节）

- `BasicTextField` + `TextFieldValue`；撤销/重做栈按设计§3.4 用 `TextFieldValue` 历史栈（100 步）；V1 不做输入合并（coalescing），每次文本变化即一步
- 退出拦截用 `BackHandler` + dirty 检查
- 入口按钮仅 `DocFormat.MD/TXT` 显示；epub/xml 无编辑（F3.1）
- 三选对话框用 `AlertDialog`；注意 Robolectric 下弹层语义不可见的既有坑——参照 `ReaderChapterListTest` 的做法，把内容抽成可直驱的 internal composable 再测

## 完成前请

1. 检查实际 diff（`git status` 含未跟踪文件 + `git diff`）；
2. 运行上述验证并附结果；
3. 报告：改动清单 / 已验证 / 未验证 / 剩余风险；
4. 不要自行提交。
