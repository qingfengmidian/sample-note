# M10 阅读器 UI 改版（F4.4/F4.5）+ 评审遗留 G1/G2/U2/U3/U6/U7 — 任务简报（Kimi Code → OpenCode）

> 你是本批次的实现者（OpenCode）。请在指定范围内完成任务。
> 依据：`docs/collab/reviews/m10-ui-refresh-design.md`（**改版设计定稿，先读它**）、`docs/需求说明书.md` F4.4/F4.5/F2.6、`docs/测试用例.md` TC-UI-01~03/06/07、`docs/文档代码一致性评审.md` §2.2 G1/G2、§5 U2/U3/U6/U7。
> 协作规则见 `docs/协同开发规范.md`；完成前自查实际 diff 并如实报告，**不要 git 写操作**（opencode.json 已 deny）。

## 目标

阅读器 UI 改版：移除常驻底部工具条，设置类操作收纳进 ⋯ 菜单唤起的 ModalBottomSheet 面板；章节列表升级为底部弹层并内置翻章；顺带落地一致性评审遗留项 G1（编码手动切换）、G2（CUSTOM 字体）、U2（DayNight+沉浸式+状态栏跟随主题）、U3（分段选择替代循环）、U6（测试样本入口仅 debug）、U7（空态/错误态操作引导）。

## 改版定稿要点（与设计文档逐条对齐，不得自由发挥）

### 1. 信息架构（设计 §2.1）

```
顶栏（常驻）：返回 | 章节名/书名 | [章节图标（epub）] | ⋯ 菜单
  ⋯ 菜单项：预览/源码（md）· 编辑（md/txt，>2MB 置灰附说明）· 阅读设置
正文区：全屏（原底部工具条位置让出）
阅读设置面板：ModalBottomSheet，排版/字体/主题三组
章节面板：ModalBottomSheet（原 AlertDialog 升级），底部加 上一章/下一章
```

- md 目录图标位置为 M11a 预留，本次只接 epub。

### 2. 阅读设置面板（设计 §2.2 + U3）

点击 ⋯ →「阅读设置」弹 ModalBottomSheet，分三组，数据源与 SettingsScreen 全局页同一 SettingsRepository：

- **排版**：换行模式（WRAP/NO_WRAP 分段选择）；字号（A- / 当前值 sp / A+）；行距（120/150/180/200% 单选，**不做循环按钮**）；**编码（仅 txt 显示，G1）**。
- **字体**：代码 / 衬线 / 无衬线 / 自定义 单选（U3：带选中态的单选列表或 SegmentedButton，选中勾/高亮）；自定义仅在 `customFontPath != null` 时出现；面板内提供「导入字体」入口（复用 SettingsScreen 的 SAF 导入流程）。
- **主题**：跟随系统 / 默认白 / 护眼绿 / 柔光米黄 / 夜间黑 单选（色块+文字，选中勾）。

面板跟随当前阅读主题配色；调节即时生效并持久化（语义同现有 cycle 系列）；可上滑/点遮罩/返回关闭。ViewModel 侧新增直接设置方法（如 `setFont(ReaderFont)`、`setTheme(ReadingTheme?)`、`setLineHeight(Int)`、`setWrapMode(WrapMode)`），**保留**现有 cycle/step 方法（SettingsScreen 仍用循环，不得改坏）。

### 3. G1 编码手动切换（F2.6，仅 txt）

- 排版组末尾加「编码」单选：**自动探测（默认）/ UTF-8 / UTF-16LE / UTF-16BE / GBK / Latin-1**。
- 选择非自动项后**即时重载**当前 txt；选择自动则恢复探测逻辑。会话内有效（不全局持久化）。
- 实现面：`TxtParser` 的内部 `parseBytes` 与 `PagedTextLoader.open` 增加可选 charset 覆盖参数（默认 null = 自动探测）；`DocumentParser` 公开接口**不得改动**；`ReaderViewModel` 持有 `encodingOverride` 并在变更时重走 load 流程（小文件 parseBytes 路径与大文件 PagedTextLoader 路径都要生效）。
- 面板显示当前生效项（自动探测时无额外探测结果展示，不做模型改动）。

### 4. G2 CUSTOM 字体

- `ReaderViewModel.cycleFont` 与 `SettingsScreen.nextFont` 的循环序列：当 `customFontPath != null` 时纳入 CUSTOM（如 CODE→SERIF→SANS→CUSTOM）；为 null 时维持三态循环。
- `importCustomFont` 重新导入成功后将旧字体文件删除（孤儿文件清理）；**切换离开 CUSTOM 不删文件**（允许切回）。

### 5. U2 DayNight + 沉浸式

- `AndroidManifest.xml` 主题改为 DayNight：新建 `res/values/themes.xml`（Light 父主题）与 `res/values-night/themes.xml`（Dark 父主题），用 values/values-night 资源对实现，**不新增 appcompat 依赖**（framework `android:Theme.Material.Light.NoActionBar` / `android:Theme.Material.NoActionBar` 作父主题即可）。
- MainActivity `onCreate` 调 `enableEdgeToEdge()`（activity-compose 已有传递依赖）。
- 状态栏/导航栏颜色跟随当前阅读主题背景：ReaderScreen 内用 `DisposableEffect` 监听 settings.theme，非空时将 window statusBarColor/navigationBarColor 设为该主题 background（跟随系统时恢复 edge-to-edge 默认）。注意 Compose 侧用 `LocalView.current.context as Activity` 取 window。

### 6. U6 测试样本入口仅 debug

- HomeScreen「测试样本」按钮与 `fixtures` 路由仅在 `BuildConfig.DEBUG` 注册/显示；release 无入口。
- `DelegatingFileOpener` 的 asset 分支同样以 BuildConfig.DEBUG 门控（非 debug 遇 asset URI 走不支持路径）。
- 注意 Robolectric 单测跑的是 debug 变体，门控不得破坏现有 fixtures 测试。

### 7. U7 空态/错误态操作引导

- 阅读错误页（ParseException 文案处）加「返回」按钮（回调走现有返回导航）。
- 首页最近文件为空时的空态文案区加可点击「打开文件」主操作（复用现有 SAF 启动器）。

### 8. 章节面板（F4.5）

- `ChapterListSheet` 由 AlertDialog 升级为 ModalBottomSheet，内容主体 `ChapterListContent` **保持不变**（高亮/缩进/selected 语义不得回退）。
- 面板底部加「上一章 / 下一章」按钮；面板内翻章后停留在面板可继续选。
- 顶栏新增章节图标按钮（`Icons.AutoMirrored.Filled.List`，contentDescription「章节列表」），替代原底部「章节」按钮。

## 允许修改

- `app/src/main/java/com/sample/docreader/ui/reader/ReaderScreen.kt`（大改）
- `app/src/main/java/com/sample/docreader/ui/reader/ReaderViewModel.kt`（新增直接设置方法/encodingOverride/G2，回调语义不变）
- `app/src/main/java/com/sample/docreader/ui/SettingsScreen.kt`（G2 nextFont/importCustomFont 清理）
- `app/src/main/java/com/sample/docreader/ui/HomeScreen.kt`（U6/U7）
- `app/src/main/java/com/sample/docreader/MainActivity.kt`（U2 enableEdgeToEdge）
- `app/src/main/AndroidManifest.xml`、`app/src/main/res/`（U2 themes）
- `app/src/main/java/com/sample/docreader/core/parse/TxtParser.kt`、`PagedTextLoader.kt`（仅限可选 charset 覆盖参数，探测默认路径不变）
- `app/src/main/java/com/sample/docreader/core/io/`（U6 门控）
- `app/src/test/` 下测试的新建与必要迁移
- `docs/collab/` 下本批次记录

## 不得修改

- `core/model/Models.kt`、`core/settings/SettingsRepository.kt`（设置模型与 DataStore 键零改动）
- `DocumentParser` 公开接口、ParserFactory、EpubParser、PlainTextParser、MarkdownPreview、EditorScreen、ReaderTextView（除面板调用接线外）、ReaderReloadNotifier
- fixtures 与 `FixtureGenerator.kt`
- `AGENTS.md` 与 `docs/` 下除 `docs/collab/` 外的文档

## 必须保持的不变量

- 既有全部单测全绿（基线 **152**；TC-UI-01~03 断言对象不变——位置保持/剪贴板/即时生效——但操作入口迁至设置面板，**测试需要先唤起面板再操作**，允许改测试的选择器路径，不得放宽断言语义）；
- `ChapterListContent` 的当前章节高亮、缩进、`semantics { selected }` 语义不回退；
- 换行切换位置保持（pendingParagraph）、进度上报、恢复滚动机制不回退；
- 全部新交互控件 contentDescription、可点击目标 ≥48dp；单选组 `Modifier.selectable` + stateDescription；
- 中文注释 + F/TC 编号交叉引用。

## 非目标（本次明确不做）

- md 目录（M11a）、epub 图文/CSS（M11b/M11c）；
- C3 撤销合并、C4 ensureActive、C7 意图上移、U4 切换映射（各有归属批次）；
- U8 首页视觉层级、TC-F1-05 用例修订（后续版本）；
- 设置页 SettingsScreen 的整体改版（仅做 G2 两处小修）。

## 测试要求

### 需要你编写

- TC-UI-06（阅读设置面板）：⋯ 菜单→「阅读设置」唤起面板；断言排版/字体/主题三组存在；字号 A-/A+、换行分段、行距单选、字体单选（选中 CUSTOM 需先置 customFontPath）、主题单选各触发一次回调/持久化；面板关闭后正文区无底部工具条。
- TC-UI-07（章节面板翻章）：epub 顶栏章节图标唤起 ModalBottomSheet；断言上一章/下一章按钮存在且点击触发 selectChapter；当前章节 selected 语义保留。
- G1：txt 载入后选择 GBK 覆盖 → 重载结果按 GBK 解码（构造 GBK 字节夹具直驱 ViewModel 或 parser 层单测）；恢复自动探测回到探测结果。
- G2：customFontPath 非空时 cycleFont 序列含 CUSTOM 且从 CUSTOM 一步可达 CODE；重新导入后旧文件被删除（临时目录构造，勿动真机 fonts 目录）。
- U6：release 变体代码路径不可测则只做 debug 下入口存在性断言；至少保证现有 fixtures 相关测试不回退。
- ModalBottomSheet 在 Robolectric 语义树的表现：只断言面板内控件存在与回调触发，不做布局断言（设计 §4 风险已定）。

### 先行约束

TC-UI-01~03 现有测试若因工具条移除而红，迁移为「唤起设置面板后操作」；断言语义不放宽。

## 应运行的验证

```bash
./gradlew testDebugUnitTest   # 全绿（152 + 你新增）
./gradlew assembleDebug       # 通过
./gradlew assembleRelease     # 通过（U6/U2 改动须过 release 编译）
```

## 允许的工具和权限

- 只用内置 Read/Glob/Grep/Write/Edit 读文件和写文件；不要用 bash 读文档内容。
- 允许的 bash：`ls`、`find`、`git status/diff/log`、`./gradlew testDebugUnitTest`、`./gradlew assembleDebug`、`./gradlew assembleRelease`。
- **不创建调试/临时测试文件；不使用 `rm`**（高危命令，本项目不放行）；临时产物用 Write 覆盖为最终内容或留待 Kimi Code 清理。被权限拒绝的 bash 命令不是致命错误，改用内置工具继续，不要停。

## 升级条件（暂停并交回 Kimi Code/人）

- ModalBottomSheet 在 Robolectric 下完全无法断言（设计 §4 风险兑现）；
- 需要动「不得修改」清单（如设置模型要加键、DocumentParser 接口要改）；
- 状态栏跟随主题与 edge-to-edge 在 API 26 模拟器/真机行为冲突。

## 完成前请

1. 检查实际 diff（`git status` 含未跟踪文件 + `git diff`）；
2. 运行上述验证并附结果；
3. 用 **Write 工具**把完成报告写到 `docs/collab/reviews/m10-opencode-report.md`（改动清单 / 已验证（命令+结果）/ 未验证 / 剩余风险）；
4. 不要自行提交。
