# M14 审查记录 — 中英双语支持（F4.6 国际化）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-17 |
| 范围 | M14 实现（红测试转绿）：双语资源 + 全量字符串外置 + 错误文案架构 + 测试迁移 |
| 红测试 | `test(red):` 提交 `be5cb76`（LocalizationTest 5 例 + LocalizationDeviceTest 1 例 + 默认英文资源骨架；实测 3 红 2 绿） |
| 实施者 | OpenCode（CLI 后台 `--auto`，deepseek-v4-flash） |
| 审查者 | Kimi Code（独立读完整 diff——38 文件 +470/-201——+ 独立重跑） |

## 一、实施报告摘要（OpenCode）

资源双文件（默认英文 ~118 键 / values-zh 对等）；UI 外置 7 个文件（HomeScreen/FixtureEntryScreen/SettingsScreen/ReaderScreen/ReaderTextView/ReaderProgressBar/EditorScreen，MainActivity 无用户字面量未动）；错误架构（新增 `ReaderError.kt` sealed + `readerErrorText()`，ReaderState.error 由 String 改类型；`HomeMessage` sealed 五条事件；`saveResultMessage` 返回 `@StringRes`）；测试迁移（Robolectric 21 处 `@Config` 加 zh 限定、设备端 6 文件断言 getString 化）。

## 二、Kimi 独立审查（逐点核对）

| 检查项 | 结论 |
| --- | --- |
| 越界/提交 | ✅ 仅 app/src 三个源集，无 git 提交；红测试 `LocalizationTest`/`LocalizationDeviceTest` `git diff` 为空（未改断言） |
| 强制键契约（TC-UI-15/16 接缝） | ✅ 14 键逐字符合（抽查 `reader_error_provider_not_running` 全角括号长句、`home_untitled_md` 等）；`app_name` 双值正确 |
| 键集对等 | ✅ 两份 XML 肉检分区一致（common/home/reader/settings/fixture/editor），`stringKeySets_parityBetweenDefaultAndZh` 绿（直接解析 XML，防资源回退链掩盖缺键） |
| zh 值保真 | ✅ 既有中文文案逐字沿用（打开文件/阅读设置/柔光米黄/编辑停顿 3 秒说明/图片无法显示…与 M14 前 grep 记录比对一致） |
| 错误架构（T3） | ✅ `ReaderError.from(e)` 穷尽五个 ParseException 子类；MalformedContent cause 仅入日志、展示固定文案；ProviderNotRunning 独立分支；`readerErrorText` 仅 @Composable 使用；Home 五条事件 UI 侧 `when` 穷尽映射 |
| T2 残留 | ✅ grep 复验：UI 包非注释中文残留仅 HomeViewModel 3 处（L98/147「未命名」文件名兜底无 Context、L227 私有异常 message 仅日志——均在简报约束 #4 口径内，见观察 1） |
| Robolectric 迁移 | ✅ 21 处 `@Config`（19×`zh`、1×`night zh`（PanelAccent）、1×`zh`+shadows（Selection））；断言文本零改动；非 Config 变更仅 5 处且全部为白名单适配：EditorSaveResultTest 经 zh `getString` 解析（文本/语义不变）、HomeViewModelTest 改断 `HomeMessage.CreateFailed`（类型断言，更强）、ReaderEditableTest 内联原串（常量删除的编译适配）、ReaderStateTest/ReaderViewModelTest error 断类型（更强） |
| 设备端迁移 | ✅ 断言全部 `uiContext.getString(R.string.*)`；夹具名/文件内容/图片 alt 不断言改动；`MainActivityTest` 首页标题断 `app_name` 资源 |
| 行为等价 | ✅ ENCODING_OPTIONS `Pair→String?`（自动探测标签渲染处本地化，charset 名原样）；THEME_OPTIONS 同理；`fontLabel`/`themeLabel`/`encodingLabel` 转 @Composable 纯映射；TocListContent/章节面板/进度条语义文案全部资源引用 |

## 三、独立复验（Kimi 亲自重跑）

```bash
./gradlew testDebugUnitTest --rerun-tasks   # 54 任务全部强制重跑
./gradlew compileDebugAndroidTestKotlin
```

- **双双 BUILD SUCCESSFUL**；单测 **268 / 0 / 1 跳过**（既有 @Ignore），XML 报告时间戳为本轮新产出；计数核对 `git grep -c '@Test'` = **268**（263 + LocalizationTest 5）。
- 红绿闭环：`be5cb76` 恰 3 红（zh app_name / 键集对等 / zh 文案抽查）→ 本实现 268 全绿。

## 四、观察（不阻塞，记录备查）

1. **「未命名」兜底（用户指示，已解决）**：审查时记录的 VM 层「未命名」硬编码兜底，经用户指示落地为**构造注入**——`HomeViewModel` 新增 `defaultDisplayName: (String?) -> String` 参数（默认中文，纯 VM 测试零改动），`HomeScreen` 注入经 `home_untitled_base` 资源本地化的实现（英文 `Untitled` / 中文「未命名」，覆盖 `onPicked` 无显示名与 `createNew` 两个兜底点）。拼写经用户确认为 `untitled`（用户初稿 untiled 为笔误）。
2. `ui.editor` 复用 `ui.reader.ReaderError`/`readerErrorText`：跨包但已在 docstring 声明「阅读/编辑界面共用」，符合 DRY。
3. `home_error_provider_not_running` 与 `reader_error_provider_not_running` 值同键异（按屏幕前缀），键集对等测试保证两边同步——可接受。

## 五、遗留与下一步

- **设备端/真机验收**：`connectedDebugAndroidTest`（含 TC-UI-16 新例 + 全量 14 例回归）+ 系统语言中/英各一遍 P0 回归目测（应用名/设置页/对话框抽查）。
- 验收通过后：打 `m14` tag、更新开发计划 M14 行与 AGENTS.md 进度段。
- 未提交改动：M14 实现一批（38 文件），提交待用户确认。
