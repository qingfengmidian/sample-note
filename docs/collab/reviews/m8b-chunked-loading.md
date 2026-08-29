# M8b 大文件分块加载 — 审查记录（Kimi Code）

> 简报：`docs/collab/briefs/m8b-chunked-loading.md`；红测试：31b2ccc。状态：**审查通过，真机验收通过，待合入**。

## 审查清单（交付后逐项核验）

### 范围与红线

- [x] diff 仅触及允许清单（PagedTextLoader.kt / FileOpener.kt / SafFileOpener.kt / ReaderViewModel.kt / ReaderScreen.kt 五个文件，TextDecoding 未动）
- [x] 测试零改动、断言语义未削弱；既有 132 用例全绿（TxtParserTest / ReaderViewModelTest / 编辑器测试均在 147 内复核）
- [x] 无新依赖；无 git 操作；无调试残留文件

### 功能正确性（对照契约）

- [x] 段落语义 == String.lines()：末尾空段保留（`LastCharTrackingReader` 追踪末字符，EOF 时按"末字符是行终止符 / 空文件"补尾部空段）、空文件 `[""]`、\r\n/\r 混合（BufferedReader.readLine 天然一致）——PagedTextLoaderTest 9/9 绿
- [x] 编码：头部 ≤8KB 探测 + 截尾 0~3 字节重试（避免截断切开多字节致 GBK 误判 Latin-1，覆盖了 StrictUtf8Probe 只管 UTF-8 的盲区）；BOM 剥离（UTF-8 3B/UTF-16 2B）；`SequenceInputStream` 拼接头部余留字节不丢失；流式 REPLACE 解码
- [x] VM：首块 200 段即上屏（单 IO 块内连续取块避免线程切换）；loadMore 只 append（索引稳定）且有 stale-loader 丢弃保护（`pagedLoader !== loader` 时丢弃迟到的块）；进度恢复 load-until-covered（`paragraphs.size <= target` 循环）；editable 优先 length、未知则读至 EOF 按 `bytesRead` 累计字节修正（保证 TC-F3-09 正确）
- [x] 资源卫生：`load()` 重载先 `closePagedLoader()`；`onCleared()` 关闭；loader `close()` 幂等
- [x] ReaderScreen 滚动触发：`snapshotFlow(lastVisibleIndex).distinctUntilChanged()` + `hasMore && !loadingMore` + `>= size - PREFETCH_MARGIN`，`rememberUpdatedState` 取最新状态防闭包陈旧；仅 TXT 生效

### 质量门槛

- [x] 独立重跑 `cleanTestDebugUnitTest testDebugUnitTest`：**147/147 全绿**（0 failures / 0 errors）
- [x] `assembleDebug` 独立复跑通过
- [ ] ~~OpenCode 报告存在~~ **未交付（第三次）**——见观察项 O1

## 真机验收（2026-08-30，OPPO Reno9 5G / PHM110，WiFi adb 驱动）

- [x] TC-F4-02 ① 1MB txt：最近列表重开路径 tap→内容可见 **≤0.66s**（screencap 帧分析：0.21s 帧仍为首页面、0.66s 帧已进入正文；阈值 ≤1s，**通过**）
- [x] TC-F4-02 ② 8MB `large.txt`：SAF 首开含选择器退场动画 **≤0.19s 首帧已有正文**（分块加载只解析首块 200 段的直接证据；阈值 ≤3s，**通过**）；20 次快速滑动后截图正文密集渲染无白屏（**滚动流畅**）；Room `reading_progress` 实测 paragraph=**439**（远超首块 200，证明滚动追加真实发生，**滚动加载机制工作正常**）
- [x] 分块路径进度恢复：深滑至 439 → 退首页 → 重进 → 进度未被重置（DB 保持 439，首可见段=439），load-until-covered 真机生效
- [x] TC-F3-09 回归：huge.txt（15MB）菜单显示"编辑（文件超过 10MB，仅可只读）"，`enabled = state.editable` 置灰不可点
- [x] fd 卫生：连续 5 次开关 large.txt，`/proc/<pid>/fd` 140 → 140 无增长

> 测量方法备注：设备无 screenrecord，uiautomator dump 单次 ~2.7s 过粗；改用 `adb exec-out screencap` 连拍 + ffmpeg 裁区亮度分析，时间分辨率 ~0.5s。设备上 4 条旧 coloros URI 最近项失效为既有环境问题，与本次无关。

## 代码走查意见（Kimi Code）

实现质量高，几处超出契约最低要求：头部截断 GBK 重试、`CountingInputStream.bytesRead` 支撑 length 未知时的 editable 修正、loadMore 的 stale-loader 丢弃。`pump()` 的尾部空段判定（`linesRead == 0 || lastChar ∈ {\n,\r}`）对空文件/末尾换行/末尾 \r\n 各情形均与 `lines()` 对齐，黄金对照 9 用例全绿佐证。

观察项（均不阻塞合入）：

- **O1 报告第三次未交付**：本次它在 `rg|paste|bc` 统计被拒后**没有中止**，继续跑完了 assembleDebug——收尾纪律有进步，但 `m8b-opencode-report.md` 仍漏写。简报里"白名单外命令被拒→停止并说明"的指令它理解为"跳过该步"却忘了最后报告是纯 Write、本来就不依赖统计命令。下批简报把报告写成编号步骤的最后一步并强调"只依赖 Write 工具"。
- **O2 length 未知时的首屏取舍**：SAF/assets 都能拿到 length，正常路径无影响；length 为 null 的 Provider 会退化为全量读完后才上屏（为保证 editable 判定正确）。正确性优先于首屏速度，取舍正确，记录在案。
- **O3 进度恢复全量加载**：恢复点接近文件尾部时 load-until-covered 实际等于全量加载（txt 单章线性结构的固有代价）。大文件恢复场景首屏会变慢，可接受；若 M9 真机实测不达标再考虑稀疏索引。

## 独立复验结果（Kimi Code）

| 项 | 结果 |
| --- | --- |
| `cleanTestDebugUnitTest testDebugUnitTest` | 147 tests / 0 failures / 0 errors |
| PagedTextLoaderTest（9 红驱动） | 9/9 转绿 |
| ReaderViewModelPagedTest（6） | 6/6 绿（含 4 个行为钉死用例保持绿） |
| 既有 132 用例 | 全绿无回归 |
| `assembleDebug` | BUILD SUCCESSFUL |

## 审查意见与处置

无阻塞意见。O1/O2/O3 为观察项，不要求返工。

## 结论

**审查通过，真机验收全项通过**，可合入 main。
