# M12b 章级窗口与全局 item 坐标（ViewModel 层）— 实现简报（Kimi Code → OpenCode）

> 分支：`main`（沿用本项目直提 main 的既有做法）。**红测试已提交**（TDD 红）：`ChapterItemMapTest` 5 例全红。
> 需求：F4.7（跨章连续滚动）、F4.8（进度条，本批次只提供坐标底座）；
> 设计：`docs/软件设计文档.md` §3.8「跨章连续滚动（F4.7）与坐标系」；用例：TC-UI-10/11；计划：`docs/开发计划.md` M12b。
> 上一批次：M12a（解析层，已审查通过并提交，见 `docs/collab/reviews/m12a-review.md`）。

## 目标

为连续滚动准备 **VM 层坐标系与窗口状态**：全局 item 索引 ↔ (章, 段) 换算、章级渐进物化、滚动上报换算落库、恢复目标换算。**本批次不动 UI**（M12c 才切换渲染），因此全部改动为**加法式**：既有 API 与行为保持不变。

## 已定型的契约（签名与语义不得改）

- `ui/reader/ChapterItemMap.kt`（Kimi 已声明，方法体为 `TODO("M12b 实现")`）：`offsets`、`totalItems`、`chapterCount`、`firstItemOfChapter`、`locate`、`globalItem`、`ChapterPosition`。语义见该文件 KDoc——**clamp 不抛异常、段落数 0 的章宽度为 0、空书退化 (0,0)**。
- `ChapterItemMapTest`（5 例）为验收标准，**断言不得修改**；如认为断言与设计冲突，暂停交回。

## 实现任务

### 1. `ChapterItemMap` 实现（`ui/reader/ChapterItemMap.kt`）

按 KDoc 语义实现三个方法与两个属性；纯函数、无 Android 依赖、不改签名。建议 `IntArray` 前缀和一次构建，`locate` 用二分或线性扫描（章数量级 ≤ 数百，线性可接受）。

### 2. `ReaderViewModel` 加法式扩展（`ui/reader/ReaderViewModel.kt`）

新增状态（`ReaderUiState`）：

- `materializedChapterCount: Int = 0`——已物化进 item 列表的章数（初始 1；恢复时 = 恢复章 + 1）；
- `totalItems: Int = 0`——全书 item 总数（= `ChapterItemMap.totalItems`）；
- `restoreItem: Int? = null`——一次性全局 item 滚动目标（M12c 消费；与既有 `restoreParagraph` 并存，后者语义不变）。

新增方法（语义即契约）：

- `fun appendNextChapter()`：`materializedChapterCount < chapters.size` 时 +1，否则空操作（末章停止）；
- `fun ensureMaterialized(chapter: Int)`：把 `materializedChapterCount` 扩到 `max(当前, chapter + 1)`，clamp 到 `chapters.size`；越界 chapter 不报错；
- `fun onFirstVisibleItemChanged(itemIndex: Int)`：经 `ChapterItemMap.locate` 换算 → 更新 `currentParagraph` 与 `chapterIndex`（当前章跟随滚动位置）→ 走既有 **500ms 防抖** 落库 `(chapter, paragraph)`；
- `fun consumeRestoreItem()`：与 `consumeRestoreParagraph()` 同型，清除一次性目标。

load 路径接入（非 txt）：构造 `ChapterItemMap`，以恢复坐标 `(chapterIndex, currentParagraph)` 设 `restoreItem = map.globalItem(...)`、`totalItems = map.totalItems`、`materializedChapterCount = min(chapterIndex + 1, chapters.size)`。

txt 分页路径：单章 → `materializedChapterCount = 1`、`totalItems = 首章段落数`；`loadMore()` 追加块后同步刷新 `totalItems`（只 append，索引稳定契约不变）。

### 3. 测试（由你补齐并跑绿）

新增 `app/src/test/java/com/sample/docreader/ui/reader/ReaderViewModelWindowTest.kt`（Robolectric，参照既有 `ReaderViewModelTest` 的构造方式与 `ProgressRepository` 断言手法），至少覆盖：

1. 打开多章 epub（用夹具 `sample.epub`，3 章）→ 初始 `materializedChapterCount == 1`、`totalItems == 各章段落数之和`；
2. 带进度恢复（`ProgressRepository` 预置 `(chapter=2, paragraph=1)`）→ `materializedChapterCount >= 3`、`restoreItem == globalItem(2, 1)`；
3. `appendNextChapter()` 递增至末章后不再变化（连续调用两次后仍 == 章数）；
4. `ensureMaterialized(5)` → 扩到 6（或 clamp 到章数）；`ensureMaterialized(999)` 不抛异常；
5. `onFirstVisibleItemChanged(globalItem(1, 2))` → 防抖后落库坐标为 `(1, 2)`，且 `chapterIndex == 1`；
6. txt 路径：`loadMore()` 追加后 `totalItems` 等于首章段落数（增长后）。

## 允许修改

- `app/src/main/java/com/sample/docreader/ui/reader/ChapterItemMap.kt`
- `app/src/main/java/com/sample/docreader/ui/reader/ReaderViewModel.kt`
- `app/src/test/java/com/sample/docreader/ui/reader/ReaderViewModelWindowTest.kt`（新增）

## 不得修改

- `ChapterItemMapTest.kt`（红测试断言）；
- 既有 UI（`ReaderScreen.kt`/`ReaderTextView.kt`/`MarkdownPreview.kt`）与 `MdViewMapping.kt`——M12c 范围；
- `core/` 解析层与模型、`ProgressRepository`/`AppDatabase`（**进度落库坐标语义必须仍是 `(chapter, paragraph)`，零迁移**）；
- 既有 `ReaderViewModelTest`/`ReaderViewModelPagedTest`/`ReaderTocTest` 等测试与 `selectChapter`/`onFirstVisibleParagraphChanged`/`restoreParagraph`/`loadMore` 的既有行为。

## 必须保持的不变量

- 既有全部单测保持绿（M12a 后基线 223 用例 + 本批次新增）；
- 旧 API 行为不变（UI 在 M12c 才切换）；
- 进度落库坐标仍为 `(chapter, paragraph)`；防抖 500ms 语义不变；
- 解析协程取消、`loadMore` 防重入与「只 append」契约不变。

## 非目标

- UI 层（多章渲染、进度条组件、目录面板、顶栏留空）→ M12c；
- 「解析层惰性化」（`parseCore` 仍全量解析，设计 §3.8 已记）；
- 已读章回收（决策日志 D-2026-09-11-03：只追加不回收）。

## 验收标准

- `ChapterItemMapTest` 5 例全绿；`ReaderViewModelWindowTest` 新增用例全绿；
- 全量 `./gradlew testDebugUnitTest --rerun-tasks` 全绿（应为 228 用例，0 失败）；
- `./gradlew generateFixtures validateFixtures` 通过。

## 应运行的验证

```bash
./gradlew testDebugUnitTest --rerun-tasks
./gradlew generateFixtures validateFixtures
./gradlew assembleDebug
```

## 允许的工具和权限

只读检查、上列文件编辑、`./gradlew assembleDebug testDebugUnitTest check generateFixtures`。**不得** `git add/commit/push`、不得引入依赖、不得删除文件。

## 升级条件（暂停并交回 Kimi Code）

- 需要改动 UI 或数据层才能满足本批次契约；
- 契约（方法签名/语义）与既有测试冲突；
- 发现 `locate`/`globalItem` 的 clamp 语义存在未覆盖歧义（如空章占比、txt 追加期的 totalItems）；
- 需要改动上文「不得修改」清单内文件。

## 完成前请

1. 检查实际 diff（含未跟踪文件）；
2. 运行上述验证并贴真实输出摘要；
3. 报告：改动清单 / 验证命令与结果 / 未验证项 / 剩余风险；
4. 不自行提交、不扩大范围。
