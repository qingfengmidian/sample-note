# M17 OpenCode §8.5 只读交叉复核 — 设置项选项列表交互 + 语言确认重启（F4.3/F4.9）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-19 |
| 复核者 | OpenCode（协同规范 §8.5 独立只读复核；无 git 写权限，未改动任何文件） |
| 复核对象 | 任务简报 `docs/collab/briefs/m17-settings-option-pickers.md`；OpenCode 实施报告 `docs/collab/reviews/m17-opencode-report.md`；Kimi 审查记录 `docs/collab/reviews/m17-review.md`（含亲改披露）；红测试 `8cbcffb`（8 红 2 绿）→ 实现 `db99531`（281/0/1skip，中间夹文档提交 `8401cf9`） |
| 复核方法 | 只读：`git diff/show/log` + 逐文件通读工作区终稿；**未运行 gradle**（构建会写 `build/`，违反只读约束），测试结论采信提交信息与 Kimi 复跑记录，并对实现做静态推演 |
| 总体结论 | **可接受进入收口**：必修 0；建议 4（S1 高优先、S2~S4 文档/加固类） |

---

## 1. 任务框定与验收标准核对

简报 §4 DoD 六条逐条核对（静态）：

| DoD | 结论 | 依据 |
| --- | --- | --- |
| 1. `testDebugUnitTest` 全绿、无回归 | **采信**（未独立复跑） | 提交信息与 `m17-review.md` §4 均记 281/0/1skip；本批净增 4（TC-UI-19 四例替代被删的 `SettingsGlobalCycleTest` 两例，TC-UI-17 改写五例） |
| 2. `assembleDebug` 通过 | **采信**（未独立复跑） | 实施报告 §3；静态核对新增 import/类型/`AppLanguage.entries` 均自洽 |
| 3. 死代码清理完成且无残留引用 | **通过** | `CycleSettingRow`/`nextLineHeight`/`nextFont`/`nextTheme`/`LocaleSwitcher.next` 全库无引用（`grep` 仅命中 `XmlPullParser.next()`）；`common_switch` 与 `ReaderViewModel.cycle*` 的保留已按简报规则处置并报告（见 §3.4） |
| 4. 五行选项对话框；语言取消不落库、确认先落库再重建 | **通过** | 见 §2.2/§2.3/§3.2/§3.3 |
| 5. 阅读器阅读设置面板不回归 | **通过** | 本批未触碰 `ReaderScreen`/`ReaderViewModel`；`OptionPickerRow` 为 `SettingsScreen.kt` 私有 |
| 6. 报告含改动清单、`cycle*` 处置、遗留观察 | **通过（有陈旧点）** | 报告齐全；§4-决策 2 与终稿不符，见 S2 |

框定对齐：需求 REV13（F4.3/F4.9 选项列表 + 确认重启）、设计 REV17 §3.7 M17 块、决策 D-2026-09-19-01/02 的契约（整行 ≥48dp、对话框标题=行标题、当前值选中、点选即生效、语言确认框文案与按钮、死代码清单、三新键）与实现逐项一致，未发现越界或遗漏的功能点。

---

## 2. 实现本身

### 2.1 `OptionPickerRow`（`SettingsScreen.kt:384-442`）

- 整行 `defaultMinSize(minHeight=48.dp).clickable { onExpandedChange(true) }`；行尾当前值文本（`clickable` 合并后代语义，可达性名 = 标题+值）。与简报 §2.1 行为契约一致。
- 对话框：`title = { Text(title) }`；正文 `Column(verticalScroll)` 列全部选项；选项行 `defaultMinSize(minHeight=48.dp).selectable(selected, onClick)`，`RadioButton(onClick=null)` 仅视觉——**选中语义落在整行合并节点**，符合简报「`assertIsSelected` 直接可用」的判别要求。
- `onClick = { onSelect(option); onExpandedChange(false) }`：点选回调后关对话框；`dismissButton`（`common_cancel`）仅 `onExpandedChange(false)`，不回调。正确。
- 相较简报建议签名增 `expanded`/`onExpandedChange`/`showValue`，报告 §4-决策 4 已披露；行为契约未变，可接受。

### 2.2 五行接线（`SettingsScreen.kt:126-241`）

选项全集与 onSelect 行为逐行核对无误：

| 行 | 选项 | onSelect |
| --- | --- | --- |
| 语言 | `AppLanguage.entries`（SYSTEM/ZH/EN） | 与当前档相同→仅关闭；不同→`pendingLanguage = selected` |
| 行距 | `ReaderViewModel.LINE_HEIGHT_STEPS`（120/150/180/200） | `save(copy(lineHeightPercent=…))` |
| 换行 | `WRAP/NO_WRAP` | `save(copy(wrapMode=…))` |
| 字体 | `CODE/SERIF/SANS` +（`customFontPath != null` 时）`CUSTOM` | `save(copy(font=…))` |
| 主题 | `null/DEFAULT/EYE_CARE/SOFT_LIGHT/NIGHT` | `save(copy(theme=…))` |

### 2.3 语言确认重启（`SettingsScreen.kt:132,244-270`）

- 选项 `onSelect` 只置 `pendingLanguage`，**不落库**；`OptionPickerRow` 随即关闭选项框，确认框出现。✓
- 确认框 `dismissButton`/`onDismissRequest` → `pendingLanguage = null`，**不落库**。✓
- 「立即重启」：先 `pendingLanguage = null`（关框），再 `scope.launch { repository.saveAppLanguage(target); if (!LocaleSwitcher.applyToFramework(context, target)) (context as? Activity)?.recreate() }`——**先落库后重建**顺序正确，且 `saveAppLanguage` 为挂起调用，写入完成才进入框架/自重建路径，`attachBaseContext` 重建时必读到新值。✓ 机制与 M16 一致（33+ 框架重建；<33 或框架不可用 `recreate()`），仅时机后移。✓

### 2.4 死代码与资源

- 已删：`CycleSettingRow`、`nextLineHeight`、`nextFont`、`nextTheme`（`SettingsScreen.kt`）、`LocaleSwitcher.next`（`LocaleSwitcher.kt`）。无残留引用。
- 保留：`ReaderViewModel.cycle*`（仅 `ReaderViewModelTest` 引用；按简报「仍有调用方则保留」）；`common_switch` 键（已无引用，简报未要求删）。均已在报告 §5 记录。
- 字符串：`common_restart_now`/`settings_language_restart_title`/`settings_language_restart_message` 中英同键同位置补齐；`grep "<string name="` 两份均为 **112** 键，`LocalizationTest` TC-UI-15 ③ 的 XML 键集对等自动覆盖。✓

---

## 3. 遗漏边界逐项

### 3.1 无障碍（≥48dp/语义）— 通过

- 行/选项行均 `defaultMinSize(minHeight=48.dp)`，且 `clickable`/`selectable` 位于尺寸修饰符之后，触摸目标 ≥48dp。
- `clickable`/`selectable` 均 `shouldMergeDescendantSemantics`，选项整行合并节点携带 `Selected` 语义；`RadioButton(onClick=null)` 不引入第二点击语义。对话框标题/正文/按钮均经 `stringResource` 文本语义外置。
- 无「额外语义键」需要（简报明确 RadioButton 自带选中语义，无需 `ReaderPanelAccentColorKey` 式接缝）。

### 3.2 取消不落库 — 通过

选项框 `common_cancel` 与确认框 `common_cancel`/`onDismissRequest` 三条路径均只改 UI 状态，无 `repository.save*`。Robolectric ④（`SettingsLanguageUiTest`，含 500ms 落盘窗口后断言 SYSTEM）与设备端 `LocaleSwitchPickerDeviceTest`（真实导航→选项框→确认框→取消）双重覆盖。

### 3.3 先落库再重建时序 — 通过

见 §2.3。补充：`pendingLanguage = null` 在协程外同步执行（先关确认框），协程内落库→重建顺序不受 UI 重组影响；重建触发后 `rememberCoroutineScope` 随 composition 取消，但落库已 `suspend` 完成，无丢失窗口。

### 3.4 死代码清理完整性 — 通过（两处有意保留，已记录）

- 简报 §2.4 要求删除项全部删除、无残留引用（编译即证）。
- 规则外保留项（`common_switch`、`ReaderViewModel.cycle*`）均有报告注明，符合简报「有调用方则保留并注明」与「简报未授权不扩面」的口径。`common_switch` 属未用资源键（非简报清单），未清理不构成缺陷（见 S3）。

### 3.5 字符串键中英对等 — 通过

3 键中英同键同值语义；键总数 112=112；`LocalizationTest` 键集反射对等 + 非空值自动覆盖。设备端断言已 `getString` 化（`LocaleSwitchPickerDeviceTest`），不受设备 locale 影响。

### 3.6 DataStore 按 `applicationContext` 缓存 — 正确性通过，存在两处轻微风险（见 S1）

`SettingsRepository.kt:25-34`：

- **正确性**：`getOrPut(applicationContext)` 以应用上下文为键，生产端 Application 唯一 → 进程内每应用一份，与旧 `preferencesDataStore` 单例语义等价；`LocaleSwitcher.wrapBaseContext(newBase)` 与各 Screen 的 Activity 上下文因共享 `applicationContext` 而命中同一实例，避免「同一文件多实例」异常——这正是测试 ② 回读与 `LocaleSwitchTest` ⑥ Activity 装箱能读到同一 DataStore 的前提，选型正确。`synchronized` 保证 `getOrPut` 原子，键为恒等比较的 `Context`，无并发/相等性风险。
- **风险 1（引用捕获）**：`produceFile = { applicationContext.preferencesDataStoreFile("settings") }` 在 lambda 内引用的是**外层接收者**的 `applicationContext` 属性，故闭包捕获的是调用时传入的 Context（`SettingsScreen` 的 `LocalContext.current` 在生产为 Activity），而非 `applicationContext` 本身。旧 `preferencesDataStore` 委托是先取局部 `applicationContext` 再建 lambda，故本改动相对旧实现**新增了对首个 Activity 的强引用**（值被静态 map 持有、键为进程级 Application，条目不会淘汰）。本应用单 Activity，影响仅为「首个 Activity 在进程存活期不被回收」的轻微泄漏，不影响功能。
- **风险 2（weak 语义失效）**：`WeakHashMap` 的弱键意图被上述闭包捕获抵消（值强引用键），实际退化为按 Context 的强引用缓存。生产端有界（1 条），测试端每个测试方法的新 Application 会各留一条，属测试内存累积，不构成验收阻塞；但注释「按 applicationContext 缓存」的弱引用表述与实际行为不完全相符。
- 该改动超出简报清单，Kimi 审查已接受并核对 `WeakHashMap` 加锁。结论：**生产语义等价、测试隔离目标达成，建议按 S1 收口**，不建议返工阻断。

---

## 4. 发现分级

### 必修项

无。

### 建议项

**S1（高优先，一行修复）`SettingsRepository.settingsDataStore()` 避免捕获非 application Context**
`SettingsRepository.kt:29-32` 建议先取局部：`val app = applicationContext; … produceFile = { app.preferencesDataStoreFile("settings") }`，使闭包只持有 Application（与旧委托行为一致），消除对首个 Activity 的进程级强引用，并让 `WeakHashMap` 的弱键语义真正成立。不改变任何行为契约与测试。

**S2（文档一致性）OpenCode 实施报告与终稿不符**
`m17-opencode-report.md` §1「测试」称「红测试零改动」，但 `db99531` 实际改动了 `SettingsOptionPickerTest.kt`/`SettingsLanguageUiTest.kt`（Kimi 亲改，已在 `m17-review.md` §3 披露）；§4-决策 2「语言行在确认框阶段显示待确认档位」亦与终稿（`SettingsScreen.kt:128` 仅显示已落库档位，Kimi 已移除该取舍）相悖。建议在报告加一行「终稿修订注记」指向 Kimi 审查 §2.2/§3，避免后续读者误判。

**S3（清理）未用资源键与测试专用生产 API**
`common_switch` 已无引用（中英同删）；`ReaderViewModel.cycle*` 仅测试调用。两者均已在报告 §5 记录，属可接受的遗留，建议后续随 `ReaderViewModelTest` 一并评估清理（用例数下降需显式接受）。

**S4（测试加固）`SettingsOptionPickerTest` 默认值断言隐含顺序依赖**
`tcUi19_lineHeightRow_opensPickerWithAllOptions` 依赖「150% 为默认」；同类的 `tcUi19_lineHeightSelectOption_persistsAndCloses` 会写 180。当前靠「按 `applicationContext` 缓存 + Robolectric 每方法重建 Application」取得隔离，即**测试正确性耦合于生产实现细节**。建议后续在该类补 `@Before` 重置（或为 picker 用例提供测试专用 DataStore），使隔离不依赖生产代码路径；`SettingsOptionPickerTest.kt:30` kdoc 仍提 `assertIsSelected`，与实际断言（`hasText and expectValue(Selected)`）不一致，可一并订正。

### 记录（不阻塞）

- 设备端 `LocaleSwitchPickerDeviceTest`（TC-UI-18 M17 增补）与真机观感（选项间距/滚动、低版本确认重启自重建）未执行，属既定真机验收范围。
- Kimi 亲改的测试断言（`hasText and expectValue`、`assertDoesNotExist("中文")`）与实现（移除待确认档显示、保留模态隔离语义）经本复核确认**自洽且与红测试判别意图一致**，无假绿迹象（模态隔离为 `assertExists` 单匹配契约下的必要实现，Kimi 已用移除实验反证）。

---

## 5. 结论

实现完整满足 M17 简报 §2 行为契约与 §4 DoD，死代码/字符串/时序/取消路径/无障碍均达标，Kimi 亲改部分经交叉核对无回归。DataStore 缓存改造功能正确，仅存 S1 的轻微引用捕获问题与 S2~S4 的文档/加固事项，均不影响验收结论。

**总体结论：可接受进入收口**（S1 建议随本批或收口时一并处置；若团队对进程级 Activity 引用零容忍，可将 S1 提升为必修后再收口）。
