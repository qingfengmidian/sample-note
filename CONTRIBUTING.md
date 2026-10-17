# 贡献指南

感谢你对本项目的关注。本项目所有开发文档使用中文，提交信息、代码注释也请使用中文。

## 开始之前

请先阅读 [AGENTS.md](AGENTS.md)（项目全貌、目录结构、代码约定）与 `docs/` 下的需求/设计/测试用例文档。需求编号 `F-*`、用例编号 `TC-*` 在代码与注释中被大量交叉引用——**修改行为前先查对应编号，不要凭空发挥**。

## 开发流程

1. 从 `main` 拉出功能分支：`feat/mN-xxx`（小改动可用 `fix/xxx`、`docs/xxx`）。
2. 实现 + 测试。注意：
   - **解析层按 TDD**：先写失败的测试，再写实现，红绿过程应可在 PR 历史中复现；
   - 测试命名/注释引用 `docs/测试用例.md` 的 `TC-*` 编号；
   - 新增/修改行为时同步更新 `docs/` 对应文档与 `AGENTS.md` 进度段。
3. 本地跑通 `./gradlew check`（含 `testDebugUnitTest`）。
4. 发起 PR。CI 必须全绿（push 到 main 及所有 PR 均触发 GitHub Actions），CI 红了不合并。

## 代码约定摘要

- Kotlin 官方代码风格；JVM target 17；注释/提交信息用中文；
- 解析器实现须在循环内调用 `ensureActive()` 响应协程取消，解析失败抛 `ParseException` 具体子类；
- 设置以 Int 百分值存储；颜色用 Compose `Color` 不用 Long；
- 无障碍硬要求：交互控件有语义描述、可点击目标 ≥ 48dp、正文对比度 ≥ 4.5:1；
- **不引入任何联网依赖或遥测**（无网络权限、无账号、不采集数据是项目红线）。

完整约定见 [AGENTS.md](AGENTS.md) §5。

## 行为准则

参与本项目即表示同意遵守 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。

## License

提交即表示你同意你的贡献以 [Apache-2.0](LICENSE) 许可证发布，与项目其余部分一致。
