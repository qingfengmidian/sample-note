# S1 SAF Provider 技术探针报告

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-08-20 |
| 设备 | OnePlus PHM110（ColorOS，无线 adb 连接） |
| Provider | Google Drive（`com.google.android.apps.docs.storage`）、本机外部存储（`com.android.externalstorage.documents`） |
| 探针应用 | `spike/saf-probe`（本目录，仅 debug） |
| 对应设计假设 | 《软件设计文档》§3.4（写回）、§3.6（SAF/持久权限） |

## 结论（TL;DR）

1. `OpenDocument` + `takePersistableUriPermission(R|W)` 在 Drive 与本机存储上均**正常工作**，权限可跨进程死亡持久。
2. `"wt"` 与 `"w"` 写回在 Drive 上均**成功且正确截断**（无旧内容残留）。
3. **关键风险确认**：ColorOS 的后台限制会杀死 Drive 进程，此后尽管持久授权仍在，访问该 URI 会抛 `FileNotFoundException: No content provider`。需要先拉起 Drive 进程（如用户打开过 Drive）才能读写。**设计必须针对此场景给出差异化提示**。

## 详细记录

### 1. OpenDocument 返回

```
uri = content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3D…
authority = com.google.android.apps.docs.storage
displayName = saf_probe_test.txt
size = 113
```

- Drive 正常返回 content URI，`query` 可取 displayName/size。✅

### 2. takePersistableUriPermission

- Drive URI：`R|W` 一次性成功；本机外部存储 URI 同样成功。✅
- **杀进程重启后** `persistedUriPermissions` 仍完整保留（read/write 均 true）。✅

### 3. "wt" 写回与截断

- Drive 进程存活时：`openOutputStream("wt")` 成功；写入 30 字节短内容后读回，**原 113 字节内容尾部无残留 → 截断正常**。✅

### 4. "w" 写回与截断

- Drive 进程存活时：`openOutputStream("w")` 同样成功且截断正常（本 Provider 上两者行为一致）。✅

### 5. 关键负面发现：Provider 进程被 OEM 杀死

- 现象：持久授权存在，但 Drive 应用进程被 ColorOS 后台限制杀死后，`openOutputStream`/`openInputStream` 均抛：

  ```
  FileNotFoundException: No content provider: content://com.google.android.apps.docs.storage/…
  ```

  `dumpsys activity providers` 查不到 Drive provider 记录，证实进程未运行。
- 恢复方式：启动 Drive 应用（`monkey -p com.google.android.apps.docs`）后 provider 发布，**同一 URI 无需重新授权即可读写**。

### 6. 恢复原内容

- "wt" 写回备份的 113 字节成功，测试文件已还原。✅

## 对设计的修订建议（需落入设计文档）

1. **§8 风险表新增一行**：云盘 Provider 进程被 OEM 杀死 → 持久授权仍在但抛 `FileNotFoundException: No content provider` → 提示"文件提供方（如云盘应用）未运行，请启动对应应用后重试"，与"权限失效/文件被删"区分。
2. **编辑保存链路**：保存失败诊断为 `No content provider` 时，不得判定为权限丢失而移除最近列表条目（授权其实还在）。
3. "wt" → "w" 回退策略在 Drive 上验证有效，维持原设计。

## 未覆盖项

- 其他云盘 Provider（OneDrive/坚果云等）未测；按同样假设处理，后续有真机条件时补充。
- Drive 在线同步延迟/冲突（写入后云端回滚）未测，超出本探针范围。
