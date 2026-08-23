# M7a 编辑器基础 — 审查记录（Kimi Code）

> 简报：`docs/collab/briefs/m7a-editor-basic.md`。状态：**待 OpenCode 交付**。

## 审查清单（交付后逐项核验）

### 范围与不变量

- [ ] diff 仅触及简报"允许修改"清单（ui/editor/**、MainActivity.kt、ReaderScreen.kt 入口、测试）
- [ ] 无任务外修改、无无关格式化；无新增依赖；fixtures 未动
- [ ] `git status` 未跟踪文件已核对

### 功能正确性（对照 TC 编号）

- [ ] TC-UT-08 五用例转绿，且实现不是"为测试硬编码"（读实现核对历史栈语义）
- [ ] TC-UI-04：dirty 返回弹三选；取消留页内容不变；放弃退出不写文件；保存项 disabled
- [ ] 非 dirty 返回不拦截
- [ ] 编辑入口仅 md/txt 显示；epub/xml 无入口
- [ ] 编辑器样式读取同一 ReadingSettings（F3.3）
- [ ] 撤销/重做按钮 enable 状态与 canUndo/canRedo 一致

### 质量门槛

- [ ] 独立重跑 `./gradlew testDebugUnitTest` 全绿
- [ ] `./gradlew assembleDebug` 通过
- [ ] 无障碍：按钮 contentDescription、可点击目标 ≥ 48dp
- [ ] 路由 URI 编码沿用 reader 的既有处理（37e252b 的坑不重现）

### 已知缺口（简报内声明，非缺陷）

- 保存落盘（M7b）；草稿恢复/自动保存/新建（M7c）；超大文件只读（M7b）

## 代码走查意见（Kimi Code，2026-08-23）

实现文件：`ui/editor/EditorViewModel.kt`（156 行）、`ui/editor/EditorScreen.kt`（263 行）、`EditorExitDialogTest.kt`（117 行，4 用例）；改动 `MainActivity.kt`（+editor 路由）、`ReaderScreen.kt`（编辑入口）。

### 通过项

- **EditorViewModel 语义与 TC-UT-08 契约一致**：文本变化入栈/光标变化不入栈、dirty 以 baseline 为基线、100 步 `removeFirst` 丢最早、新编辑清 redo 栈；undo/redo 双向栈均有界（redo 栈最大 = undo 次数 ≤ 100）。
- `load()` 异常处理规范：`CancellationException` 重抛、IO 失败统一映射 `NoPermission`（与阅读页一致）；解析层解耦（消费 `Chapter.paragraphs` 拼接全文）。
- `MainActivity` editor 路由未二次解码，且注释显式引用 37e252b 的坑。
- ReaderScreen 入口：恰好替换了 M6 遗留的占位"编辑（M7 里程碑）"disabled 项，仅 md/txt 显示 ✅。
- 无障碍：顶栏按钮/对话框三选均有 contentDescription + 48dp；编辑区有"编辑区"语义与 testTag。
- F3.3：样式复用 `resolveReadingTheme`/`lineHeightSp`/`fontFamilyFor`，与阅读页同源。
- 对话框内容抽为 `internal EditorExitDialogContent` 直驱测试，正确绕开 Robolectric AlertDialog 语义不可见的坑（沿用 ChapterListContent 模式）。

### 待核验 → 均已通过（见下"独立复验结果"）

- [x] `./gradlew testDebugUnitTest` 独立重跑全绿
- [x] `./gradlew assembleDebug` 通过
- [x] 测试报告 XML 核对：suites=20 tests=91 failures=0 errors=0（82 + 5 + 4，符合预期）

### 观察项（非阻塞）

1. BackHandler 拦截链路（dirty→弹框）无自动化覆盖，靠真机 TC-F3-03 手工兜底——已在测试注释中声明，可接受。
2. EditorViewModel 用 Compose `mutableStateOf` 而非 StateFlow，与 ReaderViewModel 风格不完全一致，但 Robolectric 下可测且语义清晰，不强制统一。
3. redo 栈依赖"undo 次数 ≤ undo 栈 ≤ 100"间接有界，正确但隐式；如后续加持久化历史需注意。

## 独立复验结果（Kimi Code）

- `cleanTestDebugUnitTest testDebugUnitTest`：**suites=20 tests=91 failures=0 errors=0**（82 既有 + 5 TC-UT-08 + 4 TC-UI-04，FROM-CACHE 按输入键命中，结果对应当前代码）。
- `assembleDebug`：BUILD SUCCESSFUL（独立复跑）。
- OpenCode 完成报告（`m7a-opencode-report.md`）与本走查一致，无夸大；"未验证"项如实披露（BackHandler 完整链路、样式目视，留真机手工）。

### 过程问题（本批次教训）

- OpenCode 无头模式下 `ask` = 自动拒绝，三次因非白名单命令（管道 `sort`、`xargs`/`echo`、`python3`）中断退出。白名单已补一轮；后续批次简报应加一句"未放行命令被拒时不要反复重试，换用已放行的内置工具或直接报告"。

## 真机验收（2026-08-23，OnePlus PHM110 / ColorOS，WiFi adb 驱动 + 截图判读）

- **TC-F3-03 通过**：dirty 状态按返回（首次 BACK 被输入法收键盘消费，第二次生效）弹出"未保存的修改"三选框；保存项置灰可见；取消 → 留页且内容（HelloAB）不丢；再次返回选放弃 → 回到阅读页且未写文件（重进编辑内容为原文）；非 dirty 按返回直接退出不弹框。保存→落盘分支属 M7b，本次置灰占位，符合简报声明。
- **TC-F3-04 通过**：输入 ABC（3 步历史）→ 撤销×3 恢复原文（撤销按钮随之禁用）→ 重做×2 恢复 "AB"，按钮 enable 状态与栈状态一致。
- **TC-F3-05 通过**：阅读页设置 增大字号/行距 180%/衬线/护眼绿 后进编辑页，截图对比字号、行距、字体、背景/前景色与阅读页一致。

## 结论

**接受**。范围、不变量、TC-UT-08/TC-UI-04 自动化验收全部通过；手工项 TC-F3-03/04/05 真机验收通过（保存分支待 M7b）。`m7` tag 待 M7c 完成 M7 整体后打。
