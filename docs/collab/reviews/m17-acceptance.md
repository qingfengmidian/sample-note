# M17 验收记录 — 设置项选项列表交互 + 语言确认重启（F4.3/F4.9）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-19 |
| 环境 | OPPO PHM110 真机（Android 13 / API 33，系统语言中文） |
| 状态 | ✅ **验收通过（2026-09-19）**：设备端 3/3 + 全量套件 17 例 0 失败、D15 走查全过、单测 281/0/1skip；**用户终审通过**，tag `m17` |
| 范围 | F4.3/F4.9 交互修订（需求 REV13 / 设计 REV17 §3.7 M17 块 / 测试用例 REV12 / 决策 D-2026-09-19-01/02） |
| 交付提交 | `136856c` 框定 → `8cbcffb` 红测试（8 红 2 绿）→ `db99531` 实现（OpenCode + Kimi 审查处置）→ `dcfb650` 设备端 import 修复 → `9e84f78` §8.5 复核处置 → 本批（设备端用例点击目标修正 + 验收记录） |
| 依据 | `docs/collab/briefs/m17-settings-option-pickers.md`、`reviews/m17-opencode-report.md`、`m17-review.md`、`m17-opencode-review.md`、`m17-opencode-response.md` |

## 一、验收结论

- **单测**：`./gradlew testDebugUnitTest` → **281 completed / 0 failed / 1 skipped**（跳过为既有 F4.8 进度条 `@Ignore`）；红→绿过程可复现（`8cbcffb` 8 红 2 绿 → `db99531` 全绿）。
- **设备端（真机 PHM110）**：`LocaleSwitchDeviceTest` 2 例 + `LocaleSwitchPickerDeviceTest` 1 例（TC-UI-18 M17 增补）→ **3/3 通过**；全量设备端套件 **17 例 / 0 失败 / 1 跳过**（进度条 `@Ignore`）。
- **D15 真机走查（Kimi adb 驱动，截图 `build/d15/` 不入库）**：
  - 设置页五行已为「标题 + 当前值」整行可点（「切换」按钮移除）；
  - 选项对话框：行距 4 档 / 主题 5 档 / 字体 3 档（无自定义字体故无 CUSTOM）/ 换行 2 档 / 语言 3 档，选项全集正确、当前值选中、点选即关闭并生效；
  - 行距 150% → 180% 即时生效且回读一致；主题 跟随系统 → 夜间黑 → 跟随系统；
  - 语言「选 English → 确认框」：文案「切换语言需要重启应用，是否立即重启？」+「取消」/「立即重启」正确；**「取消」后行值保持「跟随系统」且 `get-app-locales == []`**（不落库）；重选 English「立即重启」后**整屏英文**（General / Language / Switch 全量切换）且框架态 `[en]`；再切「跟随系统」并确认后回到中文 + `[]`；
  - 模态隔离语义在位（对话框打开时背景行值退出语义树，uiautomator 可见）；
  - 走查后设备已复位（行距 150% / 主题 跟随系统 / 语言 跟随系统）。
- **审查链**：Kimi 审查（必修 0，4 项建议处置）+ OpenCode §8.5 只读交叉复核（**可接受进入收口**，必修 0；S1 已修、S2 已注记、S4 已加固、S3 记录不修）。

## 二、DoD 对照

| DoD 项 | 状态 | 证据 |
| --- | --- | --- |
| 五个设置行为选项对话框（列出全部选项、当前值标记） | ✅ | 单测 TC-UI-19 四例 + D15 真机五行 |
| 点选即生效（语言除外） | ✅ | 单测 TC-UI-19 ②④ + D15（行距/主题回读） |
| 语言选择后经确认框；取消不落库、确认先落库再重建 | ✅ | 单测 TC-UI-17 ②~⑤ + 设备端 TC-UI-18 M17 增补 + D15 |
| 死代码清理（`CycleSettingRow`/`next*`/`LocaleSwitcher.next`） | ✅ | §8.5 复核全库无残留引用 |
| 新字符串键中英同键补齐 | ✅ | 112=112 键、`LocalizationTest` TC-UI-15 ③ |
| 阅读器阅读设置面板不回归 | ✅ | 本批未触碰 `ReaderScreen`/`ReaderViewModel`；全量套件 17 例含阅读器设备端用例全绿 |
| 无回归（单测 + 设备端） | ✅ | 281/0/1skip；设备端 17/0/1skip |

## 三、遗留与待办

1. **S3 记录不修**：`common_switch` 未用键（需中英同删）、`ReaderViewModel.cycle*` 仅测试调用——留待后续资源/测试清理批次（用例数下降需显式接受）。
2. 设备端首轮两处测试自身缺陷已修：`LocaleSwitchDeviceTest` 补 `flow.first` 导入（`dcfb650`）、M17 增补用例点击目标由 contentDescription 改为首页设置入口实际文本（本批）。
3. `m17` tag：已按惯例于用户终审通过后打点。
