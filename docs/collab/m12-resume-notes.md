# M12 续接笔记（下次启动从这里继续）

日期：2026-09-12　状态：**代码全部完成并提交，仅剩真机验收与打 tag**

## 一、已完成（不要重做）

M12 阅读器改版 + 真机反馈热修全部落地，提交链（main 直提）：

```
a8b463f docs: M12 热修简报 + 决策日志 D-2026-09-12-03
0c61549 test(red): 正文 svg 内嵌位图 + 空章跳过（5 例红）
06a78b8 fix: 正文 svg 内嵌位图提取（F2.14）+ 空章跳过（F4.7 断链），254/0
331e748 fix(ui): 进度条（F4.8）暂时隐藏——组件与用例保留
06cf42d test: 设备端测试基建修复
14324b2 docs: M12 收尾记录（M12a/b/c，248/0）
（更早：fb56f2f M12a / a86b0f3 M12b / 4f094bd M12c，红测试分别为 9d2b170 / dc45b7f / 713e3e0）
```

- 单测基线：**254 用例 0 失败（1 跳过 = 进度条 @Ignore，随 F4.8 恢复时一起恢复）**。
- 热修（`06a78b8`）已模拟器端到端验证：`/sdcard/Download/dxp.epub`（《邓小平文选》，1395431 字节，本地副本 `/tmp/dxp.epub`）书名页渲染图片非白屏（截图 216KB vs 白屏 23KB，`content-desc="图片"`×2）、从封面连滑到 1950 年文章无卡无崩溃。
- 进度条相关用例已 `@Ignore`：`ReaderWindowTest.progressBar_fractionAndSeekMaterializesTargetChapter`、`ReaderWindowDeviceTest.progressBar_exists_andSeekChangesPosition`。

## 二、待办（下次启动从这里继续）

1. **重连 OPPO 真机**（WiFi adb 又断，当前 `adb devices` 仅剩 emulator-5554）。重连后先 `adb devices` 确认。
2. **跑剩余设备端用例**（上次 12 例中 10 过，剩 2 例归因「跑测试时用户正在用手机」致 Activity 失焦）：
   ```bash
   ./gradlew connectedDebugAndroidTest --tests "com.sample.docreader.MdTocDeviceTest"
   ```
   期待 2/2 绿（tocPanel / previewMode）。
3. **D 项真机复验**（人眼 + 设备端断言，见 `docs/collab/pending-real-device-tests.md`）：
   - **D9**（TC-UI-12）：`anchor_toc.epub` 目录面板 = NCX 5 条原样、锚点条目定位正确（**进度条部分已随 F4.8 隐藏转「恢复时再做」**）。
   - **D10**（《邓小平文选》三项）：目录面板 = 文件自带「第一卷/书名页/版权页/目录/各篇文章」（无「第 N 章」）；「出版说明」章顶栏留空；点目录条目跳转正确。热修后**补验第 0 章（书名页）不再是白屏**。
   - **D11**（连续滚动体感）：`many_chapters.epub` 从开头连滑到结尾不点菜单（进度条拖动部分已随 F4.8 隐藏转恢复时再做）。
   - **D5**（长文档 md）：真实长 md（`/Users/xukk/Documents/books/` 下有 md，或「Magnifica-Humanitas」开头的 md）源码↔预览切换语义位置一致、目录深跳、杀进程重开回到标题。
4. **写验收记录** `docs/collab/reviews/m12-acceptance.md`（模板参考同目录 m10/m11 验收），回写 `pending-real-device-tests.md` 勾记 D5/D9/D10/D11 与 D1~D4/D7/D8 现状。
5. **打 `m12` tag**（`git tag m12`，主仓库里程碑 tag 惯例）。

## 三、环境备忘

- 真书目录：`/Users/xukk/Documents/books/`（epub + md）。调试用《邓小平文选》已 push 到模拟器 `/sdcard/Download/dxp.epub`。
- 模拟器：Pixel_7（`emulator-5554`，qemu pid 见 `ps aux | grep qemu`；显示截图 900×2000，orig 坐标 = 显示坐标 ×1.2）。
- **本会话限制**：图片/截图工具（ReadMediaFile / kimi-cu 截图）返回 `[Unsupported Image]`，我看不到像素——设备/模拟器验证一律用 `adb shell uiautomator dump` 抓文本 + 截图文件大小判别「空白 vs 有内容」，不要靠肉眼读图。
- 双 agent 协作：OpenCode 派发命令（**必须带 `--auto`**）：
  ```bash
  /Users/xukk/.opencode/bin/opencode run --auto -m deepseek/deepseek-v4-flash --title "..." "提示词"
  ```
  红测试断言实现方不得改；Kimi 框定 + 独立审查。
- 提交需用户批准；release 构建需 `-x lintVitalRelease`（本机代理失效）。
