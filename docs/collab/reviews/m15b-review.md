# M15b 审查记录 — epub 章节标题层级实现（F2.17）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-13 |
| 范围 | M15b 实现（红测试转绿）：解析层层级提取 + 装配透传 + 渲染合并 |
| 红测试 | `test(red):` 提交 `8bcf5a8`（7 例新增，其中 6 例红、1 例回归守卫天生绿） |
| 实施者 | OpenCode（`--auto`，deepseek-v4-flash） |
| 审查者 | Kimi Code（独立读 diff + 独立重跑） |

## 一、实施报告摘要（OpenCode）

改动 3 个生产文件、无未跟踪文件：

- `EpubParser.kt`：`ExtractedChapter.headingLevels`；`extractWithPull2` 在**同一 flush 点**并行追加层级（`headingLevelOf(ownerFrame()?.tag)`，h1~h6→1~6、其余 null）；新增 `headingLevelOf`/`fallbackExtracted`（降级路径全 null 且等长）；纯图章合成宿主段 `listOf(null)`；`Chapter` 透传。
- `ReaderItemAssembly.kt`：`headingLevel = chapter.headingLevels.getOrNull(paragraphIndex)`。
- `ReaderTextView.kt`：`headingFontScale` 落地 1.6/1.45/1.3/1.2/1.1/1.05（越界 1f）；`applyParagraphHints` 接入 `headingLevel`（书 CSS `fontSizeEm` 覆盖默认倍率、不相乘；行距随有效字号等比；bold 三态仅作用于标题段）；生产调用点传 `item.headingLevel`。

## 二、Kimi 独立审查（逐点核对）

| 检查项 | 结论 |
| --- | --- |
| 红测试断言/契约签名是否被改 | ✅ 未改（`git status` 仅 3 个生产文件；测试与夹具产物零改动） |
| 「段落数 == 层级数」不变量 | ✅ 结构性成立：全仓仅 `EpubParser.kt:946` 一处追加段落，层级在同一 `flush()` 内紧随追加；两处 `ExtractedChapter` 构造点（降级 544 / 主路径 1075）均已覆盖 |
| 覆盖而非叠加 | ✅ `headingRatio` 仅当 `fontSizeRatio == null` 时参与，`effectiveFontRatio = fontSizeRatio ?: headingRatio` |
| bold 三态 | ✅ 标题段 `if (hints?.bold == false) Normal else Bold`（null/true→Bold）；非标题段保留原 `hints?.bold?.let` 逐位不变 |
| 非标题段零变化 | ✅ 早返回改为 `hints == null && headingLevel == null`，覆盖"标题无 CSS"新路径且不放宽旧路径；`nonHeading_unchanged` 回归守卫绿 |
| WRAP/NO_WRAP 一致性 | ✅ 两路径共用同一 `paragraphStyle`（既有结构未动） |
| 无关改动/越界 | ✅ 无格式化噪声、未触碰 md 预览与 txt/md/xml 路径 |
| 取消/异常路径 | ✅ `fallbackExtracted` 等价替换原 `stripTagsFallback2` 调用，异常类型与行为不变 |

**观察（不改代码，记录备查）**：

1. 层级与样式同源自 `ownerFrame()`，故非法嵌套（如 `<h1><p>x</p></h1>`）以内层块为准 → 该段层级 null；与既有「样式归属同一 owner」规则一致，属合理近似（设计已记「已知近似」）。
2. 标题无独立行距处理：`lineHeightEm` 未声明时行距随有效字号等比，与 F2.15 既有约定一致（保持全局行距比例）。

## 三、独立复验（Kimi 亲自重跑，非采信报告）

```bash
./gradlew assembleDebug validateFixtures testDebugUnitTest --rerun-tasks
```

- **BUILD SUCCESSFUL**，89 个任务**全部强制重跑**（避免 UP-TO-DATE 造成的"未真正执行"）。
- 单测 **261 例 / 0 失败 / 1 跳过**（跳过 = F4.8 进度条 @Ignore）。
- 计数核对：基线 254 → 新增 **7** 例（`EpubParserTest` +1、`ReaderItemAssemblyTest` +1、`ReaderStyleMergeTest` +5，含回归守卫 `nonHeading_unchanged`）→ 261，逐类与 `git grep -c '@Test'` 一致。
- 6 例目标红测试全部转绿；`validateFixtures` 通过（含 `headings.epub` 结构自检）。

## 四、遗留

- **真机走查（M15c）待执行**：`headings.epub` 六级标题可辨且逐级递减、《邓小平文选》章节标题可辨、Flatland（真 CSS 书）与 `styled.epub` 不回归；设备端套件回归（`connectedDebugAndroidTest`）顺带跑。当前 WiFi adb 离线，待重连。
- 设计文档 REV11 的 `headingFactor` 表已与实现一致；若真机观感需微调，仅改 `headingFontScale` + 设计一行（决策日志 D-2026-09-13-01 已记重估条件）。

## 五、结论

实现与红测试、设计定稿一致，独立复验全绿，**审查通过、可提交**（绿提交）。真机观感项随 M15c 收口。
