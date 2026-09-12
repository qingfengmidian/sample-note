# M12c 审查记录（Kimi Code → OpenCode 实现）

> 批次：M12c 连续滚动 + epub 原生目录面板 + 全书进度条（UI 层，M12 收口）。简报：`docs/collab/briefs/m12c-continuous-scroll.md`。
> 实现方：OpenCode（`opencode run --auto`）。审查方：Kimi Code。红状态提交：`713e3e0`。

## 一、Kimi 独立复验

- `./gradlew testDebugUnitTest --rerun-tasks` → **tests=248 failures=0 errors=0 skipped=0**（自解析 test-results XML），较 M12b 基线 234 增加 14（红测 5 + 新增 8 + 适配后净增）。
- 覆盖核对（读完整 diff）：

| 检查项 | 结论 |
| --- | --- |
| `assembleReaderItems` | ✅ 窗口 clamp、0 段章不产出、图片 `groupBy(paragraphIndex)` 一次分组（保序、不跨段）、样式取章内索引、`isChapterFirst` 仅章首 |
| `ReaderTextView` 重构 | ✅ F2.14/F2.15 内部逻辑（`ChapterImage`/`ImagePlaceholder`/`applyParagraphHints`/WRAP·NO_WRAP/语义键）原样复用；图片/样式改为直接取 `ReaderItem`；key = `"章:段"` 稳定复合键 |
| 触底追加 | ✅ 非 txt 专用；`PREFETCH_MARGIN` 语义；`materializedChapterCount < 章数` 才追加；txt 仍走原 `loadMore`（互斥不重复） |
| 恢复与跳转 | ✅ `restoreItem` 一次性消费（含同步消费 `restoreParagraph`）；单章/md 兼容路径保留；`pendingItemScroll` 处理「目标超出当前物化范围」并待重组后消费 |
| 进度条 | ✅ 纯函数 `progressFraction`/`progressTargetItem`（`totalItems<=1` 不除零、clamp）；48dp 热区、`READER_PROGRESS_TAG`、`contentDescription`、比例语义键、`theme.foreground`；面板/预览打开时隐藏 |
| epub 目录面板 | ✅ 数据源 = `Document.toc`（原样条目），`ChapterListContent` 与 `"第 N 章"` 兜底**已删除**（grep 无残留）；空 toc 隐藏顶栏入口；条目点击 = `ensureMaterialized` + `globalItem` 跳锚点 + 立即落库；`epubTocSelectedIndex` 纯函数（首个条目之前 -1，与 md 的 S11 一致） |
| 顶栏（R3） | ✅ 去掉书名回落，无目录条目即留空 |
| md 零回归 | ✅ md 面板/预览映射/`switchScrollTarget`/`tocJumpParagraph` 路径未改；`ReaderMarkdownPreviewTest`/`ReaderTocTest`/`ReaderWrapPositionTest`/`ReaderStyleMergeTest`/`ReaderImageTest` 全绿 |
| 范围与红线 | ✅ 仅 `ui/reader/**` + 测试；`core/`、`ProgressRepository`/`AppDatabase`、`ChapterItemMap` 及其测试、`MdViewMapping` 未动；进度坐标仍 `(chapter, paragraph)` |

## 二、审查发现与处置

### F1（测试笔误，已由 Kimi 更正）「全空章书」用例夹具参数写错

- 我写的 `assembleReaderItems(sizedChapters(3, 0, 0), …).isEmpty()`（三章 3/0/0 段）与用例名「全空章书」矛盾；应为 `sizedChapters(0, 0, 0)`。
- OpenCode 未改断言、按升级条件暂停并给出二选一建议，判断正确（**本项目第三次由实现方拦下审查方的测试缺陷**：M12a、M12b、M12c 各一次）。
- 处置：Kimi 更正为 `(0, 0, 0)`（仅此一处）→ 248/0 全绿。

### F2（接受，记录为语义性适配）`ReaderChapterListTest` 由 spine 改为 toc 断言

- 面板数据源改为文件自带目录后，旧断言对象（`"第 3 章"` 等生成条目）本就不应存在（M12a 审查 F3 的硬要求）。核对新断言：**更严**而非放松（新增 `assertIsNotEnabled`/`assertIsEnabled`/`assertDoesNotExist`，并断言条目携带 `chapterIndex`/`anchorParagraph`）。接受。

### F3（遗留，低优先级）`ReaderContent.onSelectChapter` 成为无消费方参数

- OpenCode 未删（避免扩散改动）。功能无影响；记入 M12 收尾遗留清单，待后续批次或 M13 一并清理。

### F4（遗留）空态判定上移到整本

- `isEmptyDoc` 改为「全章段落皆空白且无图」；多章书中单章为空不再误报「文件为空」（符合连续滚动预期），TC-F2-24 空文件用例仍绿。接受并记录为行为差异。

### F5（未验证）设备端 TC-UI-12 未执行

- 审查时 `adb devices` 为空（OPPO WiFi adb 不在线，`kill-server/start-server` 未恢复）。`ReaderWindowDeviceTest`（2 例）已写入 `androidTest`，**待真机/模拟器在线后补跑**；同时登记到 `docs/collab/pending-real-device-tests.md`。

## 三、遗留清单（M12 收尾处理）

1. 设备端 TC-UI-12 补跑（目录面板条目与 NCX 一致 + 锚点定位 + 进度条拖动）；
2. 真机视觉/手感项：进度条对比度与拖动手感、连续滚动流畅度、大书物化内存画像、《邓小平文选》目录原样与「出版说明」顶栏留空；
3. `ReaderContent.onSelectChapter` 死参数清理（F3）；
4. `check` 的两项环境/结构性限制（lint 需代理、release 变体缺 `ui-test-manifest`）在开发计划中标注；
5. 进度条目前仅支持拖动（无点击轨道跳转、无 TalkBack 调节动作）——如需可作 F4.8 后续增强。
