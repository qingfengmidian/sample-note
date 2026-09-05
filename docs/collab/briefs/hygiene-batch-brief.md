# 任务简报：v0.0.1 一致性卫生批次（代码部分）

项目：/Users/xukk/code/sample-note（Android/Kotlin/Compose，请先读 AGENTS.md 了解约定）
基线：main 最新提交（a3ad5ee 之后仅文档改动）。这是一次小修批次，**严格按清单执行，不要扩 scope、不要重构无关代码**。

## 纪律（必须遵守）

- 全程中文注释/提交信息。
- **不要建任何调试文件**（如 DebugXxx.kt、test.txt 之类），不要执行 `rm` 命令；如有临时产物，用 Write 覆盖或留在原地，报告里说明，由 Kimi Code 清理。
- fixtures 是 Gradle 生成的，不要手改 app/src/test/resources 或 app/src/debug/assets 下的任何文件。
- 每项改动后跑 `./gradlew testDebugUnitTest` 验证（本机无需 `-x lintVitalRelease`，那是 release 构建的 lint 问题）；有测试断言因行为修正而失败时，允许同步修正测试，但**不得改变测试验证的行为意图**（C5 除外，它的意图就是改变行为）。
- 完成后写报告：每项改动的文件+行号、测试结果、以及你对每项的自查结论。报告写到 docs/collab/reviews/hygiene-opencode-report.md。

## 清单（6 项，均出自 docs/文档代码一致性评审.md，编号对应）

### U1（高）补应用自适应图标
- `app/src/main/AndroidManifest.xml` 的 application 标签加 `android:icon="@mipmap/ic_launcher"`（如有 roundIcon 一并加 `@mipmap/ic_launcher_round` 同名引用）。
- minSdk=26，**只需 anydpi-v26 自适应图标，不需要 png 兜底**：
  - `res/mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`：`<adaptive-icon>` 引用 foreground drawable + background。
  - foreground：`res/drawable/ic_launcher_foreground.xml` 矢量图——一个简洁的"文档+书页"意象（path 手绘即可，单色 #FFFFFF，108dp 画布内留安全区），禁止引入任何图片素材/第三方依赖。
  - background：`res/values/colors.xml` 加 `ic_launcher_background`（用主题主色，如 #6750A4 Material 3 紫，与 `ui/Theme` 主色一致——先看代码里实际主色再定）。
- 自测：`./gradlew assembleDebug` 成功即可（真机验证由 Kimi Code 做）。

### C5（低→按正确性修）保存临时文件校验失败必须中止
- `app/src/main/java/com/sample/docreader/ui/reader/EditorViewModel.kt`（先自行定位 save 相关代码）：`writeTempFile` 返回 null（缓存写失败/校验不通过）时当前仍继续 `writeBackWithFallback`——改为直接返回 `SaveResult.FailedSaveAs`（或现有失败态），中止写回；"先校验再一次写回"的防截断保证不能失效。
- 检查 `app/src/test` 下保存链相关测试：若有"temp 失败仍写回"的隐含断言，修正为新行为；最好补一个 tempFile=null → 失败的用例（如现有测试结构方便）。

### C6（低）先嗅探格式再取持久权限
- `app/src/main/java/com/sample/docreader/ui/home/HomeViewModel.kt` 的 `onPicked`：当前先 `takePersistableUriPermission` 再嗅探。调整为：先读头部（嗅探只需前 256 字节，`contentResolver.openInputStream` 短读，不需要持久权限）判定支持格式；不支持则直接提示返回，**不取持久授权**；支持再走原有授权+打开流程。
- 注意保持 `FormatSniffer`/`FileOpener` 现有接口不变；重复打开同一 uri 时 takePersistableUriPermission 重复调用是安全的（FLAG_GRANT_READ/WRITE 幂等），不要为此加额外状态。

### G4-code（低）进度写入加防抖
- `app/src/main/java/com/sample/docreader/ui/reader/ReaderViewModel.kt` 的 `onFirstVisibleParagraphChanged`/`persistProgress`：当前每段变化即写 Room。加 500ms debounce（kotlinx.coroutines Flow 操作符即可，如 `MutableStateFlow` + `debounce(500)` + `collectLatest` 落库，或 `delay` 取消式写法），退出/切章时把最新值兜底写一次（不要丢最后一段进度）。
- 对应单测如断言"调用 N 次立即写库"可能受影响——防抖后断言落库最终值即可。

### G6（低）残留旧注释修正
- `app/src/main/java/com/sample/docreader/ui/reader/ReaderScreen.kt` 中 `EDIT_HINT_TOO_LARGE` 附近注释"超出可编辑上限（>10MB）置灰"→ 改为 2MB（实际值 `MAX_EDITABLE_BYTES` 在 ReaderViewModel，先核对再改注释）。
- `app/proguard-rules.pro` 中提到 android.icu 的注释：该方案 M3 已推翻（hidden API），注释改为历史说明或直接删除该条规则（先看规则内容是否仍引用相关类）。

### pendingParagraph（低）换行切换位置保持跨旋转
- `ReaderScreen.kt` 的 `pendingParagraph` 从 `remember` 改为 `rememberSaveable`（Int 可直接 saveable），避免旋转恰好发生在切换重组间隙时位置保持断裂。

## 不要做的事

- 不要动 UI 结构（M10 的事）、不要动 epub 解析（M11 的事）。
- 不要给 G2（CUSTOM 字体循环）或 G1（编码切换）写代码——那是 M10 范围。
- 不要 git commit——由 Kimi Code 审查后统一提交。
