# M16 验收记录 — 应用内语言切换（F4.9）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-18 |
| 状态 | 🔵 **代码与单测验收完成；真机复验待重连**（审查返工后未能复跑设备端）；用户终审与 `m16` tag 待确认 |
| 范围 | F4.9：设置页语言行（跟随系统（默认）/ 中文 / 英文），应用内即时生效并持久化，手动选择优先于系统语言，未覆盖语言回退英文 |
| 交付提交 | `fe8f6ec` 框定 → `16487d6` 红测试 → `39856fd` 实现 → `2ac6ecc` 独立审查 → 本次返工补提交 |
| 依据 | 需求 REV12 F4.9 / 设计 REV15→REV16 §3.7 / 测试用例 REV10→REV11 / 决策 D-2026-09-18-01（含补记）/ 简报 `briefs/m16-language-switch.md` |

## 一、验收结论（截至本记录）

- **单测**：`./gradlew testDebugUnitTest` → **276 completed / 0 failed / 1 skipped**（基线 268 + M16 新增 8 例；跳过为既有 F4.8 进度条 `@Ignore`），返工后两次复跑一致。
- **红态证据链**：红提交 `16487d6` 6 红 1 绿 → 独立审查指出第 5 例为「假绿」（守卫条件即被测对象）→ 返工后以空壳实现反证 **6/6 全红**、恢复后全绿。归因更正（红提交说明「Robolectric 无 LocaleManager 服务」不成立）记录于 `m16-review-response.md`，不改写历史提交（按 M12 先例）。
- **设备端（首轮真机，PHM110 / Android 13）**：`LocaleSwitchDeviceTest` **2/2 通过**——① 包装路径：持久 `en` → `MainActivity` 资源取 `Sample Note`、`configuration.locales[0].language == "en"`；② 框架路径：`applyToFramework(EN)` 后 `LocaleManager.getApplicationLocales()` 为 `en`（即 33+ 不声明 `localeConfig` 亦生效）。
- **真机 UI 走查（首轮）**：设置页出现「通用」小节与「语言」行（值=跟随系统）；点击「切换」三连 → 行值与**整屏语言**依 `跟随系统 → 中文 → English → 跟随系统` 即时随动（英文态为 General / Language / Switch / Font scheme）；`am force-stop` 后重开仍为「中文」（持久化跨进程）；恢复「跟随系统」后 `adb shell cmd locale get-app-locales com.sample.docreader` == `[]`（框架态同步清除）。
- **系统侧第二入口**（静态核查）：`AndroidManifest.xml` 未声明 `android:localeConfig` → 系统「按应用语言」入口不列出本应用，与 D-2026-09-18-01「避免双源」一致。

## 二、DoD 对照（`docs/开发计划.md` M16）

| DoD 项 | 状态 | 证据 |
| --- | --- | --- |
| 设置页语言三选（跟随系统（默认）/ 中文 / 英文） | ✅ | 顶部「通用」小节 + `CycleSettingRow`（`SettingsScreen.kt`）；TC-UI-17 ⑥ + 真机走查 |
| 选中即时生效 | ✅ | 33+ 由框架重建（真机实测）；<33/框架不可用由 UI `recreate()`（单测 ⑥ + 审查探针 B/C） |
| 持久化（DataStore） | ✅ | 单测 ③④ + 真机杀进程重开保持 |
| 手动选择优先于系统语言 | ✅ | 单测 ①（包装语义）+ 真机（系统中文下切英文，整屏英文） |
| 切至未覆盖语言回退英文 | ✅ | `languageOf` 未知标签 → 跟随系统（不包装）→ 系统 locale → 资源回退链（单测 ②） |
| TC-UI 新增语言切换用例 | ✅ | TC-UI-17（Robolectric 8 例）、TC-UI-18（设备端 2 例） |
| 中英各一遍 P0 回归 | ⏳ 待补 | 需真机重连后执行 |

## 三、审查与返工

- 审查记录 `docs/collab/reviews/m16-review.md`（`2ac6ecc`）：必修 1 项（⑤ 假绿）+ 建议 8 项 + 已确认无问题 10 项。
- 逐条处置 `docs/collab/reviews/m16-review-response.md`：必修返工完成；建议 1/2/3/4/5 落地（含契约微调 `applyToFramework → Boolean`、删除 `needsManualRecreate`、`wrapBaseContext` 损坏兜底）；建议 6/8 记录不修；建议 7 随本批收尾。
- 设计同步 REV16、用例 REV11、决策 D-2026-09-18-01 补记、简报回写段。

## 四、遗留与待办

1. **返工后设备端复跑未执行**：真机无线调试掉线（`adb devices` 为空、mDNS 无发现），需重连后补跑 TC-UI-18 与 D14（含 33+ 系统侧语言入口目测）；真机当前所装 APK 为返工前构建，需重装。
2. 中英各一遍 P0 手工回归（验收标准口径）。
3. 记录不修项：档位陈旧捕获（与既有行同款）、`HomeScreen` 时间戳 `Locale.getDefault()`（中英无差异）。
4. `m16` tag：按惯例待用户确认收口后打点。
