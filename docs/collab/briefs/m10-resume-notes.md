# M10 续跑说明（OpenCode 第二次运行必读）

> 背景：M10 首次运行在重写 ReaderScreen.kt 后因配额耗尽中断。Kimi Code 已审查全部已完成改动（**主体代码合格，不要回滚重写**），修复了一处 Manifest 语法错误。本文件列出**剩余工作清单**，请据此续跑，完成 `docs/collab/briefs/m10-ui-refresh.md` 中未打勾的条目。
> 纪律条款原样有效：不建调试文件 / 不用 rm / 单条 bash / git 只读 / 被拒不停。

## 已完成（已审查，勿动）

- **F4.4**：ReaderScreen 重写——底部工具条移除、⋯ 菜单（预览/源码 md、编辑 md/txt 置灰逻辑、阅读设置）、`ReaderSettingsSheetContent`（排版/字体/主题三组单选，U3）、`ChapterSheetContent` + ModalBottomSheet（F4.5）
- **G1**：`ENCODING_OPTIONS`（ReaderScreen）、`ReaderViewModel.setEncodingOverride`、`TxtParser.parseBytes(charsetOverride)` / `TextDecoding.toParagraphsWith`、`PagedTextLoader.open(charsetOverride)`
- **G2**：`cycleFont`/`nextFont` 纳入 CUSTOM（双路径）、双导入路径旧文件清理、`ImportResult`→`FontImportResult`（internal 供复用）
- **U2**：`res/values/themes.xml` + `res/values-night/themes.xml`、Manifest 换 `@style/Theme.DocReader`、MainActivity `enableEdgeToEdge()`、ReaderScreen `DisposableEffect` 系统栏跟随主题
- **U6 部分**：MainActivity fixtures 路由 BuildConfig.DEBUG 门控、DelegatingFileOpener asset 分支门控、`buildConfig = true`
- **U7 部分**：阅读错误页「返回」按钮

## 剩余工作

### 1. HomeScreen.kt（U6 收尾 + U7 首页空态）

- 「测试样本」TextButton 加 `BuildConfig.DEBUG` 门控（import `com.sample.docreader.BuildConfig`）——路由虽已门控，按钮不门控 release 会点到死路由。
- 最近文件为空时的空态：文案区下方加可点击「打开文件」主操作按钮（复用现有 SAF 启动器回调，≥48dp、contentDescription）。

### 2. 测试迁移（当前 test 源集编译失败，必须先修）

main 编译已过；`compileDebugUnitTestKotlin` 失败，错误全部是 `ReaderContent` 旧回调参数不存在：

```
ReaderEditableTest.kt:43       onCycleTheme
ReaderMarkdownPreviewTest.kt   onToggleWrap / onCycleLineHeight / onCycleFont / onCycleTheme
ReaderSelectionTest.kt         同上
ReaderStateTest.kt             同上
ReaderThemeFontTest.kt         同上（两处）
ReaderWrapPositionTest.kt      同上
```

迁移指引（不得放宽断言语义）：

- 旧签名 `onToggleWrap/onCycleLineHeight/onCycleFont/onCycleTheme` 已删，新签名为 `onSetWrapMode(WrapMode)/onSetLineHeight(Int)/onSetFont(ReaderFont)/onSetTheme(ReadingTheme?)`。
- 这些测试原本驱动底部工具条按钮；现在对应控件在 `ReaderSettingsSheetContent`（无状态、可直接驱动，**不必在测试里弹 ModalBottomSheet**）。把"点击工具条按钮"改为"直驱 `ReaderSettingsSheetContent` 传新回调"，断言对象（位置保持/剪贴板/持久化/即时生效）不变。
- `ReaderWrapPositionTest`（TC-UI-01 位置保持）注意：`pendingParagraph` 记录与滚回逻辑在 `ReaderContent` 内部，切 wrap 回调路径仍须经过 `ReaderContent` 才能断言位置保持——最小改法是保留 `ReaderContent` 驱动、通过新回调 `onSetWrapMode` 触发切换（回调由测试传入，内部 pendingParagraph 机制照旧生效）。

### 3. 新增测试（简报"测试要求"节）

- **TC-UI-06** 阅读设置面板：直驱 `ReaderSettingsSheetContent` 断言三组存在；字号 A-/A+、行距单选、字体单选（含 CUSTOM 需先给 customFontPath）、主题单选、编码单选（isTxt=true）各触发回调；非 txt 时编码组不显示。
- **TC-UI-07** 章节面板：直驱 `ChapterSheetContent` 断言上一章/下一章存在、点击触发回调、当前章节 selected 语义（ChapterListContent 既有测试不回退）。
- **G1**：GBK 字节构造 txt，`setEncodingOverride("GBK")` 后重载结果按 GBK 解码；置 null 恢复自动探测。可直驱 ViewModel（参照 ReaderViewModelTest 模式）。
- **G2**：`customFontPath` 非空时 `cycleFont` 序列含 CUSTOM；`applyCustomFont(newPath)` 后旧文件被删除（临时目录构造，勿动真机 files/fonts）。
- **U6**：debug 下入口存在性断言即可（Robolectric 跑 debug 变体）。

### 4. 验证与报告

```bash
./gradlew testDebugUnitTest    # 全绿：基线 152 + 新增
./gradlew assembleDebug        # 通过
./gradlew assembleRelease      # 通过（U6 门控后 release 必须可编译打包）
```

完成后用 Write 写报告到 `docs/collab/reviews/m10-opencode-report.md`（改动清单 / 已验证命令+结果 / 未验证 / 剩余风险），不要自行提交。

## Kimi Code 已做的旁路改动（勿回滚）

- `AndroidManifest.xml`：注释从 `<application>` 属性列表内移到标签外（XML 标签内不允许注释，原写法 manifest 合并直接失败）。
- `opencode.json`：放行名单加 `git -C * status/diff/log/show`（与本任务代码无关）。
