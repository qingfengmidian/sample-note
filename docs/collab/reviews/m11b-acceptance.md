# M11b 验收记录 — epub 插图渲染（F2.14）+ 缓存解压 + C4（Pixel 7 虚拟环境）

| 项目 | 内容 |
| --- | --- |
| 验收日期 | 2026-09-06 |
| 验收人 | Kimi Code（adb 驱动 Pixel 7 AVD 模拟器，语义树 + Compose UI 断言） |
| 验证范围 | F2.14 epub 图片/封面/占位、缓存解压层、C4 取消粒度 |
| 自动化基线 | `testDebugUnitTest --rerun-tasks` **198 通过 / 0 失败**；`validateFixtures` 通过；`connectedDebugAndroidTest` **8/8**（M11a 5 + M11b 3） |

## 实施与审查闭环

- OpenCode 首轮实施：fixtures（3 含图 epub）+ 解析层 img/封面提取 + EpubCache 缓存解压层（LRU/同书跳过/取消/回退/zip-slip）+ 渲染层段落内图片（懒加载采样/占位/无障碍）+ C4 + 设备端 MdImageDeviceTest。单测 194/0。
- Kimi Code 审查（explore 代理逐点核）挑出并全部修复：I1 zip 目录条目导致缓存解压失败、I2 读侧 zip-slip 逃逸、M1 缓存解析失败不回退重抽、M2 占位双重播报、M3 整章纯图判"文件为空"、L1 OOM/窄高采样、L3 `%20` 解码、L4 回退读字节移 IO；位图提至 480×320。修补后 198/0。
- 设备端用例调试中发现并修复两个真实问题（非测试锅）：
  1. 长文档触摸 fling 滚动不可靠 → 改 `performScrollToNode`（ScrollToIndex 语义动作，确定性滚动）。
  2. **截断 PNG 实测能被 BitmapFactory 部分解码成真图**（不占位）→ fixture 改为头部清零（前 24 字节）保证解码必失败；validateFixtures 自检同步新语义。

## 验收结果

| 用例 | 断言要点 | 结果 |
| --- | --- | --- |
| TC-F2-17（设备端 MdImageDeviceTest） | `sample_images.epub`：PNG/JPG 位图节点 contentDescription=alt 且实际显示；svg 处出现占位；`%20` 空格文件名图可显示；正文完整可滚动到底 | ✅ |
| TC-F2-17c | `sample_cover.epub`：封面（OPF meta cover）语义「封面」置顶显示于正文前 | ✅ |
| TC-F2-17d | `broken_image.epub`：缺失条目（alt=失联配图）占位 + 头部损坏位图默认占位「图片无法显示」；正文两段完整 | ✅ |
| 回归 | M11a MdTocDeviceTest 3 例 + MainActivityTest 2 例 | ✅ |
| C4 | 取消检查落在 XHTML 段级循环（单元覆盖中断即时取消） | ✅ |

## 遗留/说明

- "按屏宽等比显示、无 OOM" 属像素级视觉，语义断言只能证明位图真实渲染与占位路径正确；解码采样/防 OOM 逻辑已单测 + 审查确认，**真机视觉抽查（TC-F2-17 视觉项）留待后续真机环节**（本轮按约定先用虚拟环境验证）。
- M11b 未触 CSS（ParagraphStyleHints/styles 提取与渲染、styled/bad_css 夹具）——留给 M11c。
- epub 缓存路径：`cache/epub/<uri md5 前 16 位>/`，单实例 LRU（不删当前书、同书跳过、取消无残留、失败回退可读）。
- 结论：**M11b 通过验收，可提交**。
