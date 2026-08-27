# M8a 设置页全项 + 错误提示钉死 — 审查记录（Kimi Code）

> 简报：`docs/collab/briefs/m8a-integration.md`。状态：**审查通过，真机验收通过，待合入**。

## 审查清单（交付后逐项核验）

### 范围与不变量

- [x] diff 仅触及允许清单（`ui/SettingsScreen.kt` 修改 + `ui/SettingsGlobalCycleTest.kt` 新增 + 本审查记录）
- [x] 无任务外修改/格式化；无新依赖；core/reader/editor/home/MainActivity/fixtures 未动
- [x] 全量 132 用例全绿（130 既有 + OpenCode 新增 2），SettingsScreenAutoSaveTest 未改坏（1/1 绿）
- [x] 阅读页底部工具条即时调节行为不回归（ReaderViewModel/reader 文件零改动，17/17 绿）

### 功能正确性（对照 F4.3/TC-F4-03）

- [x] 设置页五行齐：默认字号/默认行距/默认换行模式/默认字体/默认阅读主题，各行显示当前值
- [x] 字号步进按钮 desc "减小默认字号"/"增大默认字号"，直接复用 `ReaderViewModel.FONT_SIZE_STEPS`（端点 clamp）
- [x] 行距循环 120→150→180→200；字体循环 代码→衬线→无衬线；换行 自动换行↔不换行；主题循环 跟随系统→默认白→护眼绿→柔光米黄→夜间黑 —— 与 `ReaderViewModel` 四段函数逐行比对一致（含 CUSTOM 经 `indexOf=-1` 回落 CODE 的行为）
- [x] 每次修改立即 `SettingsRepository.save` 持久化
- [x] SettingsGlobalTest 2 用例转绿，语义未被削弱（showsAllGlobalDefaults + 字号 16→18 持久化）

### 质量门槛

- [x] 独立重跑 `cleanTestDebugUnitTest testDebugUnitTest` 全绿（tests=132 failures=0 errors=0，结果 XML 时间戳为本次运行）
- [x] `assembleDebug` 通过（BUILD SUCCESSFUL）
- [x] 无障碍：新控件均有 contentDescription，可点击目标 ≥48dp（`defaultMinSize`）
- [ ] ~~OpenCode 报告存在~~ **未交付**（见观察项 O1）

## 真机验收（2026-08-27，OPPO Reno9 5G / PHM110，WiFi adb 驱动）

- [x] TC-F4-03：设置页逐项修改（字号 16→18 / 行距 150→180 / 换行→不换行 / 字体→衬线 / 主题→默认白）→ 新打开 sample.md 工具条即时反映全部新默认值；截图目视确认：系统深色下阅读页白底（默认白覆盖跟随系统）、衬线字形、180% 行距排版。**通过**。
- [x] TC-F2-23：真机 SAF 打开 broken.epub → 显示差异化文案"epub 缺少 OPF 描述文件，无法解析"（非笼统报错）。**通过**。
- [x] 编辑器光标跨进程恢复复测（M7c 遗留核实）：两轮验证（HOME → `am kill` → 冷启动恢复任务）：
  - R1：草稿正文正确恢复；键盘聚焦编辑区（不改选区）后输入字符，落点在杀进程前光标处（中途 IME 吃掉一次方向键导致差 1 位，第二轮消除该变量）。
  - R2（确定性）：光标定位到 `…abcde|fg…`（end-11）→ 杀进程恢复 → 输入 W → 精确落在 `…abcdeWfg…`。**通过，M7c 静态结论（SavedStateHandle 接线无 bug）得到真机实证**。
- 验收环境备注：ColorOS 应用安装器在完成页叠加广告推荐弹窗，曾拦截点击造成两次"点击无效"假象；设备上原有 4 条最近文件的 `com.coloros.filemanager` URI 已全部失效（Provider 查询不到），为环境问题非应用缺陷。
- 验收后清理：m7c_new.txt 测试改动已保存落盘（草稿清空）；设置页五项已复位默认值。

## 代码走查意见（Kimi Code）

实现与契约一致，四个档位/循环函数与 ReaderViewModel 逐行一致，直接引用其常量避免了两份档位表漂移，处理得当。

观察项（均不阻塞合入）：

- **O1 OpenCode 收尾失败（复发）**：`m8a-opencode-report.md` 未写、`assembleDebug` 未跑——它在测试全绿后试图用 `grep|sed|awk` 统计结果 XML，被权限白名单拒绝即停止。与 M7c 同一模式（白名单外命令→中止收尾）。已由我独立复验补齐；规范 §3 纪律条款有效（本次无调试残留文件），但**收尾失败模式需根治**：后续简报须显式写明"白名单外命令不可用时，直接停止并说明，不要尝试绕过"。
- **O2 布局调整**：自动保存区块被移到五个阅读默认值之前（注释说明是为避开 Robolectric 可视区外 performClick 失效）。SettingsGlobalTest 契约未约束顺序，SettingsScreenAutoSaveTest 仍绿，接受；真机验收时目视确认布局合理即可。
- **O3 理论竞态（轻微）**：`repository.save(settings.copy(...))` 读取的是 `collectAsStateWithLifecycle` 的快照，极快连续点击（DataStore 尚未回播新值）可能丢一档。阅读页 ReaderViewModel 走内存 StateFlow 无此窗口。实际影响小（人手连点间隔 >> DataStore 回播延迟），记录在案，不处理。

## 独立复验结果（Kimi Code）

| 项 | 结果 |
| --- | --- |
| `cleanTestDebugUnitTest testDebugUnitTest` | 132 tests / 0 failures / 0 errors |
| SettingsGlobalTest（先行红测试） | 2/2 绿（红→绿闭环） |
| SettingsScreenAutoSaveTest（M7c） | 1/1 绿，无回归 |
| SettingsLineHeightCycleTest / SettingsThemeCycleTest（OpenCode 新增） | 各 1/1 绿 |
| ReaderViewModelTest（含 TC-F2-23 钉死） | 17/17 绿 |
| `assembleDebug` | BUILD SUCCESSFUL |

## 审查意见与处置

无阻塞意见。O1/O2/O3 为观察项，不要求返工。

## 结论

**审查通过，真机验收三项全过**，可合入 main。

> 简报：`docs/collab/briefs/m8a-integration.md`。状态：**审查通过，真机验收通过，待合入**。
