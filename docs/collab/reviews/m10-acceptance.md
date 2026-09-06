# M10 真机验收记录（F4.4/F4.5 + G1/G2/U2/U3/U6/U7）

> 验收日期：2026-09-06
> 设备：OPPO Reno9 5G（ColorOS 13，Android 13），WiFi adb
> 版本：`app-debug.apk`（M10 代码 + Kimi Code U2 修复后重装）
> 关联：`docs/collab/reviews/m10-ui-refresh-design.md`、`docs/collab/reviews/m10-opencode-report.md`
> 自动化基线：单测 160/0/0（Kimi Code 独立 `--rerun-tasks` 复核）+ `assembleDebug`/`assembleRelease`（`-x lintVitalRelease`）通过

## 逐项结论

| 项 | 结果 | 说明 |
|---|---|---|
| F4.4 无底部工具条 | ✅ | epub/txt/md 阅读页正文全屏，无常驻底部工具条 |
| F4.4 ⋯ 菜单按格式裁剪 | ✅ | epub 仅「阅读设置」；txt「编辑/阅读设置」；md「预览/编辑/阅读设置」 |
| F4.4 阅读设置面板 | ✅ | ModalBottomSheet 唤出；排版（换行分段、字号 A-/A+、行距 120/150/180/200% 单选）/字体/主题三组；面板配色跟随阅读主题 |
| F4.4 即时生效 | ✅ | A+：16sp→18sp 即时；行距 150%→180% 勾选即时（无重进面板即见） |
| U3 分段/单选 | ✅ | 换行分段二选、行距/字体/主题单选带 ✓ 与色块（主题组），选中态清晰 |
| F4.5 章节入口上移 | ✅ | 顶栏章节图标（content-desc「章节列表」）唤起底部弹层，替代原底部「章节」 |
| F4.5 面板翻章 | ✅ | 面板底部「上一章/下一章」，第 1 章时上一章置灰；下一章翻章后面板停留、章节高亮联动、顶栏标题同步 |
| F4.5 selected 语义 | ✅ | 当前章主色高亮 + 加粗（语义树 selected 由单测钉死） |
| G1 编码切换 | ✅ | sample_gbk.txt 自动探测正常（"镕"正确）；面板编码选 UTF-8 → 即时乱码（GBK 字节按 UTF-8 解码）；选自动探测 → 恢复正确 |
| G2 字体导入/CUSTOM | ✅ | 面板「导入自定义字体」→ SAF 选 roboto_test.ttf → CUSTOM 选中并渲染生效（正文由等宽变 Roboto）；字体组出现「自定义」项 |
| G2 旧文件清理 | ✅ | 重导入同字体后 `files/fonts` 恒为 1 个文件、UUID 更新（766a083c…→ dd516bb7…），孤儿清理生效 |
| U2 DayNight | ✅ | 系统深色下首页/阅读自动深色（原硬编码 Light 已除）；Manifest 主题 values/values-night 生效 |
| U2 系统栏跟随主题 | ✅ | 阅读主题=默认白（系统深色下）：状态栏/导航栏变主题背景色、**图标转深色**（初版漏图标明暗，Kimi Code 补 WindowInsetsController 修复后复测通过）；跟随系统时恢复透明 |
| U2 夜间无亮边 | ✅ | 夜间黑/跟随系统深色下正文与系统栏一体，无浅色亮边 |
| U6 测试样本仅 debug | ✅ | debug 包首页「测试样本」入口在（路由 + 按钮双 BuildConfig.DEBUG 门控）；release 编译通过且无入口（代码审查确认） |
| U7 错误态/空态 | ✅ | broken.epub（缺 OPF）→ 差异化文案「epub 缺少 OPF 描述文件，无法解析」+「返回」按钮可点回首页；首页空态「打开文件」主操作在（首装空列表截图确认） |
| 回归：>2MB 编辑置灰 | ✅ | huge.txt（5MB）⋯ 菜单显示「编辑（文件超过 2MB，仅可只读）」且项 disabled（语义树 enabled=false） |
| 回归：进度恢复 | ✅ | Flatland.epub 重开后回到上次章节（第 1 章），进度 Room 恢复正常 |
| 回归：编码探测/中文渲染 | ✅ | GBK 特有字符"镕"正确渲染；epub 正文、md 预览正常 |
| 回归：编辑入口 | ✅ | txt 正常进编辑器（撤销/重做/保存工具栏可见，无意触达） |

## 验收中发现并修复的问题

**U2 边缘缺陷（Kimi Code 修复，随 M10 提交）**：初版 DisposableEffect 只设 `statusBarColor/navigationBarColor`，未跟随系统栏图标明暗。系统深色模式下强制阅读主题「默认白」时，状态栏白底白图标、对比度不足。修复：`ReaderScreen.kt` 用 `WindowCompat.getInsetsController` 按主题背景亮度设置 `isAppearanceLightStatusBars/NavigationBars`（亮度 >0.5 → 深色图标），onDispose 恢复 enableEdgeToEdge 的系统默认外观。

## 遗留与备注

- 设备 `/sdcard/Download/` 测试残留：`broken_m10.epub`（错误态验证用副本，原 broken.epub 在 Documents/）、既往 m9_smoke.txt/roboto_test.ttf——故意不删，供后续回归。
- `files/fonts` 现有 1 个 CUSTOM 字体副本（dd516bb7…ttf，roboto_test.ttf 导入产物），与阅读设置持久化一致。
- ModalBottomSheet 在真机为半屏/全屏两段式（Material3 默认），面板内可纵向滚动，无功能问题。
