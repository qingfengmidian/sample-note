# v0.0.1 一致性卫生批次（代码部分）—— OpenCode 执行报告

- 简报：`docs/collab/briefs/hygiene-batch-brief.md`（6 项：U1/C5/C6/G4-code/G6/pendingParagraph）
- 基线：main 最新提交（工作区另有 AGENTS.md 与 docs/ 下三份文档的**既有未提交改动**，非本批次产出，未触碰）
- 执行人：OpenCode；未执行任何 git 提交/变基；未建调试文件；未手改 fixtures 产物

## 总体结论

- `./gradlew assembleDebug` ✅ BUILD SUCCESSFUL（U1 资源链接通过）
- `./gradlew testDebugUnitTest` ✅ BUILD SUCCESSFUL（全量单测通过，含本批次新增 2 个用例）
- 两个任务合并执行：`./gradlew assembleDebug testDebugUnitTest`，35s，无失败、无警告升级

## 逐项明细

### U1（高）补应用自适应图标

改动文件：

| 文件 | 行号 | 内容 |
| ---- | ---- | ---- |
| `app/src/main/AndroidManifest.xml` | 6-7 | application 标签新增 `android:icon="@mipmap/ic_launcher"` 与 `android:roundIcon="@mipmap/ic_launcher_round"` |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 新增 | `<adaptive-icon>`：background=@color/ic_launcher_background，foreground=@drawable/ic_launcher_foreground |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | 新增 | 同上（圆形遮罩由启动器决定，资源内容一致） |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | 新增 | 矢量图（108dp 画布）：白色（#FFFFFF）"文档+折角书页"剪影，`fillType=evenOdd` 镂空出折角缝与三条正文行槽 |
| `app/src/main/res/values/colors.xml` | 新增 | `ic_launcher_background=#6750A4` |

自查结论：

- 主色依据：`ui/Theme.kt` 使用 Material 3 默认 `lightColorScheme()`，其 primary 即 #6750A4，故背景色取该值（代码内无自定义品牌色）。
- minSdk=26，只建 anydpi-v26，无 png 兜底；未引入任何图片素材/第三方依赖。
- 安全区：图形坐标收敛在 x∈[38,72]、y∈[28,80]，距中心 (54,54) 最远点约 31.6px < 33px 安全半径，自适应遮罩（圆/圆角方/水滴）裁切不损形。
- 验证：`assembleDebug` 通过（资源链接 OK）。真机图标效果由 Kimi Code 验证。

### C5（低→按正确性修）保存临时文件校验失败必须中止

改动文件：

| 文件 | 行号 | 内容 |
| ---- | ---- | ---- |
| `app/src/main/java/com/sample/docreader/ui/editor/EditorViewModel.kt` | 159（KDoc）、177-188 | `writeTempFile` 返回 null 时直接 `saveResult = SaveResult.FailedSaveAs` 并 `return@launch`，不再进入 `writeBackWithFallback`；成功后 `tempFile.delete()` 改为非空调用 |
| `app/src/test/java/com/sample/docreader/ui/editor/EditorViewModelTest.kt` | 46-47（类 KDoc）、232-247（新增用例） | 新增 `tcUt09_tempFileUnavailable_abortsWriteback`：cacheDir 缺省 null → 临时文件不可建立 → 断言 writer 零调用、`FailedSaveAs`、dirty 保持 |

自查结论：

- "先校验再一次写回"的防截断保证现在真正成立：临时文件不可用时不落盘，用户数据不会被截断写。
- 现有保存链测试（`tcUt09_save_tempVerifyThenWtWriteback_utf8`、`tcUt09a_wtUnsupported_fallbackToW`、`tcUt09_saveFails_keepsTempAndDirty_promptsSaveAs`、`tcUt09_providerKilled_specificResult` 及 EditorSaveResultTest/EditorRecreateTest/EditorDraftTest）均显式传 cacheDir，行为意图不变，全部保持绿色，无需修正。
- 生产路径（EditorScreen L73-81）恒传 `context.cacheDir`，cacheDir=null 仅在测试/降级场景出现，按失败中止是安全方向。

### C6（低）先嗅探格式再取持久权限

改动文件：

| 文件 | 行号 | 内容 |
| ---- | ---- | ---- |
| `app/src/main/java/com/sample/docreader/ui/home/HomeViewModel.kt` | 62-79 | `onPicked` 内顺序调整：先 `queryDisplayName` + `readHead`（256B 短读）+ `FormatSniffer.sniff`；不支持抛 `UnsupportedFormatException` 直接提示返回；支持才 `takePersistableReadPermission` → 记最近 → 导航。KDoc 同步更新 |

自查结论：

- `FormatSniffer`/`FileOpener` 接口未动；不支持的文件现在不会在最近权限列表里留下无效授权。
- 重复打开同一 uri 时 `takePersistableUriPermission` 幂等（原有 runCatching best-effort 语义保留），未加额外状态。
- `HomeViewModelTest` 无 onPicked 用例（仅覆盖 createNew/remove/clear），无测试受顺序调整影响；异常分支（不支持的格式）走原有 `catch (UnsupportedFormatException)` 提示路径，文案不变。

### G4-code（低）进度写入加防抖

改动文件：

| 文件 | 行号 | 内容 |
| ---- | ---- | ---- |
| `app/src/main/java/com/sample/docreader/ui/reader/ReaderViewModel.kt` | 21-25（import Job/delay/CoroutineScope）、65-84（ProgressPoint + onCleared 兜底）、204-215（selectChapter/onFirstVisibleParagraphChanged）、265-286（scheduleProgressPersist/flushPendingProgress）、330-331（PROGRESS_DEBOUNCE_MS=500） | 滚动进度经 delay-取消式防抖 500ms 落库（与 EditorViewModel 草稿防抖同款写法）；切章 `selectChapter` 先 `flushPendingProgress()` 兜底写一次再立即记新章进度；`onCleared` 把未写出的最新点用一次性 `CoroutineScope(Dispatchers.IO)` 落库（viewModelScope 已取消） |
| `app/src/test/java/com/sample/docreader/ui/reader/ReaderViewModelTest.kt` | 171-174（awaitProgress 注释）、276-298（新增用例） | 新增 `progressPersist_debounced_writesLatestOnly`：防抖窗口内（400ms 处重置计时）断言未落库，越过 500ms 后断言最终值 7 落库 |

自查结论：

- 防抖只作用于滚动上报路径；`selectChapter` 的章进度仍立即落库（语义为离散事件，不属于滚动流），且切章前先兜底 flush，不丢最后一段。
- 退出路径：`onCleared` 兜底落库。生产上 ViewModel 清除发生于导航离开（进程仍存活），IO 协程可完成写入；未用 runBlocking 避免主线程 Room 写。
- 既有用例 `onFirstVisibleParagraphChanged_negative_clampedAndPersisted`、`selectChapter_valid_jumpAndPersist`、`persistProgress_withoutLoad_nothingSaved` 均经 `awaitProgress` 轮询最终值（`advanceUntilIdle` 会推过 500ms 虚拟时间），行为意图不变，全部保持绿色，未改断言。
- 测试调度器说明：`Dispatchers.setMain(StandardTestDispatcher)` 与 runTest 共享 scheduler，`advanceTimeBy` 可同时驱动 VM 内 delay，防抖用例确定性成立。

### G6（低）残留旧注释修正

改动文件：

| 文件 | 行号 | 内容 |
| ---- | ---- | ---- |
| `app/src/main/java/com/sample/docreader/ui/reader/ReaderScreen.kt` | 240 | 注释 ">10MB" → ">2MB（ReaderViewModel.MAX_EDITABLE_BYTES）"（已核对实际值 2*1024*1024，EDIT_HINT_TOO_LARGE 文案本身即为 2MB 无需改） |
| `app/proguard-rules.pro` | 1-4 | 删除"编码探测用 android.icu"的过时表述，改为历史说明：该方案 M3 已推翻（hidden API，提交 4b3693f），现为严格解码级联，无额外 keep 负担（文件本就无引用 icu 类的规则，仅注释层面修正） |
| `app/src/test/java/com/sample/docreader/ui/reader/ReaderViewModelTest.kt` | 362-372（tcF309 用例 KDoc 与断言文案） | 同源残留：测试注释/断言文案 "10 MB" → "2MB = ReaderViewModel.MAX_EDITABLE_BYTES"（纯文字，行为与断言值不变） |

自查结论：三处均为注释/文案修正，无行为变化；`MAX_EDITABLE_BYTES` 实际值核对为 2MB（ReaderViewModel L329）。

### pendingParagraph（低）换行切换位置保持跨旋转

改动文件：

| 文件 | 行号 | 内容 |
| ---- | ---- | ---- |
| `app/src/main/java/com/sample/docreader/ui/reader/ReaderScreen.kt` | 163-164 | `remember { mutableIntStateOf(-1) }` → `rememberSaveable { mutableIntStateOf(-1) }` 并补注释（Int 可直接 saveable；import 已存在，无新增依赖） |

自查结论：旋转发生在"已记录 pendingParagraph、尚未重组滚动"的间隙时，状态随 Bundle 恢复，位置保持不再断裂；`remember` 在该函数内仍有其他使用（mdBlocks、menuOpen 等），import 保留正确。

## 纪律自查

- 全程中文注释；未建任何调试/临时文件；未执行 `rm`；fixtures 产物未触碰。
- 未动 UI 结构（M10）、未动 epub 解析（M11）、未给 G1/G2 写代码；未 git commit。
- 每项改动经 `assembleDebug + testDebugUnitTest` 验证全绿；仅有 C5/G4-code 各新增 1 个针对新行为的用例，无既有断言被削弱。

## 遗留说明（供 Kimi Code 审查参考）

- U1 图标为单色剪影风格矢量图，真机/各 OEM 启动器遮罩下的视觉效果建议真机确认（简报已注明真机验证归 Kimi Code）。
- 工作区存在本批次之外的既有未提交文档改动（AGENTS.md、docs/需求说明书.md、docs/软件设计文档.md、docs/测试用例.md），未触碰，提交时请与本批次代码改动区分。
