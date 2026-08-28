# M8b 大文件分块加载 — 实现简报（Kimi Code → OpenCode）

> 分支：`feat/m8b-chunked-loading`。红测试已提交（TDD 红），你的任务是把它们做绿。
> 需求：F4.2；验收：TC-F4-02（8MB `large.txt` 首屏 ≤3s、滚动到底能读全文）；设计：`docs/软件设计文档.md` §3.2/§6（已更新为 M8b 定稿措辞）。

## 已定型的设计（不可擅自更改）

**范围**：仅 TXT 走分块加载；md/xml 维持全量解析；epub 不动。

**已有契约骨架（红测试提交内含，签名与语义不得改）**：

- `core/parse/PagedTextLoader.kt`：`PagedTextLoader.open(stream, probe)` / `hasMore` / `suspend nextChunk()` / `close()`；常量 `CHUNK_PARAGRAPHS=200`、`PREFETCH_MARGIN=50`、`HEAD_BYTES=8KB`。KDoc 即契约。
- `core/io/FileOpener.kt`：新增 `length(uri): Long? = null`。
- `ReaderViewModel`：`ReaderUiState.hasMore/loadingMore` + `loadMore()`（现为契约桩）。

## 实现任务

### 1. `PagedTextLoader` 实现（core/parse/PagedTextLoader.kt）

- `open()`：从流头读 ≤8KB（不足则到 EOF）→ `TextDecoding.detectCharset(head, probe)` 定编码 → 剥离 BOM（UTF-8 3 字节 / UTF-16LE|BE 2 字节）→ 头部余下字节与原始流拼接（如 `SequenceInputStream`），保证探测字节不丢失。
- 逐行泵出（建议 `BufferedReader(InputStreamReader(拼接流, charset))` + 一行 lookahead 维护 `hasMore`）；`nextChunk()` 每轮最多 200 段，循环内 `ensureActive()`。
- **段落语义与 `String.lines()` 完全一致**，黄金对照测试逐条钉死：
  - `\n`/`\r\n`/`\r` 都是分隔符（`BufferedReader.readLine()` 行为天然一致）；
  - 末尾换行须补尾部空段（`"a\n"` → `["a", ""]`——readLine 不会给最后的 `""`，须在 EOF 时按"最后一个字符是行终止符"补一段）；
  - 空文件首块 `[""]`；
  - 多字节字符跨读缓冲边界不乱码（`InputStreamReader` 流式解码天然处理，勿自己按字节切）。
- 解码用 REPLACE（流式不做全文严格回退，设计已记录该偏差）。
- `close()` 幂等，关闭底层流；EOF 后允许自然关闭。close 后 `hasMore=false`、`nextChunk()` 返回空。
- 取消：`nextChunk()` 在已取消上下文中必须抛 `CancellationException`（测试 `nextChunk_honoursCancellation`）。

### 2. `FileOpener.length` 实现

- `AssetsFileOpener`：`assets.openFd(path).use { it.length }`，压缩 asset 抛异常时回退 `open(uri).use { it.available().toLong() }`。
- `ContentResolverFileOpener`：`resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }`，`-1`/null 时回退 `query(OpenableColumns.SIZE)`；都拿不到返回 null。
- `DelegatingFileOpener`：按 scheme 转发。

### 3. `ReaderViewModel` 接入

- `load()` 中 **format == TXT 一律走分页路径**（小文件首块即全文，行为透明由测试钉死）：
  1. `PagedTextLoader.open(fileOpener.open(uri))`；失败按现有异常映射（FileNotFoundException/SecurityException → 现有分支）。
  2. 读首块 → 若进度恢复段落 ≥ 已载段数，循环 `nextChunk()` 追加至覆盖或 EOF（EOFD 有界）。
  3. `editable`：`fileOpener.length(uri)` 已知则按 `MAX_EDITABLE_BYTES` 判定；未知（null）先按 true，EOF 时按累计字节修正。
  4. 状态：`hasMore`/`loadingMore` 正确维护；loader 存为 VM 私有字段。
- `loadMore()`：非分页路径或 `!hasMore || loadingMore` 空操作；否则 viewModelScope 内 `nextChunk()` 追加到 `chapters[0].paragraphs` 末尾（**只 append，索引稳定**——`itemsIndexed` key 是索引，重排会炸 UI）。
- **资源卫生**：重新 `load()` 与 `onCleared()` 时关闭旧 loader（防 fd 泄漏，TC-F4-08 同类检查）。
- 非 TXT 路径（md/xml/epub 全量解析）一行都不许动。

### 4. `ReaderScreen` 滚动触发

- 在正文 `LazyColumn` 处用 `snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }` 观察；当 `lastVisible >= paragraphs.size - PagedTextLoader.PREFETCH_MARGIN` 且 `hasMore && !loadingMore` → `viewModel.loadMore()`。注意 distinctUntilChanged，避免重复触发。

## 红测试清单（全部必须转绿，语义不得削弱）

- `core/parse/PagedTextLoaderTest.kt`：9 用例（黄金对照 5000 行、多字节跨头边界、\r\n 跨头边界、行尾语义 6 组、GBK、UTF-16LE BOM、小文件单块、取消、close 幂等）。
- `ui/reader/ReaderViewModelPagedTest.kt`：6 用例（首块 200、loadMore 至 EOF 黄金对照、进度恢复覆盖、伪造 length 判只读、小 txt 透明、md 不回归）。

## 红线

- 允许触及：`core/parse/PagedTextLoader.kt`、`core/io/FileOpener.kt`、`core/io/SafFileOpener.kt`、`ui/reader/ReaderViewModel.kt`、`ui/reader/ReaderScreen.kt`、`core/parse/TextDecoding`（仅当必须新增内部辅助时，**不得改现有方法行为**）。
- 禁止：改测试断言语义；动 epub/md/xml 解析；动编辑器；新增依赖；`rm`；建调试残留文件。
- 白名单外命令（如 python3）被权限拒绝时：**直接停止并在报告中说明缺了什么**，不要尝试绕过，不要静默中止收尾。
- 提交纪律：不要 git add/commit/push（已在 opencode.json deny）。

## 自验命令

```bash
./gradlew testDebugUnitTest          # 全量：147 用例须全绿
./gradlew assembleDebug
```

## 交付物

实现 + `docs/collab/reviews/m8b-opencode-report.md`（改了什么、测试统计、遇到的偏差）。报告只需要 Write 一个 markdown 文件，不需要任何统计命令——测试统计写"全绿/数量"即可，由 Kimi Code 独立复核。
