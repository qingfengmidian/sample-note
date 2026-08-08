# 项目评审意见 · Round 3

| 项目 | 内容 |
| --- | --- |
| 文档版本 | V1.0-REV2（对应需求/设计/测试/开发计划） |
| 评审日期 | 2026-08-08 |
| 评审范围 | 《需求说明书》V1.0-REV2、《软件设计文档》V1.0-REV2、《测试用例》V1.0-REV2、《开发进度计划》V1.0 |
| 上轮评审 | 《Round 2 评审意见》|
| 评审结论 | **通过，可进入开发**——需求/设计/测试无阻塞项；开发计划需补充人天估算与依赖缺口方案后执行 |

---

## 〇、REV2 修订评估

REV2 对 Round 2 的 **两个阻塞项** 和 **全部 18 条高中低优先级建议** 都给出了实质性回应。修订质量好，未引入新的结构性缺陷。要点如下：

| Round 2 意见 | REV2 处理 | 评价 |
|-------------|-----------|------|
| 阻塞 1.1：版本命名冲突 | 文档版本 V1.0-REV2，产品版本 V1.1/V1.2/V1.3，并在第 5 章加注"产品版本与修订号无关" | ✅ 解决了命名冲突 |
| 阻塞 1.2：SavedStateHandle 大文本风险 | 改为草稿恢复文件方案：`SavedStateHandle` 仅存 uri/光标/dirty，正文存 `edit_recovery/<hash(uri)>.draft`，无大小上限 | ✅ 设计合理 |
| 重要 2.1：juniversalchardet 风险 | 替换为 `android.icu.text.CharsetDetector`（平台内置，API 24+），零依赖、零 R8 负担 | ✅ 方案更优 |
| 重要 2.2：缺少 epub 解析性能测试 | TC-UT-04c（200ms 断言）+ TC-F2-16b（首章 ≤ 3s、章节列表 ≤ 1s） | ✅ 已覆盖 |
| 重要 2.3：MalformedContent null 消息 | 改为 `cause.message ?: cause::class.simpleName`，TC-UT-06a 增加验证 | ✅ 已修正 |
| 重要 2.4：epub XHTML 编码防御 | 设计加入防御性处理 + TC-F2-17b 负例 + TC-UT-04d | ✅ 已覆盖 |
| 中 3.1~3.8 + 低 4.1~4.10 | 全部获得回应 | ✅ 均已处理 |

---

## 一、需求/设计/测试 — 边缘场景与细节建议（非阻塞）

### 1.1 草稿文件的清理机制缺失

设计 3.4 节定义了草稿写入时机（防抖 1s）和删除时机（保存成功 / 用户选"放弃"），但未说明**长期未处理的草稿文件如何清理**。场景如下：

1. 用户打开文件 A，编辑后未保存、未放弃，直接杀进程。
2. 下次启动应用，用户从不再打开文件 A。
3. `edit_recovery/<hash(uriA)>.draft` 永久残留。

**建议**：在 `SettingsRepository` 或 `EditorViewModel` 中增加草稿清理策略——例如在应用启动时遍历 `edit_recovery/` 目录，删除创建时间超过 7 天的草稿文件；或在最近文件列表中被移除的条目的对应草稿同步删除。

### 1.2 草稿文件哈希算法未指定

> `files/edit_recovery/<hash(uri)>.draft`

URI 通常为 `content://...` 格式，长度可能超过文件系统限制。哈希是正确方向，但未指定算法。

**建议**：明确使用确定性算法（如截取 MD5 后 16 字符），避免依赖 `hashCode()` 的规范表述模糊性。

### 1.3 `CharsetDetector` 降级回退路径描述不精确

设计 3.2 节：

> 探测失败回退 GBK，再兜底 Latin-1

但 `android.icu.text.CharsetDetector` 的 `detect()` 方法几乎不会返回 `null`（它总是返回一个"最佳猜测"结果）。"探测失败"更可能指**探测结果的置信度（confidence）低于阈值**的场景。当前描述可能误导开发者认为探测返回了 `null`。

**建议**：改为 "`CharsetDetector.detect()` 返回的 `CharsetMatch.getConfidence() < 50`（或类似阈值）时视为探测失败，依次回退 GBK → Latin-1"。

### 1.4 EpubParser 正则降级触发条件未定义

设计 8 节：

> XHTML 解析失败时退化为"剥标签"正则提取

"XHTML 解析失败"未具体定义触发条件——是 `XmlPullParser` 抛出异常？解析出的段落数为 0？出现大量乱码？

**建议**：明确为 `XmlPullParser` 抛出 `XmlPullParserException` 或 `IOException` 时触发正则降级；其它情况走 `ParseException.MalformedContent`。

### 1.5 Room 进度表的主键约定不清

设计 3.5 节：

> Room 表 `reading_progress(uri, chapter, paragraph)`

最近文件表已明确 `uri PRIMARY KEY`，但进度表未标主键。一个文件只有一条进度记录，`uri` 就是自然主键。

**建议**：与 `recent_file` 保持一致——明确 `uri PRIMARY KEY`，插入时用 `OnConflictStrategy.REPLACE`。

### 1.6 行距百分值与 Compose `lineHeight` 的转换

设计 3.1 节将行距改为 `Int` 百分值存储，解决了 Float 精度问题。但 `ReadingSettings` 中的 `lineHeightPercent` 是如何映射到 Compose `TextStyle.lineHeight` 的？当前设计中 `ReaderTextView` 的描述只说了"来自 ReadingSettings"，未提及转换公式。

**建议**：在 3.3 节 `ReaderTextView` 中补充一行：`lineHeight = (fontSizeSp * lineHeightPercent / 100).sp`。

### 1.7 测试用例 TC-F2-11 对 DEFAULT 主题的背景校验值可能过时

> 默认白背景为 #FAFAFA

需求说明书中 F2.5 描述仍为"默认白"，未更新颜色值。如果设计师未来调整了 DEFAULT 颜色，测试用例中的魔数需要同步更新。

**建议**：测试用例中对颜色值的引用统一为"与 `ReadingTheme.DEFAULT.background` 一致"——避免魔数解耦。或至少加注：该值与设计 3.1 节 `DEFAULT` 枚举同步。

### 1.8 epub 章节内 XHTML 编码防御——回退触发条件的精度

设计 3.2 节：

> 解析失败或出现明显乱码时回退按 UTF-8 重新解析

"出现明显乱码"是主观标准，开发和测试难以对齐。

**建议**：简化为 `XmlPullParser` 抛出 `XmlPullParserException` 时回退 UTF-8 重试（与 1.4 的触发逻辑一致）。

### 1.9 测试夹具 `sample.epub` 与 `sample3.epub` 间缺少 epub3 + NCX 回退的混合用例

TC-UT-04b 测试了"epub3 与 NCX 并存时优先使用 nav.xhtml"，但 TC-F2 的功能测试中没有对应场景。

**建议**：准备一份 epub3 + NCX 兼有的混合 fixture，加入 TC-F2-16a 的联动步骤中验证回退行为。

---

## 二、文档一致性校验

| 检查项 | 需求 | 设计 | 测试 | 状态 |
|--------|------|------|------|------|
| 默认字体 CODE | F2.4 | `ReaderFont.CODE` | TC-F2-06/07 | ✅ |
| epub3 nav.xhtml | F2.7 / 2.4 | EpubParser step 4 | TC-F2-16a / UT-04a/b | ✅ |
| 编码范围 (UTF-8/16/GBK/Latin-1) | F2.6 | CharsetDetector 探测链 | TC-F2-14 / UT-01~03b | ✅ |
| DEFAULT 主题 #FAFAFA | (隐含) | `Color(0xFFFAFAFA)` | TC-F2-11 | ✅ |
| 行距 Int 百分值 | (隐含) | `lineHeightPercent: Int = 150` | TC-F2-05 | ✅ |
| 草稿恢复方案 | — | 3.4 草稿文件方案 | TC-F4-06 / UT-09b / UI-05 | ✅ |
| 保存先写临时文件 | — | 3.4 step 1 | TC-F3-01 / UT-09 | ✅ |
| epub3+NCX 并存优先级 | — | EpubParser step 4 | TC-UT-04b | ✅ (仅单元) |
| epub 解析性能 | — | — | TC-UT-04c / F2-16b | ✅ |
| 系统主题联动 | F2.11 (中优先级) | theme=null 逻辑 | TC-F2-12/13 (P1) | ✅ |
| MIME 回退嗅探 | — | 3.6 `*/*` + 文件头 | TC-F1-03a | ✅ |
| 编辑按钮超大文件置灰 | — | — | TC-F3-09 | ✅ |

---

## 三、开发计划评审

### 3.1 整体评价

**优点**：
- 里程碑粒度和验证手段清晰——M0~M9 每个里程碑都有明确的交付物、测试用例编号映射和 DoD。
- 依赖排序基本正确：基础设施（M0/M1）→ 解析层（M2）→ 阅读器（M3）→ 文件访问（M4）→ epub（M5）→ md 预览（M6）→ 编辑器（M7）→ 集成加固（M8）→ 验收（M9）。
- 测试先行意识好：fixtures 在 M1 齐套，解析层 TDD，每个里程碑出口标准以用例通过为准。
- 风险识别务实：SAF 碎片化、epub 非标样本、手势冲突等都识别到了。

**总体缺憾**：
- **缺少时间/人天估算**：9 个里程碑无任何工时预估，无法判断资源匹配度和交付可行性。
- **M7 粒度过粗**：编辑器是整个项目中复杂度最高的模块（撤销重做、临时文件保存、草稿恢复、自动保存、新建文件、超大文件保护），合并在一个里程碑内风险集中。

### 3.2 M3 依赖缺口：需要文件输入但 M4 尚未完成

M3（阅读器核心）验证"打开 txt/md/xml"文件并展示，但 SAF 文件访问直到 M4 才实现。没有 M4 的 SAF 能力，M3 怎么加载文件用于测试？

**建议**（三选一）：

| 方案 | 内容 |
|------|------|
| A | M3 使用 `android.resource://` 或 `assets` 中的内置测试文件绕开 SAF，M4 之后再切换到 SAF 路径 |
| B | 在 M1 中预置一个最小化的 `FileOpener` 接口桩（硬编码返回 assets 中的 fixture URI），M4 替换为 SAF 实现 |
| C | 合并 M3 和 M4 为一个里程碑（阅读器 + 文件访问），两者耦合紧密 |

无论选哪种，需要在计划中明确 M3 阶段的文件来源。此外，M3 中的自定义字体导入（TC-F2-09）也依赖 SAF 选文件，同样需要在无 SAF 时期有替代路径或推迟到 M4。

### 3.3 M7 粒度过粗，建议拆分

M7 当前包含 8 个相对独立的子功能，且"保存流程"和"草稿恢复"有较高的实现和测试复杂度。

**建议**拆分为：

| 子里程碑 | 内容 | 验证 |
|----------|------|------|
| M7a | 编辑组件 + 撤销/重做 + 退出拦截 | TC-F3-01~04, UT-08, UI-04 |
| M7b | 保存流程（临时文件 + `wt`/`w` 回退 + 另存）+ 超大文件只读 | TC-F3-01/02/09, UT-09/09a |
| M7c | 草稿恢复 + 自动保存 + 新建文件 | TC-F3-05~08, UT-09b, UI-05 |

拆分后 M7a~c 各 2~3d，风险更加可控，且 M7a 的退出拦截可以先于保存流程交付验证。

### 3.4 SAF Provider 行为验证的时机错误

风险表中写：

> SAF Provider 行为碎片化 → 提前在 **M4** 做一次 spikes 验证

但 M4 本身就是"文件访问与首页"里程碑，spike 和正式开发在同一阶段，没有预留发现问题后的修复缓冲。

**建议**：spike 提前到 **M2 之后、M3 之前**做——用一个独立的技术探针工程验证 `OpenDocument`、`takePersistableUriPermission`、`openOutputStream("wt"/"w")` 在目标真机 + 至少一个云存储 Provider（如 Google Drive / OneDrive）上的行为，确认设计假设成立后再进入 M4。

### 3.5 缺少时间/人天估算

这是最大的盲区——没有工时，无法评估资源是否够用，也无法做进度跟踪。

**建议**：对每个里程碑给出人天估算（即使粗略）：

| 里程碑 | 预估人天 | 说明 |
|--------|----------|------|
| M0 | 1~2d | 工程脚手架，依赖一次性配好 |
| M1 | 2~3d | fixtures 生成脚本 + DataStore |
| M2 | 3~5d | TDD 四个 parser，复杂度集中在 EpubParser |
| M3 | 3~5d | 排版组件 + 设置联动，Compose 学习曲线 |
| M4 | 2~3d | SAF 集成，Room 最近列表 |
| M5 | 2~3d | epub 多章渲染与导航 |
| M6 | 1~2d | md 渲染 + xml 展示 |
| M7 | 5~7d | 编辑器全套（如拆分为 M7a~c 各 2~3d） |
| M8 | 2~3d | 集成加固，性能调优 |
| M9 | 1~2d | 验收回归 |

总人天约 22~35d，按一人专职约 5~7 周。若团队大于 1 人，并行度可进一步提升（见 3.7）。

### 3.6 无障碍验证推迟过晚

无障碍需求（对比度 ≥ 4.5:1、48dp、contentDescription）是 M8 才验证，但阅读主题、字体选择等 UI 组件在 M3 就实现了。如果 M3 的 UI 控件没有 contentDescription 或对比度不达标，到 M8 才返工成本高。

**建议**：每个包含 UI 交付的里程碑（M3/M5/M6/M7）的 DoD 中增加一条基本无障碍自检——至少确保按钮有 contentDescription、最小触摸区域 ≥ 48dp。M8 只做系统级 TalkBack 联调和对比度精确验证。

### 3.7 依赖图缺少并行化信息

当前依赖图是完全线性的，但实际上存在并行机会：

```
M0 ─► M1 ─► M2 ─► M3 ─► M4 ─► M5 ─► M6 ─► M7 ─► M8 ─► M9
                            │     │
                            │     └── M5 可与 M4 并行（epub 不依赖首页/最近列表）
                            │
                            └── M6 可完全与 M4/M5 并行
```

**建议**：在计划中标注可并行的里程碑，便于多人协作时分配。

### 3.8 M0 CI 方案不应是"或"

> GitHub Actions（或本地脚本）

"或本地脚本"降低了 CI 的门槛，容易退化为没有 CI。

**建议**：删除"或本地脚本"，明确为 GitHub Actions。本地脚本仅在 CI 不可用时作为开发者自检辅助。

### 3.9 缺少代码审查与合入流程

计划没有提到 PR review、分支策略、合入检查等工程规范。

**建议**：补充简短的协作规范（几行即可）：
- 功能分支从 `main` 拉出，MR 合入。
- 合入前 CI 必须绿 + 至少一人 Approve。
- 每个里程碑结束打 `mN` tag。

### 3.10 细节建议

| # | 位置 | 问题 | 建议 |
|---|------|------|------|
| a | M1 | "epub 夹具用脚本生成（Python zipfile）"——增加了 Python 构建依赖 | 考虑用 Kotlin 脚本（`.main.kts`）或 Gradle task 替代，统一工具链；或至少明确 Python 版本≥3.9 |
| b | M2 | TDD "红绿过程可复现"很好，但缺少验收方式 | 要求每个 parser 的 PR 分两次提交（先仅含测试→CI 红，再提交实现→CI 绿） |
| c | M5 | "阅读进度记录与恢复（Room）"——Room 首次实际实现在 M5，但 M4 的首页最近列表也用了 Room | 在 M4 中先行完成 Room 的基本集成验证，降低 M5 风险 |
| d | M8 | TC-F4-08 的 `lsof` 辅助验证——`lsof` 需 `adb shell` | 明确 `lsof` 仅用于 debug build + adb shell，非 CI 自动化项 |
| e | M8 | "性能指标在 API 26 模拟器与中端真机双侧达标"——模拟器数据不可靠 | 强调模拟器性能仅做参考基准（不可劣化），真机数据才是验收依据 |
| f | M5 | EpubParser 的 `ensureActive()` 取消支持在 M2 做单测（TC-UT-13），但实际集成效果到 M8 才验证 | 在 M5 epub 里程碑中增加一条集成级取消验证（解析中途退出→再次打开从零开始），作为早期确认点 |
| g | 缺失 | 没有提到 DataStore / Room 的数据库迁移策略 | M1 补充：DataStore 按需加 `Migration`；Room 表结构需做 `fallbackToDestructiveMigration` 或明确迁移路径 |
| h | M9 | "release 包（R8 开启）冒烟"——仅提了编码探测和 epub 解析 | 补充验证项：自定义字体导入（ttf 不被混淆）、Compose Navigation 路由、DataStore/Room 序列化实体 |
| i | M0 | 计划中依赖的第三方库没有列出完整清单 | M0 补充 `build.gradle` 依赖清单（Compose BOM、Material 3、DataStore、Room、Navigation、Coroutines、JUnit、Robolectric、Compose UI Test），团队审查版本兼容性 |

---

## 四、总结

### 4.1 需求/设计/测试

三份文档经过两轮修订，阻塞项全部消除，需求-设计-测试的可追溯链完整、一致性好。技术选型（平台 ICU 替代 juniversalchardet、草稿文件替代 SavedStateHandle 全量序列化）务实合理。剩余建议为边缘场景强化，不构成进入开发的障碍。

| 优先级 | 条目 | 摘要 |
|--------|------|------|
| **中** | 1.1 | 补充草稿文件的过期清理机制（建议 7 天） |
| **中** | 1.2 | 明确草稿文件哈希算法 |
| **中** | 1.4 | 明确 EpubParser 正则降级的触发条件 |
| **低** | 1.3 | `CharsetDetector` 探测失败 → 明确为置信度阈值 |
| **低** | 1.5 | Room 进度表标注 uri PRIMARY KEY |
| **低** | 1.6 | 补充行距百分值到 Compose `lineHeight` 的转换公式 |
| **低** | 1.7 | 测试用例中的颜色魔数与设计枚举解耦 |
| **低** | 1.8 | XHTML 回退重解析的触发条件给出可操作定义 |
| **低** | 1.9 | 补充 epub3+NCX 混合功能测试 |

### 4.2 开发计划

开发计划结构清晰，里程碑与测试用例的映射完整。主要短板是无时间估算和 M3/M7 的粒度/依赖问题。

| 优先级 | 条目 | 摘要 |
|--------|------|------|
| **高** | 3.5 | 补充各里程碑的人天估算 |
| **高** | 3.2 | 明确 M3 在 SAF 就绪前的文件输入方案 |
| **高** | 3.3 | M7（编辑器）拆分为 M7a/M7b/M7c |
| **中** | 3.4 | SAF Provider spike 提前到 M2 之后独立执行 |
| **中** | 3.6 | 每个 UI 里程碑增加无障碍基本自检 |
| **中** | 3.7 | 标注可并行的里程碑 |
| **中** | 3.8 | 删除 CI "或本地脚本"选项 |
| **低** | 3.9 | 补充代码审查与合入规范 |
| **低** | 3.10 | 若干细节（工具链、TDD 验收、Room 集成时机、数据库迁移、R8 冒烟项、依赖清单），见 3.10 表 |

### Round 3 总体评价

需求/设计/测试三文档**批准进入开发阶段**。开发计划补齐人天估算和 M3 文件输入方案后可作为执行跟踪基线使用。
