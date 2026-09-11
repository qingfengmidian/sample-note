# M12a epub 原生目录与锚点跳转（解析层）— 实现简报（Kimi Code → OpenCode）

> 分支：`feat/m12a-native-toc`。**红测试已提交（TDD 红）**，你的任务是把它们做绿。
> 需求：F2.16（epub 目录原样呈现与锚点跳转）、F2.13 REV8（md 目录标题剥链接）；
> 设计：`docs/软件设计文档.md` **§3.8**（M12 整节，已定稿）；用例：TC-UT-05（改写）/14/15/16；
> 计划：`docs/开发计划.md` M12（本批次为 M12a，VM/UI 属 M12b/M12c，不在本批次范围）。

## 目标

解析层不再为 epub 生成「第 N 章」兜底标题，改为**产出文件自带目录（nav/NCX 原样）**到 `Document.toc`，并附带每条目录条目的**章坐标 + 锚点段落**（供 M12c 的目录跳转定位）；md 目录标题剥除行内链接标记。

## 已定型的契约（签名与语义不得改）

红测试提交内已包含以下接缝，实现必须对齐：

- `core/model/Models.kt` `TocEntry`：新增 `chapterIndex: Int = 0`、`anchorParagraph: Int? = null`（默认值保证 md 既有调用点不变；**模型字段不得再改**）。
- `core/parse/EpubParser.kt` `internal fun chapterAnchors(bytes: ByteArray, chapterPath: String): Map<String, Int>` —— 现为占位 `emptyMap()`，你来实现（保持 internal 可见性，签名不变）。
- `app/src/test/.../EpubParserTest.kt`：`tcUt05_noNav`（改写）、`tcUt14_chapterAnchors`、`tcUt15_epubTocMapping`；
- `app/src/test/.../MarkdownParserTest.kt`：`scanHeadings_stripsInlineLink`。
- **测试断言不得修改**（红测试是验收标准）；如认为断言有误，暂停并交回 Kimi Code。

## 实现任务

### 1. 新增夹具 `anchor_toc.epub`（改 `buildSrc/src/main/kotlin/FixtureGenerator.kt`）

结构（路径与内容即测试契约，不得自由发挥）：

- `mimetype`、`META-INF/container.xml`、`OEBPS/content.opf`、`OEBPS/toc.ncx`，正文 `OEBPS/Text/p0.xhtml` … `p3.xhtml`；spine 顺序 = p0,p1,p2,p3。
- **NCX navMap**（层级 = navPoint 嵌套）：
  1. `封面页`（level 1）→ `Text/p0.xhtml`（**无 fragment**）
  2. `第一节`（level 1）→ `Text/p1.xhtml#sec1`
     - `第二节`（level 2，嵌套于「第一节」内）→ `Text/p1.xhtml#sec2`
  3. `未命中锚点`（level 1）→ `Text/p2.xhtml#nope`（该 id 不存在，验"退章首"）
  - `p3.xhtml` **不编入 NCX**（验章节 title 为 null 且不产生目录条目）
- `p0.xhtml`：`<p>封面页段落。</p>`
- `p1.xhtml`（段落索引 0..4）：
  ```
  0: <p>第一节开头段。</p>
  1: <p id="sec1">第一节锚点段。</p>
  2: <p>第一节后续段。</p>
  3: <div id="sec2"><p>第二节容器内首段。</p><p>第二节容器内次段。</p></div>   ← 容器内两段 = 索引 3、4
  ```
- `p2.xhtml`：`<p>未命中锚点章第一段。</p><p>未命中锚点章第二段。</p>`
- `p3.xhtml`：`<p>未编入目录的章节段落。</p>`（无 id）
- 在生成器内加结构校验（与既有 `check(...)` 风格一致）：NCX 含 4 条 navPoint 且嵌套层级 1/1/2/1、三条 href 原样带 fragment、p1 含 `sec1`/`sec2` id、p3 无对应 navPoint。
- 既有 `no_nav.epub` **保持不变**（其断言改为 title 全 null + toc 空）。

### 2. `EpubParser` 改动（`core/parse/EpubParser.kt`）

- **锚点映射**：段落 flush 时记录最近块级祖先元素 id → `章节 anchors: Map<String, Int>`（`<a name="x">` 同样视为锚点）。规则：段落元素 id → **该段落**；容器元素（div 等）id → **其内首个段落**；容器内无段落 → 最近前段落。纯图章合成空宿主段时锚点照常映射到该宿主段。`stripTagsFallback2` 降级路径返回空表。
  - 复用 `pushFrameFor`/`ownerFrame()` 已读到的 id 属性（当前仅用于 CSS 匹配），不要重写解析循环。
- **目录产出**：`parseCore` 用**原值 href**（含 `#fragment`，`parseNcx`/`parseNavXhtml` 已保留）经 `resolvePath` 定位目标章，再按上一小节的锚点表把 fragment 换算为 `anchorParagraph`：
  - fragment 命中 → 该段落索引；无 fragment → 0；fragment 未命中/映射缺失 → 0（仍可达章首）。
  - 条目**全部保留**（含多条目同指一章）；`title`/`level` 原样取自 nav/NCX。
  - 产出 `Document.toc`（当前 `parseCore` 未传 toc）；`titleByPath`（先出现条目优先）**仅用于章节标题匹配**，其"剥 fragment"行为保留。
- **取消「第 N 章」兜底**：章节 `title = tocEntry?.title`（可为 null）；`level` 无匹配仍为 1。**不得**再拼「第 N 章」。
- 两条数据源路径（zip 条目 `parseBytes` 与 M11b 缓存目录 `parseFromDir`）都要走同一产出逻辑（复用现有统一出口）。
- 循环内保持 `ensureActive()`/`checkInterrupted()` 取消语义不变。

### 3. `MarkdownParser` 剥链接（`core/render/MarkdownParser.kt`）

- 仅改 `plainTitle`（目录标题路径）：`[文字](url)` → `文字`（链接文字内的 `**加粗**`/`*斜体*`/`` `代码` `` 一并剥离），多余空白归一为单空格（测试期望「参见 加粗链接 与 代码」）。
- **不得改 `parseInline` 的既有语义**：预览视图对 `[文字](url)` 仍按原文输出（本期不支持链接渲染），只让目录条目标题变干净。

## 允许修改

- `buildSrc/src/main/kotlin/FixtureGenerator.kt`
- `app/src/main/java/com/sample/docreader/core/parse/EpubParser.kt`
- `app/src/main/java/com/sample/docreader/core/render/MarkdownParser.kt`
- 上述红测试文件（**仅允许为对齐既有断言风格做必要微调，不得放松断言或删除用例**；新增夹具相关断言应加在生成器侧）

## 不得修改

- `core/model/Models.kt` 的 `TocEntry` 字段定义（已定稿）；
- `ReaderViewModel`/`ReaderScreen`/`ReaderTextView`（M12b/M12c）；
- 其他解析器（TxtParser/PlainTextParser）、`EpubCache`、设置/数据层；
- fixtures 产物（`app/src/test/resources/fixtures`、`app/src/debug/assets/fixtures`）——一律由生成器产出，**不手改**。

## 必须保持的不变量

- 解析层与 UI 层解耦；`DocumentParser` 接口与 `ParseException` 分类不变；
- 除本批次改写的 TC-UT-05 外，**既有全部单测保持绿**（含 sample.epub/sample3.epub/hybrid/nested_toc 的既有标题断言——那些书的 NCX 标签本身就是「第 1 章」等）；
- md 预览渲染行为不变（只动目录标题剥离）；
- 解析失败仍抛具体 `ParseException` 子类。

## 非目标

- 目录面板/顶栏/进度条/连续滚动的任何 UI 或 VM 改动（M12b/M12c）；
- epub3 nav.xhtml 锚点与 NCX 之外的目录来源；页内链接跳转；
- 解析层惰性化（整书仍全量解析，设计 §3.8 已记）。

## 验收标准

- `tcUt05_noNav`、`tcUt14_chapterAnchors`、`tcUt15_epubTocMapping`、`scanHeadings_stripsInlineLink` 全绿；
- 全量单测绿（基线 220 用例 + 本次新增/改写），`./gradlew check` 过（含 `validateFixtures`）；
- 夹具校验覆盖 anchor_toc.epub 的 navPoint/锚点 id/未编入章节三要素。

## 应运行的验证

```bash
./gradlew testDebugUnitTest     # 全绿
./gradlew check                 # 含 validateFixtures
```
（`generateFixtures` 由任务依赖自动触发；如需手动重生成用 `./gradlew generateFixtures`。）

## 允许的工具和权限

- 只读检查（`git status/diff/log`、读取、搜索）随时可用；
- 编辑范围限上文"允许修改"清单；
- 构建与单测：`./gradlew assembleDebug testDebugUnitTest check generateFixtures`；
- **不得** `git commit`/`git push`/打 tag，不得引入新依赖，不得删除文件。

## 升级条件（必须暂停并交回 Kimi Code）

- 发现 `chapterAnchors`/`Document.toc` 产出需要改动模型字段或其他模块调用方；
- 锚点规则在两条数据源路径（zip / 缓存目录）下无法共用同一实现；
- 红测试断言与需求/设计文档冲突；
- 需要改动"不得修改"清单内文件。

## 完成前请

1. 检查实际 diff（`git status` + `git diff`，含未跟踪文件）；
2. 运行上述验证并贴真实输出摘要；
3. 报告：改动清单 / 验证命令与结果 / 未验证项 / 剩余风险；
4. 不要自行提交、发布或扩大范围。
