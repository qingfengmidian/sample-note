# M16 审查回应 — 应用内语言切换（F4.9）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-18 |
| 对象 | 审查记录 `docs/collab/reviews/m16-review.md`（提交 `2ac6ecc`：必修 1 项 + 建议 8 项 + 已确认无问题 10 项） |
| 处置 | **必修 1 项已返工**；建议落地 5 项（1/2/3/4/5），记录不修 2 项（6/8），流程收尾 1 项（7）随本批完成 |
| 验证 | 单测返工后 **276 / 0 failed / 1 skipped**（返工前 275）；**空壳反证 6/6 全红**；设备端首轮真机 2/2（返工后复跑受阻，见 §四） |
| 关联 | 设计 REV16 §3.7 回写、`docs/测试用例.md` REV11、决策 D-2026-09-18-01 补记、简报 `briefs/m16-language-switch.md` 回写段 |

## 一、必修项：TC-UI-17 ⑤ 是「假绿」——已返工

审查结论成立，已独立复核并返工：

- **事实**：⑤ 原写法为 `val locales = runCatching { ... }.getOrNull(); if (locales != null && !locales.isEmpty) { assertEquals(...) }`——守卫条件（框架态是否被写入）正是被测对象，红态（空实现）必然跳过断言并通过。审查探针 A 表明 Robolectric 4.13 自带 `ShadowLocaleManager`（minSdk 33），红提交 `16487d6` 说明中「Robolectric 无 LocaleManager 服务」**不成立**。
- **返工**：⑤ 改为无条件断言——先 `assertNotNull` 服务存在（并注明「否则本例退化为假绿」），再断言 `applyToFramework(ZH)` 返回 `true` 且框架态为 `zh`、回写「跟随系统」后框架态为空。
- **反证**：临时以空壳实现（`tagOf→null`/`languageOf→SYSTEM`/`wrap→base`/`applyToFramework→false`）覆盖 `LocaleSwitcher` 后，`LocaleSwitchTest` **6 例全部失败（6/6）**，随后恢复实现并复跑全绿——⑤⑥ 不再是假绿。
- **归因更正**：按 M12 先例（`m12-acceptance.md` 记「09-12『失焦』归因不成立」），更正记录于本文件与验收记录，**不改写历史提交**。

## 二、建议项处置

| # | 审查建议 | 处置 | 证据/落点 |
| --- | --- | --- | --- |
| 1 | `<33` 重建路径零固化测试 | ✅ 采纳 | 新增 ⑥ `@Config(sdk=[32], qualifiers="zh")`：`applyToFramework` 返回 `false`；`Robolectric.buildActivity(MainActivity).setup()` 取英文、`recreate()` 后回中文（`LocaleSwitchTest.kt`） |
| 2 | 33+ 框架调用失败无兜底重建 | ✅ 采纳（契约微调） | `applyToFramework` 改为返回「是否已交由框架处理」；UI 侧 `if (!LocaleSwitcher.applyToFramework(context, next)) recreate()`；`needsManualRecreate` 删除，避免冗余 API。设计 REV16 + 决策补记 |
| 3 | `wrapBaseContext` 无异常保护 | ✅ 采纳 | `runCatching { ... }.getOrDefault(AppLanguage.SYSTEM)`——DataStore 损坏（`CorruptionException`）回退「跟随系统」，不再把启动路径打成崩溃 |
| 4 | `internal wrap` 注释不实 | ✅ 采纳（保留可见性） | 用例① 改为真正直驱 `wrap(context, null/"zh")`，注释「供测试直驱」现成立 |
| 5 | 非局部 `return` 语义陷阱 | ✅ 采纳 | 改 `return@runCatching false`，避免后续追加语句被静默跳过 |
| 6 | 档位陈旧捕获（极快连点） | ⏸ 记录不修 | 与既有 `theme`/`font`/行距行**同款模式**；单点改动会与既有行不一致，一致性优先。记入决策补记与验收遗留 |
| 7 | 流程收尾 | ✅ 随本批完成 | `m16-acceptance.md`、`开发计划.md` M16 行状态、`pending-real-device-tests.md` D14 |
| 8 | `Locale.getDefault()` 不随应用内切换 | ⏸ 记录备查 | `HomeScreen.kt:305` 时间戳格式 `MM-dd HH:mm` 纯数字，中英无可见差异；后续若引入本地化日期格式再改 |

## 三、验证证据（本批实测）

- **单测**：`./gradlew testDebugUnitTest` → `tests=276 failures=0 errors=0 skipped=1`（返工后两次复跑一致；基线 268 + M16 新增 8 例）。
- **红态反证**：空壳覆盖下 `--tests '*LocaleSwitchTest*'` → `6 tests completed, 6 failed`；恢复实现后全绿。
- **设备端（首轮，返工前）**：`connectedDebugAndroidTest` 限 `LocaleSwitchDeviceTest` → PHM110 / Android 13 上 `Starting 2 tests / Finished 2 tests`，BUILD SUCCESSFUL（包装路径 + 33+ 框架路径）。
- **真机 UI 走查（首轮）**：设置页「通用 / 语言 / 跟随系统」→ 点击「切换」三连，行值与界面语言 `跟随系统→中文→English→跟随系统` 即时随动（英文态显示 General/Language/Switch）；`am force-stop` 后重开保持「中文」；回「跟随系统」后 `adb shell cmd locale get-app-locales com.sample.docreader` == `[]`。
- **系统侧第二入口（静态核查）**：`AndroidManifest.xml` 未声明 `android:localeConfig` → 系统「按应用语言」不列出本应用，与 D-2026-09-18-01「避免双源」一致（设备侧目测项见 D14 ④）。

## 四、未完成与阻塞

- **返工后设备端复跑未执行**：真机无线调试掉线（`adb devices` 为空、`adb mdns services` 无发现），重连前无法补跑 TC-UI-18 与 D14 走查。真机当前所装 APK 为**返工前**构建。
- 影响评估：返工对 33+ 路径的行为未变（框架成功 → 返回 `true` → 不手动重建，与首轮真机通过时一致）；新增分支「框架失败 → 自行重建」由 ⑥ 在 SDK 32/框架拒绝路径覆盖。故回归风险低，但仍需真机复跑确认后方可收口。
- 待补：TC-UI-18 真机复跑、D14 全项、中英各一遍 P0 回归、`m16` tag（按惯例待用户确认收口）。
