# M11b 任务简报 — epub 插图渲染（F2.14）+ 缓存解压 + C4 取消粒度

> 协作模式：OpenCode 按本简报实施；Kimi Code 审查。全程中文注释/提交信息。
> 版本基线：main @ `53f31ba`（M11a 已合），单测基线 **178 通过 / 0 失败**；设备端 androidTest 基线 **5/5**（Pixel 7 模拟器）。
> 验证约定（协同规范 §第五步）：渲染/面板类交互以设备端 Compose UI 语义断言为验收证据，本批次须同步补设备端用例。

## 1. 目标与范围

**做**（仅以下）——F2.14 epub 图片渲染所需的一切，**不含 CSS**：

1. **fixtures**：`FixtureGenerator.kt` 新增 3 个 epub（图片用程序生成的小位图，规则见 §0 用例清单）：
   - `sample_images.epub`（正文含 png/jpg 位图各一 + 一张 svg；位图带 alt）
   - `sample_cover.epub`（OPF `meta name="cover"` 指向位图封面）
   - `broken_image.epub`（img 引用**不存在的条目** + 一张**截断位图**）
2. **解析层（EpubParser.kt）**：正文 `<img>` 提取为 `ParagraphImage`（Models.kt 字段已就位）——块级 img 挂当前段落索引；同行内 img 归所在段落；src 按 manifest href 规范化；alt 取 `@alt`；**svg 也提取**（渲染层占位）；封面识别：OPF `meta name="cover"` 或 manifest `properties="cover-image"` → `isCover=true, paragraphIndex=0`；svg 封面仅当内嵌 `<image>` 位图引用时提取。
3. **C4（一致性评审）**：XHTML 抽取循环（`extractWithPull` 段级循环）内加取消检查（`Thread.currentThread().isInterrupted` → 抛 `CancellationException`），消除"超大单章不可取消"。
4. **缓存解压层（新建，如 `core/io/EpubCache.kt`）**：epub 打开时一次性解压到应用私有缓存 `cache/epub/<uri md5 前16位>/`；解析与图片渲染**均只从缓存目录读**（见设计 §3.2 第 5 点、§5.3）；容量 ≤ 原书大小；单实例 LRU（进入新书/超限清理最旧，**不删当前书**；同书二次打开存在即跳过）；解压循环内响应取消（取消后不留半成品，清理本次目录）；缓存失败（磁盘满/IO）**不阻塞阅读**——回退解析仍可读，图片按占位处理。
5. **渲染层**：段落 item 内渲染图片（**不新增 LazyColumn 项**，item 索引==段落索引不变量；`isCover` 图在首段文字上方，其余图在所属段落文字下方）；`BitmapFactory.decodeFile` + `inSampleSize`（目标宽 = 2×屏宽 px，先 decodeBounds 再采样）防大图 OOM；decode 在 `Dispatchers.IO`，item 可见才触发（LazyColumn 天然懒加载）；解码失败/文件缺失/svg → **占位提示**（主题前景图标 + 「图片无法显示」，alt 非空时显示 alt）；图片 contentDescription = alt（无 alt 的图片给占位语义），占位同样带 contentDescription（评审 S9）。等比宽高按图片原始尺寸 + `Modifier.aspectRatio` 保持。

**不做**（留给 M11c，越界即违规）：CSS 一切——`<style>`/内联 `style` 解析、`ParagraphStyleHints`、`Chapter.styles` 填充与渲染、`styled.epub`/`bad_css.epub` 夹具、TC-F2-17e/f。md 目录相关回归改动不做。

## 2. 设计依据（先读，严格照做）

- `docs/软件设计文档.md`：§3.1（ParagraphImage/alt 注释）、§3.2 第 3/5 点（img 提取规则、图片随机访问与缓存、封面识别）、§3.3 图片管线（挂载规则 S8/解码采样/占位/语义 S9）、§5.3（打开 epub 流程：解压到私有缓存后台可取消）、§8（epub OOM 风险）。
- `docs/需求说明书.md` F2.14。
- `docs/测试用例.md` REV4：TC-F2-17 / 17c / 17d；§0 fixtures 清单（M11 图片夹具定义）。
- `docs/collab/reviews/f2-13-15-docs-review.md` S2/S6/S8/S9/S12。
- `docs/文档代码一致性评审.md` §5 C4。
- AGENTS.md §5 解析器约定（循环内取消）。

## 3. 代码落点

允许修改：`core/parse/EpubParser.kt`、`core/io/`（新建 EpubCache 等）、`core/model/Models.kt`（仅按需微调字段/注释，勿动既有字段语义）、`ui/reader/`（ReaderScreen/ReaderTextView 的段落渲染、图片 composable）、`ReaderViewModel.kt`（epub 打开路径串缓存）、`buildSrc/.../FixtureGenerator.kt`、`app/src/test/**`、`app/src/androidTest/**`。
禁止：EpubParser 内任何 CSS/style 相关、M11a 已合代码的重构、release 配置、docs/ 除本简报与报告外的文档（文档归 Kimi Code）。**不手改 fixtures 产物目录**（只改 FixtureGenerator.kt）。

## 4. 测试要求（TDD：解析层先红后绿）

**单测（Robolectric，新增 ≥ 覆盖）**
- 解析：img 提取（块级挂段落/同行归段落/alt/src 规范化/svg 保留）；封面两种识别；svg 封面内嵌位图提取与纯矢量不提取；无图 epub images 空；既有 epub 解析断言（章节数/文本/耗时 ≤200ms）不回归。
- 缓存：解压产物与 zip 条目一致；同书二次打开跳过；LRU 清理顺序且不删当前书；取消后无半成品残留；失败回退可读。
- C4：超大单章解析中途取消（模拟 interrupt）及时抛 CancellationException。
- 既有基线 178 必须全绿（EpubParserTest 既有断言若随签名调整，须同步且不降覆盖）。
**设备端（androidTest，新 `MdImageDeviceTest`，Pixel 7 上 connectedDebugAndroidTest）**
- `sample_images.epub`：含 alt 位图节点 contentDescription=alt 且 displayed；svg 处出现占位（占位 contentDescription/文案断言）。
- `sample_cover.epub`：首屏为封面图（contentDescription 断言）。
- `broken_image.epub`：两处占位（alt 非空显示 alt；无 alt 显示默认文案），正文文本仍在。
- 语义断言注意与 M11a 同坑：图片/占位若与文本同名节点冲突，用 contentDescription 区分。

## 5. 实施纪律（既往踩坑，逐条遵守）

1. 不建调试文件（Debug* 残留）；临时验证用 Write 覆盖或留待清理。2. **不用 `rm`**。
3. 单条 bash；git **只读**不需要；列文件用 Glob、读文件用 Read——**不要用 bash 做 ls/find/git**（会被权限拒绝）；**权限被拒不是停止理由**，换内置工具继续跑完全量。
4. 不翻 Gradle 缓存依赖源码。5. 中文注释；**不自行 git commit**。
6. 完成写报告 `docs/collab/reviews/m11b-opencode-report.md`（改动清单/设计决策/测试结果/遗留）。

## 6. 验收（Kimi Code 侧，不在你的范围）

diff 审查 → 独立 `--rerun-tasks` 单测复核 → Pixel 7 上 `connectedDebugAndroidTest`（含新设备用例）→ 打开含图 epub 语义/视觉确认（TC-F2-17/17c/17d）→ 中文提交 → 打 `m11b` tag。
