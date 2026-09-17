# M13 审查记录 — 弹层/菜单强调色与阅读主题解耦（F4.4/F4.5 无障碍收尾）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-16 |
| 范围 | M13 实现（红测试转绿）：面板/菜单/按钮/加载指示强调色接线（选型 a：`theme.foreground`，决策 D-2026-09-15-01） |
| 红测试 | `test(red):` 提交 `b803bee`（TC-UI-13 Robolectric 2 例先行失败 + TC-UI-14 设备端 1 例随编译） |
| 实施者 | OpenCode（CLI 后台运行，`--auto`，deepseek-v4-flash） |
| 审查者 | Kimi Code（独立读 diff + 独立重跑） |

## 一、实施报告摘要（OpenCode）

改动仅限 `ReaderScreen.kt`（+68/-15），无其他源码/测试改动、无 git 提交：

- `SheetRadioOption` ✓ 标记色与 `TocListContent` 当前项高亮色：`colorScheme.primary` → `accent`。
- 文字按钮统一 `ButtonDefaults.textButtonColors(contentColor = accent, disabledContentColor = accent@38%)`：章节面板上一章/下一章、设置面板 A-/A+/导入字体、正文错误态「返回」。
- 加载指示 `CircularProgressIndicator(color = theme.foreground)`。
- ⋮ 下拉菜单：`DropdownMenu(containerColor = theme.background)` + 三项 `MenuDefaults.itemColors(textColor = theme.foreground, disabledTextColor = @38%)`（material3 1.3.1 主路径，无 fallback）。
- 三个 `ModalBottomSheet` 加 `dragHandle = { BottomSheetDefaults.DragHandle(color = accent) }`。
- 删除全部「M13 红」注释。

## 二、Kimi 独立审查（逐点核对）

| 检查项 | 结论 |
| --- | --- |
| 越界检查 | ✅ `git status` 仅 `ReaderScreen.kt` 一个源码文件（工作树另有本次并行的应用名文档改动 4 份，与 M13 无关、OpenCode 未触碰）；测试文件零改动 |
| 红测试断言/契约签名是否被改 | ✅ 未改；`ReaderPanelAccentTest` 语义键断言原样转绿 |
| 语义键与绘制同一局部值 | ✅ `ReaderPanelAccentColorKey` 仅两处记录（ReaderScreen.kt:932/993），分别记录 `markColor`/`currentColor`，与绘制同源局部变量 |
| 选型 a 落地 | ✅ `val accent = theme.foreground`（ReaderScreen.kt:255），10 个调用点全部传参；无新增色值、主题色值零变更 |
| 影响面完整性（对照简报清单） | ✅ ✓/目录高亮、上/下一章、A-/A+、导入字体、错误态返回、加载指示、⋮ 菜单（容器+文字+禁用）、3 个 sheet 拖拽把手——逐项在 diff 中找到对应落点 |
| 禁用态 | ✅ 统一 `accent.copy(alpha = 0.38f)`，符合简报豁免口径 |
| 红注释清除 | ✅ 全仓 grep 无「M13 红」残留 |
| 无关改动/格式化噪声 | ✅ 无 |
| 观察（不改代码） | 菜单项记录色值用的是 `theme.foreground` 而非局部 `accent`（两者同值，菜单项无语义键断言，可接受）；菜单项选中/悬停叠加态仍为 material3 默认，简报未要求定制 |

## 三、独立复验（Kimi 亲自重跑，非采信报告）

```bash
./gradlew testDebugUnitTest --rerun-tasks   # 54 任务全部强制重跑
```

- **BUILD SUCCESSFUL**；单测 **263 例 / 0 失败 / 1 跳过**（跳过 = 既有 F4.8 进度条 @Ignore），XML 报告时间戳为本轮新产出。
- 计数核对：`git grep -c '@Test'` = **263**（46 个文件），与运行报告逐例一致；较 M15 基线 261 新增 2 例（`ReaderPanelAccentTest`），恰为红测试数。
- `validateFixtures` 通过。
- `./gradlew check` 本地不可全绿，两项受阻均与 M13 无关（记录在案，不修）：
  1. `lintAnalyzeDebug` 需联网拉取 lint-gradle 31.6.1，本机代理 127.0.0.1:7890 失效（AGENTS.md 已记的已知问题）；
  2. `testReleaseUnitTest` 为既有缺口——release 变体未接入 fixtures/debug assets，全部 UI 测试（含 M13 未触碰的既有类如 `ReaderThemeFontTest`）同一「activity 解析失败 / fixtures 缺失」失败，CI 门禁本来就不含它（CI = assembleDebug + testDebugUnitTest）。

## 四、遗留

- **设备端**：`connectedDebugAndroidTest`（含新 TC-UI-14 真实导航断言）待有设备时执行；注意跑完会卸载应用需重装。
- **真机 D13**：系统深浅 × 主题明暗 4 组合弹层目测（重点：M10「菜单随阅读主题」反馈的解释是否正确、选型 a 观感是否可接受——触发重估条件则评估 `ReadingTheme.accent`）。
- 验收通过后：打 `m13` tag、更新 `docs/开发计划.md` M13 状态与 AGENTS.md 进度段。
- 工作树未提交改动两批：M13 实现（`ReaderScreen.kt`）+ 应用名文档（需求说明书 REV11 / AGENTS.md / 开发计划 M14 / 决策日志 D-2026-09-16-01），提交待用户确认。
