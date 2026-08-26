# M8a 设置页全项 + 错误提示钉死 — 任务简报（Kimi Code → OpenCode）

> 你是本批次的实现者（OpenCode）。请在指定范围内完成任务。
> 依据：`docs/开发计划.md` M8、`docs/需求说明书.md` F4.3、NF-稳定性、`docs/测试用例.md` TC-F4-03、TC-F2-23。
> 协作规则见 `docs/协同开发规范.md`；完成前自查实际 diff 并如实报告，**不要 git 写操作**（opencode.json 已 deny）。

## 目标

设置页补齐全局默认项（F4.3）：默认字号、默认行距、默认换行模式、默认字体、默认阅读主题，修改即时持久化，与阅读页共用同一套档位与 SettingsRepository；错误提示链路保持现状并将 TC-F2-23 端到端自动化钉死（已由 Kimi Code 预置，应已绿，勿改坏）。

## 背景和现状

- `ui/SettingsScreen.kt` 当前只有"字体导入"+"自动保存开关"（`AutoSaveRow`）。
- 全局设置模型与档位均已存在，**不要重复实现**：
  - `core/model/Models.kt` `ReadingSettings`（fontSizeSp 16 / lineHeightPercent 150 / wrapMode / font / theme=null 跟随系统 / autoSave）；
  - `core/settings/SettingsRepository.kt`（DataStore 单源，settings flow + save）；
  - 档位逻辑在 `ui/reader/ReaderViewModel.kt:152-175`：`stepFontSize`（FONT_SIZE_STEPS = 12/14/16/18/20/22/24，端点 clamp）、`cycleLineHeight`（120→150→180→200）、`cycleFont`（代码→衬线→无衬线）、`cycleTheme`（跟随系统→默认白→护眼绿→柔光米黄→夜间黑）。设置页调节必须产出与这些完全相同的档位序列。
- 阅读页本来就订阅同一份 settings flow，"新打开的文件应用新默认值"天然成立，无需额外机制。
- TC-F2-23 端到端用例已预置于 `ReaderViewModelTest.kt`（`load_brokenEpub_showsMissingOpfMessage`），当前已绿——这是回归钉死，不是新功能。
- 已核实：编辑器 SavedStateHandle 接线无缺陷（`createSavedStateHandle()` 在 initializer 内解析为 `CreationExtras.createSavedStateHandle()`，registry-backed，进程死亡可恢复）——本批次**不动 EditorScreen**。

## 允许修改

- `app/src/main/java/com/sample/docreader/ui/SettingsScreen.kt`
- 新建/扩展 `app/src/test` 下测试
- `docs/collab/` 下本批次记录

## 不得修改

- `core/model/`、`core/settings/`、`core/parse/`、`core/render/`、`ui/reader/`、`ui/editor/`、`ui/home/`、`MainActivity.kt`、fixtures 与 `FixtureGenerator.kt`
- `AGENTS.md` 与 `docs/` 下除 `docs/collab/` 外的文档

## 必须保持的不变量

- 既有全部单测全绿（127 + Kimi Code 预置 3 = 130；`SettingsScreenAutoSaveTest` 两个类不得改坏）；
- 阅读页底部工具条的即时调节行为不回归；
- 中文注释 + F/TC 编号交叉引用；无障碍：新交互控件 contentDescription、可点击目标 ≥ 48dp。

## 非目标（本次明确不做）

- 大文件分块加载/预取窗口（M8b）；
- `TooLarge` 提示落地（死代码清理与否留待评审，不动 `core/model/`）；
- HomeViewModel 与 ParseException 的格式不支持文案统一（首页文案含文件名、信息更全，属设计允许的差异，本批次不动）。

## 验收标准

### 自动化（先行测试已由 Kimi Code 写好，当前红）

`app/src/test/java/com/sample/docreader/ui/SettingsGlobalTest.kt`（TC-F4-03，2 用例）。实现以下 UI 契约使其转绿：

- 设置页出现五个设置行，行文本含：默认字号 / 默认行距 / 默认换行模式 / 默认字体 / 默认阅读主题；
- 字号行内两个步进按钮 contentDescription：**减小默认字号** / **增大默认字号**；档位与 `ReaderViewModel.FONT_SIZE_STEPS` 一致（16 → 增大 = 18，端点 clamp）；
- 行距/换行/字体/主题各行一个循环切换按钮，contentDescription 与行文本同名（默认行距/默认换行模式/默认字体/默认阅读主题），循环序列与 ReaderViewModel 四个 cycle 方法一致；
- 每次修改立即 `repository.save(settings.copy(...))` 持久化；
- 各行显示当前值（如 "18sp"、"150%"、"自动换行"、"代码风格（等宽）"、"跟随系统"）。

### 自动化（由你编写）

- 行距循环持久化用例（150 → 180 回读），参照 SettingsGlobalTest 模式；
- 主题循环持久化用例（null → DEFAULT 回读）。

### 手工（真机，验收时由 Kimi Code 驱动）

- TC-F4-03：设置页逐项修改 → 新打开文件应用新默认值；
- TC-F2-23：真机打开 broken.epub 看差异化文案（自动化已覆盖，真机复核）；
- 编辑器光标跨进程恢复复测（M7c 遗留核实项，预期无需改码）。

## 应运行的验证

```bash
./gradlew testDebugUnitTest   # 全绿（130 + 你新增）
./gradlew assembleDebug       # 通过
```

## 允许的工具和权限

- 上述"允许修改"内文件编辑；上述验证命令；`git status/diff/log` 只读。
- **只用内置 Read/Glob/Grep/Write/Edit 读文件和写文件**；不要用 bash 读文档内容。
- 允许的 bash：`ls`、`find`、`git status/diff/log`、`./gradlew testDebugUnitTest`、`./gradlew assembleDebug`。
- **不创建调试/临时测试文件；不使用 `rm`**（高危命令，本项目不放行）；临时产物用 Write 覆盖为最终内容或留待 Kimi Code 清理。被权限拒绝的 bash 命令不是致命错误，改用内置工具继续，不要停。

## 升级条件（暂停并交回 Kimi Code/人）

- 先行测试钉死的契约（语义描述名称、档位序列）与现实冲突需要改测试；
- 需要动"不得修改"清单（如档位逻辑要下沉到 core）；
- Robolectric 下设置页直驱不可行。

## 完成前请

1. 检查实际 diff（`git status` 含未跟踪文件 + `git diff`）；
2. 运行上述验证并附结果；
3. 用 **Write 工具**把完成报告写到 `docs/collab/reviews/m8a-opencode-report.md`（改动清单 / 已验证（命令+结果）/ 未验证 / 剩余风险）；
4. 不要自行提交。
