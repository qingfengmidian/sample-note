# M16 审查记录 — 应用内语言切换（F4.9）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-18 |
| 范围 | M16 三提交：框定 `fe8f6ec`（设计 REV15 §3.7 / 测试用例 REV10 / 决策 D-2026-09-18-01 / 简报 `briefs/m16-language-switch.md`）、红测试 `16487d6`、实现 `39856fd` |
| 红测试 | `16487d6`——TC-UI-17 Robolectric 7 例（自称 275 completed / 6 failed / 1 skipped） |
| 实施者 | 「另一条实施路线」；三提交 author = committer = 用户 git 身份、无 trailer，**无法从仓库判断是否为实施代理代为提交**（简报硬性约束 #1「不得 git 提交」因此不可核验，非违规） |
| 审查者 | Kimi Code（独立读三提交完整 diff + 全量源码 + 独立重跑 + 4 个仓库外探针） |

## 一、被审改动摘要

- 解析/数据层：`Models.kt:89` `AppLanguage { SYSTEM, ZH, EN }`；`LocaleSwitcher`（新文件，82 行）`tagOf/languageOf/next/wrapBaseContext/内部 wrap/applyToFramework/needsManualRecreate`；`SettingsRepository` 增 `Keys.APP_LANGUAGE`（`app_language`）、`appLanguage` flow、`saveAppLanguage`（键级隔离）。
- 平台层：`MainActivity.kt:34-36` `attachBaseContext` 接线（全 API 级包装）。
- UI 层：`SettingsScreen.kt:112-130` 顶部「通用」小节 + `CycleSettingRow` 语言行（先落库 → `applyToFramework` → `<33` 自行 `recreate()`）。
- 资源：`values`/`values-zh` 各 +5 键（`strings.xml:90-94` 两文件同段）。测试：3 个新文件 +7 例；无既有源文件/测试被改（`git show 39856fd -- app/src/test app/src/androidTest` 为空；`16487d6` 未触碰任何既有测试）。

## 二、逐点核对（与设计 REV15 §3.7 / D-2026-09-18-01 / 简报契约表比对）

| 检查项 | 结论 |
| --- | --- |
| 契约签名 | ✅ 全部一致：`tagOf`（`LocaleSwitcher.kt:29-33`，SYSTEM→null）/`languageOf`（`36-40`）/`next`（`43-47`）/`wrapBaseContext`（`53-54`）/`applyToFramework`（`69-77`）/`needsManualRecreate`（`80-81`） |
| 键名与取值 | ✅ `stringPreferencesKey("app_language")`（`SettingsRepository.kt:41`）；标签 `zh`/`en`（`LocaleSwitcher.kt:25-26`）；SYSTEM 时 `p.remove(...)`（`81-86`）；`save(ReadingSettings)` 未触碰该键（`63-75`，键级隔离成立） |
| 归一化 | ✅ 缺失/未知标签 → `SYSTEM`（`39`）；冒烟实测 `languageOf("de")==SYSTEM`（既有断言）；未覆盖语言不误切英文（跟随系统 = 不包装，交给系统 locale + 资源回退链） |
| 重建策略 | ✅ 与决策一致：`needsManualRecreate = SDK_INT < 33`；`33+` 不手动重建（无重复重建窗口）；写库严格先于切换（`SettingsScreen.kt:123-128` 顺序 + 探针 C 实测落库先完成） |
| 配置常量 | ✅ Manifest `configChanges` 不含 `locale` / `layoutDirection`（`AndroidManifest.xml:19`），33+ 框架可正常触发重建；未声明 `android:localeConfig`、未引 AppCompat/新依赖、未联网 |
| 资源键集对等 | ✅ 两文件同段同 5 键（`values/strings.xml:90-94` = `values-zh/strings.xml:90-94`）；`LocalizationTest` 5 例全绿（XML 直解析口径） |
| 注释/文档一致性 | ⚠️ 一处不实：`LocaleSwitcher.kt:56`「供测试直驱」——该 `internal wrap` 无任何测试调用（见建议 4）；文档侧 TC-UI-17 ⑤ 的前提陈述有误（见必修 1） |
| 风格/死代码 | ✅ 官方风格、无未用 import、红态注释（`红态（M16）：待接线`）已全部清除（`git grep 红态 app/src` = 空）；唯一死代码 = 建议 4 |
| 既有测试弱化 | ✅ 无（见 §一末行）；设置页新增行把下方各行下移，既有用例用 cd + `performScrollTo`/`assertExists`，未受影响（全量绿） |

## 三、独立复验（本人实测，不采信报告）

```bash
./gradlew testDebugUnitTest --rerun     # 强制重跑（非 --rerun-tasks，本次仅 target task 强制）
```

- **BUILD SUCCESSFUL**；从 `app/build/test-results/testDebugUnitTest/*.xml` 自行汇总：**275 tests / 0 failures / 0 errors / 1 skipped**，产物时间戳 `2026-09-18 08:56`（本机本轮新产出）。基线 268 + 新增 7 = 275，与提交说明一致；跳过例 = 既有 F4.8 进度条 `@Ignore`。
- **红态**：未做 worktree 检出复跑（避免污染仓库），改用两条独立证据——① 实现方留存 `build/probe/red.log:97-115`（`275 tests completed, 6 failed, 1 skipped`，恰为 `LocaleSwitchTest` 4 例 + `SettingsLanguageUiTest` 2 例）；② 我按 `16487d6` 的退化实现逐例静态复核：`tagOf` 恒 null、`languageOf` 恒 SYSTEM、`next` 恒 SYSTEM、`wrapBaseContext` 恒返回 base ⇒ ①②③④ 必红；设置页未加语言行 ⇒ 两例 UI 必红；⑤ 因守卫跳过断言 ⇒ 绿。**与「6 红 1 绿」吻合**。
- **设备端未复现**：本机 `adb devices` 为空；TC-UI-18 与真机手工走查（语言行即时随动、杀进程重开保持、`cmd locale get-app-locales` 为空）均**未由我复现**。实现方 `build/probe/device.log` 记有 `Starting 2 tests on PHM110 - 13 / Finished 2 tests`（PHM110 = OPPO Reno9 5G，即 AGENTS 记载真机）——属其自证，我未能独立验证。
- **仓库外探针（4 个）**：置于 `/tmp/m16probe`，经临时 `-I /tmp/m16probe.init.gradle`（`android.sourceSets.test.java.srcDir`）挂入 test 源集运行，**未新增/修改仓库内任何文件**（`git status` 复查干净）。用途与结果：

| 探针 | 场景 | 实测结果 |
| --- | --- | --- |
| A | SDK 33：`getSystemService(LocaleManager::class.java)` + `applyToFramework(ZH)` | 服务**存在**（`android.app.LocaleManager`）；前=`''`、后=`'zh'`、重新取服务仍 `'zh'` ⇒ Robolectric 4.13 自带 `ShadowLocaleManager`（minSdk 33） |
| B | SDK 32：写新值 + `ActivityController.recreate()` | `needsManualRecreate=true`；重建前后 `name=Sample Note/locale=en` → `name=简记/locale=zh`，`sameInstance=false` ⇒ `attachBaseContext` 在重建时重跑并读到新值 |
| C | SDK 32 + 英文限定符：真实 UI 路径（首页→设置→点语言行） | `persisted=ZH`；重建后 `activityAppName=简记`、`activityLocale=zh`、实例 identityHashCode 变化（185324586→371815021）、英文节点数 0 ⇒ `<33` 的 `(context as? Activity)?.recreate()` **确实被触发且生效**（`LocalContext.current` 即 Activity） |
| D1/D2 | DataStore 文件损坏（写入非法 proto） | `wrapBaseContext` **抛出** `androidx.datastore.core.CorruptionException`；既有 `settings` flow 收集（M16 前即存在）抛出同异常 ⇒ 异常路径属既有风险类别，M16 只是把一次读提前到 `attachBaseContext` |

## 四、必修项

1. **TC-UI-17 ⑤ 是「假绿」：断言被自身守卫吞掉，且提交说明的归因与实测相反** — `app/src/test/java/com/sample/docreader/LocaleSwitchTest.kt:91-102`
   - 代码：`val locales = runCatching {...}.getOrNull()` 后 `if (locales != null && !locales.isEmpty) { assertEquals("zh", locales[0].language) }`——守卫条件正是被测对象（框架态是否被写入），因此该例在红态（`applyToFramework` 空实现 → locales 为空）必然跳过断言并通过。
   - 实证：探针 A 表明 Robolectric 4.13 **提供** LocaleManager 服务，`16487d6` 提交说明「Robolectric 无 LocaleManager 服务，按设计跳过框架态断言」与实测不符；文档 `docs/测试用例.md:211`（TC-UI-17 ⑤「框架服务可用时断言 `applicationLocales`」）与该守卫同源，应一并改为无条件断言。
   - 影响：框架路径在 JVM 层**没有可失败的红→绿证据**（只有真机 TC-UI-18，其 `LocaleSwitchDeviceTest.kt:52-62` 断言无条件、无此问题）；若日后实现回退为空操作，该例仍绿。
   - 最小修复：去掉守卫，改为无条件 `assertEquals("zh", context.getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)?.language)`（红态即失败，绿态通过——已由探针 A 证可行性）；同步修正 `docs/测试用例.md:211` ⑤ 与提交说明措辞。属测试 + 文档返工，**未自行修改**。

## 五、建议项（不阻塞合入，建议随 M16 收口或后续版本处置）

1. **`<33` 重建路径无任何固化测试** — `SettingsScreen.kt:127`（`if (needsManualRecreate) recreate()`）、`LocaleSwitcher.kt:80-81`。`LocaleSwitchTest` 无 sdk 26~32 例，`SettingsLanguageUiTest` 跑默认 SDK（34，走框架分支），设备端为 Android 13（33），故 `recreate` 分支零覆盖。功能我以探针 B/C 实测有效，但无回归网。建议补一例 `@Config(sdk=[32])`（参考探针 B：`Robolectric.buildActivity(MainActivity::class.java)` + 写值 + `recreate()` 后断 `app_name`/`locales`）。
2. **33+ 框架调用失败时无兜底重建** — `LocaleSwitcher.kt:69-77` + `SettingsScreen.kt:126-127`。`applyToFramework` 静默 `runCatching` 且 `needsManualRecreate=false`，框架不可用/被拒的设备上：DataStore 已更新、但当前 Activity 不重建也不换语言（直到下次 Activity 创建），与 F4.9「即时生效」有落差；D-2026-09-18-01「框架调用失败静默回退——包装路径已独立保证行为」表述不精确（包装路径只保证**下次** Activity 创建）。建议：`applyToFramework` 返回是否成功（或回调失败），失败时回退 `recreate()`。
3. **`wrapBaseContext` 读 DataStore 无异常保护** — `LocaleSwitcher.kt:53-54`。探针 D1 实测：`preferences_pb` 损坏时 `CorruptionException` 直接从 `attachBaseContext` 抛出 ⇒ Activity 无法 attach、应用启动即崩（且早于任何 UI，无法降级提示）；D2 显示同类异常在 M16 前也会由 `settings` flow 抛出（既有风险类别，非 M16 引入），但 M16 把该读放在**每次 Activity 创建**的必经路径上。建议 `runCatching{...}.getOrNull()` → 失败回退「跟随系统」（一行成本，可避免启动期崩溃面扩大）。
4. **死代码 + 注释不实** — `LocaleSwitcher.kt:56-63`。`internal fun wrap(base, tag)` 注释称「供测试直驱」，但全仓 grep 仅 `wrapBaseContext` 调用它，无任何测试直驱（`LocaleSwitchTest` 一律走公开 `wrapBaseContext`）；且 REV15 §3.7 未记录该内部 API。建议删除改为 `private`（或让测试真正直驱它）。
5. **非局部返回的语义陷阱** — `LocaleSwitcher.kt:72` `context.getSystemService(...) ?: return`。`runCatching` 是 inline，此处 `return` 返回的是 `applyToFramework` 而非闭包；当前与 `return@runCatching` 等价、无 bug，但后续若在 `runCatching` 之后追加语句即被静默跳过。建议改 `return@runCatching`。
6. **档位陈旧捕获（低）** — `SettingsScreen.kt:122` `LocaleSwitcher.next(language)` 用组合期捕获的 `language`；极快连点两次（首帧 recompose/flow 未回）会两次算出同一目标，第二次为空操作。与既有 `theme`/`font` 行同款写法，属既有模式，仅记录。
7. **流程收尾** — `docs/开发计划.md:40/168`（M16 行无「完成」注记）、`docs/collab/reviews/` 无 `m16-acceptance.md`、`docs/collab/pending-real-device-tests.md` 未登记 M16 真机项。建议补：真机走查应含**33+ 系统侧「应用语言」入口是否出现本应用**（若出现则 DataStore 与框架态双源风险未闭环，对应该文件 D13 风格的目测条目）与「语言行点击/持久化/杀进程重开」三项。
8. **`Locale.getDefault()` 不受应用内切换影响（记录备查）** — `ui/HomeScreen.kt:306` 最近文件时间戳用 `Locale.getDefault()`，仍取系统 locale。当前格式 `MM-dd HH:mm` 纯数字，中英无差异，本期无可见影响。

## 六、已确认无问题项

- **契约逐条一致**：签名/键名/默认值/归一化/重建策略与 REV15 §3.7、D-2026-09-18-01、简报契约表逐行对齐，无未记录的行为偏差（除建议 4 的内部 API）。
- **不缓存持久值的取舍合理**：`wrapBaseContext` 每次 Activity 创建同步读一次 DataStore；本应用单进程、唯一写入方是 `saveAppLanguage`，缓存需处理失效反而增加两态不一致风险，省下的只是一次小文件读——保持现状。
- **`runBlocking` 无死锁/无 Application 缺失**：DataStore 的读写跑在其自有 IO scope（不依赖主线程）；`SettingsRepository(base)` 经 `applicationContext` 拿单例；探针 B/C（SDK 32）在真实 Activity attach 流程中实测通过（33+ 仅实现方设备日志，我未复现）。ANR 量级可忽略（小 prefs 文件解析）。
- **写库先于切换、33+ 不重复重建**：`SettingsScreen.kt:123-128` 顺序正确；探针 C 在 `<33` 下观测到「落库完成 → 重建 → 生效」；33+ 因 `needsManualRecreate=false` 不会与框架重建叠加。
- **`<33` 重建路径功能有效**（我实测，探针 B/C）：`Activity.recreate()` 后新实例 `attachBaseContext` 重跑并读到新值，界面整体由英文切到中文，`LocalContext.current` 是 Activity（cast 成功）。
- **设置页交互未破坏既有语义**：`CycleSettingRow`（`SettingsScreen.kt:322-338`）本身零改动；语言行 cd = 行文本「语言」与既有约定同名、`defaultMinSize(48.dp)` 双维达标；`onNodeWithContentDescription("语言")` 唯一可命中。
- **`SettingsLanguageUiTest` 无文本歧义**：zh 下「语言」「通用」「中文」各仅 1 处（`values-zh/strings.xml:90/91/93`）；已避开与阅读主题档位同字面的「跟随系统」（该字样在 zh 出现 2 次：`reader_theme_system`/`settings_language_system`）——用例注释与实现相符。
- **资源键集对等**：两文件 5 键同段同值（`中文`/`English` 双语一致），`LocalizationTest` 5 例绿。
- **既有测试零弱化**：实现提交不含 test/androidTest 改动；红提交亦未改既有测试；全量 275 例通过（我实测）。

## 七、遗留与下一步

- 必修 1 需返工测试与文档（测试文件由实现方在红提交自带并被简报冻结，**本次审查未改动任何源码/测试**）。
- 设备端与真机项（TC-UI-18 2 例、语言行即时随动/持久化/系统侧记录）**未由我复现**，需用户或实现方在有设备时确认；建议一并执行建议 7 的「系统侧应用语言入口」目测。
- 验收通过后：补 `m16-acceptance.md`、更新 `docs/开发计划.md` M16 行与 AGENTS.md 进度段、按惯例打 `m16` tag。
- 本次审查仅提交本文件（`docs: M16 审查记录`），**未 push、未改动任何源码或测试**。
