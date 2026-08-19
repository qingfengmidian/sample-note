# AGENTS.md — 简易文档阅读/编辑器（docreader）

> 本文件面向 AI 编码代理，假定读者对本项目一无所知。项目文档与代码注释均使用中文。

## 1. 项目概述

一款运行于 Android 设备上的**轻量级本地文档阅读/编辑器**（applicationId：`com.sample.docreader`，根工程名 `docreader`）。

- 阅读：epub（纯文本提取，兼容 epub2/NCX 与 epub3/nav.xhtml）、xml、md、txt；txt 自动探测编码（UTF-8 / UTF-16LE/BE / GBK，Latin-1 兜底）。
- 编辑：md / txt 纯文本（记事本式，非所见即所得）。
- 特性：自动换行 / 不换行（横向滚动）双排版、字号与行距可调、阅读主题（默认白/护眼绿/柔光米黄/夜间黑）、字体方案（代码风格/衬线/无衬线/自定义导入 ttf/otf）、长按选择复制。
- 约束：纯本地应用，**不联网、无账号、不采集数据**；遵循 scoped storage，优先 SAF，不申请 `MANAGE_EXTERNAL_STORAGE`。

权威文档在 `docs/` 下，需求/设计/测试的**单一事实来源**：

| 文件 | 内容 |
| --- | --- |
| `docs/需求说明书.md` | 功能需求 F1~F4（编号如 F2.3）、非功能需求、验收标准 |
| `docs/软件设计文档.md` | 技术选型、分层架构、模块设计（接口签名以此为准） |
| `docs/测试用例.md` | 用例编号 `TC-*`（§0 为 fixtures 清单） |
| `docs/开发计划.md` | 里程碑 M0~M9 + S1 spike、DoD、协作规范 |
| `docs/review_round_*.md` | 三轮评审记录 |

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
    MainActivity.kt         单 Activity，NavHost 路由（当前起点为 fixtures 入口，M4 将换 HomeScreen）
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
- 当前进度（按 git 历史）：M0（工程骨架与 CI）、M1（fixtures + 设置基础设施 + FileOpener 桩）、M2（解析层）、M3（阅读器核心）已完成；下一步 S1/M4。里程碑明细、DoD 与风险见 `docs/开发计划.md`。
