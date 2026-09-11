# M12c 阅读器连续滚动 + epub 原生目录 + 进度条（UI 层）— 实现简报（Kimi Code → OpenCode）

> 分支：`main`。**红测试已在工作区**：`ReaderItemAssemblyTest` 5 例全红（`assembleReaderItems` 为 TODO 接缝）。
> 需求：F4.7（跨章连续滚动）、F4.8（进度条）、F2.16（epub 目录原样 + 锚点跳转，UI 侧）、F2.13（md 目录不回归）；
> 设计：`docs/软件设计文档.md` §3.8；用例：TC-F4-09/10、TC-F2-25/26/27、TC-UI-10/11/12；计划：`docs/开发计划.md` M12c。
> 前置：M12a 解析层（`Document.toc` 已产出 + 锚点映射）、M12b VM 层（`ChapterItemMap` + 窗口状态与方法）均已提交。

## 目标（用户可观察）

1. 阅读 epub 时从全书开头**连续滑动到结尾**（滚近章尾自动追加下一章），不再需要经章节菜单换章；
2. 右缘有**全书进度条**，显示当前位置并可拖动跳转；
3. epub 目录面板 = **文件自带目录原样**（标题/层级/条目数），点击条目**定位到锚点段落**；无目录的书不显示目录入口；
4. 当前章节无目录条目时**顶栏标题留空**（不再回落书名，也不再出现「第 N 章」）；
5. md 的源码/预览切换与 md 目录面板行为**零回归**。

## 已定型的契约（签名与语义不得改）

- `ui/reader/ReaderItemAssembly.kt`（Kimi 已声明，方法体 TODO）：`ReaderItem(chapter, paragraph, text, images, styles, isChapterFirst)` + `assembleReaderItems(chapters, materializedCount)`。语义见该文件 KDoc（窗口 clamp、0 段落章不产出 item、图片按 `paragraphIndex` 挂段且保持相对顺序、样式按章内段落索引、`isChapterFirst` 仅章首项）。
- `ReaderItemAssemblyTest` 5 例为验收标准，**断言不得修改**；若认为断言与设计冲突，暂停交回。

## 实现任务

### 1. 组装与渲染（`ui/reader/ReaderItemAssembly.kt`、`ReaderTextView.kt`）

- 实现 `assembleReaderItems`（纯函数）。
- `ReaderTextView` 改为接收 `items: List<ReaderItem>` + `settings` + `listState` + `imageRoot`：
  - item `key = "${chapter}:${paragraph}"`（稳定复合键，只 append 不重排）；
  - **F2.14/F2.15 内部逻辑一律复用**（`ChapterImage`、`ImagePlaceholder`、`applyParagraphHints`、WRAP/NO_WRAP 双路径、`clearAndSetSemantics` 防双播报）；图片/样式直接取 `item.images`/`item.styles`，**不得再按下标查章内表**；
  - `SelectionContainer`、既有 testTag/语义键（`READER_CONTENT_TAG`、`ReaderBackgroundColorKey` 等）保持不变；
  - `isChapterFirst` 目前不要求额外视觉（不加章间标题）；如需极致留白由你决定，但不得破坏既有排版断言。

### 2. 窗口接线（`ReaderScreen.kt`）

- 正文数据：`assembleReaderItems(document.chapters, state.materializedChapterCount)`（`remember` 缓存，键为 document + materializedChapterCount）。
- **触底追加**：`snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }`，当 `>= items.size - PREFETCH_MARGIN`（复用 `PagedTextLoader.PREFETCH_MARGIN`）且 `materializedChapterCount < chapters.size` → `viewModel.appendNextChapter()`。与 txt `loadMore()` 并存（txt 走原有分页逻辑，不得重复触发）。
- **滚动上报**：`snapshotFlow { listState.firstVisibleItemIndex }` → `viewModel.onFirstVisibleItemChanged(index)`；**md 预览模式仍跳过**（M11a 既有规则），md 目录跳转仍走 `onTocJumpToParagraph`（单章坐标等价，不回归）。
- **恢复**：`state.restoreItem` 一次性 → `listState.scrollToItem(it)` + `consumeRestoreItem()`；`restoreParagraph` 路径保留给 md 预览切换语义（`switchScrollTarget`/`pendingParagraph` 机制不变）。
- **顶栏标题**：`chapter?.title?.takeIf { it.isNotBlank() }`——**删除书名回落**（R3；当前 `:360`）。
- **章节/目录面板**：数据源改为 `document.toc`（epub 与 md 统一）：
  - 条目按 `level` 缩进、当前条目高亮 + `selected` 语义（既有交互保留）；`toc.isEmpty()` → 顶栏入口隐藏（md 既有行为推广到 epub）；
  - epub 条目点击：`viewModel.ensureMaterialized(entry.chapterIndex)` → `listState.scrollToItem(ChapterItemMap(chapters).globalItem(entry.chapterIndex, entry.anchorParagraph ?: 0))` → **立即落库**（新增 VM 方法 `onTocJumpToItem(itemIndex)`，语义同 `onTocJumpToParagraph` 但坐标为全局 item）；
  - md 条目点击：沿用既有 `tocJumpParagraph`（`paragraphIndex`）路径，不得回归；
  - **删除 `ReaderScreen.kt:775` 的 `"第 ${index + 1} 章"` 兜底**（M12a 审查 F3 的硬要求）：面板不再按 spine 逐章生成条目。
- **上一章/下一章**：保留；`viewModel.selectChapter(state.chapterIndex ± 1)` 语义扩展为「切章 + 物化到该章 + `restoreItem = globalItem(index, 0)`」（`restoreParagraph` 既有行为保留，md 不受影响）。

### 3. 进度条（新增 `ui/reader/ReaderProgressBar.kt`）

- 右缘 overlay 轨道 + 拇指；比例 = `firstVisibleItemIndex / (totalItems - 1)`（`totalItems <= 1` 时按 0 处理，不除零）；
- 拖动（`detectDragGestures`/`pointerInput`）→ 目标 item：`viewModel.ensureMaterialized(targetChapter)`（先经 `ChapterItemMap.locate`）→ `listState.scrollToItem(target)`；
- 无障碍与配色硬要求：**触摸目标 ≥48dp**（可用 `minimumInteractiveComponentSize` 或外层 padding 放大热区）、`contentDescription`（如「阅读进度，第 x/y 段」）、`testTag(READER_PROGRESS_TAG)`、配色随 `ReadingTheme` 且与背景对比度 ≥4.5:1；
- 设置面板/目录面板打开时隐藏（避免遮挡弹层）。

### 4. VM 小改（允许）

- 新增 `fun onTocJumpToItem(itemIndex: Int)`（`locate` 换算 + **立即落库**，不经防抖）；
- `selectChapter(index)` 扩展为同时 `ensureMaterialized(index)` 并置 `restoreItem = globalItem(index, 0)`；既有 `restoreParagraph = 0` 与立即落库行为保持不变。

### 5. 测试（由你补齐并跑绿）

- Robolectric（`app/src/test/.../ui/reader/`，参照 `ReaderWrapPositionTest` 的「外部注入 LazyListState + `scrollToItem` 直驱」手法）至少覆盖：
  1. **TC-UI-10** 物化追加：多章 epub，直驱滚动接近物化尾部 → `materializedChapterCount` 递增；滚到末章后不再增长；已产出 item 坐标不变；
  2. **TC-UI-11** 进度条：比例 = `index/(totalItems-1)`；拖动到中部/末尾 → 目标 item 换算正确且触发物化；
  3. **恢复**：预置进度 (章, 段) → `restoreItem` 被消费并滚动到该全局 item；
  4. **F2.16 面板**：`anchor_toc.epub` → 面板条目 = NCX 5 条（标题/层级原样），点击锚点条目定位到锚点段落（可断言换算目标 item）；无目录 epub（`no_nav.epub`）顶栏无入口；
  5. **R3 顶栏**：未编入目录的章节 → 顶栏标题为空（不显示书名/「第 N 章」）；
  6. **回归**：md 源码/预览切换与 md 目录面板既有用例（`ReaderMarkdownPreviewTest`/`ReaderTocTest`/`ReaderChapterListTest`/`ReaderWrapPositionTest`/`ReaderStyleMergeTest`/`ReaderImageTest` 等）必须保持绿——若因签名变更需同步调整测试，**只允许机械式适配（构造参数/key），不得放松断言**。
- **设备端**（`app/src/androidTest/`，TC-UI-12，新增 `ReaderWindowDeviceTest`）：真实导航打开 `anchor_toc.epub` → 目录面板条目与 NCX 一致 + 点击锚点条目定位准确；进度条存在且拖动后位置变化（语义断言）。若设备不可用，明确报告未运行。

## 允许修改

- `ui/reader/ReaderItemAssembly.kt`、`ReaderTextView.kt`、`ReaderScreen.kt`、`ReaderProgressBar.kt`（新增）
- `ui/reader/ReaderViewModel.kt`（**仅**第 4 节的两种小改）
- `app/src/test/.../ui/reader/**`（新增用例 + 因签名变更的机械式适配）
- `app/src/androidTest/**`（新增设备端用例）

## 不得修改

- `ReaderItemAssemblyTest.kt` 断言；`MdViewMapping.kt` 的映射语义；`ChapterItemMap`/`ChapterItemMapTest`；
- `core/` 全部（解析层/模型已定稿）、`ProgressRepository`/`AppDatabase`（**进度坐标仍为 `(chapter, paragraph)`，零迁移**）；
- 既有 md 目录/预览行为、`ReaderSettingsSheet`/主题/字体/图片/样式合并的既有语义。

## 必须保持的不变量

- 既有全部单测保持绿（M12b 后基线 234 用例 + 本批次新增；允许机械式适配签名，不允许弱化断言）；
- F2.14 图片挂段、F2.15 样式合并、`SelectionContainer` 选择复制、无障碍语义（含防双播报）零回归；
- 滚动位置保持三件套（换行切换 `pendingParagraph`、U4 切换目标、恢复目标）语义不变；
- 单一写者：本批次结束后工作区无无关改动。

## 非目标

- 解析层改动、章节间视觉分隔/动画、进度条之外的阅读器视觉改版、M13（弹层强调色）/M14（双语）。

## 验收标准

- `ReaderItemAssemblyTest` 5 例全绿；第 5 节 Robolectric 用例全绿；`./gradlew testDebugUnitTest --rerun-tasks` 全绿（预计 240+ 用例、0 失败）；
- `./gradlew generateFixtures validateFixtures assembleDebug` 通过；
- 设备端 `connectedDebugAndroidTest`（TC-UI-12）通过或明确报告未运行原因。

## 应运行的验证

```bash
./gradlew testDebugUnitTest --rerun-tasks
./gradlew generateFixtures validateFixtures assembleDebug
./gradlew connectedDebugAndroidTest   # 设备可用时（当前 OPPO WiFi adb 在线）
```

## 升级条件（暂停并交回 Kimi Code）

- 需要改解析层/模型/数据层才能完成；
- 既有用例的适配无法保持「机械式」而必须改语义；
- `ReaderItemAssemblyTest` 或既有断言与设计冲突；
- 进度条无障碍/对比度要求与既有主题实现冲突。

## 完成前请

1. 检查实际 diff（含未跟踪文件）；
2. 运行上述验证并贴真实输出摘要；
3. 报告：改动清单 / 验证命令与结果 / 未验证项 / 剩余风险；
4. 不自行提交、不扩大范围。
