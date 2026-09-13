# M12 收口记录（原续接笔记）

日期：2026-09-13　状态：**M12 全部收口（代码 + 真机验收 + D11 体感终审 + 提交 + `m12` tag 完成）**

验收详情见 `docs/collab/reviews/m12-acceptance.md`。下一步：M13（弹层强调色与阅读主题解耦，设计见 `docs/开发计划.md`）。

## 一、本轮完成（2026-09-13 真机验收）

- **设备端全量套件真机复跑：12 例 / 0 失败 / 1 跳过**（OPPO Reno9 PHM110，WiFi adb，25.8s）；单测 254/0/1skip + `validateFixtures` 本轮实测复核。
- **修复 MdTocDeviceTest 两例确定性失败**（测试代码缺陷，非应用 bug，`app/src/androidTest/.../MdTocDeviceTest.kt`）：
  1. 位置依赖 → 新增 `openFixtureAtTop()` 有界滑回顶部锚点（进度按 uri 落库恢复致首段不在语义树）；
  2. 几何依赖 + 精确串 → 结尾改断言跳转目标 `"##### 标题五（H5）"`（源码段保留全部 # 前缀）。
  09-12 的「用户用手机致失焦」归因不成立。
- **D9** 真机通过（ReaderWindowDeviceTest.anchorToc 首过；进度条顺延）；**D10** 《邓小平文选》四项全过（目录原样/跳转/出版说明顶栏留空/书名页非白屏）；**D11** many_chapters 120 章连续滚动通过（PSS +6.5MB、无 ANR、**滑动手感用户终审通过**）；**D5** Magnifica md 切换/深跳/杀进程重开全过。
- **真实书附加走查**：Flatland.epub（665KB，Calibre 真 CSS+字体+插图+嵌套 NCX）打开/目录/插图/CSS/跨章滚动全过，PSS 123.6MB 无膨胀。
- **提交与打 tag**：`c227243`（测试修复）+ `1a6f274`（验收文档）+ tag `m12`。
- 文档回写：`pending-real-device-tests.md` D5/D9/D10/D11 勾选 + 表头状态；本文件；`AGENTS.md`。

## 二、后续（M12 已收口，无遗留待办）

1. **M13**：弹层强调色与阅读主题解耦（`docs/开发计划.md` M13）。
2. **M14**：中英双语 F4.6（需求 REV7）。
3. 恢复 F4.8 进度条时：移除 `@Ignore` 并复验 D9/D11 的进度条拖动项（组件与用例已保留）。

## 三、环境备忘

- 真机：OPPO Reno9 5G（PHM110，1080×2412），WiFi adb；断线套路 `adb kill-server; adb start-server`（有时需手机侧重开无线调试）。
- `connectedDebugAndroidTest` 结束会**卸载应用**，验收后需 `adb install -r app/build/outputs/apk/debug/app-debug.apk`（WiFi 传输可达 8 分钟，USB 更稳）。
- 打开真机文件（SAF 之外）：MediaStore 注册后 `am start -a VIEW -d content://media/external/file/<id> --grant-read-uri-permission`；
  查 id：`adb shell content query --uri content://media/external/file --projection _id:_data --where "_display_name='x.epub'"`（**冒号分隔**投影，整条命令外层双引号防单引号被吞）。
- 已推送真机：`/sdcard/Download/{dxp.epub, Magnifica-Humanitas.md, Flatland.epub}`；id 分别 1000600273 / 1000600274 / 1000592387（重启后可能变，按名查）。
- 观察工具：`uiautomator dump` 文本 + bounds（`/tmp/dui2.py` 分区打印）；[0,0][0,0] = 离屏已组合项（细条/缓冲），**不代表异常**；截图体积判白屏。
- 本会话限制：图片工具返回 `[Unsupported Image]`，像素判据用 dump 语义 + 截图体积，观感项交用户。
- 双 agent 协作：OpenCode 派发命令（**必须带 `--auto`**）；红测试断言实现方不得改；Kimi 框定 + 独立审查。
- release 构建需 `-x lintVitalRelease`（本机代理失效）。
