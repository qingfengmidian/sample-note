# M14 真机/模拟器验收记录 — 中英双语支持（F4.6）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-18 |
| 环境 | 中文：OPPO PHM110 真机（系统语言中文）；英文：Pixel_7 AVD（en-US，无头，同 CI 规格思路） |
| 范围 | M14 全部：TC-UI-15/16 + 设备端全量回归 + 中英各一遍 P0 走查 |
| 前置 | 代码审查已过（`m14-review.md`：强制重跑 268/0/1skip），实现已提交（`10adfb0`） |

## 一、自动化

| 环境 | 命令 | 结果 |
| --- | --- | --- |
| 中文真机 PHM110 | `connectedDebugAndroidTest` | **15/0/1skip**（TC-UI-16 PASS；首跑 TC-UI-16 因测试假设设备默认语言为英文而失败——真机为中文系统，修正为显式 setLocale 双语上下文后转绿，app 行为本身正确） |
| 英文模拟器 Pixel_7 | `ANDROID_SERIAL=emulator-5554 connectedDebugAndroidTest` | **15/0/1skip**（同 suite 英文环境下全绿，getString 化断言按环境取词验证） |

## 二、P0 走查（英文环境，adb 驱动 + 逐屏截图目检，截图 `build/m14-en/` 不入库）

| 屏 | 截图 | 结论 |
| --- | --- | --- |
| 首页 | 01_home | ✅ Sample Note / Test fixtures / Settings / Recent / Open file / New file / Delete / Clear recent list |
| 夹具入口 | — | ✅ Test fixtures (M3 dev entry) |
| 阅读器菜单（epub/md） | 02_menu | ✅ Reading settings；md 菜单 Preview/Edit/Reading settings |
| 阅读设置面板 | 03_settings_sheet | ✅ Layout/Auto wrap/No wrap/Font size/A-/A+/Line spacing 120–200%/Font: Monospace·Serif·Sans serif/Import custom font (ttf/otf)/Theme: System·Default white·Eye-care green·Soft beige·Night black（色块+✓ 正常） |
| 章节面板 | 04_chapters | ✅ Chapters/Previous chapter/Next chapter（禁用淡化正常） |
| 全局设置页 | 05_settings | ✅ Font scheme/Current/Editor/Auto save(+说明)/Reading defaults 五项 + Switch |
| 新建对话框 | 06_newfile_dialog | ✅ New file/Markdown (.md)/Plain text (.txt) |
| 编辑器 | 07_editor | ✅ Undo/Redo/Save（禁用态正常） |

中文环境走查由 M13/M12 验收期覆盖（界面文案逐字沿用既有中文，zh 资源值与 M14 前一致），本轮中文侧以自动化 + 资源值逐字比对兜底。

**内容边界确认**：书/文档内容保持原语言（夹具中文正文在英文 UI 下仍显示中文），仅 UI chrome 本地化——符合 F4.6 口径。

## 三、验收结论

- 中英双环境自动化 + 英文 P0 八屏走查全部通过，**M14 验收通过**。
- 用户指示项落地：英文环境无显示名兜底 `Untitled.md/txt`（中文「未命名.md/txt」）。
- 遗留观察（不阻塞）：`HomeViewModel` 私有日志异常 message 保留中文（仅日志可见，简报约束 #4）。

## 四、收口

- 打 `m14` tag；更新 `docs/开发计划.md` M14 行与 AGENTS.md 进度段。
