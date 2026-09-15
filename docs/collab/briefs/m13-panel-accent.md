# M13 弹层/菜单强调色与阅读主题解耦（F4.4/F4.5）— 实现简报（Kimi Code → OpenCode）

> 分支：`main`（沿用线性提交）。红测试与契约骨架随本简报一并提交（TDD 红），你的任务是把它们做绿。
> 需求映射：F4.4/F4.5 无障碍与视觉收尾、NF-无障碍（对比度 ≥4.5:1）；用例：TC-UI-13/14（《测试用例》REV8）；
> 设计：`docs/软件设计文档.md` REV13 §3.9；决策：`docs/collab/决策日志.md` D-2026-09-15-01。

## 背景（一句话）

面板容器背景已随阅读主题（`theme.background`），但**选中勾（✓）与当前目录项高亮取自 `MaterialTheme.colorScheme.primary`**——colorScheme 只随系统深浅色。系统深浅与阅读主题明暗相反时对比度不足（WCAG 相对亮度公式实测）：

| 色彩来源 | 默认白 #FAFAFA | 护眼绿 #C7EDCC | 柔光米黄 #F5F0E1 | 夜间黑 #121212 |
| --- | --- | --- | --- | --- |
| 系统深色主色（浅紫，colorScheme.primary） | 1.6:1 ❌ | 1.3:1 ❌ | 1.5:1 ❌ | 11.0:1 |
| 系统浅色主色（深紫，colorScheme.primary） | 6.2:1 | 5.0:1 | 5.7:1 | 2.9:1 ❌ |
| 四主题前景色（`theme.foreground`，本次目标） | 16.5:1 | 9.4:1 | 10.0:1 | 9.4:1 |

## 已定型的设计（不可擅自更改）

### 1. 强调色来源 = `theme.foreground`（选型 (a)，不新增色值）

- `ReaderScreen` 内 `val accent = theme.foreground`，经参数下发到面板；**不得**新增 `ReadingTheme.accent` 字段、不得改 `ReadingTheme` 色彩常量。
- 选中编码冗余保留：勾形符号 + 加粗 + `stateDescription`/`selected` 语义（不单靠颜色）。

### 2. 契约签名（红测试已固化，不得改名/改序/加默认值）

- 新语义键（`ReaderScreen.kt`）：`val ReaderPanelAccentColorKey = SemanticsPropertyKey<Color>("ReaderPanelAccent")`
  —— 记录**控件实际绘制所用**的强调色，供测试断言（同 TC-UI-03 的 `ReaderBackground/ForegroundColorKey` 机制，不用像素截图）。
- `ChapterSheetContent(..., accent: Color)`、`ReaderSettingsSheetContent(..., accent: Color)`、
  `TocSheetContent(..., accent: Color)`、`TocListContent(..., accent: Color)`、`SheetRadioOption(..., accent: Color)`。
- `ReaderScreen` 三个调用点（章节面板 / 目录面板 / 阅读设置面板）**已传 `theme.foreground`**（红提交里已接线，叶子尚未使用）。

### 3. 红→绿只改「接线」（实现任务）

把下述控件的绘制色与语义键从 `MaterialTheme.colorScheme.primary` 改为 `accent`；
**语义键必须与绘制使用同一个局部值**（审查点：键不得与实际绘制色脱节）：

1. `SheetRadioOption` 的 ✓（`SheetRadioOption` 内，选中时）。
2. `TocListContent` 当前项高亮（`isCurrent` 时；未选中项颜色语义不变）。
3. 面板内文字按钮：`ChapterSheetContent` 上一章/下一章、`ReaderSettingsSheetContent` A-/A+ 与「导入自定义字体」——
   `ButtonDefaults.textButtonColors(contentColor = accent, disabledContentColor = accent.copy(alpha = 0.38f))`
   （禁用态豁免对比度要求，但必须仍可见，不得沿用 colorScheme 的 onSurface@38%）。
4. 正文错误态「返回」按钮（`ReaderScreen` 错误分支）同上。
5. 正文加载指示 `CircularProgressIndicator(color = theme.foreground)`。
6. ⋮ 下拉菜单（源码/预览、编辑、阅读设置）：容器 `theme.background`、item 文字 `theme.foreground`、禁用态
   `theme.foreground.copy(alpha = 0.38f)`（`DropdownMenu`/`DropdownMenuItem` 的颜色参数；API 如不支持容器色，
   用最接近的可行方案并在报告中说明）。
7. 三个 `ModalBottomSheet` 的拖拽把手 → `accent`（`BottomSheetDefaults.DragHandle(color = ...)`；若当前
   material3 版本无 `color` 参数，则自绘 32×4dp 圆角条，颜色同为 `accent`；不得为此新增依赖）。

### 4. 非目标（本次明确不做）

- `SettingsScreen` 全局设置页、编辑器界面、`MarkdownPreview`、`ReaderProgressBar`（隐藏中）**不动**；
- 顶栏图标与系统栏已随阅读主题（M10/U2），不在改动面；
- 不引入 `ReadingTheme.accent`、不改动任何既有主题色值；
- 不改动正文渲染（`ReaderTextView` 合并规则/对比度回退）与解析层。

## 允许修改

- `app/src/main/java/com/sample/docreader/ui/reader/ReaderScreen.kt`（唯一实现文件）。

## 不得修改

- 红测试断言（`ReaderPanelAccentTest`、`ReaderAccentDeviceTest`）：如认为断言有误，**停下回报 Kimi**，不得自行改断言；
- 既有测试的 `accent` 参数传入值（调用点已由 Kimi 补齐）；
- 其它任何文件（如需越界，先回报）。

## 必须保持的不变量

- 既有单测 **261/0（1 skip）** 无回归；`./gradlew check` 过；
- `SheetRadioOption` 的 selectable/stateDescription/≥48dp、`TocListContent` 的层级缩进与 `selected` 语义不变；
- 调试/临时文件不新建；不执行 `rm`；不自行 `git commit`。

## 验收标准（TC-UI-13/14）

- `ReaderPanelAccentTest`（Robolectric，2 例，night 限定符 + `AppTheme` 复现「系统深色 × 亮色阅读主题」错配）：
  ① 阅读设置面板内所有「✓」的 `ReaderPanelAccentColorKey` == 当前阅读主题 `foreground`（四主题轮转），且对
  `theme.background` 对比度 ≥4.5:1；② `TocListContent` 当前项同上。
- `ReaderAccentDeviceTest`（androidTest，1 例）：真实导航（夹具 → ⋮ → 阅读设置 → 切「柔光米黄」）后断言面板内
  所有 ✓ 的键值 == 柔光米黄前景色且对比度 ≥4.5:1（**由 Kimi 在有设备时执行**，你只需保证可编译）。
- 真机 4 组合目测（系统浅/深 × 主题明/暗）由 Kimi/用户执行，不在你的验证范围内。

## 应运行的验证

```bash
./gradlew testDebugUnitTest   # 预期：新增 2 例转绿，既有 261 例全绿（1 skip 保持）
./gradlew check               # 含 validateFixtures
```

## 升级条件（必须暂停回报）

- 需要新增/修改 `ReadingTheme` 色值或模型字段；
- 需要修改红测试断言或既有测试的既有断言；
- material3 现有 API 无法实现 §3 中 6/7 项且你不确定替代方案；
- 发现范围外文件必须改动。

## 完成前请

1. 检查实际 diff（含未跟踪文件）；
2. 运行获准的验证并如实报告（改了什么 / 跑了什么 / 没验证什么 / 剩余风险）；
3. 不要自行提交、发布或扩大范围。
