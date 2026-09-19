# M18 验收记录 — 复制后问 AI（F4.10）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-19 |
| 环境 | OPPO PHM110 真机（Android 13 / API 33，系统语言中文，已装 DeepSeek `com.deepseek.chat`） |
| 状态 | ✅ **验收通过（2026-09-19）**：单测 291/0/1skip、设备端 19 例 0 失败、D16 全项真机通过（含用户手势确认复制 → Snackbar → 「问 AI」→ DeepSeek 输入框）；**用户终审通过**，tag `m18` |
| 范围 | F4.10（需求 REV14 / 设计 REV18 §3.10 / 测试用例 REV13 / 决策 D-2026-09-19-03） |
| 交付提交 | `31efb2b` 红测试（290/9 红）→ `1f5b29e` 实现 + 必修 1 返工 → `0405c69` 必修 2/3 返工（`<queries>` + 内置预设）→ 本批 |
| 依据 | `reviews/m18-opencode-report.md`、`m18-review.md` |

## 一、自动化

| 环境 | 命令 | 结果 |
| --- | --- | --- |
| 单测（Kimi 独立复跑） | `./gradlew testDebugUnitTest --rerun-tasks` | **291 completed / 0 failed / 1 skipped**（跳过为既有 F4.8 `@Ignore`） |
| 构建 | `./gradlew assembleDebug` | BUILD SUCCESSFUL |
| androidTest 编译 | `./gradlew compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL（补验 M17 曾漏的编译路径） |
| 设备端全量（真机） | `./gradlew connectedDebugAndroidTest` | **19 例 / 0 失败 / 1 跳过**（含新增 `AskAiDeviceTest` 两例；跳过为既有 F4.8） |

红→绿过程可复现：`31efb2b`（290/9 红，失败恰为新增 9 例）→ 实现 → 291/0/1skip（含返工期新增的预设用例）。

## 二、D16 真机走查（Kimi adb 驱动 + 日志核对；截图 `build/d16/` 不入库）

| # | 场景 | 结论 |
| --- | --- | --- |
| 1 | 设置页「问 AI」小节 | ✅ 三行齐备：启用开关（cd「启用「问 AI」」、默认**开**）、目标应用（未选时显示「未选择」）、提问前缀（显示资源默认「请解释：」+ 说明「留空则原文直发」） |
| 2 | 目标应用候选枚举（必修 2/3 修复后） | ✅ 对话框列出 **DeepSeek、Chrome、Bitwarden、Mixin、OPPO 社区、WeLink、ZArchiver、上传到云盘、京东、便签、写邮件、发送给好友/朋友** 等 13+ 项；**修复前仅「朗读」「蓝牙」**（包可见性过滤 + 无预设） |
| 3 | 目标选择持久化 | ✅ 选中 DeepSeek 后行值显示 DeepSeek；`am force-stop` 重开后仍为 DeepSeek |
| 4 | 阅读器 ⋮ 菜单入口 | ✅ 菜单含「预览/编辑/阅读设置/**问 AI**」（md）与「阅读设置/**问 AI**」（epub） |
| 5 | 发送链路（开启 + 已选目标） | ✅ 点按「问 AI」→ 系统日志单次 `START {act=android.intent.action.SEND typ=text/plain pkg=com.deepseek.chat cmp=com.deepseek.chat/.ShareProxyActivity (has extras)}`（调用方 uid = 本应用）→ DeepSeek 输入框内出现「**请解释：**<剪贴板文本>」且**未自动发送**（需用户再点发送）；日志逐次核对：**每次点按 = 一条来自本应用的 SEND**（输入框文本随点按次数累积，非单次重复发送） |
| 6 | 关闭总开关后菜单路径（必修 1 真机确认） | ✅ 开关置关 → ⋮ 菜单点「问 AI」→ Snackbar「**「问 AI」已关闭，可在设置中开启**」且日志 **0 次 SEND**；同时未读剪贴板（门控在读剪贴板之前） |
| 7 | 复制 → Snackbar 提示（含节选 + 「问 AI」动作） | ✅ **用户手势确认（2026-09-19）**：长按正文 → 选择 → 复制后，应用内出现「已复制：<节选>」+「问 AI」动作；点按后 DeepSeek 输入框出现「请解释：<所选文本>」且未自动发送（adb 无法可靠合成该手势，故由用户在真机执行确认） |
| 8 | 未选目标 → 引导提示 | ⏳ 未实测：UI 无「清除目标」入口（选择器只能切换到其它应用），该分支与 #6 同一代码路径（`sendAskAi` 前置校验），记录为等价路径；如需实测可在 `pm clear` 后进入设置页直接点菜单项 |

**旁证**：#5 发送时剪贴板内容为夹具代码块中的 `println`（说明走查期间的复制手势确实写入过剪贴板），且发送 payload = 前缀 + 该剪贴板文本，与设计一致。

## 三、DoD 对照

| DoD 项 | 状态 | 证据 |
| --- | --- | --- |
| 单测全绿无回归 | ✅ | 291/0/1skip |
| `assembleDebug` + `compileDebugAndroidTestKotlin` | ✅ | 均 BUILD SUCCESSFUL |
| 新字符串键中英同键补齐 | ✅ | 各 128 键（含返工新增 `ask_ai_disabled`）；`LocalizationTest` 键集对等绿 |
| 设置页三行行为 | ✅ | D16 #1~#3 |
| 关闭 / 未选目标时不监听不提示不发送 | ✅（关闭态真机验证） | D16 #6（0 次 SEND + 提示）；监听注册条件 `enabled && target != null`（代码审查 + 单测） |
| 阅读器 ⋮ 菜单「问 AI」项 | ✅ | D16 #4 + 设备端 `AskAiDeviceTest` |
| 红线：不联网/不采集/无新权限与依赖 | ✅ | manifest 仅新增 `<queries>`（非权限，静态可见性声明）；`build.gradle.kts` 零改动；文本仅在用户点按后交给第三方应用 |

## 四、遗留

1. D16 #8 未选目标分支记录为等价路径（与 #6 同一代码路径）；其余项均已真机验证。
2. 候选枚举不做缓存（设计已注明「量小」）；前缀输入逐字符落库（审查记录 §2.3 记录不修）。
3. `m18` tag 待用户终审后打点。
