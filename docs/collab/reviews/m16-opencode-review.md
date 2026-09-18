# M16 OpenCode 只读复核报告 — 应用内语言切换（F4.9）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-18 |
| 复核者 | OpenCode（只读，§8.5 交叉复核） |
| 复核对象 | 任务框定（`fe8f6ec` / 设计 REV15、需求 REV12、用例 REV10）、红测试 `16487d6`、实现 `39856fd`、审查返工 `5c6178c`，以及当前 main `253416d` 工作树 |
| 依据 | `AGENTS.md` §5/§6、`docs/协同开发规范.md` §8.5、需求 F4.9、设计 §3.7（REV15/REV16）、用例 TC-UI-17/18（REV10/REV11）、决策 D-2026-09-18-01（含补记）/02/03、简报 `briefs/m16-language-switch.md`（含回写段） |
| 前轮材料 | `m16-review.md`（Kimi 侧）与 `m16-review-response.md`：**仅作对照，本报告结论独立得出**，未照抄 |

## 〇、结论速览

**可接受进入收口；无必修项。** 任务框定、验收标准、实现、既有测试均无越界或缩水；独立实测单测 **276 tests / 0 failed / 0 errors / 1 skipped**（与实现方及验收记录一致）。发现 1 处应随收口顺手修的**注释陈旧**与若干测试/边界建议（见 §三、§四）。设备端与真机项本轮无法复现（见 §五）。

---

## 一、改动核对清单（对照任务框定/契约/验收标准）

### 1.1 范围与交付物

| 契约/范围项 | 期望（简报/设计/决策） | 实现落点 | 判定 |
| --- | --- | --- | --- |
| `AppLanguage { SYSTEM, ZH, EN }` | `core/model/Models.kt`，与既有枚举同处 | `Models.kt:89`（`WrapMode`/`ReaderFont` 旁，含 F4.9 中文注释） | ✅ |
| DataStore 键 `app_language`（BCP-47 `zh`/`en`） | 缺失/未知 = SYSTEM | `SettingsRepository.kt:41`（`stringPreferencesKey("app_language")`） | ✅ |
| `appLanguage: Flow<AppLanguage>` | 经 `LocaleSwitcher.languageOf` 归一化、未知不抛异常 | `SettingsRepository.kt:59-61` | ✅ |
| `saveAppLanguage`；SYSTEM 移除键；`save(ReadingSettings)` 不触碰 | 键级隔离 | `SettingsRepository.kt:81-86`；`save` 仅写 6 个阅读键（`63-75`） | ✅ |
| `tagOf` / `languageOf` / `next` | SYSTEM→null；未知→SYSTEM；SYSTEM→ZH→EN→SYSTEM | `LocaleSwitcher.kt:29-47` | ✅ |
| `wrapBaseContext` | 读持久值包装；跟随系统原样返回 | `LocaleSwitcher.kt:55-60` | ✅ |
| `applyToFramework` | 33+ 同步框架态；返回「是否交框架」；失败回退 | `LocaleSwitcher.kt:76-85` | ✅（REV16 契约） |
| `MainActivity.attachBaseContext` 接线 | 全 API 级包装 | `MainActivity.kt:34-36` | ✅ |
| 设置页「通用」小节 + 语言行（复用 `CycleSettingRow`） | 顶部；值=档位标签；点击→下一档；先落库再切换 | `SettingsScreen.kt:112-132`；`CycleSettingRow` `324-339`（未改） | ✅ |
| 5 个字符串键，键集严格对等 | zh 值逐字沿用 | `values/strings.xml:90-94`、`values-zh/strings.xml:90-94` | ✅ |
| 范围收口：不声明 `localeConfig`、不引 AppCompat/新依赖、资源对零改动 | — | `AndroidManifest.xml:5-11` 无 `localeConfig`；`git show 39856fd --stat` 仅 3 个 main 文件；资源仅新增 5 键 | ✅ |
| 测试：TC-UI-17（Robolectric）+ TC-UI-18（设备端） | 红测试先行 | `LocaleSwitchTest.kt`（6 例）、`SettingsLanguageUiTest.kt`（2 例）、`LocaleSwitchDeviceTest.kt`（2 例） | ✅ |

### 1.2 任务外改动核查

- `39856fd`：仅 `MainActivity.kt` / `LocaleSwitcher.kt` / `SettingsScreen.kt`（`--stat`），无越界。
- `16487d6`：仅 `Models.kt` / `LocaleSwitcher.kt` / `SettingsRepository.kt` / 两 `strings.xml` / 3 个新测试文件，**未触碰任何既有测试**（符合简报硬性约束 #2）。
- `5c6178c`：仅 `LocaleSwitcher.kt` / `SettingsScreen.kt` / `LocaleSwitchTest.kt`，返工范围与审查处置一致。
- `git diff 5c6178c HEAD -- app/src` 为空 ⇒ 当前 main 的源码/测试 = 返工后状态，文档提交（`2ac6ecc`/`4e64944`/`253416d`）未夹带代码改动。

---

## 二、验证命令与结果（本人实测）

| 命令 | 结果 |
| --- | --- |
| `git status` | 工作树**非全净**：仅 `docs/collab/briefs/m16-opencode-review.md`（即本复核任务简报自身）被修改，内容为追加「执行方式约束」第 5 条；**无任何 `app/src`、`buildSrc`、`AGENTS.md` 等实现文件脏改动** |
| `git log --oneline -15` | HEAD = `253416d`；M16 链完整：`fe8f6ec`→`16487d6`→`39856fd`→`2ac6ecc`→`5c6178c`→`4e64944`→`253416d` |
| `./gradlew testDebugUnitTest`（首次） | BUILD SUCCESSFUL，但 `:app:testDebugUnitTest UP-TO-DATE`（未真正执行，**不作数**） |
| `./gradlew testDebugUnitTest --rerun` | **BUILD SUCCESSFUL**；`:app:testDebugUnitTest` 真正执行（`2 executed`）。逐份汇总 `app/build/test-results/testDebugUnitTest/*.xml`（时间戳 `2026-09-18T14:01 UTC` / 本机 22:01，本轮新产出） |
| 逐类计数 | **合计 276 tests / 0 failures / 0 errors / 1 skipped**；其中新增类 `LocaleSwitchTest` tests=6、`SettingsLanguageUiTest` tests=2、`LocalizationTest` tests=5（键集对等回归绿）；唯一 skip = 既有 `ReaderWindowTest`（F4.8 进度条 `@Ignore`） |

计数与 `m16-acceptance.md`「276/0/1skip」及返工提交说明一致；**本数字为本人独立重跑所得，未引用任何转述**。

---

## 三、复核发现

### 3.1 必修项

**无。** 未发现功能性缺陷、契约违背、越界或缩水。

### 3.2 建议项

1. **注释陈旧，引用了已删除的 API（低，建议随收口顺手修）** — `app/src/main/java/com/sample/docreader/MainActivity.kt:32`
   - 原文：「API 33+ 的框架态同步与重建见 `LocaleSwitcher.applyToFramework / needsManualRecreate`」。
   - 返工 `5c6178c` 已删除 `needsManualRecreate`（全仓仅此注释仍引用，`grep` 证据），`LocaleSwitcher` 头注释与 `m16-review-response.md` 建议 4 声称「注释修正」时**漏改此处**。
   - 行为无影响，但违反 `AGENTS.md` §5「注释与行为一致」，且误导后续读者。建议改为「见 `LocaleSwitcher.applyToFramework`（返回 false 时由 UI `recreate()`）」。

2. **TC-UI-17 编号在文档与测试注释间漂移（低）** — `docs/测试用例.md:211` vs `LocaleSwitchTest.kt`
   - 文档把「档位映射」与「循环」并为 ②，随后 ③=持久化、④=sdk33、⑤=sdk32、⑥=设置页行；
   - 测试文件却拆为 ②映射、③循环，导致 ④=持久化、⑤=sdk33、⑥=sdk32，设置页行在另一个类且无编号。
   - 结果：文档 ②~⑥ 与测试注释 ②~⑥ 逐位错开一位。功能无碍，但 `AGENTS.md` §6 要求测试引用 `TC-*` 编号可追溯，建议对齐（改文档或改注释其一）。

3. **`<33`/框架不可用的「UI 自行 recreate」分支无直接回归网（中）** — `SettingsScreen.kt:127-129`
   - 测试 ⑥（`LocaleSwitchTest.kt:127-143`）直接调 `controller.recreate()`，固化了「`applyToFramework` 返回 false」与「重建后 `attachBaseContext` 重新包装」两个机制，**但未驱动 `SettingsScreen` 的 `if (!applyToFramework(...)) recreate()` 决策本身**。
   - `SettingsLanguageUiTest` 跑默认 SDK 34（框架分支），设备端真机为 Android 13（33），故 `<33` 的 UI 自动重建在自动化里零覆盖（仅靠审查探针 B/C 与静态推演）。返工说明「固化 `<33` 自重建路径」措辞略强于实际。建议后续以 `@Config(sdk=[32])` 渲染 `SettingsScreen` 并断言重建被触发（或接受该缺口并记录）。

4. **设备端用例的判别力依赖系统语言非英文（中）** — `LocaleSwitchDeviceTest.kt:40-49`
   - `tcUi18_launchActivity_appliesPersistedLanguage` 断言 `app_name == "Sample Note"` 且 `locales[0].language == "en"`。若真机/CI 模拟器系统语言本就是英文，则包装路径即使失效也会通过（假绿）。
   - 当前 PHM110 为中文系统故有效；但 CI `instrumented-test` 为英文模拟器（`AGENTS.md` §4），该例在 CI 上无判别力。建议断言「活动 locale 被覆盖为与系统不同的语言」（如先在测试内断言基础 `app_name == 简记` 再断 `en`），或显式 `setLocale` 造中文环境。

5. **设备端用例缺 `@Before` 前置清理（低）** — `LocaleSwitchDeviceTest.kt`
   - 仅有 `@After restoreFollowSystem`，未在每例前重置。JUnit 不保证顺序，两例间依赖前一例的 `@After` 收尾；任一例中途失败时 `@After` 仍会跑，故当前无实害，但建议补 `@Before` 与 `@After` 同构，保证顺序无关。

6. **键级隔离缺显式回归（低）** — `SettingsRepositoryTest.kt`
   - 契约声明 `save(ReadingSettings)` 不触碰 `app_language`，实现确实如此（`SettingsRepository.kt:63-75` 未含该键），但无测试固化。建议补一例：写 `appLanguage=ZH` → `save(ReadingSettings(...))` → 仍为 `ZH`。

7. **`SettingsScreen.kt:128` 内联全限定名（风格，极低）** — `(context as? android.app.Activity)?.recreate()` 未 import `android.app.Activity`；同文件其余类型均走 import。可读性小瑕疵，非缺陷。

### 3.3 已确认无问题项（关键点，均经静态核对）

- **包装语义正确**：`wrap` 用 `Configuration(base.resources.configuration)` 拷贝后 `setLocale`（API 24+ 内部同步 `locales`，minSdk 26 安全），`createConfigurationContext` 生成新上下文；跟随系统 `tag==null` 原样返回。测试 ① 以「英文基线 → zh 包装取『简记』→ en 包装取『Sample Note』」双向断言，非复述实现。
- **档位归一化正确**：`languageOf` 仅 `zh`/`en` 精确匹配，其余（含 `de`、`null`）→ SYSTEM；未覆盖语言回退默认英文由资源回退链保证（不误切英文）。
- **写库先于切换正确**：`SettingsScreen.kt:126-129` 顺序为 `saveAppLanguage` → `applyToFramework` → 失败才 `recreate`；`recreate` 在新实例重跑 `attachBaseContext` 读到新值（测试 ⑥ 断言 en→zh 双向）。
- **重建策略与决策一致**：33+ 框架成功返回 true、不手动重建（无重复重建）；<33 或框架服务缺失/被拒返回 false → UI 重建。`runCatching` 兜底使 `setApplicationLocales` 异常不崩溃。
- **`LocaleList` 空表语义正确**：SYSTEM → `LocaleList.getEmptyLocaleList()`，测试 ⑤ 断言回写后框架态为空。
- **启动期损坏兜底**：`wrapBaseContext` `runCatching { runBlocking { ... } }.getOrDefault(SYSTEM)`，DataStore `CorruptionException` 不再从 `attachBaseContext` 抛出（对应审查建议 3）。
- **`return@runCatching false`** 已取代非局部返回（建议 5），后续追加语句不会被静默跳过。
- **无障碍/布局**：`CycleSettingRow` 可点击目标 `defaultMinSize(48.dp×48.dp)` 双维达标，`contentDescription = title`（与既有行约定同名「语言」），语言行语义键唯一可命中；`SettingsLanguageUiTest` 用 cd 而非文本规避了 zh 下「跟随系统」与阅读主题标签同字面的歧义。
- **资源键集对等**：两文件同段同 5 键，`中文`/`English` 双语逐字一致；`LocalizationTest` 5 例绿。
- **无死代码/未用 import**：`internal wrap` 现被用例 ① 真正直驱（`LocaleSwitchTest.kt:59-60`），注释「随 TC-UI-17 直驱」属实；`needsManualRecreate` 已彻底删除；红态占位注释仅存于测试解释性注释（`LocaleSwitchTest.kt:106`），非生产代码。
- **既有测试零弱化**：三提交均未改既有测试（`git show --stat` + `39856fd -- app/src/test app/src/androidTest` 为空）；全量 276 例通过（本人实测）。

### 3.4 红态证据的独立评估

- 返工前红态（6 红 1 绿）与「⑤ 守卫式断言在红态跳过」的审查结论，可由代码静态推演复现（空壳 `tagOf→null`/`languageOf→SYSTEM`/`next→SYSTEM`/`wrap→base` 下，①~④ 必红；设置页无语言行则两 UI 例必红；原 ⑤ 守卫跳过 ⇒ 绿）。
- 返工后「空壳 6/6 全红」为**实现方自证**，我未复现（不得改代码）；静态推演成立：新 ⑤ 无条件断 `applyToFramework` 返回 true、新 ⑥ 断包装后取英文，空壳下均必失败。
- 需注意：返工的新断言与新实现同在 `5c6178c` 一个提交内，⑤/⑥ 的「自然红→绿」未在提交历史中单独留下，红态依赖空壳反证；这属于过程留痕强度问题，非代码缺陷。

---

## 四、遗漏边界排查（我另行想到的、现有测试未覆盖的点）

| 边界 | 现状 | 评估 |
| --- | --- | --- |
| 进程首启、无键 | 默认 SYSTEM，测试 ①/④ 隐含覆盖 | ✅ 可接受 |
| Activity 旋转/多窗口 | Manifest `configChanges` 不含 `locale`；旋转不重建，locale 不变；多窗口未测 | 低风险，机制上无问题 |
| 深/浅色 | 与 locale 正交，`AppTheme` 独立 | ✅ 无关 |
| DataStore 损坏后的设置页 | `wrapBaseContext` 只保护启动；`SettingsScreen`/`HomeScreen` 收集 `dataStore.data` 仍会抛 `CorruptionException` | **既有风险类别**（M16 前即存在），M16 未扩大；记录即可 |
| 33+ OEM 静默忽略 `setApplicationLocales` | `applyToFramework` 返回 true 但无法同步校验框架是否真的应用；此时不 `recreate`，即时生效窗口仍在 | **剩余风险**，设计已接受（见 §六）；可在后续用 `getApplicationLocales()` 异步复核 |
| `languageOf` 对 `zh-Hans`/`en-US` 等变体 | 仅精确匹配 `zh`/`en`，变体会归为 SYSTEM | 唯一写入方是 `saveAppLanguage`，不会产生变体；仅记录 |
| `saveAppLanguage` 写失败（磁盘满） | `scope.launch` 内未捕获，会向上抛；与既有 `theme`/`font` 行同款 | 既有模式，非 M16 引入 |
| 无障碍朗读语言行 | cd=「语言」，值另为文本节点；TalkBack 可读 | ✅ 达标；TTS 发音语言由系统决定，非应用可控 |
| 未覆盖语言（如法语）系统 | 跟随系统不包装 → 系统 locale → 资源回退英文 | ✅ 符合 F4.9 |

---

## 五、未验证项

1. **`connectedDebugAndroidTest` / 真机 TC-UI-18 与 D14 走查**：本机无设备、无 `adb`，未复现；仅能确认测试源码的断言口径（并已在 §3.2 第 4/5 条指出其判别力与清理问题）。验收记录称返工后真机复跑因无线调试掉线未执行，**该缺口真实存在**。
2. **`./gradlew compileDebugAndroidTestKotlin`**：不在预批命令范围，未执行；设备端测试能否编译本轮未独立确认（返工未改设备端文件，风险低）。
3. **空壳反证 6/6 全红**：未复现（不得改代码），仅静态推演支持。
4. **`wrapBaseContext` 每次 Activity 创建的同步 `runBlocking` 实测耗时/StrictMode**：未做性能测量；设计已接受该取舍。

---

## 六、剩余风险

1. **33+ 框架路径的「即时生效」在 OEM 静默忽略时仍有窗口**（§4 表）——设计层面接受，建议后续版本用框架态回读补偿。
2. **`attachBaseContext` 内 `runBlocking` 读 DataStore**：冷启动主线程同步 I/O，小文件量级可忽略，但属性能敏感点；若未来 DataStore 膨胀或迁移，需重新评估（设计已注明「不缓存以免脏读」，取舍合理）。
3. **数据文件损坏时仅启动路径被兜底**，设置页收集仍可能崩——既有风险，非本批引入，但 M16 使该读进入每次 Activity 创建必经路径，暴露面略有扩大（已被 `runCatching` 限制在启动段）。
4. **设备端 CI 判别力不足**（英文模拟器下 TC-UI-18 包装例可能假绿），建议按 §3.2-4 加固。

---

## 七、总体结论

**M16 实现可接受进入收口。** 无必修项：任务框定/验收标准/实现三方自洽，契约逐条落地，范围未越界亦未缩水，独立重跑单测 276/0/1skip 全绿。建议在收口时顺手处置 §3.2 第 1 条（MainActivity 陈旧注释，成本一行）与第 2 条（TC-UI-17 编号对齐）；第 3/4/5/6 条列入 M16 遗留清单或后续版本。真机复跑（TC-UI-18、D14、中英 P0 回归）仍是收口前的必要人工环节，不因本复核而豁免。

> 本报告仅新增本文件，未修改任何 `app/src`、`buildSrc`、`AGENTS.md` 或其他文档；未执行 `git add/commit/push`。
