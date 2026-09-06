# M11a 任务简报 — md 目录（F2.13）+ 源码/预览切换位置映射（U4）

> 协作模式：OpenCode 按本简报实施；Kimi Code 审查。全程代码注释/提交信息使用中文。
> 版本基线：main @ `108face`（需求说明书 REV7 之后），单测基线 **160 通过 / 0 失败**。

## 1. 目标与范围

**做**（仅这些）：

1. **F2.13 md 目录（TOC）展示与跳转**：
   - 解析层：md 解析时提取目录 → `Document.toc`（按设计文档 REV5 §3.1/§3.2）。
   - UI 层：顶栏「目录」图标按钮（与 epub「章节」同位置，F4.5 已预留）→ 底部弹层面板，按 `level` 缩进、当前标题高亮、点击跳转（源码/预览双视图，预览经块↔段落映射）。
2. **U4 源码/预览切换的位置映射**（一致性评审 §5）：切换视图时经 block↔段落索引映射换算，保持阅读语义位置，而非停留在"同序号不同内容"。

**不做**（留给 M11b/M11c）：epub 图片（F2.14）、CSS 样式（F2.15）、EpubParser 任何改动、缓存解压。

## 2. 设计依据（必须先读，严格照做）

- `docs/软件设计文档.md`：
  - §3.1 数据模型：`TocEntry(level, title, paragraphIndex)`、`Document.toc`（注释已留位，"M11 待实施"字样由你删除）；`MarkdownBlock` 增补 `startParagraphIndex` 字段（评审 S4）。
  - §3.2 解析层：PlainTextParser 对 md **额外提取目录**——与 `MarkdownParser` **共用同一标题扫描函数**（防两版逻辑漂移，评审 S10）；仅 ATX（`^#{1,6}\s+`），**不支持** ATX 封闭（`# 标题 #`）与 Setext；围栏代码块内的行一律跳过；标题文本去 `#` 前缀与行内标记取纯文本；无标题文档 toc 为空；单次线性扫描、无额外 IO。
  - §3.3 渲染层（md 目录段，含 S3/S4/S10/S11 处置）：跳转目标为段落索引；预览视图经「MarkdownBlock ↔ 段落起始索引」映射换算；**预览视图经目录跳转后按反向映射得到的段落索引上报进度（S3 方案①），预览模式普通滚动仍不上报**；当前标题高亮规则（源码取首个可见段落所属目录项、预览按块映射、首个目录标题之前无高亮 S11）。
  - §4 导航 / §5.3 关键流程。
- `docs/需求说明书.md` F2.13、F4.5。
- `docs/文档代码一致性评审.md` §5 U4。
- `docs/collab/reviews/f2-13-15-docs-review.md` 中 S1/S2/S4/S6/S7/S10/S11（M11a 只处置与目录相关的 S4/S10/S11；S3 的方案①上文已引用）。

## 3. 代码落点

允许修改：

- `app/src/main/java/com/sample/docreader/core/model/Models.kt` — 加 `TocEntry`、`Document.toc`。
- `app/src/main/java/com/sample/docreader/core/render/MarkdownParser.kt` — `MarkdownBlock` 加 `startParagraphIndex`；抽出标题扫描共用函数（返回段落索引）。
- `app/src/main/java/com/sample/docreader/core/parse/PlainTextParser.kt` — md 分支提取 toc（共用上述扫描函数）。
- `app/src/main/java/com/sample/docreader/ui/reader/` — ReaderScreen（目录图标/面板，复用 epub 章节面板交互模式：抽无状态 composable 直驱，Robolectric 下 ModalBottomSheet 不进语义树）、ReaderViewModel（切换映射、跳转、进度上报）。
- `app/src/test/`、（如需要）`app/src/androidTest/` 新增/改写测试。
- `buildSrc/src/main/kotlin/FixtureGenerator.kt` — **新增 `sample_toc.md` 夹具**（测试用例 §0 已定义：多级 ATX 标题混合正文/列表/围栏代码块，代码块内含 `#` 注释行）。**不得手改 `app/src/test/resources/fixtures` 或 `app/src/debug/assets/fixtures` 下的任何文件**（自动生成，不入库）。

**禁止**：动 `EpubParser.kt`、CSS 相关、release 配置、docs/ 下除本简报外的文档（文档归 Kimi Code）。

## 4. 测试要求（TDD：解析层先红后绿）

- 新增/落地用例（编号见 `docs/测试用例.md` REV4）：
  - **TC-F2-20a**（P0）：目录层级缩进、代码块内标题文本不出现、源码/预览点击均准确定位、跳转后进度持久化重开回到该标题。
  - **TC-F2-20b**（P2）：无标题 md 目录图标隐藏。
  - **TC-F2-20**（既有 P1）：补断言——源码↔预览来回切换后经映射定位到语义一致位置（U4），不只是同序号。
- 解析层单测：标题扫描（ATX 各层级、行内标记剥离、围栏代码块跳过、封闭 ATX 与 Setext 不识别、无标题空 toc）；`startParagraphIndex` 映射正确性（含 CodeBlock 锚定围栏起始行）。
- UI 层：面板主体抽无状态 composable 直驱断言（M10 立的模式）；目录项 `selected` 语义 + ≥48dp。
- 跑 `./gradlew testDebugUnitTest` 必须全绿，且总数 ≥ 基线 160 + 新增。
- 性能断言：toc 提取随解析单次线性扫描完成（不得引入二次全文件扫描）。

## 5. 实施纪律（既往踩坑，逐条遵守）

1. **不建调试文件**（不要 DebugXxx.kt / temp 脚本残留）；临时验证用 Write 覆盖既有测试文件或留待 Kimi Code 清理。
2. **不用 `rm`**；不需要删文件。
3. 单条 bash / git 只读命令执行；**权限被拒不是停止理由**——换内置工具（Read/Grep/Glob）继续，跑完全量再汇报。
4. 不得翻 Gradle 缓存里的依赖源码。
5. 全程中文注释；**不自行 git commit**（提交由 Kimi Code 统一做）。
6. 完成后写报告：`docs/collab/reviews/m11a-opencode-report.md`（改动文件清单、设计决策、测试结果、遗留问题）。

## 6. 验收（Kimi Code 侧，不在你的范围）

diff 审查 → 独立 `--rerun-tasks` 单测复核 → 真机 OPPO Reno9 验收（TC-F2-20a/b + U4 切换 + 回归 P0）→ 中文提交。
