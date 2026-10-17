# 安全政策

## 声明

本应用为纯本地应用：无网络权限、无账号系统、不采集数据，攻击面很小。所有文件访问仅通过 Android SAF（Storage Access Framework）且权限由用户主动授予。

即便如此，如果你发现了安全问题（例如：SAF 权限处理缺陷、路径逃逸、解析器对恶意构造文件的崩溃/资源耗尽等），欢迎报告。

## 报告方式

- **不要**为安全漏洞公开创建 Issue；
- 请通过 GitHub 的 **Private vulnerability reporting**（仓库 Security 页 → Report a vulnerability）或私信维护者提交。

报告请尽量包含：受影响版本（tag/提交号）、复现步骤、影响描述。

## 响应

维护者会在 7 天内确认收到报告。修复后将通过 release note 致谢（除非报告者要求匿名）。
