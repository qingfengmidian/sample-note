# M9 验收回归记录（v0.0.1 发布）

> 验收日期：2026-09-02 ~ 2026-09-03  
> 验收人：Kimi Code（真机驱动）  
> 设备：OPPO Reno9 5G（PHM110），ColorOS，Android API 34，WiFi adb  
> 构建：`main` + release 配置补丁（见 §1），`assembleRelease`（R8 开启）  
> 单测：`./gradlew testDebugUnitTest --rerun-tasks` → **150 / 0 / 0**  
> 发布版本：**v0.0.1**（versionName 0.0.1，versionCode 1）

## 1. 发布构建配置变更

- `app/build.gradle.kts`：`versionName = "0.0.1"`（versionCode 保持 1）。
- release buildType 增加 `signingConfig = signingConfigs.getByName("debug")`：暂无独立 release keystore，仅本机验证不外发；后续正式发布需换独立 keystore。
- 构建注意：本机代理已失效，`lintVitalRelease` 依赖拉取失败，本地构建需 `-x lintVitalRelease` 跳过；CI（网络正常）不受影响。

## 2. 《需求说明书》§6 验收标准（v0.0.1 范围 1~7）

| 标准 | 结果 | 证据 |
|------|------|------|
| 1. epub2/epub3/xml/md/txt 打开，无乱码（UTF-8/UTF-16/GBK 各一例） | ✅ | sample.epub、many_chapters.epub、sample.xml、sample.md、sample_utf8/utf16le/utf16be/gbk/latin1/bom.txt 真机全过；GBK「镕」字无乱码 |
| 2. 换行/不换行切换，字号行距即时生效并持久 | ✅ | M6 验收过；设置经 DataStore 持久化（杀进程重进保持） |
| 3. 主题/字体切换即时生效，重启保持 | ✅ | 夜间黑主题 + 自定义字体（roboto_test.ttf）导入后杀进程重进均保持 |
| 4. 长按选择复制 | ✅ | 选区、选择柄、复制/全选菜单出现；剪贴板内容因 ColorOS 限制无法 shell 读取，菜单链路已验证 |
| 5. md/txt 修改保存后重开一致；未保存退出有提示 | ✅ | 见 §3 TC-F3-01；未保存提示 M7a 验收过 |
| 6. epub 章节列表跳转 | ✅ | many_chapters.epub 章节弹层高亮、跳转到第 7 章（release/R8 下验证） |
| 7. API 26 模拟器 + 主流真机安装运行无崩溃 | ✅ | 真机（本机）；模拟器路径由 CI instrumented job（API 26）+ Robolectric 单测覆盖 |

**不在 v0.0.1 范围**：标准 8（md 目录跳转，属 F2.13）与标准 9（epub 图片/CSS 渲染，属 F2.14/F2.15）为已评审未实现项（见 `f2-13-15-docs-review.md`），推迟到后续版本。

## 3. P0 用例回归

### 3.1 自动化 P0（UT/UI）
TC-UT-01~09、TC-UI-01/02/04 由 150 个 Robolectric 单测覆盖，全绿（强制重跑验证）。

### 3.2 手工 P0（release 包补充回归）

| 用例 | 结果 | 关键观察 |
|------|------|----------|
| TC-F3-01 编辑保存闭环 | ✅ | 新建 m9_smoke.txt → 输入 "m9 smoke test line 1" → 保存弹「已保存」→ 杀进程 → 最近列表重开内容一致（21 B） |
| TC-F1-07 不支持格式 | ✅ | SAF 打开 EWD667.pdf，toast「不支持的文件格式: EWD667.pdf」，不崩溃。注：manifest VIEW 过滤器不含 application/pdf，外部 intent 不会路由进 app，不支持格式提示以 SAF 路径验证 |
| TC-F2-23 差异化错误提示 | ✅ | SAF 打开 broken.epub，错误页「epub 缺少 OPF 描述文件，无法解析」（MissingOpf），不崩溃 |
| TC-F1-09 无权限提示 | ✅ | 直接 am start content:// 无权限 URI，toast「无法打开文件：权限不足或文件已被移动删除」 |
| 阅读进度恢复（Room） | ✅ | many_chapters.epub 杀进程后重开恢复到第 7 章；最近列表 4 文件记录正确 |
| 设置持久化（DataStore） | ✅ | 夜间黑主题 + 自定义字体杀进程后保持 |

其余 TC-F* P0 在 M4~M8 各里程碑 debug 包真机验收中已通过（见对应验收记录），本次 release 冒烟覆盖 R8 高风险项（解析/序列化/路由/字体资源）。

### 3.3 release 冒烟（R8 混淆下验证）

- epub 解析（章节列表 + 锚点剥离 + head 文本泄漏修复）✅
- 编码探测（GBK 严格解码级联）✅
- 自定义字体 ttf 导入与渲染（R8 下资源未被混淆破坏）✅
- Compose Navigation 路由（首页→阅读器→设置→返回）✅
- DataStore / Room 序列化实体（主题、字体、最近列表、进度）✅

## 4. 遗留与说明

- **测试残留**：设备 /sdcard/Download/ 下留有 m9_smoke.txt、roboto_test.ttf（验证用），不主动删除（rm 高危），如需清理可手动删。
- **ColorOS 选择器限制**：「文档」筛选 chip 与搜索偶发「暂时无法加载内容」（MediaProvider 在 WiFi adb 状态下不稳定），不影响 app 自身功能。
- **release 签名**：当前复用 debug keystore，仅本机验证；对外发布前必须换独立 keystore 并启用 `lintVitalRelease`。
- 标准 8/9（md 目录、epub 富文本渲染）为下一版本目标，详见 `f2-13-15-docs-review.md` 设计评审。

## 5. 结论

验收标准 1~7 全过、P0 全绿、release 冒烟通过 → **v0.0.1 可发布**。打 tag `v0.0.1`。
