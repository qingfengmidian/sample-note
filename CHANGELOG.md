# 更新日志

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本对应 git tag。完整里程碑与验收记录见 `docs/开发计划.md` 与 `docs/collab/reviews/`。

## [v0.0.1] — 首个发布（tag m0 ~ m9）

基线版本：epub/xml/md/txt 阅读、md/txt 编辑、阅读设置与主题、自定义字体导入、最近文件与阅读进度（Room/DataStore）、保存流程与草稿恢复、编码手动切换。范围 = 《需求说明书》验收标准 1~7 + 全部 P0 用例。md 目录与 epub 图片/CSS 当时推迟（见后续版本）。

## [m10] 阅读器 UI 改版

底部工具条移除，设置收纳为 ⋮ 菜单 → 阅读设置 ModalBottomSheet；章节面板弹层化、面板内翻章（F4.5）；顺带落地一致性评审项 G1/G2/U2/U6/U7。

## [m11] 阅读富文本（F2.13 / F2.14 / F2.15）

- m11a：md 目录（F2.13）+ 源码/预览切换映射（U4）
- m11b：epub 图片（F2.14）+ 缓存解压层（EpubCache，LRU + zip-slip 防护）
- m11c：epub 基本 CSS（F2.15）+ WCAG 对比度回退

## [m12] 连续滚动（F2.16 / F4.7 / F4.8）

解析层原生目录（nav/NCX）+ 章级渐进物化 + 全局 item 坐标进度恢复；右缘全书进度条。

## [m13] 弹层强调色与阅读主题解耦

弹层选中 ✓/目录高亮/面板按钮等改随阅读主题前景色，修复系统深浅 × 主题明暗相反时对比度不足。

## [m14] 中英双语（F4.6）

默认英文 Sample Note / 中文「简记」；UI 全量字符串资源化；错误文案架构（VM 传类型、UI 本地化）。

## [m15] epub 章节标题层级排版（F2.17）

按 XHTML 标签名的 H1~H6 默认层级字号（书内 CSS 声明即覆盖、不叠加）。

## [m16] 应用内语言切换（F4.9）

设置页手动切换跟随系统/中文/English，26~32 重建 Activity、33+ 走 `LocaleManager`。

## [m17] 设置项选项列表交互（F4.3 修订）

五个循环设置行改单选对话框；语言切换经确认后先落库再重建。

## [m18] 复制后「问 AI」（F4.10）

剪贴板监听 + Snackbar 一键发送到指定 AI 应用（ACTION_SEND + `<queries>` 包可见性声明，不自动发送）。

## [m19] 清理批次

删除 `cycle*`/`toggleWrap()` 死代码与未用字符串键，无行为变更。

---

[v0.0.1]: https://github.com/<org>/<repo>/releases/tag/v0.0.1
