# M18 审查记录 — 复制后问 AI（Kimi Code 侧）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-19 |
| 审查对象 | OpenCode 实现（工作区改动，基线 `31efb2b` 红测试），依据需求 REV14 / 设计 REV18 §3.10 / 测试用例 REV13 / 决策 D-2026-09-19-03 / 简报 `m18-ask-ai.md` |
| 实现者 | OpenCode（实施报告 `docs/collab/reviews/m18-opencode-report.md`） |
| 结论 | ✅ **审查通过（两轮返工后）**：必修 1（菜单未受总开关约束）、必修 2（缺 `<queries>` 致真机枚举失效）、必修 3（未实现内置预设）均已由 OpenCode 返工修复并通过复审；建议 3 项已处置/记录 |

## 1. 必修项

**必修 1：⋮ 菜单「问 AI」入口未检查总开关 `ask_ai_enabled`**
- 事实：`MainActivity.kt:151-163` 的 `onAskAi` 只做「剪贴板空 → 提示」，否则直接 `sendAskAi(text)`；`sendAskAi` 只校验目标应用（`askAiTarget`），**未校验 `askAiEnabled`**。故「设置里关闭问 AI」后，从阅读器 ⋮ 菜单点「问 AI」仍会把内容发给目标应用。
- 违反：设计 REV18 §3.10-1「关闭时既不监听剪贴板**也不提供菜单发送**」；测试用例 TC-F4-11 ③「设置关闭后无提示、菜单项亦不发送（提示未启用）」。
- 红测试未能捕获的原因：该路径属手工用例（TC-F4-11），自动化红测试覆盖的是 `AskAi.shouldOffer` 的 `enabled` 门控（剪贴板路径），二者不是同一入口——属**测试覆盖边界**而非实现者疏忽，但终态必须补齐。
- 处置：返工（交 OpenCode，保持「实现由 OpenCode 完成」的固定顺序）。要求：菜单路径在 `enabled == false` 时给出「未启用」提示且不发送；新增字符串键 `ask_ai_disabled`（中英同键）；不改测试。
- **返工结果（2026-09-19，OpenCode）**：`MainActivity.kt` 的 `onAskAi` 改为优先判断 `askAiEnabled`——关闭时 Snackbar 提示「已关闭，可在设置中开启」且**不读剪贴板、不发送**；新增 `ask_ai_disabled` 键（values/values-zh 各 128 键，键集对等）；测试零改动；报告追加「6. 审查返工记录」。
- **复审（Kimi）**：查看返工 diff 确认门控位于剪贴板读取之前（无「关闭态仍读剪贴板」副作用）；键计数 128=128；返工后 `testDebugUnitTest --rerun-tasks` 独立复跑 **290/0/1skip**、`assembleDebug` 与 `compileDebugAndroidTestKotlin` 通过。**必修 1 关闭。**

**必修 2：`AndroidManifest.xml` 缺 `<queries>` 声明 → 真机候选枚举近乎失效（D16 走查发现）**

- 事实：真机（PHM110 / Android 13）设置页「目标应用」对话框只列出「朗读」「蓝牙」，**已安装的 DeepSeek 不在其中**；`adb shell cmd package query-activities -a android.intent.action.SEND -t text/plain` 显示系统侧存在 5+ 个接收方（浏览器/彩信/云盘等），而应用内 `queryIntentActivities` 仅返回 2 个。
- 根因：Android 11+ 包可见性过滤——未在 `<queries>` 声明目标 intent 的应用，`queryIntentActivities`/`getApplicationInfo` 只能看到系统预置的一部分；此前本应用从未声明 `<queries>`。
- 违反：需求 F4.10「目标应用由**动态枚举**（可接收 text/plain 的已安装应用）中选择」；设计 §3.10-2。属**功能不可用**级缺陷。
- 处置：返工——`AndroidManifest.xml` 增 `<queries>`（SEND/text-plain intent + 预设包名 `<package>` 两项）。**不是权限**：不申请运行时权限、不影响「不联网/不采集」红线（已在设计 §3.10-2 注明）。

**必修 3：未实现「内置预设」（需求与决策明确要求）**

- 事实：实现只有动态枚举，无预设候选；决策 D-2026-09-19-03 与需求 F4.10 均为「动态枚举 **+ 内置 DeepSeek/豆包预设**」。框定侧亦有缺口（原设计 §3.10-2 未写预设语义，已在返工前补写）。
- 处置：返工——`AskAi.PRESET_TARGETS`（DeepSeek `com.deepseek.chat`、豆包 `com.larus.nova`）+ `candidates` = 预设中已安装者 ∪ 枚举结果 → 按包名去重（label 优先 `loadLabel`、回落预设展示名）→ 排序 → 排除自身；补红测试 `tcUt19_candidates_includesInstalledPresets`（返工前已确认为红）。
- **返工结果（2026-09-19，OpenCode，第二轮）**：manifest `<queries>` 落地；`AskAi` 预设合并实现；测试零改动；报告追加「7. 审查返工记录（第二轮）」；`testDebugUnitTest` **291/0/1skip**（+1 为新增预设用例转绿）、`assembleDebug` 与 `compileDebugAndroidTestKotlin` 通过。
- **复审（Kimi）**：核对 manifest 无新增权限项、`<queries>` 仅声明可见范围；核对合并逻辑去重/标签回落/排除自身与预设语义一致；独立复跑 291/0/1skip；**重做 D16 走查确认真机候选出现 DeepSeek**（见 `m18-acceptance.md`）。

## 2. 建议与处置

1. **设计文档键名与实现不一致**（设计 §3.10 写 `ask_ai_default_prefix`，实现取 `settings_ask_ai_prefix_default`，简报 §2.5 已授权二者取一并注明）：**处置：回写设计文档为实际键名**（Kimi 侧文档修订）。
2. **简报 §2.5 键数笔误**：简报称「各 16 新键」，实测各 **15** 键（OpenCode 报告 §1.5 已勘误）。**处置：接受报告口径**，键集对等由 `LocalizationTest` 保证。
3. **前缀输入逐字符落库**：`AskAiPrefixRow` 每次 `onValueChange` 即 `saveAskAiPrefix`（DataStore 每击键一次写）。**记录不修**：量与既有「行距/主题点选即存」同量级，且无网络/文件 IO；如后续感知抖动可加防抖（不在本批次）。

## 3. 审查通过项（逐条核对）

- **红线**：无新权限（`AndroidManifest.xml` 未改）、无新依赖（`app/build.gradle.kts` 未改）、无联网代码；文本仅在用户点按后经 `ACTION_SEND` 交给第三方应用 —— 符合。
- **核心逻辑**：`candidates` 去重/排序/排除自身；`composePayload` 三态；`shouldOffer` 四门控；`excerpt` 截断；`buildSendIntent` 字段齐备（`setPackage` 确保不弹分享面板）；`send` 异常收敛为 `false` —— 与设计签名一致。
- **生命周期**：`LifecycleResumeEffect(enabled, targetPackage)` 仅 RESUMED 注册监听、离开注销；关闭或未选目标时不注册 —— 与设计 §3.10-3 一致（后台不读剪贴板）。
- **不打扰**：`lastOffered` 去重 + Snackbar action 才发送（`ActionPerformed`）—— 与 TC-F4-11 ② 一致。
- **设置页**：三行齐备；开关默认开且持久化；目标应用行未选显示「未选择」、无候选空态；前缀清空 = 空串（原文直发）与「未设置 = 资源默认」区分正确；`OptionPickerRow` 新增 `emptyText` 为可选参数，五个既有调用方零改动 —— 与设计 §3.10-4 一致。
- **无障碍**：开关/输入框 `contentDescription` 与行文本同串、行高 ≥ 48dp、菜单项沿用 M13 主题前景色规范 —— 符合。
- **未改动测试**：`git status` 仅 6 个主代码/资源文件 + 报告，三个红测试文件零改动 —— 符合。

## 4. 独立验证

- `./gradlew testDebugUnitTest --rerun-tasks`：**290 completed / 0 failed / 1 skipped**（唯一 skip 为既有 F4.8 `@Ignore`）——独立复跑与 OpenCode 报告一致。
- `./gradlew compileDebugAndroidTestKotlin`：BUILD SUCCESSFUL（Kimi 代跑，补 M17 曾漏的 androidTest 编译路径校验）。
- 设备端 `AskAiDeviceTest`（TC-UI-21）与真机 D16 待执行。

## 5. 过程记录（协同规范 §6）

- M18 期间 OpenCode 会话被权限墙中止 4 次：① `./gradlew compileDebugAndroidTestKotlin` 未在白名单；②③ 复合命令（`&&`）与 `rg` 未在白名单；④ 补白名单后复合命令仍被 `"*": "ask"` 兜底拒绝。经用户批准将 `bash."*"` 改为 `allow`（git 写操作 deny 保留，探针验证 `git add*` 仍被拦截），第 5 次会话完成自验证与报告。
- 实现始终由 OpenCode 完成，Kimi 只做框定（红测试契约 + 接口桩）与审查，符合「OpenCode 实现 → Kimi 审查」的固定顺序。
