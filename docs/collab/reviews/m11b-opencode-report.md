# M11b 实施报告 — epub 插图渲染（F2.14）+ 缓存解压 + C4 取消粒度

| 项目 | 内容 |
| --- | --- |
| 任务简报 | `docs/collab/briefs/m11b-epub-images.md`（OpenCode 实施） |
| 基线 | main @ `53f31ba`（M11a 已合），单测基线 **178 通过 / 0 失败** |
| 实施者 | OpenCode |
| 完成日期 | 2026-09-06 |

## 1. 改动清单

### 主代码
| 文件 | 改动 |
| --- | --- |
| `core/model/Models.kt` | `Chapter` 增 `images: List<ParagraphImage>`；新增 `ParagraphImage(paragraphIndex, src, alt, isCover)`（设计 §3.1 既有字段落位）；`Document` 增 `imageRoot: String?`（epub 缓存目录绝对路径，渲染层据 src 定位文件）。字段语义与既有字段零冲突（均追加在尾、带默认值） |
| `core/parse/EpubParser.kt` | 重构为统一同步核心 `parseCore(files)` + 惰性数据源 `EpubFiles`（`MapFiles` 内存 / `DirFiles` 磁盘）；新增 `parseFromDir(dir, uri, name)`（生产缓存路径，正文不整书驻留，评审 C1）；`parseBytes` 保留（单测/缓存回退）；`<img>` 提取为 `ParagraphImage`（行内归段/块级挂前段/svg 保留 src，src 按章节目录解析为 manifest 规范化 zip 路径）；封面识别 `meta name="cover"` / `properties="cover-image"` + svg 封面内嵌 `<image>` 位图提取（纯矢量 svg 不提取，评审 S6）；XHTML 抽取循环 **段级取消检查**（C4）+ `runInterruptible` 包裹解析入口 |
| `core/io/EpubCache.kt`（新建） | 单实例 LRU 解压缓存：`cache/epub/<uri md5 前16位>/`；container.xml 作完整性标记（同书二次打开跳过）；解压循环段级取消 + 半成品清理；失败返回 null（不抛未受控异常）；zip-slip 防护（拒绝绝对路径/`..` 条目） |
| `ui/reader/ReaderViewModel.kt` | 构造参数增 `epubCache: EpubCache? = null`（默认空，既有单测零改动）；`load` 拆 `loadNonTxt`：epub 且缓存可用 → `runInterruptible` 解压 + `parseFromDir`；缓存未接入/失败回退整字节 `parseBytes`（图片走占位，不阻塞阅读） |
| `ui/reader/ReaderTextView.kt` | 增 `images`/`imageRoot` 参数；段落 item 内渲染图片（**不新增 LazyColumn 项**，item 索引==段落索引不变量，评审 S8）：封面（isCover）在首段文字上方、正文图在所属段落文字下方；`ChapterImage` composable——`produceState` + `Dispatchers.IO` 懒解码（LazyColumn 可见才组合）、`BitmapFactory` 先 bounds 再 `inSampleSize`（目标宽=2×屏宽）、`Modifier.aspectRatio` 等比；svg/缺失/失败/缓存不可用统一占位（主题前景 `Warning` 图标 + 「图片无法显示」，alt 非空显示 alt），占位与图片均带 `contentDescription`（评审 S9；无 alt 图给「封面」/「图片」通用语义） |
| `ui/reader/ReaderScreen.kt` | 阅读正文调用点传 `chapter.images` 与 `document.imageRoot`；ViewModel 工厂注入 `EpubCache(File(context.cacheDir, "epub"))` |
| `ui/FixtureEntryScreen.kt` / `MainActivity.kt` | 调试 fixtures 入口补 epub 样本与 `DocFormat.EPUB` 映射（MdImageDeviceTest 设备端走查所需，M11b 使能接线） |

### 测试与夹具
| 文件 | 内容 |
| --- | --- |
| `buildSrc/.../FixtureGenerator.kt` | 程序生成小位图（`javax.imageio`，纯软件编码）；新增 `sample_images.epub`（png/jpg 带 alt + svg）、`sample_cover.epub`（OPF meta cover 位图）、`broken_image.epub`（缺失条目带 alt + 截断位图）；`validate` 补三件套结构/媒体类型断言。**未手改 fixtures 产物** |
| `app/src/test/.../EpubParserTest.kt` | +7：块级/行内/svg 提取与 src 规范化、meta cover、properties="cover-image"、svg 封面内嵌位图 vs 纯矢量、broken_image 文本完整、无图 epub images 空、C4 段级取消（可注入 cancelCheck 确定性验证"中途即抛"） |
| `app/src/test/.../core/io/EpubCacheTest.kt`（新建） | +6：产物与 zip 条目一致、同书二次打开跳过、LRU 清理最旧且不删当前书、取消无半成品残留、失败容忍返回 null、`parseFromDir` 与 `parseBytes` 产出一致且 imageRoot 注入 |
| `app/src/test/.../ui/reader/ReaderImageTest.kt`（新建） | +3：缓存不可用（imageRoot=null）回退渲染——broken 两处占位（alt/默认语义）+ 正文仍在、无图 epub 无占位节点、封面占位渲染于正文之上 |
| `app/src/androidTest/.../MdImageDeviceTest.kt`（新建） | 设备端 TC-F2-17/17c/17d 语义断言：含 alt 位图 contentDescription=alt 且 displayed、svg 处占位、封面图置顶、broken 双占位 + 正文完整（`connectedDebugAndroidTest`，Pixel 7 执行） |

## 2. 关键设计决策

1. **数据源抽象（EpubFiles）统一两种解析路径**：`parseBytes`（单测/缓存失败回退）与 `parseFromDir`（生产缓存）共用同一同步核心，图片/章节/封面逻辑零分叉（EpubCacheTest 校验两路径产出一致）。缓存路径正文**不整书驻留内存**——每次仅读 OPF/导航/当前章节，峰值内存 ≈ 最大单章（评审 C1 顺带缓解）。
2. **img 归属语义**（设计 §3.2-3 落地，样本刻意覆盖三种布局）：段内已有文字 → 归当前未 flush 段；段间独立/纯图段 → 挂最近已 flush 段（渲染紧随其文字下方，阅读顺序自然）；`<img src=*.svg>` 仅保留 src（渲染层占位，svg 一律不直接渲染）。src 相对所在章节目录解析，输出 manifest 规范化 zip 路径（剥 `.`/`..`/`#fragment`/query）。
3. **封面**：优先 `meta name="cover"` 的 content id，回退 manifest `properties="cover-image"`；svg 封面读文件解析内嵌 `<image>` href（`href`/`xlink:href` 兼容）提取位图，纯矢量/内嵌缺失不提取（评审 S6）；声明存在但文件缺失静默跳过不阻塞。
4. **C4 取消粒度**：解析入口改 `runInterruptible(Dispatchers.IO)`（协程取消即中断工作线程），逐章 + XHTML **段级**循环检查中断抛 `CancellationException`，超大单章可及时取消。段级检查做成可注入 `cancelCheck`（生产默认线程中断）以支持确定性单测。
5. **缓存层**：以 `META-INF/container.xml` 作完整性标记（同书存在即跳过）；解压到 `tmp_<key>` 后原子 rename，取消/失败均清理本次目录（无半成品）；LRU 以**当前书解压后体积为预算**清理最旧目录且跳过当前书（"容量 ≤ 原书大小"语义按单书解压体积解释）；zip-slip 防护。
6. **图片管线**：图片挂在段落 item 内（评审 S8，进度/toc/换行位置三大机制零改动）；`produceState` 随 item 进入组合才在 `Dispatchers.IO` 解码（LazyColumn 天然懒加载）；先 `inJustDecodeBounds` 再 `inSampleSize`（解码宽 ≈ 2×屏宽），`aspectRatio` 等比。
7. **无障碍/语义**（S9）：解码成功 contentDescription = alt（无 alt → 通用「图片」/封面「封面」）；占位 contentDescription = alt（无 alt →「图片无法显示」），占位视觉为图标 + 文案、alt 非空时显示 alt。

## 3. 测试结果

- **TDD 红绿**：先加 fixtures + 解析层用例，`./gradlew testDebugUnitTest --tests EpubParserTest` **红 6 失败**（图片/封面/内嵌位图提取/文本完整断言未就绪）；实现后转绿。缓存层属新类（用例需先编译类存在），实现后以新写用例覆盖（报告注明）。
- **最终**：`./gradlew testDebugUnitTest`（含 generateFixtures/产物自动再生成）**198 通过 / 0 失败 / 0 跳过**（基线 178 + 新增 20：EpubParser +9、EpubCache +7、ReaderImage +3、ReaderViewModelCacheRecovery +1；含 Kimi Code 审查问题处置新增用例）。
- `generateFixtures validateFixtures` 通过（含新增含图三件套结构/媒体/目录条目断言）。
- `assembleDebugAndroidTest` 通过（MdImageDeviceTest 编译级校验；运行需 Pixel 7 `connectedDebugAndroidTest`，见遗留）。

## 4. 遗留问题

1. **设备端用例未实跑**：本机无设备，`MdImageDeviceTest` 仅编译通过；需 Kimi Code 在 Pixel 7 上 `connectedDebugAndroidTest` 执行 TC-F2-17/17c/17d（含真实 Bitmap 解码与 contentDescription 语义断言）。
2. **CSS 全部未做**（按要求留给 M11c）：`<style>`/内联 style、`ParagraphStyleHints`、`Chapter.styles`、styled/bad_css 夹具、TC-F2-17e/f 均未触碰。
3. **正文内联 `<svg>`/`<svg><image>` 未提取**：F2.14 范围仅正文 `<img>` + OPF 级封面 svg；若某书以 spine 封面页内嵌 `<svg>` 承载封面位图，本期不显示（属已知边界，未突破简报范围）。
4. **图片均在段落文字下方**：行内 `<img>` 无法精确"插在文字中间"（设计 §3.3 明确挂 item 内文字下方，属既定取舍）。
5. **LRU 预算口径**："容量 ≤ 原书大小"按**当前书解压后体积**解释（未解压前的压缩体积无实际约束意义）；缓存标记文件 `.last_access` 不入 zip 条目比对。
6. **NO_WRAP 下图片仍按屏宽渲染**（未并入横向滚动区），符合"图片按屏宽"需求，后续若需每图可横滑可再评估。
7. 未自行 `git commit`/`tag`；fixtures 产物由 Gradle 生成不入库。

## 5. 附：验证命令

```bash
./gradlew generateFixtures validateFixtures     # 通过
./gradlew testDebugUnitTest                     # 198 通过 / 0 失败
./gradlew assembleDebugAndroidTest              # 编译通过（运行待 Pixel 7）
```

## 6. 审查问题处置（Kimi Code I1/M1/M2/M3/L1/L3/L4 + fixtures）

> 逐条修复后全量 `testDebugUnitTest` **198 通过 / 0 失败**；每条含 修法 / 涉及文件 / 测试。

### I1（必修）zip 目录条目导致缓存解压必失败
- **修法**：`EpubCache.extractEntries` 对 `entry.isDirectory`（含 `name` 以 `/` 结尾）**直接跳过**，不再当文件写（避免 IOException 使整书解压失败→图片静默占位）。
- **文件**：`core/io/EpubCache.kt`。
- **fixtures**：`FixtureGenerator.epub()` 增 `directories` 参数，`sample_images.epub` 显式写入 `OEBPS/` 目录条目；`validate` 补该断言。
- **测试**：`EpubCacheTest.extract_epubWithDirectoryEntries_succeeds`（含目录条目解压成功、目录条目不落为文件、文件条目正常）。

### I2（必修）读侧 zip-slip 逃逸
- **修法**：`EpubParser.DirFiles.read` 对每次读取做**规范化路径包含校验**（canonicalPath 必须等于缓存根或在缓存根之下，`runCatching` 兜底拒绝），container/OPF/spine/nav 经朴素拼接的 `../` 一律按缺失处理，读不到缓存目录外文件。
- **文件**：`core/parse/EpubParser.kt`（`DirFiles`/`pathWithinDir`）。
- **测试**：`EpubParserTest.i2_readSideEscape_rejectedAsMissing`——container `full-path="../secret.opf"`（目录外确有该 OPF），断言抛 `MissingOpf`（拒绝读取）而非 `MalformedContent`（读到了外部文件）。

### M1（必修）缓存完整性只判 container.xml、解析失败不回退
- **修法**：`ReaderViewModel.loadNonTxt` 对 epub 缓存目录解析失败（`ParseException`）时**删除该缓存目录并重新解压一次**（`EpubCache.delete` + 重新 `extract`，force 语义），仍失败才回退整书字节解析；新增 `EpubCache.delete(uri)`。
- **文件**：`ui/reader/ReaderViewModel.kt`、`core/io/EpubCache.kt`。
- **测试**：`ReaderViewModelCacheRecoveryTest.partiallyMissingCache_autoReExtractRecovers`——预置"container.xml 在但 OEBPS/ch2.xhtml 缺失"的半损坏缓存，`load` 后自动重抽恢复（3 章完整、无 error、imageRoot 指向重建目录）。

### M2（修）占位双重播报
- **修法**：占位容器从 `.semantics{contentDescription}` 改为 `.clearAndSetSemantics{contentDescription}`——清除子 Text/图标的自身语义，TalkBack 不再对 contentDescription 与子文本双重播报（视觉文案不变）。
- **文件**：`ui/reader/ReaderTextView.kt`（`ImagePlaceholder`）。
- **测试**：既有设备/单元占位断言（`MdImageDeviceTest`、`ReaderImageTest`）继续按 contentDescription 通过。

### M3（修）整章纯图被误判"文件为空"
- **修法**：解析层对"某章 `images` 非空且 `paragraphs` 为空"（扉页/图版章）**合成一条空字符串宿主段落**承载图片（owner 0 对齐）；渲染层空态判断改为"无文本**且无图**"才算空，真无图真空章仍走既有空态（TC-F2-24 不回归）。
- **文件**：`core/parse/EpubParser.kt`（parseCore 宿主段落合成）、`ui/reader/ReaderScreen.kt`（空态条件）。
- **测试**：`EpubParserTest.m3_pureImageChapter_hostParagraphSynthesized`（纯图章 `paragraphs==[""]`、images 挂 index 0）；既有 ReaderStateTest（空文件/空 spine）仍空态通过。

### L1（修）decodeFile 未捕获 OOM + 采样缺高度维度
- **修法**：`decodeSampled` 增**目标高**（2×屏高）维度，采样条件改为"宽或高任一仍 ≥ 目标就加大 inSampleSize"，极窄超高图同样限总量；`BitmapFactory.decodeFile` 单独 `catch (OutOfMemoryError)` 返回 null 走占位。
- **文件**：`ui/reader/ReaderTextView.kt`（`ChapterImage` 传入高目标、`decodeSampled` 双维度 + OOM 捕获）。
- **测试**：编译/既有用例回归（真机解码路径仍由 MdImageDeviceTest 覆盖）。

### L3（修）img src 百分号编码查找失败
- **修法**：`resolveImageFile` 直接查找失败后，`URLDecoder.decode(src, "UTF-8")` 再找一次（文件名含空格等场景）。
- **文件**：`ui/reader/ReaderTextView.kt`。
- **fixtures**：`sample_images.epub` 增 `images/with space.png` + `<img src="images/with%20space.png" alt="空格文件名图"/>`；`validate` 补断言。
- **测试**：`EpubParserTest.m11_images_*` 断言 `src=="OEBPS/images/with%20space.png"` 保留；`MdImageDeviceTest.sampleImages_*` 增"空格文件名图"节点解码显示断言（设备端）。

### L4（修）缓存回退整书 readBytes 不在 IO
- **修法**：`loadNonTxt` 回退路径的 `fileOpener.open(uri).use{it.readBytes()}` 移入 `withContext(Dispatchers.IO)`。
- **文件**：`ui/reader/ReaderViewModel.kt`。
- **测试**：既有 VM epub 加载用例（含新 M1 recovery）回归通过。

### fixtures：位图分辨率提升
- **修法**：正文位图 96×64 → **480×320**、封面 128×160 → 480×640（正文按屏宽等比显示不过糊）；svg/broken（96×64）/truncated 维持原尺寸。
- **文件**：`buildSrc/.../FixtureGenerator.kt`；校验同步更新。

### 补充测试汇总（相对首版 +4）
- `EpubParserTest`：+`m3_pureImageChapter_hostParagraphSynthesized`、`i2_readSideEscape_rejectedAsMissing`（26，+2）。
- `EpubCacheTest`：+`extract_epubWithDirectoryEntries_succeeds`（7，+1）。
- `ReaderViewModelCacheRecoveryTest`：新增 M1 自动重抽用例（1）。
