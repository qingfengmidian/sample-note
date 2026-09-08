# M11c 验收记录 — epub 基本 CSS 文字样式渲染（F2.15）· M11 收口

| 项目 | 内容 |
| --- | --- |
| 验收日期 | 2026-09-09 |
| 验收人 | Kimi Code（Pixel 7 AVD 设备端语义断言 + 单测复核） |
| 范围 | F2.15 CSS 子集解析与渲染合并（M11 最后一块） |
| 自动化基线 | `testDebugUnitTest --rerun-tasks` **220 通过 / 0 失败**（基线 198 + 22）；`validateFixtures` 通过；`connectedDebugAndroidTest` **10/10**（M11a 5 + M11b 3 + M11c 2） |

## 实施与审查闭环

- OpenCode 实施：`styled.epub`/`bad_css.epub` 夹具；Models.kt `Chapter.styles` + `ParagraphStyleHints`（三态 bold/italic、paragraphSpacing）；EpubParser CSS 子集解析（四类选择器、7+2 属性、继承/内联优先/层叠、失败静默回退纯文本）；ReaderTextView 段落样式合并（全局绝对基准、clamp 0.5~3.0、WCAG 对比度 <4.5:1 回退主题前景、WRAP/NO_WRAP 双路径）。单测 218/0。
- Kimi Code 审查（explore 逐点核）：需求/设计逐项符合、无越界（无排版引擎级能力、无 h 默认字号、M11a/b 未回归、styles 与图片共用段落坐标无错位、取消粒度 OK）；可验收无必修；两项低成本真 bug 顺手修：
  1. 跨 `<style>` 块层叠 order 复位（前表表内靠后规则误压后表）→ `parseStyleSheet(startOrder)` 全局递增 + 单测断言后表赢；
  2. margin/padding 简写 2/3 值 bottom 取位错误 → 修正 + 单测。
  修复后 220/0。三条低优先级近似语义（非法内联值遮断、line-height+font-size 同元素叠加、段落边界扩展观察）不改代码，记入实施报告遗留 §5。
- 设计文档 REV9 同步：§3.1 ParagraphStyleHints 模型签名回写实现（三态/Int ARGB/paragraphSpacing）。

## 验收结果

| 用例 | 断言要点 | 结果 |
| --- | --- | --- |
| TC-F2-17e（MdCssDeviceTest，设备端） | `styled.epub` 打开正常、正文可读（字重/斜体/对齐/颜色/相对字号的**功能**由解析层+合并纯函数单测覆盖） | ✅ |
| TC-F2-17f（MdCssDeviceTest，设备端） | `bad_css.epub`（非法语法/未知选择器混排）打开不崩、整书可读 | ✅ |
| 合并单测（ReaderStyleMergeTest） | clamp 0.5/3.0 边界、WCAG 对比度回退（四主题背景边界）、段距 0/非 0 两档、单段失败回退全局 | ✅ |
| 解析单测（EpubCssStyleTest） | 四类选择器、px/pt/em/% 倍率、继承与内联优先、跨表层叠后表赢、非法静默忽略、无样式书 styles 恒空 | ✅ |
| 回归 | 既有设备端 8 例 + 全量单测 220/0 | ✅ |

## 遗留/说明（进真机清单）

- 视觉级：`styled.epub` 字重/斜体/对齐/颜色观感、夜间主题下对比度回退目测、`line-height`+`font-size` 同元素叠加的行距观感——追加为真机清单 D8（见 `docs/collab/pending-real-device-tests.md`）。
- 近似语义记录：非法内联值遮断回退默认色（仍可读）；段落边界较"仅 p/h1~h6/li"扩展（div/section 裸文本切得更细，p 包裹书坐标不变）；C4 超大 `<style>` 单块不可中断（实际影响忽略）。
- 结论：**M11c 通过验收；M11（F2.13/F2.14/F2.15）全部完成，可提交并打 m11c tag**。
