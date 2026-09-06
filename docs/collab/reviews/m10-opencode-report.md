# M10 阅读器 UI 改版 — OpenCode 完成报告

> 简报：`docs/collab/briefs/m10-ui-refresh.md`；续跑说明：`docs/collab/briefs/m10-resume-notes.md`。
> 状态：**交付，待 Kimi Code/人审查验收**。分两节记录改动：首次运行（已完成、已审查）与续跑（测试迁移/新增 + 收尾验证）。

## 改动清单

### 首次运行已完成（Kimi Code 已审查，勿回滚）

- **F4.4/F4.5（ReaderScreen 重写，`ui/reader/ReaderScreen.kt`，506 行变更）**
  - 常驻底部工具条移除；功能收纳为顶栏 ⋯ 菜单（md 预览/源码切换、md/txt 编辑入口与置灰逻辑、阅读设置）。
  - 阅读设置收纳为 `⋯ → 阅读设置` 的 `ModalBottomSheet` 面板：正文主体抽为 `ReaderSettingsSheetContent`（排版/换行、字号 A-/A+、行距、字体、主题三组单选，U3），从面板抽出以便 Robolectric 直驱。
  - epub 章节面板升级为 `ModalBottomSheet`：`ChapterSheetContent` 内置上一章/下一章翻章按钮（F4.5），顶栏"章节列表"图标唤起；当前章节 selected 语义保留。
  - ReaderContent 旧回调 `onToggleWrap/onCycleLineHeight/onCycleFont/onCycleTheme` 删除，改 `onSetWrapMode(WrapMode)/onSetLineHeight(Int)/onSetFont(ReaderFont)/onSetTheme(ReadingTheme?)`。

- **G1（txt 手动编码覆盖，F2.6）**
  - `ReaderScreen` 编码选择组（`ENCODING_OPTIONS`，仅 txt 显示）；`ReaderViewModel.setEncodingOverride`（会话内有效，不落 DataStore）+ `ReaderUiState.encodingOverride`。
  - `TxtParser.parseBytes(bytes, name, uri, charsetOverride)` / `TextDecoding.toParagraphsWith`；`PagedTextLoader.open(stream, probe, charsetOverride)`——跳过探测级联按指定编码解码，BOM 剥离规则与自动路径一致。

- **G2（自定义字体双路径一致 + 孤儿文件清理）**
  - `ReaderViewModel.cycleFont` 与 `SettingsScreen.nextFont` 在 `customFontPath` 非空时把 CUSTOM 纳入循环序列（代码→衬线→无衬线→自定义）。
  - `SettingsScreen` 与阅读设置面板双导入路径：重新导入成功后删除上一版旧字体文件（切离 CUSTOM 不删，允许切回）。
  - `ImportResult` → `FontImportResult`（internal，供 ReaderScreen 面板复用同一 SAF 导入流程）；`importCustomFont` internal 化。

- **U2（DayNight 主题 / 沉浸式）**
  - `res/values/themes.xml`（`Theme.DocReader`，framework Material Light NoActionBar）+ `res/values-night/themes.xml`；Manifest 换 `@style/Theme.DocReader`。
  - `MainActivity.enableEdgeToEdge()`；ReaderScreen `DisposableEffect(theme)` 系统栏跟随阅读主题（含深色透明化）。

- **U6 部分**：`buildFeatures.buildConfig = true`；MainActivity fixtures 路由按 `BuildConfig.DEBUG` 门控；`DelegatingFileOpener` asset 分支门控（release 遇 asset URI 走"文件不可访问"路径）。
- **U7 部分**：阅读错误页「返回」按钮。

### 续跑完成（含收尾 release 验证）

- **HomeScreen（U6 收尾 + U7 首页空态，`ui/HomeScreen.kt`）**
  - 「测试样本」TextButton 加 `BuildConfig.DEBUG` 门控（release 下不显示，避免点到未注册的死路由）。
  - 最近文件为空时给出可点击「打开文件」主操作（≥48dp + `contentDescription`，复用既有 SAF launcher 回调）；列表非空时保留底部固定入口（空态不再重复引导）。

- **测试迁移（`compileDebugUnitTestKotlin` 修复，不得放宽断言语义）**
  - `ReaderEditableTest / ReaderMarkdownPreviewTest / ReaderSelectionTest / ReaderStateTest`：移除旧回调形参。
  - `ReaderThemeFontTest`（TC-UI-03）：改经 `ReaderContent` 新回调 `onSetTheme/onSetFont` 触发切换，即时生效断言不变。
  - `ReaderWrapPositionTest`（TC-UI-01）：切换路径改为唤起 ⋯ 菜单 → 阅读设置 → 面板内换行单选，经 `ReaderContent` + `onSetWrapMode` 触发，`pendingParagraph` 位置保持机制照旧生效。
  - `ReaderChapterListTest`：章节入口断言从底部工具条改顶栏常驻图标（M10 F4.5）。

- **新增测试**
  - `ReaderSettingsSheetTest.kt`（TC-UI-06，3 用例）：直驱 `ReaderSettingsSheetContent`——排版/字体/主题三组存在；A-/A+、行距、字体（含 CUSTOM）、主题、编码单选各触发回调；非 txt 不显示编码组；`customFontPath` 为空时 CUSTOM 项不出现。
  - `ReaderChapterListTest.chapterSheet_hasPrevNext_andCurrentSelected`（TC-UI-07）：上一章/下一章按钮存在并触发回调、当前章节 selected 语义、面板内点章触发 onSelect。
  - `ReaderViewModelTest`（G1/G2，3 用例）：GBK 字节构造 txt，`setEncodingOverride("UTF-8")` 后按 UTF-8 黄金对照解码、置 null 恢复自动探测；`cycleFont` 在 customFontPath 非空时含 CUSTOM；`applyCustomFont` 重导入删旧文件（临时目录构造，切离 CUSTOM 不删）。
  - `HomeScreenDebugEntryTest.kt`（U6，1 用例）：debug 变体下首页测试样本入口存在（release 反向断言由编译门控兜底）。

## 已验证

- `./gradlew testDebugUnitTest` → **BUILD SUCCESSFUL**，160 用例全绿（152 基线 + 8 新增），0 失败 0 错误。
- `./gradlew assembleDebug` → **BUILD SUCCESSFUL**。
- `./gradlew assembleRelease -x lintVitalRelease` → **BUILD SUCCESSFUL**（23s），产出 `app-release.apk`（1,807,846 字节），`minifyReleaseWithR8`/`packageRelease` 正常——**U6 门控后 release 可编译打包**。`-x lintVitalRelease` 为本机代理失效的既有约束（AGENTS.md §4），非代码问题。

## 未验证

- release 真机冒烟（发布项）：Navigation 路由、DataStore/Room 序列化、自定义字体 ttf 不被 R8 混淆、深色/浅色系统栏实际观感。
- `lintVitalRelease`（本机网络限制无法执行）。
- `connectedDebugAndroidTest`（需真机/模拟器）。
- 真机/手工 TC-F\* 项：U2 深浅色实机表现、G1 编码切换观感、⋯ 菜单与设置/章节面板可达性、自定义字体双路径导入。

## 剩余风险

- ReaderScreen 系统栏用 `window.statusBarColor`/`navigationBarColor`（编译有 deprecated 警告，非错误），与 `enableEdgeToEdge` 并存的透明化逻辑较绕，后续 SDK 升级可改 WindowInsetsController 简化。
- U7 空态在 recent 为空时隐藏底部固定"打开文件"、改由空态主操作承接——若后续空态文案/布局回归需注意该分支。
- 编码覆盖为会话级不持久化（设计如此），UI 状态与 DataStore 默认值均无需迁移，但用户重启后需重新选择。
- 全部改动未提交（遵 git 只读纪律）；提交与合入、真机验收交由协作者执行。
