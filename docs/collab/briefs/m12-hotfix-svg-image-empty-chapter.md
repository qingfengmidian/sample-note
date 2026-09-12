# M12 热修简报：正文 SVG 内嵌位图提取 + 空章跳过（F4.7 连续滚动断链）

日期：2026-09-12　框定：Kimi　实现：OpenCode　状态：待实现

## 背景（真机缺陷）

真机验收 M12 时用户反馈：**「当前通过滑动只能看完一章内容，无法自动续接下一章」**（邓小平文选 epub，已在最近打开列表）。模拟器（Pixel 7 API 37，同款构建）复现并定案：

- 连滑多章续接**正常**（动员新兵 → 党与抗日民主政权 → … 全程贯通），非「章界卡死」；
- 打开《邓小平文选》落在**空白页**，滑动完全无效——这才是用户遭遇的实际缺陷。

## 根因（三层，同一 bug 家族：空章卡死）

《邓小平文选》前 4 个 spine 章（封面页/书名页/版权页/目录页）是**扫描图片页**，正文以 `<svg><image xlink:href="../Images/*.jpeg"/></svg>` 内嵌位图（`OEBPS/Text/cover_page.xhtml`、`part0000~0002.xhtml` 实证）。EpubParser 只提取 `<img src>`，svg 内嵌 `<image>` 不提取 → 这些章解析为 `paragraphs=[] && images=[]` 的**纯空章**：

1. **打开即空章**：首章空 → `assembleReaderItems` 产出 0 item → LazyColumn 无项；`ReaderScreen.kt` 触底追加触发器条件 `lastVisible >= 0`（`visibleItemsInfo.lastOrNull() ?: -1`）永不成立 → 白屏卡死，滑动无效；
2. **书中空章断链**：章 N 非空、N+1 空时，滑到 N 尾部触发 `appendNextChapter` 物化 N+1（+0 item）→ snapshotFlow 计算值（lastVisible 索引）不变不重发 → N+2 永不追加，卡在 N 尾部；
3. 退化情形：svg 矢量-only 章（内嵌 .svg）按评审 S6 本就不提取，同样产生空章。

## 修复设计（两层，均已先行失败测试）

### 层 1：解析层——正文 svg 内嵌位图提取（F2.14 扩展）

`EpubParser.extractWithPull2` 的 START_TAG 分支新增 `tag == "image"` 处理（与 `img` 并列）：

- href 读取：**先 xlink 命名空间** `getAttributeValue("http://www.w3.org/1999/xlink", "href")`，后无命名空间 `getAttributeValue(null, "href")`（epub3 svg 用裸 `href`）；两者皆有取 xlink 优先（epub2 习惯，与 `innerRasterHref` 同思路）；
- **`.svg` 结尾（不区分大小写）不提取**（矢量不可渲染，评审 S6/S9 契约不变）；空/空白 href 跳过；
- src 经 `resolveHref(chapterDir, …)` 规范化为 manifest 形式（如 `OEBPS/images/page.jpeg`）；
- owner 归属规则与 `img` **完全一致**（段内有未 flush 文本归当前段，否则挂最近已 flush 段、章首兜底 0）→ 纯 svg 图章自动命中既有「合成空宿主段」逻辑（`parseCore` 450 行，`isEmpty() && images.isNotEmpty()` → `listOf("")`），章节非空；
- **END_TAG 对称性**：`<image/>` 自闭合 kxml2 报 START+END 两个事件。START 走新分支**不得压样式帧**，END 必须把 `tag == "image"` 加入跳过名单（现有 `tag == "img" || tag in voidTags -> {}` 处），否则会把 svg 帧 pop 掉破坏样式栈。`<svg>` 根本身保持现状（压帧/pop 对称）；
- alt：`<image>` 无 alt 概念，恒 null；isCover 恒 false（封面走 `resolveCover` 不动）。

### 层 2：VM 层——空章跳过（防御纵深，F4.7 补洞）

解析层修复后仍可能产生空章（矢量-only、真空章），VM 保证连续性：

- `ReaderViewModel.appendNextChapter()`：追加时**越过连续空章**——从 `materializedChapterCount` 起，向前扫描跳过 `paragraphs.isEmpty()` 的章，直达下一非空章或书尾；「只增不减」语义保持（扫描天然单调）；空章判定与 `ChapterItemMap` 宽度契约一致（宽度 = paragraphs.size）；
- `ReaderViewModel.load()`（非 txt 路径）：恢复/起始 `chapterIndex` 若为空章且全书存在非空章 → 前进到其后第一个非空章，`restoreParagraph/currentParagraph` 归零、`restoreItem = globalItem(新章, 0)`、`materializedChapterCount = minOf(新章+1, 章数)`；全空书保持原章不越界；
- `selectChapter`/`onTocJumpToItem` **不改**：`globalItem(空章,0)` = 下一非空章首 item（ChapterItemMap 偏移契约），滚动后 `onFirstVisibleItemChanged` 自愈修正当前章；`ensureMaterialized` 不动。

## 约束（协作不变量）

- **不得修改红测试断言**（三次先例，冲突暂停交回 Kimi 裁决）；
- 注释/文档中文，引用 TC/F 编号；循环内取消检查（C4）不变；
- 不改公共模型签名（`ParagraphImage`/`Chapter` 不变）；`Document.toc` 语义不变；
- 单测基线 248 用例 0 失败 + 新增用例；Robolectric 4.13 运行。

## 验收

1. 新增解析层 3 例 + VM 层 3 例全绿；
2. 全量 `./gradlew testDebugUnitTest` 绿（≥254 用例）；
3. 模拟器装新包打开 `/sdcard/Download/dxp.epub`：首屏显示封面/书名页图片（非白屏），连滑可从最前到最尾；`one-to-infinity.epub` 回归正常。

## 影响面

`EpubParser.kt`（extractWithPull2 两个分支）、`ReaderViewModel.kt`（appendNextChapter/load）、测试两个文件。提交计划：红（测试）→ 绿（实现）两次提交，沿用 main 直提 + 用户批准。
