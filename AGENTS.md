# AGENTS.md — 简易文档阅读/编辑器（docreader）

> 本文件面向 AI 编码代理，假定读者对本项目一无所知。项目文档与代码注释均使用中文。

## 1. 项目概述

一款运行于 Android 设备上的**轻量级本地文档阅读/编辑器**（应用名：中文「简记」/ 英文 Sample Note，2026-09-16 用户命名；applicationId：`com.sample.docreader`，根工程名 `docreader`）。

- 阅读：epub（纯文本提取，兼容 epub2/NCX 与 epub3/nav.xhtml）、xml、md、txt；txt 自动探测编码（UTF-8 / UTF-16LE/BE / GBK，Latin-1 兜底）。
- 编辑：md / txt 纯文本（记事本式，非所见即所得）。
- 特性：自动换行 / 不换行（横向滚动）双排版、字号与行距可调、阅读主题（默认白/护眼绿/柔光米黄/夜间黑）、字体方案（代码风格/衬线/无衬线/自定义导入 ttf/otf）、长按选择复制。
- 约束：纯本地应用，**不联网、无账号、不采集数据**；遵循 scoped storage，优先 SAF，不申请 `MANAGE_EXTERNAL_STORAGE`。

权威文档在 `docs/` 下，需求/设计/测试的**单一事实来源**：

| 文件                       | 内容                               |
| ------------------------ | -------------------------------- |
| `docs/需求说明书.md`          | 功能需求 F1\~F4（编号如 F2.3）、非功能需求、验收标准 |
| `docs/软件设计文档.md`         | 技术选型、分层架构、模块设计（接口签名以此为准）         |
| `docs/测试用例.md`           | 用例编号 `TC-*`（§0 为 fixtures 清单）    |
| `docs/开发计划.md`           | 里程碑 M0\~M9 + S1 spike、DoD、协作规范   |
| `docs/review_round_*.md` | 三轮评审记录                           |

需求（F 编号）、用例（TC 编号）在代码注释中被大量交叉引用，**修改行为时先查对应编号，不要凭空发挥**。

## 2. 技术栈与架构

- 语言 **Kotlin 2.0.21**，AGP **8.6.1**，JDK/JVM target **17**，minSdk **26** / targetSdk **34** / compileSdk **35**。
- UI：**Jetpack Compose + Material 3**（Compose BOM 2024.12.01），单 Activity + Navigation Compose。
- 架构：**MVVM**（ViewModel + StateFlow）；**不引入 DI 框架**，手写简单工厂/单例。
- 存储：**DataStore Preferences**（全局设置 `SettingsRepository`）+ **Room 2.6.1**（最近文件、阅读进度；Room 在 M4 才首次集成）。
- 异步：Kotlin Coroutines（`Dispatchers.IO`）。
- **零第三方解析/编码依赖**：epub 解析自实现（`java.util.zip` + `XmlPullParser`）；编码探测用严格解码级联（BOM → 严格 UTF-8 → 严格 GBK → Latin-1）。注意：`android.icu.text.CharsetDetector` 是真机 hidden API，**禁止使用**（M3 已踩坑，见提交 4b3693f）。
- 文档/代码注释中出现的 `asset:///fixtures/...` 是 M3 开发期桩，正式实现走 SAF `content://` URI。

分层（解析层与 UI 层完全解耦，新增格式只需实现 `DocumentParser` 接口）：

```
UI 层 (Compose)  →  ViewModel 层 (StateFlow)  →  领域/数据层 (Parser/Repository)  →  平台层 (SAF/DataStore/Room)
```

核心统一模型（`app/src/main/java/com/sample/docreader/core/model/Models.kt`）：所有格式解析后归一化为 `Document(chapters: List<Chapter>)`，`Chapter.paragraphs: List<String>` 是渲染/编辑的基础单位；`ParseException` 是受控异常体系，UI 按类型给差异化提示（UnsupportedFormat / MissingOpf / MalformedContent / TooLarge / NoPermission）。

## 3. 目录与模块划分

```
app/                        主 application 模块（namespace com.sample.docreader）
  src/main/java/com/sample/docreader/
    MainActivity.kt         单 Activity，NavHost 路由（起点 HomeScreen；另注册 ACTION_VIEW 外部打开意图）
    core/
      model/Models.kt       DocFormat / Document / Chapter / ReadingSettings / ReadingTheme / ParseException
      parse/                DocumentParser 接口 + ParserFactory + TxtParser / PlainTextParser / EpubParser
      io/FileOpener.kt      文件打开抽象；AssetsFileOpener 为 M3 桩，M4 换 SAF 实现（接口不变）
      settings/             SettingsRepository（DataStore，全局共享一套设置，不按文件独立存储）
    ui/                     HomeScreen / SettingsScreen / Theme / FixtureEntryScreen
    ui/reader/              ReaderScreen / ReaderViewModel / ReaderTextView（WRAP / NO_WRAP + 位置保持）
  src/test/                 Robolectric 单元测试（含 Compose UI Test on Robolectric）
  src/androidTest/          仪器测试（Espresso + Compose UI Test）
  src/debug/assets/fixtures 测试夹具（Gradle 生成，不入库）
buildSrc/src/main/kotlin/FixtureGenerator.kt   fixtures 生成器（java.util.zip，统一 Kotlin 工具链，不引 Python）
tools/                      双仓同步脚本（仅本仓，开源仓不含）：oss-sync-check.sh 一致性校验 / oss-sync.sh 私仓→开源仓同步
spike/saf-probe/            S1 SAF Provider 技术探针（独立 application 模块，仅 debug，不入 release）
.github/workflows/ci.yml    CI（见 §4）
docs/                       需求/设计/测试用例/开发计划/评审记录
```

## 4. 构建与测试命令

使用 Gradle Wrapper（`./gradlew`），无需自行安装 Gradle。

```bash
./gradlew assembleDebug              # 构建 debug 包
./gradlew testDebugUnitTest          # 单元测试（Robolectric + Compose UI Test）
./gradlew connectedDebugAndroidTest  # 仪器测试（需 API 26 模拟器/真机）
./gradlew check                      # 含 validateFixtures（校验 fixtures 编码与结构）
./gradlew generateFixtures           # 手动重新生成测试夹具
./gradlew assembleRelease            # release 包（R8/混淆开启）
```

注意事项：

- **fixtures 自动生成**：`preBuild`、`testDebugUnitTest`、`connectedDebugAndroidTest` 均依赖 `generateFixtures`，产物写入 `app/src/test/resources/fixtures` 与 `app/src/debug/assets/fixtures`，**不入库**（gitignore），不要手工修改这些文件——改 `FixtureGenerator.kt`。
- Robolectric 单测依赖 debug assets 中的 fixtures（`unitTests.isIncludeAndroidResources = true`）。
- release 开启 R8；发布冒烟需覆盖：编码探测与 epub 解析、自定义字体 ttf 不被混淆、Navigation 路由、DataStore/Room 序列化实体。

CI（GitHub Actions，`.github/workflows/ci.yml`）两个 job：

1. `build-and-unit-test`：`assembleDebug` + `testDebugUnitTest`（ubuntu + JDK 17 temurin）。
2. `instrumented-test`：API 26 模拟器跑 `connectedDebugUnitTest`（reactivecircus/android-emulator-runner）。

**合入前 CI 必须保持绿**（push 到 main 及所有 PR 均触发）。

## 5. 代码风格与约定

- Kotlin 官方代码风格（`kotlin.code.style=official`）；注释、文档、提交信息使用**中文**。
- 解析器实现约束（`DocumentParser`）：全量解析但**须在循环内调用 `ensureActive()`** 响应协程取消；大文件分块读取；不在 UI 线程执行。
- 解析失败抛 `ParseException` 具体子类（如 epub 缺 OPF 必须抛 `MissingOpf`），不得抛笼统异常。
- 设置存储约定：行距以 **Int 百分值**存储（150 = 1.5 倍），避免 Float 精度漂移；`theme = null` 表示跟随系统深浅色；DataStore 键缺失回退默认值；V1.0 阶段 Room 用 `fallbackToDestructiveMigration`。
- 颜色用 Compose `Color`，不用 Long（避免 ARGB 顺序错误）。
- 不打包字体文件；等线（DengXian）为微软商业字体不可分发，自定义字体走 SAF 导入用户自备 ttf/otf。
- 无障碍硬要求：交互控件有 contentDescription/语义描述、可点击目标 ≥ 48dp、主题正文对比度 ≥ 4.5:1。

## 6. 测试策略

- **解析层按 TDD**：先写测试（PR 第一次提交，CI 红）再写实现（第二次提交，CI 绿），红绿过程须在 PR 历史中可复现。
- 单测栈：JUnit4 + Robolectric 4.13 + kotlinx-coroutines-test；UI 自动化用 Compose UI Test（Robolectric 上跑 `TC-UI-*`，`androidx.test:core`），并配 Robolectric shadow（如 `testutil/ShadowNoOpMagnifier.kt`）。
- 测试命名/注释引用 `docs/测试用例.md` 的 `TC-*` 编号；性能断言以解析层单测兜底（如 epub 解析 ≤200ms），**最终性能验收以真机数据为准**，模拟器数据仅作基准。
- 手工验收用例（`TC-F*`）需真机执行；验收标准为《需求说明书》§6 七条 + 全部 P0 用例回归。

## 7. 安全与隐私

- 无网络权限、无账号、无数据采集——不要引入任何联网依赖或遥测。
- 文件访问只走 SAF/ContentResolver + 持久权限（`takePersistableUriPermission`），遵循 scoped storage。
- 无密钥/证书类敏感文件；`local.properties` 仅含本机 SDK 路径，不入库。

## 8. 协作流程

- 功能分支从 `main` 拉出（`feat/mN-xxx`），PR 合入；合入前 CI 绿 + 至少一人 Approve。
- 每个里程碑结束打 `mN` tag（spike 为 `s1`）。
- 双智能体协作（Kimi Code 架构/审查 + OpenCode 实现）见 `docs/协同开发规范.md`；任务简报、审查记录与决策日志放 `docs/collab/`。
- **开源发布（双仓结构，2026-10-16 落地，决策 D-2026-10-16-01）**：日常开发在本仓（私密仓，原样保留全部历史）；开源仓为独立目录的独立 git 仓（初始单提交 + tag `v0.0.1`），含构建必需文件、`app/` 全量源码与测试、`docs/` 九份与本文件，排除 `opencode.json`、`docs/collab/`、`spike/`。共享内容以 `tools/oss-sync-check.sh` 校验（git blob 哈希级，清单在脚本内维护）、`tools/oss-sync.sh` 同步；开源仓挂 pre-push 钩子，push 公共远端前强制校验一致。**push 开源仓前先跑 `tools/oss-sync.sh`**。
- **双智能体操作硬约束（2026-09-18 固化，决策 D-2026-09-18-03）**：实现统一由 Kimi Code 以 `opencode run`（非交互）把任务简报喂给 OpenCode 执行；OpenCode 无 git 写权限（opencode.json 已禁 add/commit），提交由 Kimi Code 执行。**OpenCode 不可用按协同规范 §6 有界处置**：排除环境误判 → 按既定方式重试（递增间隔、连续 ≤3 次、记录每次）→ 暂停批次、保存任务状态、**立即提醒用户**等待指示；**禁止静默接管实现、降级模型、跳过交叉审查、照常宣称批次完成**。确需 Kimi Code 接管实现的特殊情形，必须先经用户批准并当即按 §6 入决策日志（原因/实际实现者/缺失审查环节/剩余风险）。Kimi Code 亲自写的代码由 OpenCode 只读复核（§8.5，复核对象含框定、验收标准、实现、遗漏边界）。
- 当前进度（按 git 历史）：M0\~M6、S1、M7a（编辑器基础）、M7b（保存流程 + 超大文件只读）、M7c（草稿恢复 + 自动保存 + 新建文件）、M8（全局联调与非功能加固）、M9（验收回归，**v0.0.1 已发布**）、M10（阅读器 UI 改版 F4.4/F4.5）全部完成。v0.0.1 范围 = 验收标准 1~7 + 全部 P0 用例；验收标准 8（md 目录 F2.13）与 9（epub 图片/CSS F2.14/15）已评审未实现，推迟到后续版本。M10 完成记录：底部工具条移除、设置收纳为 ⋮ 菜单→阅读设置 ModalBottomSheet（U3 分段/单选）、章节面板弹层化 + 面板内翻章（F4.5）；顺带落地一致性评审 G1（编码手动切换）/G2（CUSTOM 字体+孤儿清理）/U2（DayNight+edge-to-edge+系统栏跟随主题）/U6（测试样本 BuildConfig.DEBUG 门控）/U7（错误态返回/空态主操作）。release 构建注意：本机代理失效导致 `lintVitalRelease` 拉取失败，本地构建需 `-x lintVitalRelease`；release 暂用 debug keystore 签名，仅本机验证不外发。验收详情见 `docs/collab/reviews/m10-acceptance.md`。**下一步 M11（阅读富文本，F2.13 md 目录 + F2.14/F2.15 epub 图文/CSS）**：设计已就绪（软件设计文档 REV5/REV6，含 ContentBlock 块模型、CSS 合并规则、对比度回退），评审遗留 S1/S2/S4/S6/S7 随 M11 处置；实施再拆 M11a（md 目录，含 U4 切换映射）→ M11b（epub 图文+缓存解压提前+C4 ensureActive）→ M11c（CSS）。**M11a 已完成（2026-09-06）**：F2.13 md 目录 + U4 源码/预览切换映射——`TocEntry`/`Document.toc`（Models.kt）、`MarkdownParser.scanHeadings` 单次线性扫描（与 parse 共用标题识别、跳过围栏、剥行内标记、循环内查线程中断）、`MdViewMapping.kt` 三坐标纯函数映射、顶栏目录图标（空 toc 隐藏）+ 底部目录面板（level 缩进/selected 语义）、目录跳转立即落库（S3 方案①）；单测 178/0（+18）；验收见 `docs/collab/reviews/m11a-acceptance.md`。遗留：目录高亮仍用 colorScheme.primary，并入 M12。**M11b 已完成（2026-09-06）**：F2.14 epub 图文——FixtureGenerator 3 含图夹具（sample_images/cover/broken_image，broken 为头部清零 PNG 保证解码必败）、EpubParser 提取 img/封面（meta cover + properties=cover-image，svg 封面仅内嵌位图时取）、新建 EpubCache 缓存解压层（cache/epub/<uri md5 前16>，LRU 不删当前书/同书跳过/取消无残留/失败回退/写读双面 zip-slip 防护）、ReaderTextView 段落 item 内图片渲染（懒加载 IO 解码 + inSampleSize 双维采样 + OOM 兜底占位、svg/缺失/损坏→占位含 alt 语义、clearAndSetSemantics 防双播报）、纯图章合成宿主段、C4 XHTML 段级取消；设备端 MdImageDeviceTest（TC-F2-17/17c/17d）滚动改 performScrollToNode 确定性翻页；单测 198/0（+20）、设备端 8/8；验收见 `docs/collab/reviews/m11b-acceptance.md`。**M11c 已完成（2026-09-09，M11 收口）**：F2.15 epub 基本 CSS——fixtures styled/bad_css；Models `Chapter.styles`+`ParagraphStyleHints`（三态 bold/italic、colorArgb Int、paragraphSpacing，设计 REV9 回写）；EpubParser CSS 子集（四类选择器/7+2 属性/继承/内联优先/跨表全局递增层叠/失败静默回退纯文本）；ReaderTextView 段落合并（全局绝对基准、clamp 0.5~3.0、WCAG 对比度 <4.5:1 回退主题前景、WRAP/NO_WRAP 双路径）；MdCssDeviceTest（TC-F2-17e/f）；单测 220/0（+22）、设备端 10/10；验收见 `docs/collab/reviews/m11c-acceptance.md`。**M11 全部完成**：验收标准 8（md 目录）/9（epub 富文本）落地；视觉项与真机项见 `docs/collab/pending-real-device-tests.md`：D1~D5、D7~D11 已真机通过（D5 于 2026-09-13 M12 验收期补验），D6（TalkBack）暂缓。**M12 代码完成（2026-09-12，M12a/b/c）**：阅读器连续滚动 + epub 原生目录 + 全书进度条（F2.16/F4.7/F4.8）——解析层取消「第 N 章」兜底、产出文件自带目录（nav/NCX 原样）到 `Document.toc` 并附章坐标 + 锚点段落（元素 id → 段落；容器 id → 其内首段；`<a name>` 段中锚点 → 所在段）；VM 层 `ChapterItemMap`（章级前缀和）+ 章级渐进物化（`materializedChapterCount`/`appendNextChapter`/`ensureMaterialized`）+ 全局 item 坐标恢复与落库（`(chapter, paragraph)` 零迁移）；UI 层多章 flatten 渲染（`ReaderItem`/`assembleReaderItems`，item key = "章:段"）、右缘全书进度条（`ReaderProgressBar`，48dp 热区 + 语义 + 主题前景色）、epub 目录面板改数据源为 `Document.toc`（删「第 N 章」与 `ChapterListContent`）、顶栏无目录条目即留空（去书名回落）。单测 248/0；审查记录 `docs/collab/reviews/m12{a,b,c}-review.md`。**M12 真机验收完成（2026-09-13）**：设备端全量套件真机 **12 例 / 0 失败 / 1 跳过**（进度条 @Ignore）、单测 254/0 复核；修复 MdTocDeviceTest 两例确定性失败（测试位置无关化 `openFixtureAtTop` + 跳转目标精确串断言，09-12「失焦」归因不成立）；D9（anchor_toc 目录 NCX 原样+锚点）、D10（邓小平文选目录原样/跳转/出版说明顶栏留空/书名页非白屏）、D11（120 章连续滚动 PSS +6.5MB 无 ANR）、D5（长 md 切换/深跳/杀进程重开）真机通过；追加 Flatland.epub 真实书走查（真 CSS/插图/嵌套目录/跨章滚动）通过；验收见 `docs/collab/reviews/m12-acceptance.md`，**D 项含 D11 用户体感终审全部通过**（F4.8 进度条相关拖动项随进度条恢复复验）。**注意**：`connectedDebugAndroidTest` 结束会卸载应用，需重装 debug 包。**M13 已完成（2026-09-17，弹层强调色与阅读主题解耦）**：M10 真机反馈立项——弹层选中 ✓/章节高亮取 colorScheme.primary、不随阅读主题，系统深浅 × 主题明暗相反时对比度不足；选型 a（强调色=`theme.foreground`，决策 D-2026-09-15-01），OpenCode 实现只改 `ReaderScreen.kt`：✓/目录当前项高亮/面板文字按钮（上/下一章、A-/A+、导入字体）/错误态返回/加载指示/⋮ 下拉菜单（容器+文字+禁用）/三个 ModalBottomSheet 拖拽把手全部随阅读主题；新增语义键 `ReaderPanelAccentColorKey`（键与绘制同一局部值）；单测 263/0/1skip（TC-UI-13，独立审查 `docs/collab/reviews/m13-review.md`）、设备端 13/0/1skip（TC-UI-14）、真机 D13 五组合截图矩阵首检通过（M10「柔光米黄菜单不易识别」确认解决，验收 `docs/collab/reviews/m13-acceptance.md`）；用户确认收口，tag `m13`。**M14 已完成（2026-09-18，中英双语 F4.6，需求 REV7）**：应用名落地——默认英文 Sample Note / zh「简记」。双资源键集严格对等（values 默认英文 ~119 键 + values-zh，中文值逐字沿用既有文案）；UI 全量 stringResource 化（含无障碍 contentDescription/语义）；**错误文案架构**：VM 只传类型（新增 `ReaderError` sealed + `readerErrorText()`；`HomeMessage` 事件；`saveResultMessage` 返回 @StringRes），UI when→stringResource，ParseException.message 仅日志保留中文；**测试口径**：Robolectric UI 类统一 `@Config(qualifiers="zh")` 断言零改动、设备端断言 `getString` 化、解析层夹具内容断言不动；新增 TC-UI-15（Robolectric LocalizationTest 键集对等+双语抽查）/TC-UI-16（设备端显式 setLocale 双语上下文）。无显示名兜底构造注入本地化（英文 Untitled.md/txt、中文 未命名.md/txt，用户指示，拼写确认为 untitled）。单测 268/0/1skip、设备端中文真机 15/0/1skip + 英文 Pixel_7 AVD 15/0/1skip、英文 P0 八屏走查通过；审查 `docs/collab/reviews/m14-review.md`、验收 `docs/collab/reviews/m14-acceptance.md`；tag `m14`。**M15（F2.17 epub 章节标题层级排版）已完成（2026-09-13）**：真机反馈「章节标题与正文看不出字体差异」立项（范围仅 epub）；解析层 `Chapter.headingLevels`（按 XHTML 标签名，与 `styles` 解耦）、渲染层默认标题层 `headingFontScale`（H1~H6 = 1.6/1.45/1.3/1.2/1.1/1.05 + 默认加粗）且**书 CSS 声明即覆盖、不叠加**；红测试 7 例（6 红）→ 实现 → 独立审查 → 单测 **261/0/1skip**、设备端 **12/0/1skip**；真机量化：`headings.epub` 六级节点高度 104/97/88/83/77/74 vs 正文 72（与设计表 ±1px）、书 CSS 覆盖语义与字号档/NO_WRAP 路径验证，《邓小平文选》文章标题（`<h2>`）明显大于正文；验收见 `docs/collab/reviews/m15c-acceptance.md`（**观感经用户终审通过 2026-09-13**，档位维持设计定稿）。**设计边界**：层级按标签名判定，故用 `<p class>`/`<i>` 表达层级且样式在外链 CSS 的书（Calibre 产出的 Flatland：全书 0 个 h 标签）不受益——候选需求 A（加载外链样式表，设计 §3.2 边界）可解。**M16 已完成（2026-09-19，应用内语言切换 F4.9，需求 REV12）**：M14 验收后用户提出手动切换需求立项；`LocaleSwitcher` 双路径——① 全 API 级 `MainActivity.attachBaseContext` 按持久值 `createConfigurationContext` 包装（真值源 = DataStore `app_language` 键，手动选择优先于系统语言由此保证）；② API 33+ 额外调 `LocaleManager.setApplicationLocales` 使框架态与应用态一致（不引 AppCompat、不声明 `android:localeConfig` 避免系统第二入口双源，D-2026-09-18-01）；③ 26~32 由设置页写入后 `Activity.recreate()` 自重建。设置页「通用」小节语言行复用 `CycleSettingRow`（跟随系统→中文→English）；`AppLanguage` 入 Models.kt。新增 TC-UI-17（Robolectric `LocaleSwitchTest` 8 例 + `SettingsLanguageUiTest`）/TC-UI-18（设备端 2 例）。单测 277/0/1skip；设备端 TC-UI-18 真机复跑 2/2（判别力改造版）+ 全量套件 17 例 0 失败；D14 走查 ①~④ 全过（含 `get-app-locales` 回 `[]`、系统应用信息页无语言入口）；中英 P0 双走查通过（英文八屏 + 中文阅读链路 + TC-F2-23 复验）。审查链：Kimi 侧审查（必修 1 假绿返工）+ OpenCode §8.5 交叉复核（无必修，建议 7 处置 6 修 1 记录）；实现经 D-2026-09-18-02 批准由 Kimi 代行（OpenCode 不可用有界处置后用户批准），交叉独立性以 OpenCode 复核补齐；验收 `docs/collab/reviews/m16-acceptance.md`，tag `m16`。**M17 已完成（2026-09-19，设置项选项列表交互 + 语言确认重启 F4.3/F4.9 交互修订，需求 REV13）**：真机反馈立项（循环切换看不到可选值 + 语言切换选中即黑闪）——设置页五个循环行（语言/默认行距/默认换行模式/默认字体/默认阅读主题）改 `OptionPickerRow` 单选对话框（整行可点，AlertDialog 列全部选项、当前值 `Modifier.selectable` 选中语义、点选即生效）；语言行选定后经确认框「切换语言需要重启应用，是否立即重启？」确认才**先落库再重建**（取消不改动；重启机制沿用 M16，仅时机后移，黑屏成为用户预期的重启）；对话框打开期间背景行值退出语义树（模态隔离，`assertExists` 严格单匹配下的必要实现，审查已反证）；删 `CycleSettingRow`/`nextLineHeight`/`nextFont`/`nextTheme`/`LocaleSwitcher.next` 死代码；三新键中英同键（112=112）；`SettingsRepository` DataStore 改按 `applicationContext` 缓存（消除 Robolectric 用例串数据，生产语义等价；§8.5 S1 修正闭包仅持有 Application）。测试：新增 TC-UI-19（Robolectric 四例）+ TC-UI-17 改写五例 + TC-UI-18 设备端增补一例；红→绿可复现（`8cbcffb` 8 红 2 绿 → `db99531` 全绿）；单测 **281/0/1skip**、设备端全量 **17 例 0 失败**（1 skip 为既有 F4.8）、D15 真机走查全过（五行对话框 + 取消不落库 + 立即重启整屏英文 + 复位）。审查链：Kimi（`m17-review.md` 必修 0/4 建议处置）+ OpenCode §8.5 只读复核（`m17-opencode-review.md` 可接受进入收口，S1 已修 / S2 已注记 / S4 已加固 / S3 记录不修）；验收 `docs/collab/reviews/m17-acceptance.md`，tag `m17`。**M18 已完成（2026-09-19，复制后问 AI F4.10，需求 REV14/设计 REV18 §3.10）**：真机反馈立项「复制文本 → 一键交给 AI 应用」——① `core/askai/AskAi.kt`（候选枚举/前缀三态/提示门控/节选/Intent 构造/发送，纯函数）；② `MainActivity` 全局 `SnackbarHost` + `LifecycleResumeEffect` **仅前台**注册剪贴板监听（开关关或未选目标不注册）、同文 `lastOffered` 去重、点按 action 才发送；③ 阅读器 ⋮ 菜单「问 AI」兜底入口（受总开关约束，关闭时提示未启用且不读剪贴板）；④ 设置页「问 AI」小节（开关默认开 / 目标应用单选 / 提问前缀即时落库，空串 = 原文直发）；⑤ **目标应用 = 内置预设（DeepSeek/豆包）∪ 动态枚举**，并在 Manifest 声明 `<queries>`（SEND/text-plain + 预设包名）解决 **Android 11+ 包可见性**——D16 走查实测修复前候选仅「朗读/蓝牙」、修复后 13+ 项含 DeepSeek（`<queries>` 非权限，不破不联网/不采集红线）；⑥ 发送走 `ACTION_SEND` text/plain + `setPackage`（不弹分享面板），失败给出「目标应用不可用」提示。测试：TC-UT-19（6 例）/TC-UI-20（4 例）/TC-UI-21（设备端 2 例）/TC-F4-11（手工）；红→绿可复现（`31efb2b` 290/9 红 → 291/0/1skip）；设备端全量 **19 例 0 失败**；D16 真机全项通过（含用户手势确认复制 → Snackbar → DeepSeek 输入框出现「请解释：<原文>」未自动发送）。审查：Kimi 两轮必修返工（菜单总开关门控；`<queries>` + 预设）——**过程记录**：OpenCode 会话曾因 `opencode.json` 权限白名单不匹配中止 4 次，经用户批准放宽 bash 白名单（git 写操作仍 deny，探针验证仍拦截），实现始终由 OpenCode 完成、Kimi 审查，未反转角色。验收 `docs/collab/reviews/m18-acceptance.md`，审查 `m18-review.md`，实现报告 `m18-opencode-report.md`；tag `m18`。**M19 已完成（2026-10-07，清理批次，无行为变更/无真机项）**：删 M17 设计 REV17 §3 授权遗留的 `ReaderViewModel.cycle*`（cycleLineHeight/cycleFont/cycleTheme）+ 同类死代码 `toggleWrap()`（生产零调用方，换行走 `setWrapMode`）+ `common_switch` 未用字符串键（中英同删，LocalizationTest 只硬编码 app_name）；测试同步：`cycleTheme()`→`setTheme()`、删 2 个循环前提消失的测试、字号端点钳制断言迁移为新测 `fontSizeStep_endpointNoOverflow`、`applyCustomFont` 切 CUSTOM 断言迁入 G2 孤儿清理测试；无红阶段（删除 + 既有行为断言迁移不存在可复现红态，验证 = 编译 + 全量套件 + 引用零残留）；OpenCode 实现、Kimi 审查必修 2 返工（陈旧注释指代已删方法、未用 `WrapMode` 导入）+ 独立 `--rerun-tasks` 复跑单测 **290/0/1skip**、androidTest 编译通过、引用零残留；简报 `docs/collab/briefs/m19-cleanup.md`、审查 `docs/collab/reviews/m19-review.md`；tag `m19`。下一步候选：F4.8 进度条恢复 / 问 AI B 方案（自定义选择工具栏）/ D6 TalkBack（暂缓）；候选需求 A（外链样式表）经 REV10 关闭。- 应用显示名：英文 Sample Note / 中文「简记」（F4.6，REV7）；工程名 docreader、applicationId 不变。

