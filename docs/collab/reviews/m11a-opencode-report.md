# M11a OpenCode 实施报告 — md 目录（F2.13）+ 源码/预览切换位置映射（U4）

| 项目 | 内容 |
| --- | --- |
| 任务简报 | `docs/collab/briefs/m11a-md-toc.md` |
| 实施日期 | 2026-09-06 |
| 实施者 | OpenCode |
| 基线 | `main@108face`，单测基线 160 通过 / 0 失败 |
| 结果 | `./gradlew testDebugUnitTest` 全绿，**178 通过 / 0 失败**（基线 160 + 新增 18） |

## 1. 改动文件清单

**生产代码（app/src/main）**

| 文件 | 改动 | 对应需求/评审 |
| --- | --- | --- |
| `core/model/Models.kt` | 新增 `TocEntry(level, title, paragraphIndex)`；`Document` 增 `toc: List<TocEntry> = emptyList()` | F2.13 / 设计§3.1 |
| `core/render/MarkdownParser.kt` | `MarkdownBlock` 各子类增 `startParagraphIndex` 字段；`parse()` 记录块起始段落索引（CodeBlock 锚定围栏起始行）；抽出共用标题识别 `atxHeading()`；新增 `scanHeadings(paragraphs)` 单次线性扫描提取目录（跳过围栏、剥行内标记）；`scanHeadings` 循环内检查线程中断 | F2.13 / S4、S10 / AGENTS §5 |
| `core/parse/PlainTextParser.kt` | md 分支调用 `MarkdownParser.scanHeadings` 生成 `Document.toc`（与 MarkdownParser 共用同一标题扫描，防逻辑漂移） | F2.13 / S10 |
| `ui/reader/MdViewMapping.kt`（新） | 纯函数三坐标映射：段落→所属块、块→起始段落、可见段落→所属目录项（-1 无高亮） | S4 / U4 / S11 |
| `ui/reader/ReaderScreen.kt` | md 顶栏「目录」图标（toc 为空隐藏）；`TocSheetContent`/`TocListContent` 目录底部弹层（level 缩进、selected 高亮、≥48dp）；目录跳转滚动 + 进度上报；源码↔预览切换位置映射（U4） | F2.13 / F4.5 / U4 |
| `ui/reader/ReaderViewModel.kt` | 新增 `onTocJumpToParagraph`：目录跳转后**立即落库**（不经 500ms 防抖），保证跳转即退出也能重开恢复 | F2.13 / S3 方案① |
| `buildSrc/.../FixtureGenerator.kt` | 新增 `sample_toc.md` 夹具（H1~H6 多级标题 + 正文/列表 + 围栏代码块含 `#` 注释）；textChecks 加结构自检 | TC-F2-20a / §0 fixtures |

**测试代码**

- 新增：`core/parse/PlainTextParserTest.kt`、`ui/reader/MdViewMappingTest.kt`、`ui/reader/ReaderTocTest.kt`（3 个新测试文件）
- 改写/扩充：`core/render/MarkdownParserTest.kt`（补 startParagraphIndex、scanHeadings 用例，既有断言同步新字段）、`ui/reader/ReaderMarkdownPreviewTest.kt`（U4 切换映射 UI 用例）、`ui/reader/ReaderViewModelTest.kt`（md toc 加载 + 目录跳转持久化/重开恢复）
- 未动 `EpubParser.kt`、CSS、release 配置、docs/ 除本报告外文档；未手改 fixtures 产物、未使用 `rm`、未创建调试文件、未自行 commit。

## 2. 关键设计决策

1. **标题扫描与块解析共用函数（S10，防漂移）**：目录提取与 MarkdownParser 的标题识别统一收敛到 `atxHeading(line)`——仅开放形式 `^#{1,6}\s+`；**封闭 ATX（`# 标题 #`）返回 null（降级为普通段落，目录与预览行为一致）**，Setext（`===`/`---`）天然不满足 ATX 语法不识别。PlainTextParser 调 `scanHeadings` 而非另写一份，杜绝两版逻辑漂移。
2. **`startParagraphIndex` 语义（S4）**：每个 `MarkdownBlock` 携带"块起始行在源码段落流中的行号"；CodeBlock 锚定围栏起始行。源码 LazyColumn item 索引 == 段落索引这一不变量保持，预览 item 是块、与段落不同义，靠该字段建立双向坐标。
3. **block↔段落双向映射（S4/U4）**：`MdViewMapping`——段落→所属块（取 startParagraphIndex ≤ p 的最大块，含落在围栏内的行归其 CodeBlock）；块→段落（取 startParagraphIndex）；可见段落→目录项（取 paragraphIndex ≤ p 的末项）。
4. **S3 方案①：预览跳转进度上报**：目录跳转后（源码与预览两视图）统一经 `ReaderViewModel.onTocJumpToParagraph` **立即落库**；预览模式下普通滚动仍不上报（既有机制不破坏），仅目录跳转例外；重开即回到该标题段落。
5. **U4 切换映射**：源码↔预览切换前记录当前首可见项，重组后由 `LaunchedEffect(mdPreview)` 滚到映射目标——源码→预览按"段落所属块"，预览→源码按"块起始段落"，保持阅读语义位置而非"同序号不同内容"。切换后源码视图的 snapshotFlow 进度上报自然恢复。
6. **高亮边界（S11）**：首个可见段落位于第一个目录标题之前时 selectedIndex = -1（无显式高亮），TocListContent 以 `selected` 语义承载，点击目标 ≥48dp。
7. **夹具只经生成器**：`sample_toc.md` 只改 `FixtureGenerator.kt`，产物由 `generateFixtures` 自动落到 test resources 与 debug assets（不入库）。
8. **取消约定（AGENTS §5）**：`scanHeadings` 为阻塞式非挂起函数（供 UI remember 与 PlainTextParser 共用），不能 `ensureActive`，故循环内检查 `Thread.currentThread().isInterrupted` 尽早止损；中断位不消费，交由外层协程处理（withContext 出口会转 CancellationException）。

## 3. 测试结果

- 命令：`./gradlew testDebugUnitTest`（含 `generateFixtures` 自动前置）。
- 结果：**BUILD SUCCESSFUL，178 通过 / 0 失败**（= 基线 160 + 新增 18）。
- 新增/扩充用例对应：
  - 解析层：标题扫描（ATX 各层级、行内标记剥离、围栏代码块跳过、封闭 ATX 与 Setext 不识别、无标题空 toc）；`startParagraphIndex` 映射正确性（含 CodeBlock 锚定围栏起始行）；PlainTextParser 经 `sample_toc.md` 校验 toc 层级/文本/段落索引精确锚定与代码注释不入目录；xml toc 为空。
  - ViewModel：md 加载 → Document.toc 非空；`onTocJumpToParagraph` 立即落库 → 重开恢复回到该标题段落。
  - UI（Compose on Robolectric）：顶栏「目录」图标显隐（TC-F2-20b）；`TocSheetContent`/`TocListContent` 直驱（点击回调携带 TocEntry、selected 语义、S11 -1 无高亮）；U4 源码↔预览来回切换经映射落到语义一致位置（TC-F2-20 补断言）。
- 性能断言沿用既有 `MarkdownParser.parse` 大输入耗时兜底；toc 提取为解析中单次线性扫描，无额外 IO（不重复全文件扫描）。

## 4. 遗留问题 / 待 Kimi Code 关注

1. **围栏识别仅限行首**：目录扫描与 MarkdownParser 同源——只识别"trimStart 后以三个反引号开头的行"进入/离开围栏；**列表/引用内嵌套围栏不识别**，其 `#` 内容会按普通段落处理（可能被当普通文本而非标题，风险低）。已在 `scanHeadings` KDoc 明示，如需支持嵌套围栏属后续增强。
2. **预览↔源码切换语义精度**：U4 按"块起始段落"回源码，若源码首可见恰在代码块**内部**某行，切换后回到块顶（围栏起始段），非精确到原行内偏移——设计文档只约定到段落级映射，可接受；如需行内级保持属增强。
3. **目录跳转在 Robolectric 无法全链路自动化**：ModalBottomSheet 内容不进 Robolectric 语义树（M10 既定限制），面板主体以无状态 composable 直驱 + 映射/VM 单测覆盖；「点击目录项 → 实际滚动定位」的最终验收仍须真机执行 TC-F2-20a。
4. **真机回归**：TC-F2-20a/b 与 U4 切换、P0 回归（OPPO Reno9）由 Kimi Code 侧执行。
