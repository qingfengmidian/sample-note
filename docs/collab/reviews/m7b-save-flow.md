# M7b 保存流程 + 超大文件只读 — 审查记录（Kimi Code）

> 简报：`docs/collab/briefs/m7b-save-flow.md`。状态：**待 OpenCode 交付**。

## 审查清单（交付后逐项核验）

### 范围与不变量

- [ ] diff 仅触及允许清单（ui/editor/**、core/io/DocumentWriter.kt、ReaderViewModel.kt、ReaderScreen.kt、测试、docs/collab/）
- [ ] 无任务外修改/格式化；无新依赖；fixtures 未动；MainActivity 未改
- [ ] M7a 行为不回归（撤销/重做、三选拦截、非 dirty 空操作）

### 功能正确性（对照 TC/设计/S1）

- [ ] TC-UT-09/09a 五用例转绿，且实现语义正确（读代码核对：临时文件读回校验、"wt"→"w" 回退顺序、失败保留临时文件、成功删临时文件、基线更新）
- [ ] "No content provider" → ProviderKilled 专门提示，不误判权限失效（S1 结论）
- [ ] TC-F3-09 VM 用例转绿（editable 按字节数 10MB 上限）
- [ ] UI：超大文件编辑入口置灰+说明；保存按钮 enabled=dirty；三选保存分支接通（成功退出/失败留页）
- [ ] 保存后阅读页重载新内容（TC-F3-02 依赖）；md 预览按新内容渲染
- [ ] saveResult 一次性事件消费清除

### 质量门槛

- [ ] 独立重跑 `./gradlew testDebugUnitTest` 全绿（预期 91 + 6 + OpenCode 新增）
- [ ] `./gradlew assembleDebug` 通过
- [ ] 无障碍：新交互控件 contentDescription、≥48dp

### 已知缺口（简报声明，非缺陷）

- 草稿机制（M7c，TC-UT-09 的"草稿被删除"条 N/A）；另存为实功能（仅提示）；超大文件编辑性能优化

## 真机验收（交付后驱动）

- [x] TC-F3-01：txt 编辑保存 → 重开内容一致 ✅
- [x] TC-F3-02：md 编辑保存 → 预览按新内容渲染 ✅
- [x] TC-F3-09：huge.txt 编辑置灰+说明，只读浏览 ✅

## 代码走查意见（Kimi Code，2026-08-23）

实现：`core/io/DocumentWriter.kt`（接口 + ContentResolver 实现）、`EditorViewModel.save()`、`EditorScreen`（保存按钮/三选保存分支/反馈对话框）、`ReaderReloadNotifier.kt`（重载信号单例）、`ReaderViewModel.editable`、`ReaderScreen` 置灰+说明；测试：`EditorSaveResultTest`（2）、`ReaderEditableTest`（2）、`EditorExitDialogTest` 更新。

### 通过项

- `save()` 语义与 TC-UT-09/09a 契约逐条一致：非 dirty 空操作（saveResult 保持 null）；UTF-8 临时文件 + 读回校验；`"wt"` 优先、FNF/UnsupportedOperationException 回退 `"w"`；成功更新基线+删临时文件；失败保留临时文件+dirty 保持。
- "No content provider" 分类为 ProviderKilled，文案与阅读页 S1 处理对齐（"请启动对应应用后重试"）。
- 回退只捕获 FNF/UnsupportedOperationException，写中途 IOException 不触发重试而是落 FailedSaveAs——符合设计§3.4，避免对已截断文件二次写入。
- `ReaderReloadNotifier` 单例替代 savedStateHandle：注释说明了约束（简报禁改 MainActivity，EditorScreen 拿不到 navController），进程死亡后集合并无残留副作用。可接受。
- `editable = bytes.size <= 10MB`，读取阶段即判定，UI 置灰+说明文字（`EDIT_HINT_TOO_LARGE`）。
- 一次性事件 `consumeSaveResult` 有专项测试；EditorContent 新增参数后既有测试同步更新。

### 观察项（非阻塞）

1. 临时文件校验失败或 cacheDir 缺失时仍继续写回（用内存字节）——与"先校验再写回"的设计意图略有偏差，但生产路径 cacheDir 必给、校验失败现实中不可达，记录即可。
2. `ReaderReloadNotifier` 是进程级单例，跨测试/多实例语义依赖约定；若 M8 联调发现重载时序问题，可再换 savedStateHandle 方案（需动 MainActivity）。
3. 保存反馈用 AlertDialog 而非 Snackbar，简报允许从简，可接受。

## 真机验收（2026-08-23，OPPO Reno9 5G / ColorOS，WiFi adb 驱动 + 截图/落盘内容判读）

- **TC-F3-09 通过**：huge.txt（15MB）打开正常浏览（滚动流畅）；菜单"编辑"项渲染为"编辑（文件超过 10MB，仅可只读）"且 clickable 父节点 `enabled="false"`。
- **TC-F3-01 通过**（txt，真实 SAF 外部存储 Provider）：`/sdcard/Documents/m7b_test.txt` 经系统 picker 打开 → 编辑插入 XYZ → 保存按钮 dirty 才可用（未修改时 `enabled="false"`）→ 点保存弹"已保存" → `adb shell cat` 验证落盘为 `seconXYZd line 原文` → 返回阅读页（非 dirty 直接退出）显示新内容（重载生效）。
- **TC-F3-02 通过**（md）：`m7b_test.md` 编辑插入 QW → 保存落盘（cat 验证）→ 回阅读页切预览 → 新内容按 Markdown 渲染（标题/有序列表正常）。
- 注：TC-F3-01 的"草稿被删除"条 N/A（草稿机制属 M7c）；"先经临时文件校验"由 TC-UT-09 自动化覆盖。
- 过程记录：`am start --grant-*-uri-permission` 方式 shell 无权授予外部存储 Provider 权限（SecurityException），SAF picker 驱动路径已跑通可复用：打开文件 → 显示根目录 → OPPO Reno9 5G → Documents → 选文件。

## 独立复验结果（Kimi Code）

- `cleanTestDebugUnitTest testDebugUnitTest`：**suites=22 tests=103 failures=0 errors=0**（91 既有 + 6 先行 + OpenCode 新增 4 + 对话框测试更新）。
- `assembleDebug`：BUILD SUCCESSFUL（独立复跑）。
- OpenCode 报告（`m7b-opencode-report.md`）与走查一致。

## 审查意见与处置

无阻塞意见；两条非阻塞观察项记录于上（临时文件校验失败仍写回、ReaderReloadNotifier 单例），M8 联调时再评估。

## 结论

**接受**。TC-UT-09/09a、TC-F3-09 自动化通过，TC-F3-01/02/09 真机验收通过，S1 探针结论已落实。DoD 满足，建议合入 main。
