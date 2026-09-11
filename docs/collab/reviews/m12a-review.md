# M12a 审查记录（Kimi Code → OpenCode 实现）

> 批次：M12a epub 原生目录与锚点跳转（解析层）。简报：`docs/collab/briefs/m12a-native-toc.md`。
> 实现方：OpenCode（`opencode run --auto`，会话 ses_f6f38af0fffev02dj7u0b1zLW9）。审查方：Kimi Code。
> 过程中另有一轮：首次以非交互 `opencode run`（无 `--auto`）启动，因权限 "ask" 被自动拒且 run 随即结束，未产生任何改动；改为 `--auto` 续跑（deny 列表仍生效）。

## 一、OpenCode 报告摘要（待核验）

- 改动 3 个文件：`FixtureGenerator.kt`（anchor_toc.epub + 4 项结构校验）、`EpubParser.kt`（`ExtractedChapter.anchors`、`StyleFrame.id/anchorName`、`flush()` 记锚点、`chapterAnchors()`、`parseCore` 产出 `Document.toc`、取消「第 N 章」）、`MarkdownParser.kt`（`plainTitle` 剥链接）。
- 验证：`generateFixtures validateFixtures` 成功；`testDebugUnitTest` 223/0；`assembleDebug` 成功；`check` 失败两处（lint 代理、release 单测）。
- 自报未验证项：M12b/M12c 的 UI/VM；真机 TC-F2-25/26/27。
- 自报剩余风险：`<a name>` 段中锚点落上一段；「遍历全部祖先 id」比设计措辞更宽；`ReaderScreen` 注释与新 toc 事实不一致。

## 二、Kimi 独立审查

**独立复验（不采信报告）**：`./gradlew testDebugUnitTest validateFixtures --rerun-tasks` → 全量执行 55 任务通过；自解析 test-results XML 汇总 **tests=223 failures=0 errors=0 skipped=0**，与报告一致。

**读完整 diff 的核对结论**：

| 检查项 | 结论 |
| --- | --- |
| 两条数据源路径共用产出逻辑 | ✅ `parseBytes`/`parseFromDir` 均走 `parseCore`（EpubParser.kt:397/403） |
| `Document.toc` 换算（章坐标/锚点/多条目同指一章/未命中退章首） | ✅ 逻辑与设计 §3.8 一致；`chapterIndexByPath` 先出现者优先 |
| 取消「第 N 章」兜底 | ✅ 解析层已无生成式标题（`title = tocEntry?.title`） |
| 取消/中断语义 | ✅ `checkInterrupted()`/`ensureActive()` 未改动 |
| md 预览语义 | ✅ 仅 `plainTitle` 剥链接，`parseInline` 未动 |
| 夹具产物 | ✅ 由生成器产出、gitignore 覆盖（`app/src/test/resources/fixtures/`、`app/src/debug/assets/fixtures/`） |
| 任务外改动 | ✅ 无；diff 文件清单与「允许修改」一致 |
| `check` 两处失败归因 | ✅ 非本批次引入：lint 需外网代理（AGENTS §8 已记录）；`testReleaseUnitTest` 46 用例失败源于 `ui-test-manifest` 仅 `debugImplementation`（`app/build.gradle.kts:61` 未改动，既有结构性缺口） |

## 三、审查发现与处置

### F1（需修复，已派发）段中 `<a name>` 锚点映射错一段

- 位置：`EpubParser.kt` END_TAG 分支的锚点兜底（`val fallback = (paragraphs.size - 1).coerceAtLeast(0)`）。
- 问题：段中闭合的 `<a name>`（段落在累积、尚未 flush）取 `paragraphs.size - 1` = **上一段**；正确目标是其**所在段落**（将成为 `paragraphs.size`）。首段内偏差被 `coerceAtLeast(0)` 掩盖，段中偏差真实存在。
- 证据：新增边界红测试（本次先行提交）`tcUt14_chapterAnchors` 的 p4 断言 + `tcUt15_epubTocMapping` 扩展 → `28 tests completed, 2 failed`（精确红）。
- 处置：**已修复并复验通过**（OpenCode 第二轮：新增 p4.xhtml 夹具与 NCX 条目、修正 fallback 分支为「行内标签且 `sb` 非空 → `paragraphs.size`，否则 `paragraphs.size - 1`（≥0）」）。
- 复验（Kimi）：`./gradlew testDebugUnitTest validateFixtures --rerun-tasks` → 55 任务全执行、**tests=223 failures=0 errors=0 skipped=0**（自解析 test-results XML）。
- 说明：OpenCode 报告将其列为「已知近似」，审查确认其为可修复缺陷（非设计取舍），故要求修复而非接受。
- 复验中的插曲：第二轮 OpenCode 拒绝改测试而是在 `tcUt15` 上暂停交回，理由是**审查方（Kimi）写的 `chapterIndex` 期望值与夹具规格自相矛盾**（p4 追加在 p3 之后 → spine 序应为 4，我误写 3）。核对成立——它按「红测试断言与设计冲突」升级条件正确处理；由 Kimi 更正笔误（`3 → 4`，仅此一处，其余断言未动）后全绿。**这条记录为协作规范的正面样本：实现方不得为转绿而改断言。**

### F2（不采纳，记录理由）

- `chapterAnchors()` 二次解析该章（仅单测接缝，生产路径不经它）。开销只在测试，接口清晰，保持现状。

### F3（转 M12c 处置）

- `ReaderScreen.kt:775` 章节面板仍以 `"第 ${index + 1} 章"` 兜底空白标题、`:360` 顶栏仍回落书名。本批次边界内不动 UI，但 **M12c 简报必须明确要求**：面板数据源改 `Document.toc`、清除该生成式兜底（F2.16/R1/R3）。

### F4（记录为既有遗留）

- `./gradlew check` 在本机不可全绿（lint 需代理、release 变体缺 `ui-test-manifest`）。Gradle 门禁改用 `check -x lint -x lintDebug -x lintAnalyzeDebug -x lintReportDebug -x testReleaseUnitTest` 或在 CI（有网络）执行全量；该偏差记入遗留，待 M12 收尾时在开发计划标注。

## 四、复验要求（F1 修复后）

- `./gradlew testDebugUnitTest --rerun-tasks`：**223 → 223 用例全绿**（新增断言并入既有两条用例，不新增用例数）；
- `./gradlew generateFixtures validateFixtures`：夹具新校验项通过；
- 复查 p4 夹具结构（`<p>第二段前文<a name="mid">锚点后文</a>。</p>`）与生成器校验同步更新。
