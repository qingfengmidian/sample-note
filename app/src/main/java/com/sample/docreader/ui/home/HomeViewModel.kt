package com.sample.docreader.ui.home

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sample.docreader.core.format.FormatSniffer
import com.sample.docreader.core.io.DraftStore
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.recent.RecentFile
import com.sample.docreader.core.recent.RecentFilesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** 阅读入口导航目标：uri、显示名与已嗅探出的格式经路由传入 ReaderScreen */
data class OpenTarget(val uri: String, val name: String, val format: DocFormat)

/** 首页状态（设计§4.1：最近列表 + 打开入口 + 一次性提示/导航事件） */
data class HomeUiState(
    val recent: List<RecentFile> = emptyList(),
    val message: String? = null,
    val openTarget: OpenTarget? = null,
    /** 新建文件（F3.5/TC-F3-08）导航目标：指向编辑器而非阅读器 */
    val editTarget: OpenTarget? = null
)

/**
 * 首页 ViewModel（M4）：SAF 打开文件（嗅探格式、持久权限、记录最近），
 * 以及最近列表的打开/删除/清空。文件访问走 Dispatchers.IO。
 * M7c：新建文件（F3.5）、最近列表删除/清空联动删草稿（F4.6）、启动清理孤儿草稿（TC-F4-06a）。
 */
class HomeViewModel(
    private val recentRepository: RecentFilesRepository,
    private val resolver: ContentResolver,
    private val draftStore: DraftStore? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            recentRepository.recent.collect { list ->
                _uiState.update { it.copy(recent = list) }
            }
        }
        // 启动清理 7 天前的孤儿草稿（TC-F4-06a ③）；无 draftStore 时降级为无操作
        draftStore?.let { store ->
            viewModelScope.launch(Dispatchers.IO) { store.cleanupOrphans() }
        }
    }

    /**
     * 用户在系统文件选择器选中文件后调用（F1.1/F1.4）：
     * 先读显示名/文件头嗅探格式（短读前 256 字节即可，无需持久权限）→ 不支持则直接提示返回、
     * 不取持久授权（C6）；支持才持久化读权限 → 记录最近 → 触发导航。
     */
    fun onPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val displayName = queryDisplayName(uri)
                    val head = readHead(uri, HEAD_BYTES)
                    val format = FormatSniffer.sniff(displayName, head)
                        ?: throw UnsupportedFormatException(displayName)
                    // 格式确认支持后才取持久授权；重复打开同一 uri 重复调用是幂等安全的
                    takePersistableReadPermission(uri)
                    val name = displayName ?: uri.lastPathSegment ?: "未命名"
                    recentRepository.touch(uri.toString(), name, format)
                    _uiState.update {
                        it.copy(openTarget = OpenTarget(uri.toString(), name, format))
                    }
                }
            } catch (e: UnsupportedFormatException) {
                _uiState.update { it.copy(message = e.message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = openErrorMessage(e)) }
            }
        }
    }

    /** 打开最近列表中的文件：预检可访问性，失败区分"权限失效"与"云盘 Provider 未运行"（REV4） */
    fun openRecent(item: RecentFile) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val uri = Uri.parse(item.uri)
                    resolver.openInputStream(uri)?.use { it.read() } ?: throw FileNotFoundException(item.uri)
                    recentRepository.touch(item.uri, item.name, item.format)
                    _uiState.update { it.copy(openTarget = OpenTarget(item.uri, item.name, item.format)) }
                }
            } catch (e: SecurityException) {
                // 持久权限已失效（文件被移动/删除/Provider 撤销授权）：移出列表并提示
                recentRepository.remove(item.uri)
                _uiState.update { it.copy(message = "无法访问该文件，已从最近列表移除") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = openErrorMessage(e)) }
            }
        }
    }

    /**
     * 新建文件（F3.5/TC-F3-08）：用户在 ACTION_CREATE_DOCUMENT 选择器中命名后调用。
     * 持久化写权限（读权限一并取，供编辑器载入空文件）→ 记录最近 → 触发编辑器导航。
     * format 由首页"新建"入口的 mimeType 决定（md/txt）；失败给一次性提示。
     */
    fun createNew(uri: Uri, format: DocFormat) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    takePersistableWritePermission(uri)
                    val name = queryDisplayName(uri)
                        ?: "未命名.${if (format == DocFormat.MD) "md" else "txt"}"
                    recentRepository.touch(uri.toString(), name, format)
                    _uiState.update {
                        it.copy(editTarget = OpenTarget(uri.toString(), name, format))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "新建文件失败：无法访问所选位置") }
            }
        }
    }

    /** 单条删除：联动删除对应编辑草稿（F4.6 数据一致性，不动 Repository 职责） */
    fun remove(uri: String) {
        draftStore?.delete(uri)
        viewModelScope.launch { recentRepository.remove(uri) }
    }

    /** 清空最近列表：联动删除所有已记录文件的草稿（F4.6） */
    fun clear() {
        val store = draftStore
        if (store != null) _uiState.value.recent.forEach { store.delete(it.uri) }
        viewModelScope.launch { recentRepository.clear() }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun consumeOpenTarget() = _uiState.update { it.copy(openTarget = null) }

    fun consumeEditTarget() = _uiState.update { it.copy(editTarget = null) }

    private fun takePersistableReadPermission(uri: Uri) {
        // 部分虚拟文档/Provider 不支持持久权限，失败不影响本次打开
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** 新建文件：取写权限（读权限一并取，供编辑器载入）；Provider 不支持时降级为本次可用 */
    private fun takePersistableWritePermission(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        return name
    }

    private fun readHead(uri: Uri, maxBytes: Int): ByteArray {
        val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        stream.use { input ->
            val buffer = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val read = input.read(buffer, total, maxBytes - total)
                if (read < 0) break
                total += read
            }
            return buffer.copyOf(total)
        }
    }

    private fun openErrorMessage(e: Exception): String =
        if (e is FileNotFoundException && (e.message ?: "").contains("No content provider")) {
            "文件提供方（如云盘应用）未运行，请启动对应应用后重试"
        } else {
            "无法打开文件：权限不足或文件已被移动/删除"
        }

    private class UnsupportedFormatException(displayName: String?) :
        Exception("不支持的文件格式${displayName?.let { "：$it" } ?: ""}")

    companion object {
        private const val HEAD_BYTES = 256
    }
}
