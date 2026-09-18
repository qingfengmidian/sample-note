# M16 OpenCode 只读复核任务简报

你是本批次的**只读复核者（OpenCode）**。按 `docs/协同开发规范.md` §8.5：「Kimi Code 亲自写的代码由 OpenCode 复核，复核对象同时包括：任务框定、验收标准、实现本身、遗漏的边界」。本批次 M16「应用内语言切换（F4.9）」的实现由 Kimi Code 代行（背景与批准见 `docs/collab/决策日志.md` D-2026-09-18-02），由你补做交叉复核。

## 硬性约束

1. **只读复核**：不得修改任何 `app/src/`、`docs/`（除下述审查报告）、`buildSrc/`、`AGENTS.md` 等仓库文件；不得 `git add/commit/push`（你的 git 写权限已被 opencode.json 禁用）。
2. 唯一允许的写入：`docs/collab/reviews/m16-opencode-review.md`（新建）。
3. 只运行已预批命令：`git status` / `git log` / `git diff` / `git show`、文件读取、`./gradlew testDebugUnitTest`；其余命令一律不执行，需要时记入报告「未验证项」。
4. 发现必修问题**不要动手修**，写入报告等待处置。
5. **执行方式约束（重要）**：每条 shell 命令**单独执行**，不要用 `&&`、`;`、`||` 或换行拼接多条命令，也不要用 `echo` 做分隔——opencode.json 的权限按**单条命令前缀**匹配，复合命令会被自动拒绝并中止任务。例如依次单独执行 `git status`、再 `git log --oneline -15`、再 `git show 39856fd`。

## 必读材料（按序）

1. `AGENTS.md`（项目约定，§5 代码风格、§6 测试策略）
2. `docs/协同开发规范.md`（角色与复核要求）
3. `docs/需求说明书.md` F4.9（REV12 修订记录内）
4. `docs/软件设计文档.md` §3.7 国际化（REV15/REV16，含「应用内语言切换」小节）
5. `docs/测试用例.md` TC-UI-17 / TC-UI-18（§3 修订记录 REV10/REV11）
6. `docs/开发计划.md` M16 节（含框定记录）
7. `docs/collab/决策日志.md` D-2026-09-18-01（含补记）
8. `docs/collab/briefs/m16-language-switch.md`（含「实现回写（2026-09-18，审查后）」段——契约曾微调）
9. `docs/collab/reviews/m16-review.md` 与 `m16-review-response.md`（前一轮 Kimi 侧审查与返工；你的复核须**独立**，不得照抄其结论）
10. 代码：`git show 16487d6`（红测试 + 契约骨架）、`git show 39856fd`（实现）、`git show 5c6178c`（审查返工）、当前 `git status`（应干净；若有未提交改动先记录再决定）

## 复核范围

对照上述设计/决策/简报，逐条核对 `app/src/main` 中的 M16 实现（`core/settings/LocaleSwitcher.kt`、`core/settings/SettingsRepository.kt`、`core/model/Models.kt`、`MainActivity.kt`、`ui/SettingsScreen.kt`、`res/values*/strings.xml`）与测试（`LocaleSwitchTest.kt`、`ui/SettingsLanguageUiTest.kt`、`androidTest/.../LocaleSwitchDeviceTest.kt`）：

1. **任务框定与验收标准**：简报/设计/用例三处的范围、验收口径是否自洽；实现是否越界或缩水。
2. **实现正确性**：包装语义、档位归一化（缺失/未知/未覆盖语言）、重建路径（33+ 框架 / <33 自重建 / 框架失败回退）、写库与切换顺序、`runCatching` 兜底语义、`LocaleList` 空表语义、DataStore 键级隔离。
3. **遗漏边界**：除既有测试外，你是否能想到未覆盖的边界（如进程首启、Activity 旋转、深色/浅色、多窗口、异常恢复、无障碍朗读语言行）；既有测试是否只盖成功路径。
4. **规范符合性**：AGENTS.md §5（中文注释、TC 编号引用、无障碍 ≥48dp/contentDescription、Kotlin 官方风格）、注释与行为一致、无任务外改动、无未用 import/死代码。
5. **测试质量**：断言是否断言行为而非复述实现；⑤ 无条件断言是否真能红（可静态推演，不要改代码验证）；⑥ 的 sdk=32 路径是否有意义；设备端用例的清理是否完备。

## 必做验证

- 独立重跑 `./gradlew testDebugUnitTest`，报告**你自己实测**的计数（不得引用任何转述数字）。

## 报告要求（写入 `docs/collab/reviews/m16-opencode-review.md`）

- 结论分**必修项 / 建议项 / 已确认无问题项**，每条给 `文件:行号` 或提交 hash 证据；
- 明确列出「未验证项」与「剩余风险」；
- 报告格式按协同规范第三步交回格式：改动核对清单 / 验证命令与结果 / 未验证项 / 剩余风险；
- 结尾给出总体结论：「M16 实现是否可接受进入收口」或「需返工（列必修项）」。
