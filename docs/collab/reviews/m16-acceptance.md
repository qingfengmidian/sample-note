# M16 验收记录 — 应用内语言切换（F4.9）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-18（真机复验 2026-09-19） |
| 状态 | ✅ **验收通过（2026-09-19）**：设备端复跑、D14 走查与中英 P0 回归全部通过（PHM110 真机），**用户终审通过**，tag `m16` |
| 范围 | F4.9：设置页语言行（跟随系统（默认）/ 中文 / 英文），应用内即时生效并持久化，手动选择优先于系统语言，未覆盖语言回退英文 |
| 交付提交 | `fe8f6ec` 框定 → `16487d6` 红测试 → `39856fd` 实现（Kimi 代行，D-2026-09-18-02）→ `2ac6ecc` Kimi 侧审查 → `5c6178c` 审查返工 → `253416d` 流程固化 → OpenCode 复核与建议处置（本批） |
| 依据 | 需求 REV12 F4.9 / 设计 REV15→REV16 §3.7 / 测试用例 REV10→REV11 / 决策 D-2026-09-18-01（含补记）/02/03 |

## 一、验收结论（截至本记录）

- **单测**：`./gradlew testDebugUnitTest` → **277 completed / 0 failed / 1 skipped**（基线 268 + M16 新增 9 例；跳过为既有 F4.8 进度条 `@Ignore`），返工后三次复跑一致。
- **红态证据链**：红提交 `16487d6` 6 红 1 绿 → Kimi 侧审查指出第 5 例为「假绿」（守卫条件即被测对象）→ 返工后以空壳实现反证 **6/6 全红**、恢复后全绿。归因更正（红提交说明「Robolectric 无 LocaleManager 服务」不成立）记录于 `m16-review-response.md`，不改写历史提交（按 M12 先例）。
- **OpenCode 交叉复核**（协同规范 §8.5，D-2026-09-18-02 批准）：`m16-opencode-review.md`——**无必修项，总体结论「可接受进入收口」**，独立重跑单测 276/0/1skip；建议 7 项经 `m16-opencode-response.md` 逐条处置（6 修 1 记录），处置后单测 277/0/1skip、`compileDebugAndroidTestKotlin` 通过。
- **设备端（首轮真机，PHM110 / Android 13）**：`LocaleSwitchDeviceTest` 首轮 **2/2 通过**——① 包装路径：持久 `en` → `MainActivity` 资源取 `Sample Note`、`configuration.locales[0].language == "en"`；② 框架路径：`applyToFramework(EN)` 后 `LocaleManager.getApplicationLocales()` 为 `en`（即 33+ 不声明 `localeConfig` 亦生效）。
- **设备端复跑（2026-09-19 真机重连后，含建议 4 判别力改造版本）**：`LocaleSwitchDeviceTest` **2/2 通过**（tests=2 failures=0 errors=0 skipped=0，29.7s）；全量设备端套件 **17 例 / 0 失败 / 1 跳过**（既有 F4.8 进度条 `@Ignore`）。
- **真机 UI 走查（首轮）**：设置页「通用 / 语言 / 跟随系统」→ 点击「切换」三连，行值与**整屏语言**依 `跟随系统 → 中文 → English → 跟随系统` 即时随动；`am force-stop` 后重开仍为「中文」；回「跟随系统」后 `adb shell cmd locale get-app-locales com.sample.docreader` == `[]`。
- **D14 走查（2026-09-19 复审，Kimi adb 驱动 + 截图，截图 `build/d14/` 不入库）**：① 三连切换整屏随动逐步验证（跟随系统/中文两态均中文渲染、English 态 General/Language/Switch 等全量英文）；② `am force-stop` 重开保持 English；③ 回「跟随系统」后 `get-app-locales` == `[]`；④ 静态核查 manifest 无 `localeConfig` + 系统「应用信息」页（16 条目）无语言入口，双源风险闭环。**①~④ 全过**。
- **中英各一遍 P0 走查（2026-09-19）**：**英文态** M14 口径八屏全过（首页/夹具/阅读器菜单 Preview·Edit·Reading settings/阅读设置面板 Layout·行距✓/章节面板 Chapters·Previous·Next/设置页/新建文件对话框/编辑器 Undo·Redo·Save，截图 `build/d14/en_0*.png`；正文保持原文语言符合 F4.6 口径）；**中文态**覆盖首页/阅读器菜单（预览·编辑·阅读设置）/阅读设置面板（排版·自动换行✓·行距）+ 附带复验 TC-F2-23（broken.epub 差异化提示「epub 缺少 OPF 描述文件，无法解析」中文渲染正确），截图 `build/d14/zh_*.png`；中文侧其余屏由 09-18 首轮走查 + 全量设备端套件（中文环境 17 例全绿）兜底。走查后语言已复位「跟随系统」。
- **系统侧第二入口**（静态核查）：`AndroidManifest.xml` 未声明 `android:localeConfig` → 系统「按应用语言」入口不列出本应用，与 D-2026-09-18-01「避免双源」一致。

## 二、DoD 对照（`docs/开发计划.md` M16）

| DoD 项 | 状态 | 证据 |
| --- | --- | --- |
| 设置页语言三选（跟随系统（默认）/ 中文 / 英文） | ✅ | 顶部「通用」小节 + `CycleSettingRow`；TC-UI-17 ⑥ + 真机走查 |
| 选中即时生效 | ✅ | 33+ 由框架重建（真机实测）；<33/框架不可用由 UI `recreate()`（单测 ⑥ + 审查探针 B/C） |
| 持久化（DataStore） | ✅ | 单测 ③④ + 真机杀进程重开保持 |
| 手动选择优先于系统语言 | ✅ | 单测 ①（包装语义）+ 真机（系统中文下切英文，整屏英文） |
| 切至未覆盖语言回退英文 | ✅ | `languageOf` 未知标签 → 跟随系统（不包装）→ 系统 locale → 资源回退链（单测 ②） |
| TC-UI 新增语言切换用例 | ✅ | TC-UI-17（Robolectric 8 例）、TC-UI-18（设备端 2 例） |
| 中英各一遍 P0 回归 | ✅ | 2026-09-19 真机双语言 P0 走查（英文八屏 + 中文阅读链路 + TC-F2-23 复验），详见上节 |

## 三、审查链（双智能体，协同规范）

1. **Kimi 侧审查**（`m16-review.md` / `m16-review-response.md`）：必修 1（TC-UI-17 ⑤ 假绿）已返工 + 无条件断言；建议 1~5 落地、6/8 记录不修、7 收尾。
2. **OpenCode §8.5 交叉复核**（`m16-opencode-review.md` / `m16-opencode-response.md`）：无必修；建议 7 项（陈旧注释、编号对齐、<33 分支缺口记录、设备端判别力、@Before、键级隔离、import 风格）已处置，处置后单测 277/0/1skip。
3. 注：本轮实现由 Kimi 代行（违规与批准见 D-2026-09-18-02/03），交叉独立性以 OpenCode 复核补齐；流程硬约束已固化入 `AGENTS.md` §8，后续里程碑恢复「OpenCode 实现」正途。

## 四、遗留与待办

1. 记录不修/接受缺口：档位陈旧捕获（与既有行同款）、`HomeScreen` 时间戳 `Locale.getDefault()`（中英无差异）、<33 UI 自重建判定行的自动化覆盖（`m16-opencode-response.md` §二，含重估触发）。
2. `m16` tag：已按惯例于用户终审通过后打点。
