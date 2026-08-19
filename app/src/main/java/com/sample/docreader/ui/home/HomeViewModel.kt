package com.sample.docreader.ui.home

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sample.docreader.core.format.FormatSniffer
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

/** 阅读入口导航目标：uri 与已嗅探出的格式经路由传入 ReaderScreen */
data class OpenTarget(val uri: String, val format: DocFormat)

/** 首页状态（设计§4.1：最近列表 + 打开入口 + 一次性提示/导航事件） */
data class HomeUiState(
    val recent: List<RecentFile> = emptyList(),
    val message: String? = null,
    val openTarget: OpenTarget? = null
)

/**
 * 首页 ViewModel（M4）：SAF 打开文件（嗅探格式、持久权限、记录最近），
 * 以及最近列表的打开/删除/清空。文件访问走 Dispatchers.IO。
 */
class HomeViewModel(
    private val recentRepository: RecentFilesRepository,
    private val resolver: ContentResolver
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            recentRepository.recent.collect { list ->
                _uiState.update { it.copy(recent = list) }
            }
        }
    }

    /**
     * 用户在系统文件选择器选中文件后调用（F1.1/F1.4）：
     * 持久化读权限 → 读显示名/文件头 → 嗅探格式 → 记录最近 → 触发导航。
     */
    fun onPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    takePersistableReadPermission(uri)
                    val displayName = queryDisplayName(uri)
                    val head = readHead(uri, HEAD_BYTES)
                    val format = FormatSniffer.sniff(displayName, head)
                        ?: throw UnsupportedFormatException(displayName)
                    val name = displayName ?: uri.lastPathSegment ?: "未命名"
                    recentRepository.touch(uri.toString(), name, format)
                    _uiState.update {
                        it.copy(openTarget = OpenTarget(uri.toString(), format))
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
                    _uiState.update { it.copy(openTarget = OpenTarget(item.uri, item.format)) }
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

    fun remove(uri: String) {
        viewModelScope.launch { recentRepository.remove(uri) }
    }

    fun clear() {
        viewModelScope.launch { recentRepository.clear() }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun consumeOpenTarget() = _uiState.update { it.copy(openTarget = null) }

    private fun takePersistableReadPermission(uri: Uri) {
        // 部分虚拟文档/Provider 不支持持久权限，失败不影响本次打开
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
