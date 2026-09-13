# AGENTS.md — 简易文档阅读/编辑器（docreader）

> 本文件面向 AI 编码代理，假定读者对本项目一无所知。项目文档与代码注释均使用中文。

## 1. 项目概述

一款运行于 Android 设备上的**轻量级本地文档阅读/编辑器**（applicationId：`com.sample.docreader`，根工程名 `docreader`）。

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
- 当前进度（按 git 历史）：M0\~M6、S1、M7a（编辑器基础）、M7b（保存流程 + 超大文件只读）、M7c（草稿恢复 + 自动保存 + 新建文件）、M8（全局联调与非功能加固）、M9（验收回归，**v0.0.1 已发布**）、M10（阅读器 UI 改版 F4.4/F4.5）全部完成。v0.0.1 范围 = 验收标准 1~7 + 全部 P0 用例；验收标准 8（md 目录 F2.13）与 9（epub 图片/CSS F2.14/15）已评审未实现，推迟到后续版本。M10 完成记录：底部工具条移除、设置收纳为 ⋮ 菜单→阅读设置 ModalBottomSheet（U3 分段/单选）、章节面板弹层化 + 面板内翻章（F4.5）；顺带落地一致性评审 G1（编码手动切换）/G2（CUSTOM 字体+孤儿清理）/U2（DayNight+edge-to-edge+系统栏跟随主题）/U6（测试样本 BuildConfig.DEBUG 门控）/U7（错误态返回/空态主操作）。release 构建注意：本机代理失效导致 `lintVitalRelease` 拉取失败，本地构建需 `-x lintVitalRelease`；release 暂用 debug keystore 签名，仅本机验证不外发。验收详情见 `docs/collab/reviews/m10-acceptance.md`。**下一步 M11（阅读富文本，F2.13 md 目录 + F2.14/F2.15 epub 图文/CSS）**：设计已就绪（软件设计文档 REV5/REV6，含 ContentBlock 块模型、CSS 合并规则、对比度回退），评审遗留 S1/S2/S4/S6/S7 随 M11 处置；实施再拆 M11a（md 目录，含 U4 切换映射）→ M11b（epub 图文+缓存解压提前+C4 ensureActive）→ M11c（CSS）。**M11a 已完成（2026-09-06）**：F2.13 md 目录 + U4 源码/预览切换映射——`TocEntry`/`Document.toc`（Models.kt）、`MarkdownParser.scanHeadings` 单次线性扫描（与 parse 共用标题识别、跳过围栏、剥行内标记、循环内查线程中断）、`MdViewMapping.kt` 三坐标纯函数映射、顶栏目录图标（空 toc 隐藏）+ 底部目录面板（level 缩进/selected 语义）、目录跳转立即落库（S3 方案①）；单测 178/0（+18）；验收见 `docs/collab/reviews/m11a-acceptance.md`。遗留：目录高亮仍用 colorScheme.primary，并入 M12。**M11b 已完成（2026-09-06）**：F2.14 epub 图文——FixtureGenerator 3 含图夹具（sample_images/cover/broken_image，broken 为头部清零 PNG 保证解码必败）、EpubParser 提取 img/封面（meta cover + properties=cover-image，svg 封面仅内嵌位图时取）、新建 EpubCache 缓存解压层（cache/epub/<uri md5 前16>，LRU 不删当前书/同书跳过/取消无残留/失败回退/写读双面 zip-slip 防护）、ReaderTextView 段落 item 内图片渲染（懒加载 IO 解码 + inSampleSize 双维采样 + OOM 兜底占位、svg/缺失/损坏→占位含 alt 语义、clearAndSetSemantics 防双播报）、纯图章合成宿主段、C4 XHTML 段级取消；设备端 MdImageDeviceTest（TC-F2-17/17c/17d）滚动改 performScrollToNode 确定性翻页；单测 198/0（+20）、设备端 8/8；验收见 `docs/collab/reviews/m11b-acceptance.md`。**M11c 已完成（2026-09-09，M11 收口）**：F2.15 epub 基本 CSS——fixtures styled/bad_css；Models `Chapter.styles`+`ParagraphStyleHints`（三态 bold/italic、colorArgb Int、paragraphSpacing，设计 REV9 回写）；EpubParser CSS 子集（四类选择器/7+2 属性/继承/内联优先/跨表全局递增层叠/失败静默回退纯文本）；ReaderTextView 段落合并（全局绝对基准、clamp 0.5~3.0、WCAG 对比度 <4.5:1 回退主题前景、WRAP/NO_WRAP 双路径）；MdCssDeviceTest（TC-F2-17e/f）；单测 220/0（+22）、设备端 10/10；验收见 `docs/collab/reviews/m11c-acceptance.md`。**M11 全部完成**：验收标准 8（md 目录）/9（epub 富文本）落地；视觉项与真机项见 `docs/collab/pending-real-device-tests.md`：D1~D5、D7~D11 已真机通过（D5 于 2026-09-13 M12 验收期补验），D6（TalkBack）暂缓。**M12 代码完成（2026-09-12，M12a/b/c）**：阅读器连续滚动 + epub 原生目录 + 全书进度条（F2.16/F4.7/F4.8）——解析层取消「第 N 章」兜底、产出文件自带目录（nav/NCX 原样）到 `Document.toc` 并附章坐标 + 锚点段落（元素 id → 段落；容器 id → 其内首段；`<a name>` 段中锚点 → 所在段）；VM 层 `ChapterItemMap`（章级前缀和）+ 章级渐进物化（`materializedChapterCount`/`appendNextChapter`/`ensureMaterialized`）+ 全局 item 坐标恢复与落库（`(chapter, paragraph)` 零迁移）；UI 层多章 flatten 渲染（`ReaderItem`/`assembleReaderItems`，item key = "章:段"）、右缘全书进度条（`ReaderProgressBar`，48dp 热区 + 语义 + 主题前景色）、epub 目录面板改数据源为 `Document.toc`（删「第 N 章」与 `ChapterListContent`）、顶栏无目录条目即留空（去书名回落）。单测 248/0；审查记录 `docs/collab/reviews/m12{a,b,c}-review.md`。**M12 真机验收完成（2026-09-13）**：设备端全量套件真机 **12 例 / 0 失败 / 1 跳过**（进度条 @Ignore）、单测 254/0 复核；修复 MdTocDeviceTest 两例确定性失败（测试位置无关化 `openFixtureAtTop` + 跳转目标精确串断言，09-12「失焦」归因不成立）；D9（anchor_toc 目录 NCX 原样+锚点）、D10（邓小平文选目录原样/跳转/出版说明顶栏留空/书名页非白屏）、D11（120 章连续滚动 PSS +6.5MB 无 ANR）、D5（长 md 切换/深跳/杀进程重开）真机通过；追加 Flatland.epub 真实书走查（真 CSS/插图/嵌套目录/跨章滚动）通过；验收见 `docs/collab/reviews/m12-acceptance.md`，**D 项含 D11 用户体感终审全部通过**（F4.8 进度条相关拖动项随进度条恢复复验）。**注意**：`connectedDebugAndroidTest` 结束会卸载应用，需重装 debug 包。**M13（弹层强调色与阅读主题解耦，UX 配色一致性）**：M10 真机反馈——弹层选中 ✓/章节高亮取 colorScheme.primary、不随阅读主题，系统深浅 × 主题明暗相反时对比度不足（如系统深色 + 柔光米黄），待 M12 后实施，见 `docs/开发计划.md` M13。**M15（F2.17 epub 章节标题层级排版）已完成（2026-09-13）**：真机反馈「章节标题与正文看不出字体差异」立项（范围仅 epub）；解析层 `Chapter.headingLevels`（按 XHTML 标签名，与 `styles` 解耦）、渲染层默认标题层 `headingFontScale`（H1~H6 = 1.6/1.45/1.3/1.2/1.1/1.05 + 默认加粗）且**书 CSS 声明即覆盖、不叠加**；红测试 7 例（6 红）→ 实现 → 独立审查 → 单测 **261/0/1skip**、设备端 **12/0/1skip**；真机量化：`headings.epub` 六级节点高度 104/97/88/83/77/74 vs 正文 72（与设计表 ±1px）、书 CSS 覆盖语义与字号档/NO_WRAP 路径验证，《邓小平文选》文章标题（`<h2>`）明显大于正文；验收见 `docs/collab/reviews/m15c-acceptance.md`（**观感经用户终审通过 2026-09-13**，档位维持设计定稿）。**设计边界**：层级按标签名判定，故用 `<p class>`/`<i>` 表达层级且样式在外链 CSS 的书（Calibre 产出的 Flatland：全书 0 个 h 标签）不受益——候选需求 A（加载外链样式表，设计 §3.2 边界）可解。**M14（中英双语 F4.6，需求说明书 REV7）**：界面字符串资源外置（values 默认英文 app_name="Sample Note" + values-zh 中文「简记」），跟随系统语言、本期不做法内切换，设计见软件设计文档 §3.7，M13 后实施。
- 应用显示名：英文 Sample Note / 中文「简记」（F4.6，REV7）；工程名 docreader、applicationId 不变。

